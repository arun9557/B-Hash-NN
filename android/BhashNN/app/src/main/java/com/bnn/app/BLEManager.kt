package com.bnn.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

// ══════════════════════════════════════════════════════════════════
//  B#NN BLE UUIDs — must match ble_gateway.py exactly
// ══════════════════════════════════════════════════════════════════

val BNN_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
val BNN_RX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
val BNN_TX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")

// Standard CCCD UUID — needed to enable BLE notifications on the TX characteristic
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val TAG = "B#NN-BLE"
private const val DEFAULT_DEVICE_NAME = "B#NN_DEVICE"
private const val HEARTBEAT_INTERVAL_MS = 10_000L   // send ping every 10 seconds
private const val RELAY_SCAN_PERIOD_MS = 15_000L     // scan for 15s, pause 5s
private const val RELAY_SCAN_PAUSE_MS = 5_000L
private const val MAX_SEEN_IDS = 500                  // max dedup cache size

// ══════════════════════════════════════════════════════════════════
//  CALLBACK INTERFACE  — BLEManager talks back to MainActivity
// ══════════════════════════════════════════════════════════════════

interface BLECallback {
    fun onConnected(deviceName: String)
    fun onDisconnected()
    fun onMessageReceived(message: String, isRelay: Boolean = false)
    fun onStatusChanged(status: String)
    fun onError(error: String)
    fun onRelayPeerConnected(name: String)
    fun onRelayPeerDisconnected(name: String)
}

// ══════════════════════════════════════════════════════════════════
//  BLE MANAGER  — all BLE logic lives here
// ══════════════════════════════════════════════════════════════════

