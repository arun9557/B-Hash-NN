/*
 ╔══════════════════════════════════════════════════════════════════╗
 ║          B#NN  —  B Hash Neural Network                         ║
 ║          ESP32 BLE Client  v1.0                                 ║
 ║                                                                  ║
 ║  Role  : BLE Central (ESP32 connects TO phone/gateway)          ║
 ║  Model : GATT Client  <-> GATT Server (phone peripheral)       ║
 ║                                                                  ║
 ║  Flow  :                                                         ║
 ║    ESP32 --BLE--> Phone --BLE--> Gateway --HTTP--> Ollama AI    ║
 ║    Ollama AI <-- Gateway <--BLE-- Phone <--BLE-- ESP32          ║
 ╚══════════════════════════════════════════════════════════════════╝

 Features:
   - Scans for B#NN BLE peripherals (by name or service UUID)
   - Connects as GATT Client and subscribes to notifications
   - Sends AI prompts typed via Serial
   - Receives and displays AI responses on Serial
   - Heartbeat ping/pong every 10 seconds
   - Auto-reconnect with exponential backoff
   - Mesh relay forwarding (decrement TTL, increment hops)
   - Circular dedup buffer (last 50 message IDs)
   - Chunk reassembly for large BLE messages

 Hardware:
   - Any ESP32 dev board (ESP32, ESP32-S3, ESP32-C3, etc.)
   - Built-in LED on GPIO 2 (most common DevKit boards)

 Library:
   - ESP32 BLE Arduino (included with ESP32 board package)
*/

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEClient.h>
#include <Arduino.h>


// ══════════════════════════════════════════════════════════════════
//  CONFIGURATION
// ══════════════════════════════════════════════════════════════════

// GATT UUIDs — must match ble_gateway.py and BLEManager.kt exactly
static BLEUUID BNN_SERVICE_UUID("12345678-1234-5678-1234-56789abcdef0");
static BLEUUID BNN_RX_CHAR_UUID("12345678-1234-5678-1234-56789abcdef1");  // ESP32 WRITES to this (server's RX)
static BLEUUID BNN_TX_CHAR_UUID("12345678-1234-5678-1234-56789abcdef2");  // ESP32 gets NOTIFICATIONS from this (server's TX)

// Device identity
static const char* DEVICE_ID     = "esp32_01";
static const char* DEVICE_NAME   = "B#NN_ESP32";

// Timing
static const unsigned long HEARTBEAT_INTERVAL_MS  = 10000;   // ping every 10 seconds
static const unsigned long SCAN_DURATION_SEC      = 5;       // BLE scan window
static const unsigned long RECONNECT_BASE_MS      = 2000;    // first retry delay
static const unsigned long RECONNECT_MAX_MS       = 60000;   // cap for exponential backoff
static const int           MAX_RECONNECT_TRIES    = 10;      // give up after this many

// Mesh
static const int MAX_TTL       = 5;
static const int DEDUP_SIZE    = 50;   // circular buffer for message ID dedup

// Hardware
static const int LED_PIN = 2;   // built-in LED on most ESP32 DevKit boards


// ══════════════════════════════════════════════════════════════════
//  GLOBAL STATE
// ══════════════════════════════════════════════════════════════════

static BLEClient*             pClient          = nullptr;
static BLERemoteCharacteristic* pRxChar        = nullptr;   // we write to this
static BLERemoteCharacteristic* pTxChar        = nullptr;   // we get notifications from this
static BLEScan*               pBLEScan         = nullptr;
static BLEAdvertisedDevice*   pTargetDevice    = nullptr;

static bool deviceFound       = false;
static bool isConnected       = false;
static bool doConnect         = false;

// Reconnect state
static int           reconnectAttempt   = 0;
static unsigned long lastReconnectMs    = 0;
static unsigned long currentBackoffMs   = RECONNECT_BASE_MS;

// Heartbeat
static unsigned long lastHeartbeatMs    = 0;

