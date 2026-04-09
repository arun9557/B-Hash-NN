"""
B#NN (B Hash Neural Network) - BLE Gateway
The heart of B#NN: receives BLE messages from clients,
routes them to the Flask API, sends AI responses back.

Mesh relay: if a client is too far, intermediate devices
forward the packet, extending range hop-by-hop.

Architecture:
  Client → [BLE] → Gateway → [HTTP] → Flask API → Ollama AI
  Ollama AI → Flask API → [HTTP] → Gateway → [BLE] → Client
"""

import asyncio
import json
import logging
import time
import uuid
import requests
from dataclasses import dataclass, field
from typing import Dict, Optional

from bleak import BleakScanner, BleakClient
from bleak.backends.characteristic import BleakGATTCharacteristic

# ── UUIDs (must match your client app) ───────────────────────────────────────
BNN_SERVICE_UUID  = "12345678-1234-5678-1234-56789abcdef0"
BNN_TX_CHAR_UUID  = "12345678-1234-5678-1234-56789abcdef1"  # client writes here
BNN_RX_CHAR_UUID  = "12345678-1234-5678-1234-56789abcdef2"  # server notifies here

# ── config ────────────────────────────────────────────────────────────────────
FLASK_API_URL    = "http://localhost:5000/chat"
SCAN_TIMEOUT     = 10          # seconds to scan for BLE devices
MAX_BLE_CHUNK    = 512         # max bytes per BLE notification
MAX_HOPS         = 5           # mesh packet TTL (to prevent infinite loops)
RECONNECT_DELAY  = 5           # seconds before retrying a dropped client

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger("B#NN-BLE")


# ── packet format ─────────────────────────────────────────────────────────────
# Every BLE message is a JSON string:
# {
#   "msg_id":    "uuid4",          # unique per message (dedup for mesh)
#   "src":       "device_mac",     # original sender
#   "dst":       "server" | "mac", # destination
#   "type":      "query" | "response" | "ping" | "relay",
#   "payload":   "...",            # the actual text
#   "hops":      0,                # incremented at each relay
#   "ttl":       5,                # decremented at each relay, drop at 0
#   "ts":        1234567890.123    # unix timestamp
# }


@dataclass
class ConnectedDevice:
    mac:     str
    client:  BleakClient
    name:    str = "unknown"
    hops:    int = 0           # 0 = directly connected, 1+ = via relay


