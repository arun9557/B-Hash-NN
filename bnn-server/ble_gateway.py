"""
╔══════════════════════════════════════════════════════════════════╗
║          B#NN  —  B Hash Neural Network                         ║
║          BLE Gateway  v2.0  (Production Grade)                  ║
║                                                                  ║
║  Role  : BLE Central (this laptop/Pi connects TO phones/ESP32)  ║
║  Model : GATT Client  <-> GATT Server (phone/ESP32)             ║
║                                                                  ║
║  Flow  :                                                         ║
║    Phone --BLE--> Gateway --HTTP--> Flask --> Ollama AI          ║
║    Ollama AI <-- Flask <--HTTP-- Gateway <--BLE-- Phone          ║
╚══════════════════════════════════════════════════════════════════╝

What's new in v2.0 vs v1:
  - Auto-reconnect with exponential backoff per device
  - Heartbeat ping/pong every 10 s  (OS won't drop idle links)
  - Watchdog detects silent disconnects (no pong in 30 s)
  - Chunked BLE transfer for long AI responses
  - Clean DeviceState machine (DISCOVERED -> CONNECTING -> CONNECTED -> DEAD)
  - Thread-safe deduplication for mesh flooding prevention
  - Full error handling — never crashes on bad packet / dropped link
"""

import asyncio
import json
import logging
import time
import uuid
from enum import Enum, auto
from typing import Dict, List, Optional

import requests
from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.device import BLEDevice
from bleak.exc import BleakError


# ══════════════════════════════════════════════════════════════════
#  CONFIGURATION
# ══════════════════════════════════════════════════════════════════

# Custom GATT UUIDs — must be identical in your phone / ESP32 firmware
BNN_SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
BNN_RX_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"  # laptop WRITES → phone reads
BNN_TX_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef2"  # phone WRITES  → laptop notified

# Flask / Ollama
FLASK_API_URL = "http://localhost:5000/chat"

# Scanning
SCAN_INTERVAL_SEC = 8    # how often to re-scan
SCAN_DURATION_SEC = 5    # how long each scan lasts

# Connection / reconnect
CONNECT_TIMEOUT_SEC  = 10   # give up connecting after this many seconds
MAX_RECONNECT_TRIES  = 10   # declare DEAD after this many failed attempts
RECONNECT_BASE_SEC   = 2    # first retry delay (doubles each time)
RECONNECT_MAX_SEC    = 60   # cap for exponential backoff

# Heartbeat
HEARTBEAT_INTERVAL_SEC = 10  # send ping every N seconds
HEARTBEAT_TIMEOUT_SEC  = 30  # declare device gone if no pong within N seconds

# BLE transfer limits
BLE_CHUNK_SIZE    = 384    # max bytes per BLE write (leave room for chunk envelope)
CHUNK_SEQ_TIMEOUT = 5.0    # seconds to wait for all chunks of one message

# Mesh
MAX_HOPS    = 5     # how many relay hops before discarding
DEDUP_LIMIT = 2000  # keep last N message IDs for dedup


# ══════════════════════════════════════════════════════════════════
#  LOGGING
# ══════════════════════════════════════════════════════════════════

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  B#NN  |  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("B#NN")


# ══════════════════════════════════════════════════════════════════
#  DEVICE STATE MACHINE
# ══════════════════════════════════════════════════════════════════

class DeviceState(Enum):
    DISCOVERED   = auto()   # seen in scan, not yet connecting
    CONNECTING   = auto()   # connection attempt in progress
    CONNECTED    = auto()   # fully connected and subscribed
    DISCONNECTED = auto()   # was connected, now lost — will retry
    DEAD         = auto()   # exhausted all retry attempts


# ══════════════════════════════════════════════════════════════════
#  DEVICE SESSION  — one per BLE peripheral
# ══════════════════════════════════════════════════════════════════