// Serial input buffer
static String serialBuffer = "";

// Dedup circular buffer
static String dedupBuffer[DEDUP_SIZE];
static int    dedupIndex = 0;

// Chunk reassembly
// We support reassembling one chunked message at a time (simple single-sender model)
static String chunkId       = "";
static int    chunkTotal    = 0;
static String chunkPieces[32];   // max 32 chunks per message
static int    chunksReceived = 0;

// Simple message counter for unique IDs (ESP32 has no UUID library built-in)
static unsigned long msgCounter = 0;


// ══════════════════════════════════════════════════════════════════
//  ASCII ART BANNER
// ══════════════════════════════════════════════════════════════════

static void printBanner() {
  Serial.println();
  Serial.println(F("  ╔══════════════════════════════════════════════╗"));
  Serial.println(F("  ║   ____   _   _  _   _  _   _               ║"));
  Serial.println(F("  ║  | __ ) | | | || \\ | || \\ | |              ║"));
  Serial.println(F("  ║  |  _ \\ |#| | ||  \\| ||  \\| |              ║"));
  Serial.println(F("  ║  | |_) ||   | || |\\  || |\\  |              ║"));
  Serial.println(F("  ║  |____/ |_| |_||_| \\_||_| \\_|              ║"));
  Serial.println(F("  ║                                              ║"));
  Serial.println(F("  ║  B Hash Neural Network — ESP32 BLE Client   ║"));
  Serial.println(F("  ║  Offline AI over Bluetooth Mesh              ║"));
  Serial.println(F("  ╚══════════════════════════════════════════════╝"));
  Serial.println();
}


// ══════════════════════════════════════════════════════════════════
//  HELPER: Generate a simple unique message ID
// ══════════════════════════════════════════════════════════════════

static String generateMsgId() {
  msgCounter++;
  // Combine device ID + millis + counter for uniqueness
  return String(DEVICE_ID) + "-" + String(millis()) + "-" + String(msgCounter);
}


// ══════════════════════════════════════════════════════════════════
//  HELPER: Build a B#NN JSON message
// ══════════════════════════════════════════════════════════════════

/*
 Packet format:
 {
   "id":      "unique-message-id",
   "type":    "request|response|ping|pong|relay",
   "payload": "text content",
   "src":     "esp32_01",
   "dst":     "server",
   "hops":    0,
   "ttl":     5,
   "ts":      1234567890
 }
*/

static String buildMessage(const char* type, const String& payload,
                           const char* dst = "server", int hops = 0, int ttl = MAX_TTL) {
  String id = generateMsgId();
  unsigned long ts = millis() / 1000;  // seconds since boot (no RTC)

  String json = "{";
  json += "\"id\":\"" + id + "\",";
  json += "\"type\":\"" + String(type) + "\",";

  // Escape any quotes in payload for valid JSON
  String escapedPayload = payload;
  escapedPayload.replace("\\", "\\\\");
  escapedPayload.replace("\"", "\\\"");
  json += "\"payload\":\"" + escapedPayload + "\",";

  json += "\"src\":\"" + String(DEVICE_ID) + "\",";
  json += "\"dst\":\"" + String(dst) + "\",";
  json += "\"hops\":" + String(hops) + ",";
  json += "\"ttl\":" + String(ttl) + ",";
  json += "\"ts\":" + String(ts);
  json += "}";

  return json;
}


// ══════════════════════════════════════════════════════════════════
//  HELPER: Build a relay-forwarded message (preserves original ID)
// ══════════════════════════════════════════════════════════════════

static String buildRelayForward(const String& originalId, const String& payload,
                                const String& src, const String& dst,
                                int hops, int ttl) {
  unsigned long ts = millis() / 1000;

  String json = "{";
  json += "\"id\":\"" + originalId + "\",";
  json += "\"type\":\"relay\",";

  String escapedPayload = payload;
  escapedPayload.replace("\\", "\\\\");
  escapedPayload.replace("\"", "\\\"");
  json += "\"payload\":\"" + escapedPayload + "\",";

  json += "\"src\":\"" + src + "\",";
  json += "\"dst\":\"" + dst + "\",";
  json += "\"hops\":" + String(hops) + ",";
  json += "\"ttl\":" + String(ttl) + ",";
  json += "\"ts\":" + String(ts);
  json += "}";

  return json;
}


