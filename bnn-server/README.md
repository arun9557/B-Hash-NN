# B#NN — B Hash Neural Network

Offline AI over Bluetooth mesh.  
One device runs the model. Everyone else connects via BLE — no internet needed.

```
Phone A ──BLE──► Relay Phone B ──BLE──► Server (Laptop/Pi)
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
  "msg_id":  "uuid4",
  "src":     "device_mac_or_server",
  "dst":     "server_or_device_mac",
  "type":    "query | response | ping | relay",
  "payload": "the actual text",
  "hops":    0,
  "ttl":     5,
  "ts":      1234567890.123
}
```

- `ttl` (time-to-live): decremented at each mesh relay hop — prevents infinite loops
- `msg_id`: used for deduplication across mesh floods

---

## BLE UUIDs (must match client)

```
Service:  12345678-1234-5678-1234-56789abcdef0
TX Char:  12345678-1234-5678-1234-56789abcdef1  (client writes)
RX Char:  12345678-1234-5678-1234-56789abcdef2  (server notifies)
```

Name your BLE peripheral with "BNN" in the name so the gateway auto-discovers it.

---

## Mesh Range Extension

The more phones connected, the further the range:

```
Phone A (50m) → Phone B (relay, 50m) → Phone C (relay, 50m) → Server
                                                               Total: ~150m
```

Each relay device runs the B#NN client app in relay mode (next phase).

---

## Model recommendations

| Device          | Recommended model | RAM needed |
|-----------------|-------------------|------------|
| Raspberry Pi 4  | phi3 / tinyllama  | 4 GB       |
| Laptop (8 GB)   | llama3.2:3b       | 6 GB       |
| Laptop (16 GB+) | llama3 / mistral  | 8 GB+      |

---

## Next: Client Side
- Android app (BLE GATT client + relay mode)
- ESP32 / Arduino firmware
- Bitchat protocol integration