class DeviceSession:
    """
    Tracks everything about one BLE device:
    state, bleak client handle, heartbeat timer, chunk-reassembly buffer.
    """

    def __init__(self, mac: str, name: str):
        self.mac:   str   = mac
        self.name:  str   = name or mac
        self.state: DeviceState = DeviceState.DISCOVERED

        self.client:          Optional[BleakClient] = None
        self.reconnect_count: int   = 0
        self.last_pong_ts:    float = time.time()  # seed so watchdog doesn't fire immediately

        # Chunk reassembly: chunk_id -> {index: bytes_piece}
        self._chunk_buf:  Dict[str, Dict[int, bytes]] = {}
        self._chunk_meta: Dict[str, dict] = {}  # chunk_id -> {total, ts}

    # ── convenience ───────────────────────────────────────────────
    def is_alive(self) -> bool:
        """True only when fully connected and ready."""
        return self.state == DeviceState.CONNECTED

    def record_pong(self):
        """Call this every time we receive a pong (or any sign of life)."""
        self.last_pong_ts = time.time()

    def pong_overdue(self) -> bool:
        """True if we haven't heard a pong in HEARTBEAT_TIMEOUT_SEC seconds."""
        return (time.time() - self.last_pong_ts) > HEARTBEAT_TIMEOUT_SEC

    def __repr__(self):
        return f"<Device {self.name} [{self.mac[:8]}…] {self.state.name}>"


# ══════════════════════════════════════════════════════════════════
#  BLE GATEWAY  — main class
# ══════════════════════════════════════════════════════════════════