// ══════════════════════════════════════════════════════════════════
//  DEDUP: Check if we've already seen this message ID
// ══════════════════════════════════════════════════════════════════

static bool isDuplicate(const String& msgId) {
  if (msgId.length() == 0) return false;

  for (int i = 0; i < DEDUP_SIZE; i++) {
    if (dedupBuffer[i] == msgId) {
      return true;
    }
  }
  return false;
}

static void recordMsgId(const String& msgId) {
  if (msgId.length() == 0) return;

  dedupBuffer[dedupIndex] = msgId;
  dedupIndex = (dedupIndex + 1) % DEDUP_SIZE;
}


// ══════════════════════════════════════════════════════════════════
//  JSON PARSER: Minimal field extraction (no external JSON library)
// ══════════════════════════════════════════════════════════════════

/*
 Simple JSON string field extractor.
 Handles: "key":"value" and "key":number
 Does NOT handle nested objects, arrays, or escaped quotes in values.
 Sufficient for the flat B#NN packet format.
*/

static String jsonGetString(const String& json, const String& key) {
  String searchKey = "\"" + key + "\":\"";
  int startIdx = json.indexOf(searchKey);
  if (startIdx == -1) return "";

  startIdx += searchKey.length();
  int endIdx = json.indexOf("\"", startIdx);
  if (endIdx == -1) return "";

  return json.substring(startIdx, endIdx);
}

static int jsonGetInt(const String& json, const String& key) {
  // Try quoted integer first: "key":"123"
  String strVal = jsonGetString(json, key);
  if (strVal.length() > 0) {
    return strVal.toInt();
  }

  // Try unquoted integer: "key":123
  String searchKey = "\"" + key + "\":";
  int startIdx = json.indexOf(searchKey);
  if (startIdx == -1) return 0;

  startIdx += searchKey.length();

  // Skip whitespace
  while (startIdx < (int)json.length() && json.charAt(startIdx) == ' ') {
    startIdx++;
  }

  // Read digits (and possible negative sign)
  String numStr = "";
  while (startIdx < (int)json.length()) {
    char c = json.charAt(startIdx);
    if (c == '-' || (c >= '0' && c <= '9')) {
      numStr += c;
      startIdx++;
    } else {
      break;
    }
  }

  return numStr.toInt();
}


// ══════════════════════════════════════════════════════════════════
//  CHUNK REASSEMBLY
// ══════════════════════════════════════════════════════════════════

/*
 Chunked messages arrive as:
 {"chunk_id":"xxx", "chunk_idx":0, "chunk_total":3, "data":"..."}

 We buffer pieces and reassemble when all chunks arrive.
*/

static String handleChunk(const String& raw) {
  String cid    = jsonGetString(raw, "chunk_id");
  int    cidx   = jsonGetInt(raw, "chunk_idx");
  int    ctotal = jsonGetInt(raw, "chunk_total");
  String data   = jsonGetString(raw, "data");

  if (cid.length() == 0 || ctotal <= 0) return "";

  // New chunked message or continuation?
  if (chunkId != cid) {
    // Start fresh for this chunk_id
    chunkId       = cid;
    chunkTotal    = ctotal;
    chunksReceived = 0;
    for (int i = 0; i < 32; i++) {
      chunkPieces[i] = "";
    }
  }

  // Store this piece
  if (cidx >= 0 && cidx < 32) {
    chunkPieces[cidx] = data;
    chunksReceived++;
  }

  Serial.printf("  [Chunk %d/%d for %s]\n", cidx + 1, ctotal, cid.c_str());

  // All chunks received?
  if (chunksReceived >= chunkTotal) {
    String assembled = "";
    for (int i = 0; i < chunkTotal; i++) {
      assembled += chunkPieces[i];
    }

    // Reset chunk state
    chunkId       = "";
    chunkTotal    = 0;
    chunksReceived = 0;

    Serial.printf("  [Chunk reassembly complete: %d bytes]\n", assembled.length());
    return assembled;
  }

  return "";  // still waiting for more chunks
}


