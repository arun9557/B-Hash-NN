# B#NN — B Hash Neural Network

Offline AI over Bluetooth mesh.  
One device runs the model. Everyone else connects via BLE — no internet needed.

```
Phone A ──BLE──► Relay Phone B ──BLE──► Gateway (Laptop/Pi)
                                              │
                                         Ollama AI
```

---

## Server Setup

### 1. Install Ollama
```bash
# Linux / Raspberry Pi
curl -fsSL https://ollama.com/install.sh | sh

# Pull a small model (good for Pi)
ollama pull phi3

# Or a bigger model for laptop
ollama pull llama3
```

### 2. Install Python dependencies
```bash
pip install -r requirements.txt
```

### 3. Start the API server (Terminal 1)
```bash
python server.py
```
Flask API will be live at `http://localhost:5000`

### 4. Start the BLE Gateway (Terminal 2)
```bash
# Linux: needs bluetooth permissions
sudo python ble_gateway.py

# Raspberry Pi: add user to bluetooth group first
sudo usermod -aG bluetooth $USER
python ble_gateway.py
```

---

## API Endpoints

| Method | URL       | Body                                  | Description        |
|--------|-----------|---------------------------------------|--------------------|
| POST   | `/chat`   | `{"prompt":"hi","device_id":"ph1"}`   | Send prompt to AI  |
| GET    | `/health` | —                                     | Server status      |
| GET    | `/model`  | —                                     | Active model info  |

### Quick test (curl)
```bash
curl -X POST http://localhost:5000/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is 2+2?", "device_id": "test"}'
```

---

## BLE Packet Format

Every message over BLE is a JSON string:

```json
{
  "id":      "unique-message-id",
  "type":    "request | response | ping | pong | relay",
  "payload": "the actual text",
  "src":     "device_id_or_server",
  "dst":     "server_or_device_id",
  "hops":    0,
  "ttl":     5,
  "ts":      1234567890.123
}
```

### Field reference

| Field     | Type   | Description                                                    |
|-----------|--------|----------------------------------------------------------------|
| `id`      | string | Unique message ID (UUID or device-generated) — used for dedup |
| `type`    | string | One of: `request`, `response`, `ping`, `pong`, `relay`        |
| `payload` | string | The actual content (prompt text, AI response, status, etc.)    |
| `src`     | string | Sender identifier (`"server"`, device MAC, or device ID)       |
| `dst`     | string | Destination (`"server"`, `"broadcast"`, or specific device)    |
| `hops`    | int    | Number of relay hops this message has traversed                |
| `ttl`     | int    | Time-to-live — decremented at each relay hop, prevents loops   |
| `ts`      | float  | Timestamp (seconds since epoch or since boot)                  |

### Message types

| Type       | Direction        | Description                              |
|------------|------------------|------------------------------------------|
| `request`  | Client → Server  | AI prompt from a device                  |
| `response` | Server → Client  | AI answer back to the device             |
| `ping`     | Either direction | Heartbeat probe ("are you alive?")       |
| `pong`     | Either direction | Heartbeat reply ("yes, I'm alive")       |
| `relay`    | Device → Device  | Mesh-forwarded packet (TTL decremented)  |

---

## BLE UUIDs

```
Service:  12345678-1234-5678-1234-56789abcdef0
Char 1:   12345678-1234-5678-1234-56789abcdef1
Char 2:   12345678-1234-5678-1234-56789abcdef2
```

### Perspective table

The same characteristic is called different things depending on who you are:

| UUID        | Server/Gateway perspective | Phone/ESP32 peripheral perspective |
|-------------|----------------------------|------------------------------------|
| `…def1`     | Gateway WRITES to this     | Peripheral receives (RX)           |
| `…def2`     | Gateway gets NOTIFIED      | Peripheral sends/notifies (TX)     |

> **Rule of thumb:** `def1` = the pipe data flows INTO the peripheral. `def2` = the pipe data flows OUT of the peripheral.

Name your BLE peripheral with **"BNN"** in the name so the gateway auto-discovers it.

---

## Relay Mode (Mesh)

Relay mode extends the network range by letting devices forward messages for each other.

```
Phone A (50m) → Phone B (relay, 50m) → Phone C (relay, 50m) → Server
                                                               Total: ~150m
```

### How relay works

1. Device receives a message with `"type": "relay"` and `ttl > 0`
2. Device decrements `ttl` by 1, increments `hops` by 1
3. Device forwards the modified message to the next hop
4. If `ttl == 0`, the message is discarded (prevents infinite loops)

### Deduplication

Every node keeps a buffer of recently-seen message IDs (`id` field):
- Gateway: last 2000 IDs
- Android: handled by BLEManager
- ESP32: circular buffer of last 50 IDs

Duplicate messages are silently dropped to prevent mesh flooding.

---

## ESP32 Client Setup

### Hardware
- Any ESP32 development board (ESP32, ESP32-S3, ESP32-C3)
- Built-in LED on GPIO 2 (most DevKit boards)

### Software
1. Install [Arduino IDE](https://www.arduino.cc/en/software) or PlatformIO
2. Add ESP32 board support:
   - Arduino IDE: `File → Preferences → Board Manager URLs`, add:  
     `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
   - Install "ESP32 by Espressif Systems" from Board Manager
3. Open `esp32_client/esp32_client.ino`
4. Select your board (e.g., "ESP32 Dev Module")
5. Upload and open Serial Monitor at 115200 baud

### Usage
- The ESP32 will automatically scan for B#NN peripherals
- Once connected, type a message in Serial Monitor and press Enter
- AI responses appear in the Serial output
- LED: **solid** = connected, **blinking** = scanning/disconnected

---

## Model Recommendations

| Device          | Recommended model | RAM needed |
|-----------------|-------------------|------------|
| Raspberry Pi 4  | phi3 / tinyllama  | 4 GB       |
| Laptop (8 GB)   | llama3.2:3b       | 6 GB       |
| Laptop (16 GB+) | llama3 / mistral  | 8 GB+      |

---

## Next: Client Side
- Android app (BLE GATT client + relay mode) — see `android/`
- ESP32 / Arduino firmware — see `esp32_client/`
- Bitchat protocol integration