class BNNGateway:
    """
    The central BLE node of the B#NN network.

    Three background tasks run concurrently (via asyncio.gather):
      _scan_loop()      — discovers new peripherals, spawns connect tasks
      _heartbeat_loop() — pings every connected device on a timer
      _watchdog_loop()  — force-disconnects devices that stopped responding

    Each peripheral gets its own _connect_with_retry() task which:
      • Connects (with timeout)
      • Subscribes to GATT notifications
      • Blocks until disconnect
      • Retries with exponential backoff
    """

    def __init__(self):
        self._sessions: Dict[str, DeviceSession] = {}
        self._seen_ids: List[str] = []   # message IDs we've already processed
        self._lock = asyncio.Lock()      # protects _sessions across concurrent tasks

    # ──────────────────────────────────────────────────────────────
    #  ENTRY POINT
    # ──────────────────────────────────────────────────────────────

    async def run(self):
        """Start the gateway. Runs forever until Ctrl+C."""
        log.info("===========================================")
        log.info("  B#NN Gateway v2.0  —  Starting up")
        log.info("  Scanning for B#NN BLE peripherals…")
        log.info("===========================================")

        await asyncio.gather(
            self._scan_loop(),
            self._heartbeat_loop(),
            self._watchdog_loop(),
        )

    # ──────────────────────────────────────────────────────────────
    #  TASK 1: SCAN LOOP
    # ──────────────────────────────────────────────────────────────

    async def _scan_loop(self):
        """Continuously scan for B#NN peripherals."""
        while True:
            try:
                await self._do_scan()
            except Exception as e:
                log.error(f"Scan failed: {e}")
            await asyncio.sleep(SCAN_INTERVAL_SEC)

    async def _do_scan(self):
        discovered: List[BLEDevice] = await BleakScanner.discover(
            timeout=SCAN_DURATION_SEC
        )
        for device in discovered:
            # Temporary open scan: print every visible BLE device for debugging.
            print(device.name, device.address)

            # Keep gateway connection logic focused on B#NN peripherals only.
            if not _is_bnn_device(device):
                continue

            async with self._lock:
                session = self._sessions.get(device.address)

            # Only spawn a connection task if we're not already managing this device
            already_managed = (
                session is not None
                and session.state not in (DeviceState.DEAD, DeviceState.DISCONNECTED)
            )
            if not already_managed:
                log.info(f"Found: {device.name} [{device.address}]")
                asyncio.create_task(
                    self._connect_with_retry(device.address, device.name or device.address)
                )

    # ──────────────────────────────────────────────────────────────
    #  TASK 2: HEARTBEAT LOOP
    # ──────────────────────────────────────────────────────────────

    async def _heartbeat_loop(self):
        """
        Send a ping to every connected device every HEARTBEAT_INTERVAL_SEC seconds.
        This prevents the OS Bluetooth stack from treating the link as idle and
        silently dropping it.
        """
        while True:
            await asyncio.sleep(HEARTBEAT_INTERVAL_SEC)
            async with self._lock:
                alive_sessions = [s for s in self._sessions.values() if s.is_alive()]

            for session in alive_sessions:
                ping = _make_msg("ping", "heartbeat")
                await self._send(session, ping)
                log.debug(f"[{session.name}] Heartbeat ping sent.")

    # ──────────────────────────────────────────────────────────────
    #  TASK 3: WATCHDOG LOOP
    # ──────────────────────────────────────────────────────────────

    async def _watchdog_loop(self):
        """
        If a device hasn't replied with a pong in HEARTBEAT_TIMEOUT_SEC seconds,
        it's considered silently dead. Force-disconnect it so the reconnect loop
        kicks in and cleans up.
        """
        while True:
            await asyncio.sleep(HEARTBEAT_INTERVAL_SEC)
            async with self._lock:
                sessions = list(self._sessions.values())

            for session in sessions:
                if session.is_alive() and session.pong_overdue():
                    log.warning(
                        f"[{session.name}] No pong in {HEARTBEAT_TIMEOUT_SEC}s "
                        "— forcing disconnect."
                    )
                    try:
                        if session.client:
                            await session.client.disconnect()
                    except Exception:
                        pass  # disconnect callback will handle state update

    # ──────────────────────────────────────────────────────────────
    #  CONNECT WITH RETRY  (per-device background task)
    # ──────────────────────────────────────────────────────────────

    async def _connect_with_retry(self, mac: str, name: str):
        """
        Keep trying to connect to a device.
        Uses exponential backoff between attempts.
        Gives up after MAX_RECONNECT_TRIES failures in a row.
        A successful connection resets the attempt counter.
        """
        session = DeviceSession(mac, name)
        async with self._lock:
            self._sessions[mac] = session

        attempt = 0
        while attempt < MAX_RECONNECT_TRIES:

            if attempt > 0:
                delay = min(RECONNECT_BASE_SEC * (2 ** (attempt - 1)), RECONNECT_MAX_SEC)
                log.info(f"[{name}] Retry {attempt}/{MAX_RECONNECT_TRIES} in {delay:.0f}s…")
                await asyncio.sleep(delay)

            session.state = DeviceState.CONNECTING
            session.reconnect_count = attempt

            connected_ok = await self._connect_once(session)

            if connected_ok:
                # Device was working but then disconnected — reset counter and retry
                log.warning(f"[{name}] Disconnected. Will reconnect.")
                attempt = 0
            else:
                attempt += 1

        log.error(f"[{name}] Gave up after {MAX_RECONNECT_TRIES} failed attempts.")
        session.state = DeviceState.DEAD

    async def _connect_once(self, session: DeviceSession) -> bool:
        """
        Attempt a single connection to `session`.

        Returns True  if we successfully connected (even if we later disconnected).
        Returns False if the initial connection attempt failed.

        This coroutine blocks until the device disconnects, so
        _connect_with_retry can loop and retry.
        """
        mac  = session.mac
        name = session.name

        # This event is set when the BLE link drops — unblocks this coroutine
        link_dropped = asyncio.Event()

        def on_disconnect(client: BleakClient):
            """Called by bleak when the BLE connection drops."""
            session.state  = DeviceState.DISCONNECTED
            session.client = None
            log.warning(f"[{name}] BLE link dropped.")
            link_dropped.set()

        try:
            client = BleakClient(
                mac,
                disconnected_callback=on_disconnect,
                timeout=CONNECT_TIMEOUT_SEC,
            )

            log.info(f"[{name}] Connecting…")
            await client.connect()

            # Verify B#NN service is present on this peripheral
            service_uuids = [str(s.uuid).lower() for s in client.services]
            if BNN_SERVICE_UUID.lower() not in service_uuids:
                log.warning(f"[{name}] B#NN GATT service not found — wrong device?")
                await client.disconnect()
                return False

            # Store client handle and mark as connected
            session.client = client
            session.state  = DeviceState.CONNECTED
            session.record_pong()

            log.info(f"[{name}] Connected. Subscribing to TX notifications…")

            # Subscribe: fires _on_notify whenever phone writes to TX_CHAR
            await client.start_notify(
                BNN_TX_CHAR_UUID,
                lambda char, data: self._on_notify(session, char, data),
            )

            log.info(f"[{name}] Ready. Waiting for messages.")

            # Block here until disconnect callback fires
            await link_dropped.wait()
            return True  # we were connected — retry makes sense

        except asyncio.TimeoutError:
            log.warning(f"[{name}] Connection timed out ({CONNECT_TIMEOUT_SEC}s).")
            return False

        except BleakError as e:
            log.warning(f"[{name}] BleakError: {e}")
            return False

        except Exception as e:
            log.error(f"[{name}] Unexpected connect error: {e}")
            return False

    # ──────────────────────────────────────────────────────────────
    #  RECEIVE  (notification callback)
    # ──────────────────────────────────────────────────────────────

    def _on_notify(
        self,
        session: DeviceSession,
        _char: BleakGATTCharacteristic,
        raw: bytearray,
    ):
        """
        Called by bleak's internal thread when the peripheral sends a notification.
        Hand off to the asyncio event loop immediately — don't do work here.
        """
        asyncio.create_task(self._handle_raw(session, bytes(raw)))

    async def _handle_raw(self, session: DeviceSession, raw: bytes):
        """Decode raw bytes → JSON. Handle chunked messages."""
        try:
            packet = json.loads(raw.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            log.warning(f"[{session.name}] Received malformed data — ignoring.")
            return

        # Is this a chunk of a bigger message?
        if "chunk_id" in packet:
            packet = self._reassemble_chunk(session, packet)
            if packet is None:
                return  # still waiting for more chunks

        await self._dispatch(session, packet)

    async def _dispatch(self, session: DeviceSession, msg: dict):
        """Route a complete, assembled message based on its type field."""
        msg_id   = msg.get("id", "")
        msg_type = msg.get("type", "unknown")
        payload  = msg.get("payload", "")

        # ── Deduplication (prevents mesh relay loops) ─────────────
        if msg_id and msg_id in self._seen_ids:
            log.debug(f"Duplicate msg {msg_id[:8]} — dropped.")
            return
        if msg_id:
            self._seen_ids.append(msg_id)
            if len(self._seen_ids) > DEDUP_LIMIT:
                # Trim oldest half when we hit the limit
                self._seen_ids = self._seen_ids[-(DEDUP_LIMIT // 2):]

        log.info(f"[{session.name}] <- {msg_type.upper():<10} | {payload[:60]}")

        # ── Dispatch ──────────────────────────────────────────────
        if msg_type == "ping":
            # Device checking if server is alive
            await self._send(session, _make_msg("pong", "B#NN server online"))

        elif msg_type == "pong":
            # Reply to our heartbeat — device is still alive
            session.record_pong()

        elif msg_type == "request":
            # AI query — handle in a separate task so we don't block dispatch
            asyncio.create_task(self._handle_ai_request(session, msg))

        elif msg_type == "relay":
            # Mesh packet from a nearby device — forward if TTL allows
            ttl = msg.get("ttl", 0)
            if ttl > 0:
                msg["ttl"]  -= 1
                msg["hops"] = msg.get("hops", 0) + 1
                await self._broadcast(msg, exclude_mac=session.mac)
            else:
                log.debug("Relay packet TTL = 0 — discarded.")

        else:
            log.warning(f"[{session.name}] Unknown message type '{msg_type}' — ignored.")

    # ──────────────────────────────────────────────────────────────
    #  AI REQUEST HANDLER
    # ──────────────────────────────────────────────────────────────

    async def _handle_ai_request(self, session: DeviceSession, msg: dict):
        """
        Receive a prompt from a BLE device.
        Send it to the Flask API (which talks to Ollama).
        Return the AI response to the same device.
        """
        prompt = msg.get("payload", "").strip()
        if not prompt:
            log.warning(f"[{session.name}] Empty prompt — skipping.")
            return

        log.info(f"[{session.name}] Sending to AI: '{prompt[:80]}'")

        try:
            # _call_api is a blocking HTTP call — run in thread pool
            loop     = asyncio.get_event_loop()
            ai_reply = await loop.run_in_executor(
                None, _call_api, prompt, session.mac
            )
            log.info(f"[{session.name}] AI reply ready ({len(ai_reply)} chars).")

        except requests.Timeout:
            ai_reply = "The AI model took too long. Please try again."
            log.error(f"[{session.name}] Flask API timeout.")

        except requests.ConnectionError:
            ai_reply = "AI server unreachable. Is server.py running?"
            log.error(f"[{session.name}] Flask API connection refused.")

        except requests.HTTPError as e:
            ai_reply = f"AI server error: {e}"
            log.error(f"[{session.name}] Flask API HTTP error: {e}")

        except Exception as e:
            ai_reply = f"Unexpected error: {e}"
            log.error(f"[{session.name}] Unexpected: {e}")

        response = _make_msg("response", ai_reply, dst=session.mac)
        await self._send(session, response)

    # ──────────────────────────────────────────────────────────────
    #  SEND  — write to one device (with chunking)
    # ──────────────────────────────────────────────────────────────

    async def _send(self, session: DeviceSession, msg: dict):
        """
        Serialize msg to JSON and write to the device's RX characteristic.

        Long messages (longer than BLE_CHUNK_SIZE) are automatically split
        into numbered chunks. The device reassembles them in order.
        """
        if not session.is_alive() or session.client is None:
            log.warning(f"[{session.name}] Send skipped — device not connected.")
            return

        raw = json.dumps(msg, ensure_ascii=False).encode("utf-8")

        if len(raw) <= BLE_CHUNK_SIZE:
            # Message fits in a single BLE packet
            await self._ble_write(session, raw)
        else:
            # Split into chunks and send each one
            pieces   = list(_split_bytes(raw, BLE_CHUNK_SIZE))
            chunk_id = str(uuid.uuid4())
            total    = len(pieces)
            log.debug(f"[{session.name}] Message too large — splitting into {total} chunks.")

            for idx, piece in enumerate(pieces):
                envelope = {
                    "chunk_id":    chunk_id,
                    "chunk_idx":   idx,
                    "chunk_total": total,
                    "data":        piece.decode("utf-8", errors="replace"),
                }
                await self._ble_write(session, json.dumps(envelope).encode("utf-8"))
                await asyncio.sleep(0.04)  # small pause so peripheral can keep up

    async def _ble_write(self, session: DeviceSession, raw: bytes):
        """Lowest-level BLE write with error handling."""
        try:
            await session.client.write_gatt_char(
                BNN_RX_CHAR_UUID,
                raw,
                response=False,  # write-without-response is faster for bulk data
            )
        except BleakError as e:
            log.error(f"[{session.name}] BLE write error: {e}")
        except Exception as e:
            log.error(f"[{session.name}] Write failed: {e}")

    # ──────────────────────────────────────────────────────────────
    #  BROADCAST  — send to all connected devices
    # ──────────────────────────────────────────────────────────────

    async def _broadcast(self, msg: dict, exclude_mac: Optional[str] = None):
        """Send msg to every connected device, optionally excluding one MAC."""
        async with self._lock:
            sessions = list(self._sessions.values())

        sent = 0
        for session in sessions:
            if session.mac == exclude_mac:
                continue
            if session.is_alive():
                await self._send(session, msg)
                sent += 1

        log.debug(f"Broadcast sent to {sent} device(s).")

    # ──────────────────────────────────────────────────────────────
    #  CHUNK REASSEMBLY
    # ──────────────────────────────────────────────────────────────

    def _reassemble_chunk(self, session: DeviceSession, packet: dict) -> Optional[dict]:
        """
        Buffer incoming chunks by chunk_id.
        Returns the fully assembled message dict when all chunks have arrived.
        Returns None while still waiting.

        Stale incomplete messages (timed out) are automatically discarded.
        """
        chunk_id    = packet.get("chunk_id")
        chunk_idx   = packet.get("chunk_idx", 0)
        chunk_total = packet.get("chunk_total", 1)
        data_str    = packet.get("data", "")

        if not chunk_id:
            return None

        # Initialise buffer for this chunk_id
        if chunk_id not in session._chunk_buf:
            session._chunk_buf[chunk_id]  = {}
            session._chunk_meta[chunk_id] = {"total": chunk_total, "ts": time.time()}

        session._chunk_buf[chunk_id][chunk_idx] = data_str.encode("utf-8")

        # Check if all chunks have arrived
        if len(session._chunk_buf[chunk_id]) >= chunk_total:
            full = b"".join(
                session._chunk_buf[chunk_id][i] for i in range(chunk_total)
            )
            del session._chunk_buf[chunk_id]
            del session._chunk_meta[chunk_id]

            try:
                return json.loads(full.decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                log.error(f"[{session.name}] Chunk reassembly produced invalid JSON.")
                return None

        # Expire stale incomplete messages (some chunks were lost)
        now   = time.time()
        stale = [
            cid for cid, meta in session._chunk_meta.items()
            if now - meta["ts"] > CHUNK_SEQ_TIMEOUT
        ]
        for cid in stale:
            log.warning(
                f"[{session.name}] Incomplete chunked message {cid[:8]}… timed out — dropped."
            )
            session._chunk_buf.pop(cid, None)
            session._chunk_meta.pop(cid, None)

        return None  # still waiting for remaining chunks


# ══════════════════════════════════════════════════════════════════
#  PURE HELPER FUNCTIONS
# ══════════════════════════════════════════════════════════════════

def _is_bnn_device(device: BLEDevice) -> bool:
    """True if this peripheral advertises a B#NN name."""
    return "BNN" in (device.name or "").upper()


def _make_msg(
    msg_type: str,
    payload:  str,
    src:  str = "server",
    dst:  str = "broadcast",
    hops: int = 0,
    ttl:  int = MAX_HOPS,
) -> dict:
    """
    Create a standard B#NN message.

    msg_type options:
      "request"  — client asking AI a question
      "response" — server's AI answer
      "ping"     — heartbeat probe
      "pong"     — heartbeat reply
      "relay"    — mesh forwarding packet
    """
    return {
        "id":      str(uuid.uuid4()),   # unique ID for deduplication
        "type":    msg_type,
        "payload": payload,
        "src":     src,
        "dst":     dst,
        "hops":    hops,
        "ttl":     ttl,
        "ts":      time.time(),
    }


def _split_bytes(data: bytes, size: int):
    """Yield `size`-byte chunks from `data`."""
    for i in range(0, len(data), size):
        yield data[i : i + size]


def _call_api(prompt: str, device_id: str) -> str:
    """
    Blocking HTTP POST to Flask API.
    MUST be called via run_in_executor — never directly from async code.
    """
    resp = requests.post(
        FLASK_API_URL,
        json={"prompt": prompt, "device_id": device_id},
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json().get("response", "").strip()


# ══════════════════════════════════════════════════════════════════
#  ENTRY POINT
# ══════════════════════════════════════════════════════════════════

async def main():
    gateway = BNNGateway()
    await gateway.run()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("B#NN Gateway stopped by user (Ctrl+C).")