// ══════════════════════════════════════════════════════════════════
//  BLE SEND: Write data to the server's RX characteristic
// ══════════════════════════════════════════════════════════════════

static void bleSend(const String& message) {
  if (!isConnected || pRxChar == nullptr) {
    Serial.println(F("[BLE] Cannot send — not connected."));
    return;
  }

  // BLE write size limit check — chunk if needed
  if (message.length() <= 512) {
    pRxChar->writeValue((uint8_t*)message.c_str(), message.length(), true);
    Serial.printf("[BLE] Sent %d bytes\n", message.length());
  } else {
    // Split into chunks and send each one
    String cid = generateMsgId();
    int chunkSize = 384;  // leave room for chunk envelope JSON overhead
    int total = (message.length() + chunkSize - 1) / chunkSize;

    Serial.printf("[BLE] Message too large (%d bytes) — splitting into %d chunks\n",
                  message.length(), total);

    for (int i = 0; i < total; i++) {
      int start = i * chunkSize;
      int len   = min(chunkSize, (int)message.length() - start);
      String piece = message.substring(start, start + len);

      // Escape the piece for embedding in JSON
      piece.replace("\\", "\\\\");
      piece.replace("\"", "\\\"");

      String envelope = "{";
      envelope += "\"chunk_id\":\"" + cid + "\",";
      envelope += "\"chunk_idx\":" + String(i) + ",";
      envelope += "\"chunk_total\":" + String(total) + ",";
      envelope += "\"data\":\"" + piece + "\"";
      envelope += "}";

      pRxChar->writeValue((uint8_t*)envelope.c_str(), envelope.length(), true);
      delay(50);  // small pause between chunks so peripheral can process
    }
  }
}


// ══════════════════════════════════════════════════════════════════
//  MESSAGE DISPATCH: Handle a complete received message
// ══════════════════════════════════════════════════════════════════

static void handleMessage(const String& json) {
  String msgId   = jsonGetString(json, "id");
  String msgType = jsonGetString(json, "type");
  String payload = jsonGetString(json, "payload");
  String src     = jsonGetString(json, "src");
  int    hops    = jsonGetInt(json, "hops");
  int    ttl     = jsonGetInt(json, "ttl");

  // ── Dedup check ─────────────────────────────────────────────
  if (isDuplicate(msgId)) {
    Serial.println(F("  [Duplicate message — dropped]"));
    return;
  }
  recordMsgId(msgId);

  // ── Dispatch by type ────────────────────────────────────────
  if (msgType == "pong") {
    // Heartbeat reply from server — all good
    Serial.println(F("[♥] Heartbeat acknowledged by server."));
  }
  else if (msgType == "ping") {
    // Server is checking if we're alive — reply with pong
    Serial.println(F("[♥] Ping from server — sending pong."));
    String pong = buildMessage("pong", "B#NN ESP32 online");
    bleSend(pong);
  }
  else if (msgType == "response") {
    // AI response from server — display it
    Serial.println(F(""));
    Serial.println(F("┌─────────────────────────────────────────────┐"));
    Serial.println(F("│  AI Response                                │"));
    Serial.println(F("├─────────────────────────────────────────────┤"));
    Serial.print(F("│  "));
    Serial.println(payload);
    Serial.println(F("└─────────────────────────────────────────────┘"));
    Serial.println();
    Serial.print(F("B#NN > "));  // prompt for next input
  }
  else if (msgType == "relay") {
    // Mesh relay packet — forward if TTL allows
    if (ttl > 0) {
      Serial.printf("[Relay] Forwarding message (hops=%d, ttl=%d → %d)\n",
                    hops, ttl, ttl - 1);
      String forward = buildRelayForward(msgId, payload, src, "server",
                                         hops + 1, ttl - 1);
      bleSend(forward);
    } else {
      Serial.println(F("[Relay] TTL expired — discarding."));
    }
  }
  else if (msgType == "request") {
    // Shouldn't normally receive requests on the client side
    Serial.print(F("[Request from "));
    Serial.print(src);
    Serial.print(F("] "));
    Serial.println(payload);
  }
  else {
    Serial.print(F("[Unknown type: "));
    Serial.print(msgType);
    Serial.print(F("] "));
    Serial.println(payload);
  }
}


