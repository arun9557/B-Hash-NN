package com.bnn.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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

// RX = phone READS (laptop writes to this)
val BNN_RX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

// TX = phone WRITES (laptop subscribes to notifications on this)
val BNN_TX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")

// Standard CCCD UUID — needed to enable BLE notifications on the TX characteristic
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val TAG = "B#NN-BLE"
private const val DEVICE_NAME = "B#NN_DEVICE"
private const val HEARTBEAT_INTERVAL_MS = 10_000L   // send ping every 10 seconds
private const val ADVERTISE_START_DELAY_MS = 1_500L

// ══════════════════════════════════════════════════════════════════
//  CALLBACK INTERFACE  — BLEManager talks back to MainActivity
// ══════════════════════════════════════════════════════════════════

interface BLECallback {
    fun onConnected(deviceName: String)
    fun onDisconnected()
    fun onMessageReceived(message: String)
    fun onStatusChanged(status: String)
    fun onError(error: String)
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
    private val notificationEnabledDevices = mutableSetOf<String>()

    // ── TX characteristic — we notify the central through this ───
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // ── Heartbeat ─────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    // ── Chunk reassembly (for large messages from laptop) ─────────
    private val chunkBuffer = mutableMapOf<String, MutableMap<Int, String>>()  // chunkId -> {index -> data}
    private val chunkMeta   = mutableMapOf<String, Int>()                      // chunkId -> totalChunks

    val isConnected: Boolean
        get() = connectedDevice != null

    // ──────────────────────────────────────────────────────────────
    //  START  — setup GATT server and begin advertising
    // ──────────────────────────────────────────────────────────────

    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            callback.onError("Bluetooth is off. Please enable it.")
            return
        }

        setupGattServer()
        callback.onStatusChanged("Preparing GATT service…")
        Log.i(TAG, "B#NN BLE Peripheral starting (waiting for service add callback).")
    }

    fun stop() {
        stopHeartbeat()
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        Log.i(TAG, "B#NN BLE Peripheral stopped.")
    }

    // ──────────────────────────────────────────────────────────────
    //  GATT SERVER SETUP
    // ──────────────────────────────────────────────────────────────

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        // Build the GATT service with two characteristics
        val service = BluetoothGattService(
            BNN_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

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
        val submitted = gattServer?.addService(service) == true

        if (submitted) {
            Log.i(TAG, "GATT service add requested. Waiting for onServiceAdded callback.")
        } else {
            callback.onError("Failed to add GATT service.")
            Log.e(TAG, "addService returned false.")
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  ADVERTISING
    // ──────────────────────────────────────────────────────────────

    private fun startAdvertising() {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            callback.onError("BLE advertising not supported on this device.")
            return
        }

        // Set device name
        bluetoothAdapter.name = DEVICE_NAME

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)                   // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BNN_SERVICE_UUID))  // so Central can filter by service
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.i(TAG, "BLE advertising started.")
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started successfully.")
        }
        override fun onStartFailure(errorCode: Int) {
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

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "Service added: $status uuid=${service.uuid}")

            if (service.uuid != BNN_SERVICE_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.postDelayed({
                    startAdvertising()
                    callback.onStatusChanged("Advertising as \"$DEVICE_NAME\"…")
                }, ADVERTISE_START_DELAY_MS)
            } else {
                mainHandler.post {
                    callback.onError("GATT service add failed: $status")
                }
            }
        }

        // Device connected or disconnected
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    notificationEnabledDevices.remove(device.address)
                    val name = device.name ?: device.address
                    Log.i(TAG, "Connected: $name")
                    mainHandler.post {
                        callback.onConnected(name)
                        startHeartbeat()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: ${device.address}")
                    notificationEnabledDevices.remove(device.address)
                    connectedDevice = null
                    mainHandler.post {
                        stopHeartbeat()
                        callback.onDisconnected()
                        // Resume advertising so the Central can reconnect
                        startAdvertising()
                        callback.onStatusChanged("Advertising as \"$DEVICE_NAME\"…")
                    }
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
            Log.d(TAG, "Received raw: ${raw.take(120)}")

            mainHandler.post { handleRawMessage(raw) }
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
            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic?.uuid == BNN_TX_CHAR_UUID) {
                descriptor.value = value
                val notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

                if (notificationsEnabled) {
                    notificationEnabledDevices.add(device.address)
                    Log.i(TAG, "Notifications enabled for ${device.address}")
                } else {
                    notificationEnabledDevices.remove(device.address)
                    Log.i(TAG, "Notifications disabled for ${device.address}")
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, null
                )
            }
            Log.d(TAG, "Descriptor write from ${device.address}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  MESSAGE HANDLING  — parse JSON, detect chunks, dispatch
    // ──────────────────────────────────────────────────────────────

    private fun handleRawMessage(raw: String) {
        val json = tryParseJson(raw) ?: run {
            Log.w(TAG, "Malformed packet — ignored.")
            return
        }

        // Is this a chunk of a large message?
        if (json.has("chunk_id")) {
            val assembled = handleChunk(json)
            if (assembled != null) handleMessage(assembled)
            return
        }

        handleMessage(json)
    }

    private fun handleMessage(msg: JSONObject) {
        val type    = msg.optString("type", "unknown")
        val payload = msg.optString("payload", "")

        Log.i(TAG, "Message type=$type payload=${payload.take(80)}")

        when (type) {
            "ping" -> {
                // Laptop is checking we're alive — reply with pong
                sendMessage(buildMsg("pong", "B#NN device online"))
            }
            "pong" -> {
                // Reply to our heartbeat — all good
                Log.d(TAG, "Heartbeat acknowledged by server.")
            }
            "response" -> {
                // AI response from server — show in chat
                callback.onMessageReceived(payload)
            }
            "request" -> {
                // Server relayed a request to us? Shouldn't happen normally.
                callback.onMessageReceived("[request] $payload")
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
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
        if (!notificationEnabledDevices.contains(device.address)) {
            Log.w(TAG, "Notification skipped: CCCD not enabled for ${device.address}")
            return
        }

        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, txChar, false, raw)
                ?: BluetoothGatt.GATT_FAILURE
        } else {
            txChar.value = raw
            val ok = gattServer?.notifyCharacteristicChanged(device, txChar, false) == true
            if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Notification sent: ${raw.size} bytes")
        } else {
            Log.w(TAG, "Notification failed (status=$status, bytes=${raw.size})")
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
        if (!isConnected) {
            callback.onError("Not connected to B#NN server.")
            return
        }
        val msg = buildMsg("request", text)
        sendMessage(msg)
        Log.i(TAG, "Prompt sent: ${text.take(60)}")
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
            put("src",     bluetoothAdapter.address ?: "android")
            put("dst",     "server")
            put("hops",    0)
            put("ttl",     5)
            put("ts",      System.currentTimeMillis() / 1000.0)
        }
    }
}