@SuppressLint("MissingPermission")   // permissions checked in MainActivity before calling any method
class BLEManager(
    private val context: Context,
    private val callback: BLECallback
) {

    // ── BLE system objects ────────────────────────────────────────
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isAdvertising = false

    // ── TX characteristic — we notify the central through this ───
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // ── Heartbeat ─────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    // ── Chunk reassembly (for large messages from laptop) ─────────
    private val chunkBuffer = mutableMapOf<String, MutableMap<Int, String>>()  // chunkId -> {index -> data}
    private val chunkMeta   = mutableMapOf<String, Int>()                      // chunkId -> totalChunks

    // ── Relay Mode ────────────────────────────────────────────────
    private var relayEnabled = false
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val relayPeers = mutableMapOf<String, BluetoothGatt>()  // address -> BluetoothGatt
    private val pendingConnections = mutableSetOf<String>()          // addresses currently connecting
    private val seenMessageIds = LinkedHashSet<String>()             // mesh dedup
    private var relayScanRunnable: Runnable? = null

    val isConnected: Boolean
        get() = connectedDevice != null

    val relayPeerCount: Int
        get() = relayPeers.size

    // ──────────────────────────────────────────────────────────────
    //  START  — setup GATT server and begin advertising
    // ──────────────────────────────────────────────────────────────

    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            callback.onError("Bluetooth is off. Please enable it.")
            return
        }

        // 1-4. Setup GATT Server and Service first
        setupGattServer()

        // 5. THEN start advertising
        startAdvertising()

        val myName = BnnDeviceIdentifier.get(context)
        callback.onStatusChanged("Advertising as \"$myName\"…")
        Log.i(TAG, "B#NN BLE Peripheral started.")
    }

    fun stop() {
        stopHeartbeat()
        stopRelayScan()
        disconnectAllRelayPeers()
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        Log.i(TAG, "B#NN BLE Peripheral stopped.")
    }

    // ──────────────────────────────────────────────────────────────
    //  RELAY MODE  — toggle scanning and forwarding
    // ──────────────────────────────────────────────────────────────

    fun setRelayMode(enabled: Boolean) {
        relayEnabled = enabled
        Log.i(TAG, "Relay mode ${if (enabled) "ENABLED" else "DISABLED"}")
        if (enabled) {
            startRelayScan()
        } else {
            stopRelayScan()
            disconnectAllRelayPeers()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  RELAY SCANNING  — discover nearby B#NN devices
    // ──────────────────────────────────────────────────────────────

    private fun startRelayScan() {
        if (isScanning) return
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }
        scheduleRelayScanCycle()
    }

    private fun scheduleRelayScanCycle() {
        if (!relayEnabled) return

        val scanner = bleScanner ?: return

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        // Scan filters: match the B#NN service UUID. We do not restrict by device name
        // at the OS level to avoid filtering bugs on various Android BLE stacks.
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BNN_SERVICE_UUID))
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, relayScanCallback)
        isScanning = true
        Log.d(TAG, "Relay scan started with UUID and name filters")

        // Stop scan after RELAY_SCAN_PERIOD_MS, then restart after pause
        relayScanRunnable = Runnable {
            stopCurrentScan()
            if (relayEnabled) {
                mainHandler.postDelayed({
                    if (relayEnabled) {
                        scheduleRelayScanCycle()
                    }
                }, RELAY_SCAN_PAUSE_MS)
            }
        }
        mainHandler.postDelayed(relayScanRunnable!!, RELAY_SCAN_PERIOD_MS)
    }

    private fun stopCurrentScan() {
        if (!isScanning) return
        try {
            bleScanner?.stopScan(relayScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
        isScanning = false
        Log.d(TAG, "Relay scan stopped")
    }

    private fun stopRelayScan() {
        relayScanRunnable?.let { mainHandler.removeCallbacks(it) }
        relayScanRunnable = null
        stopCurrentScan()
        bleScanner = null
    }

    private val relayScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val name = device.name ?: ""

            // Skip if already connected or connecting to this device
            if (relayPeers.containsKey(address) || pendingConnections.contains(address)) return

            // Skip ourselves (shouldn't happen, but just in case)
            if (address == bluetoothAdapter.address) return

            // Skip the gateway device (already connected as server)
            if (connectedDevice?.address == address) return

            val myName = BnnDeviceIdentifier.get(context)
            // Symmetrical connection avoidance: only connect if our name is alphabetically
            // smaller than the peer's name/address. This ensures exactly one client-server link.
            val peerId = if (name.isNotEmpty()) name else address
            val shouldConnect = myName.compareTo(peerId) < 0

            // Check name or service UUID match for B#NN devices
            val hasServiceUuid = result.scanRecord?.serviceUuids?.any { it.uuid == BNN_SERVICE_UUID } == true
            val isBnnName = name.startsWith("Phone_", ignoreCase = true) || name.contains("BNN", ignoreCase = true) || name.contains("B#NN", ignoreCase = true)
            
            if (isBnnName || hasServiceUuid) {
                if (shouldConnect) {
                    Log.i(TAG, "Found B#NN relay peer: $name ($address) — connecting…")
                    connectToRelayPeer(device)
                } else {
                    Log.d(TAG, "Skipping relay connection to $name ($address) to avoid duplicate link (waiting for them to connect to us)")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Relay scan failed: $errorCode")
            isScanning = false
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  RELAY PEER CONNECTION  — GATT Client
    // ──────────────────────────────────────────────────────────────

    private fun connectToRelayPeer(device: BluetoothDevice) {
        pendingConnections.add(device.address)
        device.connectGatt(context, false, relayGattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnectAllRelayPeers() {
        val peers = relayPeers.toMap()
        relayPeers.clear()
        pendingConnections.clear()
        for ((address, gatt) in peers) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting relay peer $address: ${e.message}")
            }
            val name = gatt.device.name ?: address
            mainHandler.post { callback.onRelayPeerDisconnected(name) }
        }
    }

    private val relayGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val name = gatt.device.name ?: address

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Relay peer connected: $name ($address)")
                    pendingConnections.remove(address)
                    relayPeers[address] = gatt
                    gatt.discoverServices()
                    mainHandler.post { callback.onRelayPeerConnected(name) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Relay peer disconnected: $name ($address)")
                    pendingConnections.remove(address)
                    relayPeers.remove(address)
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing relay gatt: ${e.message}")
                    }
                    mainHandler.post { callback.onRelayPeerDisconnected(name) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed for relay peer: $status")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(BNN_SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "B#NN service not found on relay peer ${gatt.device.address}")
                gatt.disconnect()
                return
            }

            // Subscribe to TX notifications from the relay peer
            val txChar = service.getCharacteristic(BNN_TX_CHAR_UUID)
            if (txChar != null) {
                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
                Log.i(TAG, "Subscribed to TX notifications on relay peer ${gatt.device.address}")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Pre-API 33 callback
            if (characteristic.uuid != BNN_TX_CHAR_UUID) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleRelayPeerMessage(gatt, data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // API 33+ callback
            if (characteristic.uuid != BNN_TX_CHAR_UUID) return
            handleRelayPeerMessage(gatt, value)
        }
    }

    private fun handleRelayPeerMessage(gatt: BluetoothGatt, data: ByteArray) {
        val raw = String(data, Charsets.UTF_8)
        Log.d(TAG, "Relay peer ${gatt.device.address} sent: ${raw.take(120)}")

        mainHandler.post {
            val json = tryParseJson(raw) ?: return@post

            // Dedup check
            val msgId = json.optString("id", "")
            if (msgId.isNotEmpty() && !addSeenId(msgId)) {
                Log.d(TAG, "Duplicate relay message $msgId — skipped")
                return@post
            }

            // Check TTL
            val ttl = json.optInt("ttl", 0)
            if (ttl <= 0) {
                Log.d(TAG, "Relay message TTL expired — dropped")
                return@post
            }

            val type = json.optString("type")
            val dst = json.optString("dst")
            val payload = json.optString("payload")
            val myId = BnnDeviceIdentifier.get(context)

            // 1. If it is a response meant for us, display it in the chat!
            if (type == "response" || type == "relay") {
                if (dst.equals(myId, ignoreCase = true) || dst == "broadcast" || dst.isEmpty()) {
                    callback.onMessageReceived(payload, true)
                }
            }

            // 2. Route/forward to other devices
            if (isConnected) {
                forwardToGateway(json)
            } else if (relayEnabled) {
                relayToAllPeers(json)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  RELAY FORWARDING
    // ──────────────────────────────────────────────────────────────

    private fun forwardToGateway(msg: JSONObject) {
        val device = connectedDevice ?: return
        val txChar = txCharacteristic ?: return

        // Decrement TTL, increment hops
        val newTtl = msg.optInt("ttl", 5) - 1
        val newHops = msg.optInt("hops", 0) + 1
        msg.put("ttl", newTtl)
        msg.put("hops", newHops)

        val raw = msg.toString().toByteArray(Charsets.UTF_8)
        if (raw.size <= 512) {
            sendRawNotification(device, txChar, raw)
        } else {
            sendChunked(device, txChar, raw)
        }
        Log.d(TAG, "Relayed message to gateway (ttl=$newTtl, hops=$newHops)")
    }

    private fun relayToAllPeers(msg: JSONObject) {
        if (!relayEnabled) return

        // Decrement TTL, increment hops
        val newTtl = msg.optInt("ttl", 5) - 1
        val newHops = msg.optInt("hops", 0) + 1
        if (newTtl <= 0) {
            Log.d(TAG, "Not relaying — TTL would reach 0")
            return
        }
        msg.put("ttl", newTtl)
        msg.put("hops", newHops)

        val raw = msg.toString().toByteArray(Charsets.UTF_8)

        // 1. Send to relay client peers (devices we connected to)
        for ((address, gatt) in relayPeers) {
            val service = gatt.getService(BNN_SERVICE_UUID) ?: continue
            val rxChar = service.getCharacteristic(BNN_RX_CHAR_UUID) ?: continue

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    rxChar, raw,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                rxChar.value = raw
                @Suppress("DEPRECATION")
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }
            Log.d(TAG, "Forwarded to client peer: $address")
        }

        // 2. Send to server client connections (devices connected to us)
        val serverDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
        val txChar = txCharacteristic
        if (txChar != null) {
            for (device in serverDevices) {
                if (device == connectedDevice) continue // skip gateway
                sendRawNotification(device, txChar, raw)
                Log.d(TAG, "Forwarded to server peer: ${device.address}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  DEDUP HELPER
    // ──────────────────────────────────────────────────────────────

    /** Returns true if the ID was newly added, false if already seen. */
    private fun addSeenId(id: String): Boolean {
        if (seenMessageIds.contains(id)) return false
        seenMessageIds.add(id)
        // Evict oldest entries if cache too large
        while (seenMessageIds.size > MAX_SEEN_IDS) {
            val iterator = seenMessageIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    // ──────────────────────────────────────────────────────────────
    //  GATT SERVER SETUP
    // ──────────────────────────────────────────────────────────────

    private fun setupGattServer() {
        // 1. Create GATT server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        // 2. Create service
        val service = BluetoothGattService(
            BNN_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 3. Add characteristics
        // RX Characteristic — Central writes to this (we receive messages)
        val rxChar = BluetoothGattCharacteristic(
            BNN_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // TX Characteristic — We notify Central through this (we send messages)
        val txChar = BluetoothGattCharacteristic(
            BNN_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // CCCD descriptor — required to enable notifications on TX
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)
        txCharacteristic = txChar

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        // 4. Add service to server
        gattServer?.addService(service)

        Log.d("BLE", "Service added successfully")
        Log.i(TAG, "GATT server set up with B#NN service.")
    }

    // ──────────────────────────────────────────────────────────────
    //  ADVERTISING
    // ──────────────────────────────────────────────────────────────

    private fun startAdvertising() {
        if (isAdvertising) return

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            callback.onError("BLE advertising not supported on this device.")
            return
        }

        // Set unique device name
        val myName = BnnDeviceIdentifier.get(context)
        bluetoothAdapter.name = myName

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)                   // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BNN_SERVICE_UUID))  // Only UUID in main advertisement
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)                    // Device name in scan response
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        isAdvertising = true
        Log.i(TAG, "BLE advertising started (UUID in AdvertiseData, Name in ScanResponse).")
    }

    private fun stopAdvertising() {
        if (!isAdvertising) return
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started successfully.")
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE       -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED      -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR       -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED  -> "Not supported"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "Advertising failed: $reason")
            callback.onError("BLE Advertising failed: $reason")
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  GATT SERVER CALLBACK  — handles all BLE events
    // ──────────────────────────────────────────────────────────────

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        // Device connected or disconnected
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val name = device.name ?: device.address
                    Log.i(TAG, "Device connected to GATT server: $name (${device.address})")
                    // Restart advertising after a small delay to make sure we stay discoverable
                    mainHandler.postDelayed({
                        if (isAdvertising) {
                            stopAdvertising()
                        }
                        startAdvertising()
                    }, 1000)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = device.address
                    Log.i(TAG, "Device disconnected from GATT server: $address")
                    if (device == connectedDevice) {
                        Log.i(TAG, "Gateway disconnected.")
                        connectedDevice = null
                        mainHandler.post {
                            stopHeartbeat()
                            callback.onDisconnected()
                        }
                    }
                    // Restart advertising to make sure we are visible
                    mainHandler.postDelayed({
                        if (isAdvertising) {
                            stopAdvertising()
                        }
                        startAdvertising()
                    }, 1000)
                }
            }
        }

        // Central wrote to RX characteristic — this is an incoming message
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Always send GATT_SUCCESS so Central knows we received it
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, null
                )
            }

            if (characteristic.uuid != BNN_RX_CHAR_UUID) return

            val raw = String(value, Charsets.UTF_8)
            Log.d(TAG, "Received raw from ${device.address}: ${raw.take(120)}")

            mainHandler.post { handleRawMessage(device, raw) }
        }

        // Central wrote to a descriptor (e.g. enabling notifications on TX)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, null
                )
            }
            Log.d(TAG, "Descriptor write from ${device.address} — notifications enabled.")
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  MESSAGE HANDLING  — parse JSON, detect chunks, dispatch
    // ──────────────────────────────────────────────────────────────

    private fun handleRawMessage(device: BluetoothDevice, raw: String) {
        val json = tryParseJson(raw) ?: run {
            Log.w(TAG, "Malformed packet — ignored.")
            return
        }

        // Is this a chunk of a large message?
        if (json.has("chunk_id")) {
            val assembled = handleChunk(json)
            if (assembled != null) handleMessage(device, assembled)
            return
        }

        handleMessage(device, json)
    }

    private fun handleMessage(device: BluetoothDevice, msg: JSONObject) {
        val type    = msg.optString("type", "unknown")
        val payload = msg.optString("payload", "")
        val msgId   = msg.optString("id", "")
        val src     = msg.optString("src", "")
        val dst     = msg.optString("dst", "")

        Log.i(TAG, "Message type=$type payload=${payload.take(80)}")

        // Add to dedup set
        if (msgId.isNotEmpty()) {
            addSeenId(msgId)
        }

        // Identify the gateway/server connection dynamically
        if (src == "server") {
            if (connectedDevice != device) {
                connectedDevice = device
                val name = device.name ?: "B#NN Server"
                Log.i(TAG, "Identified gateway server: $name (${device.address})")
                callback.onConnected(name)
                startHeartbeat()
            }
        }

        when (type) {
            "ping" -> {
                // Laptop/peer is checking we're alive — reply with pong
                sendMessage(buildMsg("pong", "B#NN device online"))
            }
            "pong" -> {
                // Reply to our heartbeat — all good
                Log.d(TAG, "Heartbeat acknowledged by server.")
            }
            "response" -> {
                // AI response from server — show in chat only if meant for us
                val dst = msg.optString("dst", "")
                val myId = BnnDeviceIdentifier.get(context)
                if (dst.isEmpty() || dst == "broadcast" || dst.equals(myId, ignoreCase = true)) {
                    val hops = msg.optInt("hops", 0)
                    callback.onMessageReceived(payload, hops > 0)
                }
                // Relay to peers if relay mode is on
                if (relayEnabled) {
                    val relayMsg = JSONObject(msg.toString())
                    relayToAllPeers(relayMsg)
                }
            }
            "request" -> {
                // If it is from a peer, forward it to the gateway (or relay to peers if not directly connected but in relay mode)
                if (src != "server") {
                    if (isConnected) {
                        forwardToGateway(msg)
                    } else if (relayEnabled) {
                        val relayMsg = JSONObject(msg.toString())
                        relayToAllPeers(relayMsg)
                    }
                } else {
                    val hops = msg.optInt("hops", 0)
                    callback.onMessageReceived("[request] $payload", hops > 0)
                }
            }
            "relay" -> {
                // If this is a response meant for us, display it!
                val myId = BnnDeviceIdentifier.get(context)
                if (dst.equals(myId, ignoreCase = true) || dst == "broadcast") {
                    callback.onMessageReceived(payload, true)
                }
                // Also forward/relay it further if TTL allows and relay mode is enabled
                if (relayEnabled) {
                    val relayMsg = JSONObject(msg.toString())
                    relayToAllPeers(relayMsg)
                }
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
                if (relayEnabled) {
                    val relayMsg = JSONObject(msg.toString())
                    relayToAllPeers(relayMsg)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  CHUNK REASSEMBLY  — for long AI responses split into pieces
    // ──────────────────────────────────────────────────────────────

    private fun handleChunk(json: JSONObject): JSONObject? {
        val chunkId    = json.optString("chunk_id")    ?: return null
        val chunkIdx   = json.optInt("chunk_idx", -1)
        val chunkTotal = json.optInt("chunk_total", -1)
        val data       = json.optString("data", "")

        if (chunkId.isEmpty() || chunkIdx < 0 || chunkTotal <= 0) return null

        // Store this piece
        chunkBuffer.getOrPut(chunkId) { mutableMapOf() }[chunkIdx] = data
        chunkMeta[chunkId] = chunkTotal

        Log.d(TAG, "Chunk $chunkIdx/$chunkTotal for $chunkId")

        // Check if all pieces have arrived
        val pieces = chunkBuffer[chunkId] ?: return null
        if (pieces.size < chunkTotal) return null

        // Reassemble in order
        val full = (0 until chunkTotal).joinToString("") { pieces[it] ?: "" }
        chunkBuffer.remove(chunkId)
        chunkMeta.remove(chunkId)

        Log.d(TAG, "Chunk reassembled: ${full.length} chars")
        return tryParseJson(full)
    }

    // ──────────────────────────────────────────────────────────────
    //  SEND MESSAGE  — notify the connected Central
    // ──────────────────────────────────────────────────────────────

    fun sendMessage(msg: JSONObject) {
        val device = connectedDevice ?: run {
            Log.w(TAG, "Send skipped — no device connected.")
            return
        }
        val txChar = txCharacteristic ?: return

        val raw = msg.toString().toByteArray(Charsets.UTF_8)

        // BLE notification limit: 512 bytes. If larger, split into chunks.
        if (raw.size <= 512) {
            sendRawNotification(device, txChar, raw)
        } else {
            sendChunked(device, txChar, raw)
        }
    }

    private fun sendRawNotification(
        device: BluetoothDevice,
        txChar: BluetoothGattCharacteristic,
        raw: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gattServer?.notifyCharacteristicChanged(device, txChar, false, raw)
            Log.d(TAG, "Notification sent (API 33+): ${raw.size} bytes, result=$result")
        } else {
            @Suppress("DEPRECATION")
            txChar.value = raw
            @Suppress("DEPRECATION")
            val success = gattServer?.notifyCharacteristicChanged(device, txChar, false)
            Log.d(TAG, "Notification sent (legacy): ${raw.size} bytes, success=$success")
        }
    }

    private fun sendChunked(
        device: BluetoothDevice,
        txChar: BluetoothGattCharacteristic,
        raw: ByteArray
    ) {
        val chunkId  = java.util.UUID.randomUUID().toString()
        val pieces   = raw.toList().chunked(384)  // 384 bytes of data per chunk
        val total    = pieces.size

        Log.d(TAG, "Sending $total chunks (total ${raw.size} bytes).")

        mainHandler.post {
            pieces.forEachIndexed { idx, piece ->
                mainHandler.postDelayed({
                    val envelope = JSONObject().apply {
                        put("chunk_id",    chunkId)
                        put("chunk_idx",   idx)
                        put("chunk_total", total)
                        put("data",        String(piece.toByteArray(), Charsets.UTF_8))
                    }
                    sendRawNotification(device, txChar, envelope.toString().toByteArray(Charsets.UTF_8))
                }, idx * 50L)  // 50 ms between chunks so Central can process them
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  SEND PROMPT  — called from UI when user hits Send
    // ──────────────────────────────────────────────────────────────

    fun sendPrompt(text: String) {
        val msg = buildMsg("request", text)
        val msgId = msg.optString("id", "")
        if (msgId.isNotEmpty()) {
            addSeenId(msgId)
        }

        if (isConnected) {
            sendMessage(msg)
            Log.i(TAG, "Prompt sent directly to gateway: ${text.take(60)}")
        } else {
            val serverDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
            val hasClientPeers = relayPeerCount > 0
            val hasServerPeers = serverDevices.any { it != connectedDevice }

            if (hasClientPeers || hasServerPeers) {
                relayToAllPeers(msg)
                Log.i(TAG, "Prompt sent via mesh relay (clientPeers=$relayPeerCount, serverPeers=${serverDevices.size}): ${text.take(60)}")
            } else {
                callback.onError("No connection to B#NN mesh network.")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HEARTBEAT  — keep the connection alive
    // ──────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    sendMessage(buildMsg("ping", "heartbeat"))
                    Log.d(TAG, "Heartbeat ping sent.")
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ──────────────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────────────

    private fun tryParseJson(raw: String): JSONObject? {
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            null
        }
    }

    private fun buildMsg(type: String, payload: String): JSONObject {
        return JSONObject().apply {
            put("id",      java.util.UUID.randomUUID().toString())
            put("type",    type)
            put("payload", payload)
            put("src",     BnnDeviceIdentifier.get(context))
            put("dst",     "server")
            put("hops",    0)
            put("ttl",     5)
            put("ts",      System.currentTimeMillis() / 1000.0)
        }
    }
}