// ══════════════════════════════════════════════════════════════════
//  BLE NOTIFICATION CALLBACK
// ══════════════════════════════════════════════════════════════════

/*
 Called when the peripheral (phone/gateway) sends a notification
 on the TX characteristic. This is how we receive AI responses,
 pongs, and relay messages.
*/

static void notifyCallback(BLERemoteCharacteristic* pChar,
                            uint8_t* pData, size_t length, bool isNotify) {
  String raw = String((char*)pData).substring(0, length);

  Serial.printf("[BLE] Notification received (%d bytes)\n", length);

  // Check if this is a chunked message
  if (raw.indexOf("\"chunk_id\"") >= 0) {
    String assembled = handleChunk(raw);
    if (assembled.length() > 0) {
      handleMessage(assembled);
    }
    return;
  }

  // Regular single-packet message
  handleMessage(raw);
}


// ══════════════════════════════════════════════════════════════════
//  BLE CLIENT CALLBACKS
// ══════════════════════════════════════════════════════════════════

class BNNClientCallback : public BLEClientCallbacks {
  void onConnect(BLEClient* client) override {
    Serial.println(F("[BLE] Connected to peripheral!"));
    isConnected = true;
    reconnectAttempt = 0;
    currentBackoffMs = RECONNECT_BASE_MS;
    digitalWrite(LED_PIN, HIGH);  // LED ON = connected
  }

  void onDisconnect(BLEClient* client) override {
    Serial.println(F("[BLE] Disconnected from peripheral."));
    isConnected = false;
    pRxChar     = nullptr;
    pTxChar     = nullptr;
    digitalWrite(LED_PIN, LOW);   // LED OFF = disconnected
  }
};


// ══════════════════════════════════════════════════════════════════
//  BLE SCAN CALLBACK: Find B#NN peripherals
// ══════════════════════════════════════════════════════════════════

class BNNAdvertisedDeviceCallback : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    bool nameMatch = false;
    bool uuidMatch = false;

    // Check device name for "BNN" (case-insensitive check)
    if (advertisedDevice.haveName()) {
      String name = advertisedDevice.getName().c_str();
      String nameUpper = name;
      nameUpper.toUpperCase();

      // Strip non-alphanumeric for matching (handles "B#NN" -> "BNN")
      String normalized = "";
      for (unsigned int i = 0; i < nameUpper.length(); i++) {
        char c = nameUpper.charAt(i);
        if (isAlphaNumeric(c)) {
          normalized += c;
        }
      }
      if (normalized.indexOf("BNN") >= 0) {
        nameMatch = true;
      }
    }

    // Check advertised service UUIDs
    if (advertisedDevice.haveServiceUUID() &&
        advertisedDevice.isAdvertisingService(BNN_SERVICE_UUID)) {
      uuidMatch = true;
    }

    if (nameMatch || uuidMatch) {
      Serial.println(F(""));
      Serial.println(F("╔═══════════════════════════════════════════╗"));
      Serial.print(F("║  B#NN Device Found: "));
      Serial.println(advertisedDevice.getName().c_str());
      Serial.print(F("║  Address: "));
      Serial.println(advertisedDevice.getAddress().toString().c_str());
      Serial.print(F("║  RSSI: "));
      Serial.println(advertisedDevice.getRSSI());
      Serial.println(F("╚═══════════════════════════════════════════╝"));
      Serial.println();

      // Stop scanning and prepare to connect
      pBLEScan->stop();
      pTargetDevice = new BLEAdvertisedDevice(advertisedDevice);
      deviceFound = true;
      doConnect   = true;
    }
  }
};