@dataclass
class BNNGateway:
    devices:      Dict[str, ConnectedDevice] = field(default_factory=dict)
    seen_msg_ids: set = field(default_factory=set)  # dedup mesh floods

    # ── scan & connect ─────────────────────────────────────────────────────
    async def start(self):
        log.info("B#NN Gateway starting — scanning for BLE clients…")
        while True:
            await self._scan_and_connect()
            await asyncio.sleep(RECONNECT_DELAY)

    async def _scan_and_connect(self):
        log.info("Scanning for B#NN devices…")
        devices = await BleakScanner.discover(timeout=SCAN_TIMEOUT)

        for d in devices:
            if d.address in self.devices:
                continue
            if d.name and "BNN" in d.name.upper():
                log.info(f"Found B#NN device: {d.name} [{d.address}]")
                asyncio.create_task(self._connect_device(d.address, d.name))

    async def _connect_device(self, mac: str, name: str):
        try:
            client = BleakClient(mac, disconnected_callback=self._on_disconnect)
            await client.connect()
            log.info(f"Connected: {name} [{mac}]")

            cd = ConnectedDevice(mac=mac, client=client, name=name)
            self.devices[mac] = cd

            # Subscribe to TX characteristic (client writes prompts here)
            await client.start_notify(BNN_TX_CHAR_UUID, self._on_message_received)
            log.info(f"Subscribed to notifications from {name}")
        except Exception as e:
            log.error(f"Failed to connect {mac}: {e}")

    def _on_disconnect(self, client: BleakClient):
        mac = client.address
        if mac in self.devices:
            log.warning(f"Device disconnected: {self.devices[mac].name} [{mac}]")
            del self.devices[mac]

    # ── receive message from BLE client ───────────────────────────────────
    def _on_message_received(self, char: BleakGATTCharacteristic, data: bytearray):
        try:
            packet = json.loads(data.decode("utf-8"))
            asyncio.create_task(self._handle_packet(packet))
        except json.JSONDecodeError:
            log.warning("Received malformed packet — ignoring")

    async def _handle_packet(self, packet: dict):
        msg_id = packet.get("msg_id", "")
        ptype  = packet.get("type", "query")
        src    = packet.get("src", "unknown")
        ttl    = packet.get("ttl", MAX_HOPS)

        # ── dedup: ignore packets we've already handled (mesh flood control)
        if msg_id in self.seen_msg_ids:
            return
        self.seen_msg_ids.add(msg_id)
        if len(self.seen_msg_ids) > 1000:   # prevent memory growth
            self.seen_msg_ids = set(list(self.seen_msg_ids)[-500:])

        log.info(f"Packet from {src} | type={ptype} | ttl={ttl}")

        if ptype == "ping":
            await self._send_to_device(src, _make_packet(
                src="server", dst=src, ptype="pong",
                payload="B#NN server online"
            ))
            return

        if ptype == "query":
            await self._route_query(packet)
            return

        if ptype == "relay":
            # Re-broadcast to our own connected devices (extend range)
            if ttl > 0:
                packet["hops"] += 1
                packet["ttl"]  -= 1
                await self._broadcast(packet, exclude=src)
            return

    # ── route query to Flask / Ollama ──────────────────────────────────────
    async def _route_query(self, packet: dict):
        src     = packet.get("src", "unknown")
        prompt  = packet.get("payload", "")

        if not prompt:
            return

        log.info(f"Routing to AI: '{prompt[:60]}…'")

        try:
            loop = asyncio.get_event_loop()
            resp = await loop.run_in_executor(
                None, _call_flask_api, prompt, src
            )
        except Exception as e:
            resp = f"Error: {e}"
            log.error(f"Flask API call failed: {e}")

        response_packet = _make_packet(
            src="server", dst=src, ptype="response", payload=resp
        )
        await self._send_to_device(src, response_packet)

    # ── send to specific device ────────────────────────────────────────────
    async def _send_to_device(self, mac: str, packet: dict):
        cd = self.devices.get(mac)
        if not cd:
            # Device not directly connected — broadcast for mesh relay
            log.info(f"{mac} not directly connected, broadcasting via mesh")
            await self._broadcast(packet)
            return

        raw = json.dumps(packet).encode("utf-8")
        try:
            # BLE has 512-byte limit — chunk if needed
            for chunk in _chunk_bytes(raw, MAX_BLE_CHUNK):
                await cd.client.write_gatt_char(BNN_RX_CHAR_UUID, chunk)
            log.info(f"Sent response to {cd.name} [{mac}]")
        except Exception as e:
            log.error(f"Failed to send to {mac}: {e}")

    # ── broadcast to all connected devices (mesh flooding) ────────────────
    async def _broadcast(self, packet: dict, exclude: Optional[str] = None):
        raw = json.dumps(packet).encode("utf-8")
        for mac, cd in list(self.devices.items()):
            if mac == exclude:
                continue
            try:
                for chunk in _chunk_bytes(raw, MAX_BLE_CHUNK):
                    await cd.client.write_gatt_char(BNN_RX_CHAR_UUID, chunk)
            except Exception as e:
                log.warning(f"Broadcast failed to {mac}: {e}")


# ── helpers ───────────────────────────────────────────────────────────────────
def _make_packet(src: str, dst: str, ptype: str, payload: str,
                  hops: int = 0, ttl: int = MAX_HOPS) -> dict:
    return {
        "msg_id":  str(uuid.uuid4()),
        "src":     src,
        "dst":     dst,
        "type":    ptype,
        "payload": payload,
        "hops":    hops,
        "ttl":     ttl,
        "ts":      time.time(),
    }


def _chunk_bytes(data: bytes, size: int):
    """Split bytes into BLE-safe chunks."""
    for i in range(0, len(data), size):
        yield data[i:i + size]


def _call_flask_api(prompt: str, device_id: str) -> str:
    """Blocking HTTP call to Flask — run in executor to avoid blocking event loop."""
    r = requests.post(
        FLASK_API_URL,
        json={"prompt": prompt, "device_id": device_id},
        timeout=120
    )
    r.raise_for_status()
    return r.json().get("response", "")


# ── entry point ───────────────────────────────────────────────────────────────
async def main():
    gateway = BNNGateway()
    log.info("B#NN BLE Gateway running. Waiting for devices…")
    await gateway.start()


if __name__ == "__main__":
    asyncio.run(main())