// ══════════════════════════════════════════════════════════════════
//  CONNECT TO PERIPHERAL
// ══════════════════════════════════════════════════════════════════

static bool connectToServer() {
  if (pTargetDevice == nullptr) {
    Serial.println(F("[BLE] No target device to connect to."));
    return false;
  }

  Serial.print(F("[BLE] Connecting to "));
  Serial.print(pTargetDevice->getName().c_str());
  Serial.println(F("…"));

  // Create BLE client
  pClient = BLEDevice::createClient();
  pClient->setClientCallbacks(new BNNClientCallback());

  // Connect to the peripheral
  if (!pClient->connect(pTargetDevice)) {
    Serial.println(F("[BLE] Connection failed."));
    return false;
  }

  Serial.println(F("[BLE] Connected. Discovering services…"));

  // Get the B#NN service
  BLERemoteService* pService = pClient->getService(BNN_SERVICE_UUID);
  if (pService == nullptr) {
    Serial.println(F("[BLE] B#NN service not found — wrong device?"));
    pClient->disconnect();
    return false;
  }

  Serial.println(F("[BLE] B#NN service found!"));

  // Get the RX characteristic (we WRITE to this)
  pRxChar = pService->getCharacteristic(BNN_RX_CHAR_UUID);
  if (pRxChar == nullptr) {
    Serial.println(F("[BLE] RX characteristic not found."));
    pClient->disconnect();
    return false;
  }

  if (!pRxChar->canWrite()) {
    Serial.println(F("[BLE] WARNING: RX characteristic is not writable."));
  }

  Serial.println(F("[BLE] RX characteristic ready (we write to this)."));

  // Get the TX characteristic (we receive NOTIFICATIONS from this)
  pTxChar = pService->getCharacteristic(BNN_TX_CHAR_UUID);
  if (pTxChar == nullptr) {
    Serial.println(F("[BLE] TX characteristic not found."));
    pClient->disconnect();
    return false;
  }

  // Subscribe to notifications
  if (pTxChar->canNotify()) {
    pTxChar->registerForNotify(notifyCallback);
    Serial.println(F("[BLE] Subscribed to TX notifications."));
  } else {
    Serial.println(F("[BLE] WARNING: TX characteristic does not support notifications."));
  }

  Serial.println(F(""));
  Serial.println(F("═══════════════════════════════════════════════"));
  Serial.println(F("  ✓ Connected to B#NN network!"));
  Serial.println(F("  Type a message and press Enter to send."));
  Serial.println(F("═══════════════════════════════════════════════"));
  Serial.println(F(""));
  Serial.print(F("B#NN > "));

  // Reset heartbeat timer
  lastHeartbeatMs = millis();

  return true;
}


// ══════════════════════════════════════════════════════════════════
//  SCAN FOR DEVICES
// ══════════════════════════════════════════════════════════════════

static void startScan() {
  Serial.println(F("[BLE] Scanning for B#NN peripherals…"));

  deviceFound = false;
  doConnect   = false;

  pBLEScan->setAdvertisedDeviceCallbacks(new BNNAdvertisedDeviceCallback());
  pBLEScan->setActiveScan(true);     // active scan for more advertisement data
  pBLEScan->setWindow(99);           // scan window in ms
  pBLEScan->setInterval(100);        // scan interval in ms
  pBLEScan->start(SCAN_DURATION_SEC, false);
}


// ══════════════════════════════════════════════════════════════════
//  LED BLINK: Visual feedback during scanning
// ══════════════════════════════════════════════════════════════════

static unsigned long lastBlinkMs = 0;
static bool          ledState    = false;

static void blinkLED() {
  // Blink LED while disconnected (scanning)
  if (!isConnected) {
    if (millis() - lastBlinkMs > 500) {
      ledState = !ledState;
      digitalWrite(LED_PIN, ledState ? HIGH : LOW);
      lastBlinkMs = millis();
    }
  }
}


// ══════════════════════════════════════════════════════════════════
//  SETUP
// ══════════════════════════════════════════════════════════════════

void setup() {
  // Initialize Serial for user interaction
  Serial.begin(115200);
  delay(1000);  // wait for Serial to stabilize

  // Print startup banner
  printBanner();

  // Setup LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Initialize BLE
  Serial.println(F("[BLE] Initializing BLE stack…"));
  BLEDevice::init(DEVICE_NAME);

  // Create scan object
  pBLEScan = BLEDevice::getScan();

  Serial.println(F("[BLE] Ready. Starting scan…"));
  Serial.println();

  // Start initial scan
  startScan();
}


// ══════════════════════════════════════════════════════════════════
//  MAIN LOOP
// ══════════════════════════════════════════════════════════════════

void loop() {
  unsigned long now = millis();

  // ── Connection attempt ─────────────────────────────────────────
  if (doConnect && !isConnected) {
    doConnect = false;

    if (connectToServer()) {
      Serial.println(F("[BLE] Connection established successfully."));
    } else {
      Serial.println(F("[BLE] Connection attempt failed."));
      // Will retry via reconnect logic below
    }
  }

  // ── Auto-reconnect with exponential backoff ────────────────────
  if (!isConnected && deviceFound && !doConnect) {
    if (now - lastReconnectMs >= currentBackoffMs) {
      reconnectAttempt++;

      if (reconnectAttempt > MAX_RECONNECT_TRIES) {
        Serial.println(F("[BLE] Max reconnect attempts reached. Restarting scan…"));
        reconnectAttempt = 0;
        currentBackoffMs = RECONNECT_BASE_MS;
        deviceFound      = false;
        startScan();
      } else {
        Serial.printf("[BLE] Reconnect attempt %d/%d (backoff: %lu ms)…\n",
                      reconnectAttempt, MAX_RECONNECT_TRIES, currentBackoffMs);

        lastReconnectMs = now;
        doConnect       = true;

        // Exponential backoff: double the delay each time, capped
        currentBackoffMs = min(currentBackoffMs * 2, RECONNECT_MAX_MS);
      }
    }
  }

  // ── Scan again if no device found ──────────────────────────────
  if (!isConnected && !deviceFound) {
    // Rescan periodically
    static unsigned long lastScanMs = 0;
    if (now - lastScanMs > (SCAN_DURATION_SEC * 1000 + 3000)) {
      lastScanMs = now;
      startScan();
    }
  }

  // ── Heartbeat ping every 10 seconds ────────────────────────────
  if (isConnected && (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS)) {
    lastHeartbeatMs = now;
    String ping = buildMessage("ping", "heartbeat");
    bleSend(ping);
    Serial.println(F("[♥] Heartbeat ping sent."));
  }

  // ── Read Serial input ──────────────────────────────────────────
  while (Serial.available()) {
    char c = Serial.read();

    if (c == '\n' || c == '\r') {
      serialBuffer.trim();

      if (serialBuffer.length() > 0) {
        if (isConnected) {
          Serial.print(F("[You] "));
          Serial.println(serialBuffer);

          // Build and send AI request
          String request = buildMessage("request", serialBuffer);
          bleSend(request);

          Serial.println(F("[BLE] Request sent — waiting for AI response…"));
        } else {
          Serial.println(F("[!] Not connected. Message not sent."));
          Serial.println(F("[!] Waiting for connection to B#NN network…"));
        }

        serialBuffer = "";
      }
    } else {
      serialBuffer += c;
    }
  }

  // ── LED feedback ───────────────────────────────────────────────
  blinkLED();

  // Small delay to prevent busy-looping
  delay(10);
}
