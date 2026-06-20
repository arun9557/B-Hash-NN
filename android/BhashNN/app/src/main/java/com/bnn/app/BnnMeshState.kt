package com.bnn.app

import com.bnn.app.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BnnMeshState — singleton mesh callback + StateFlows.
 * Shared between BnnForegroundService (writes) and BnnViewModel (reads).
 * Tracks peers from ALL transports: BLE, WiFi LAN, WiFi Direct, WiFi Aware.
 */
class BnnMeshState : BLECallback {

    // ── Messages ───────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Peers ──────────────────────────────────────────────────────────────
    // BLE peers (gateway + direct BLE connections)
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    // BLE relay peers (phones connected via BLE mesh relay)
    private val _relayPeers = MutableStateFlow<List<String>>(emptyList())
    val relayPeers: StateFlow<List<String>> = _relayPeers.asStateFlow()

    // ── WiFi Peers ─────────────────────────────────────────────────────────
    private val _wifiLanPeers = MutableStateFlow<List<String>>(emptyList())
    val wifiLanPeers: StateFlow<List<String>> = _wifiLanPeers.asStateFlow()

    private val _wifiDirectPeers = MutableStateFlow<List<String>>(emptyList())
    val wifiDirectPeers: StateFlow<List<String>> = _wifiDirectPeers.asStateFlow()

    private val _wifiAwarePeers = MutableStateFlow<List<String>>(emptyList())
    val wifiAwarePeers: StateFlow<List<String>> = _wifiAwarePeers.asStateFlow()

    // Active transport types (for UI indicator)
    private val _activeTransports = MutableStateFlow<List<TransportType>>(emptyList())
    val activeTransports: StateFlow<List<TransportType>> = _activeTransports.asStateFlow()

    // ── Status ─────────────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _bleStatus = MutableStateFlow("Initialising…")
    val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

    private val _deviceName = MutableStateFlow("Waiting for server")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── BLECallback implementation ─────────────────────────────────────────

    override fun onConnected(deviceName: String) {
        _isConnected.value = true
        _bleStatus.value = "Connected"
        _deviceName.value = deviceName
        if (_connectedPeers.value.none { it == deviceName }) {
            _connectedPeers.value = _connectedPeers.value + deviceName
        }
    }

    override fun onDisconnected() {
        _isConnected.value = false
        _bleStatus.value = "Advertising…"
        _deviceName.value = "Waiting for server"
        _connectedPeers.value = emptyList()
        _isLoading.value = false
    }

    override fun onMessageReceived(message: String, isRelay: Boolean) {
        _isLoading.value = false
        _messages.value = _messages.value + ChatMessage(message, isOutgoing = false, isRelay = isRelay)
    }

    override fun onStatusChanged(status: String) {
        _bleStatus.value = status
    }

    override fun onError(error: String) {
        _isLoading.value = false
        _messages.value = _messages.value + ChatMessage("⚠ $error", isOutgoing = false)
    }

    override fun onRelayPeerConnected(name: String) {
        if (_relayPeers.value.none { it == name }) {
            _relayPeers.value = _relayPeers.value + name
        }
    }

    override fun onRelayPeerDisconnected(name: String) {
        _relayPeers.value = _relayPeers.value.filter { it != name }
    }

    // ── WiFi peer updates (called by MeshEngine) ───────────────────────────

    fun onWifiPeerConnected(peerId: String, transport: TransportType) {
        when (transport) {
            TransportType.WIFI_LAN -> {
                if (_wifiLanPeers.value.none { it == peerId })
                    _wifiLanPeers.value = _wifiLanPeers.value + peerId
            }
            TransportType.WIFI_DIRECT -> {
                if (_wifiDirectPeers.value.none { it == peerId })
                    _wifiDirectPeers.value = _wifiDirectPeers.value + peerId
            }
            TransportType.WIFI_AWARE -> {
                if (_wifiAwarePeers.value.none { it == peerId })
                    _wifiAwarePeers.value = _wifiAwarePeers.value + peerId
            }
            else -> {} // BLE handled via BLECallback
        }
        _isConnected.value = true
        refreshStatus()
    }

    fun onWifiPeerDisconnected(peerId: String, transport: TransportType) {
        when (transport) {
            TransportType.WIFI_LAN -> _wifiLanPeers.value = _wifiLanPeers.value.filter { it != peerId }
            TransportType.WIFI_DIRECT -> _wifiDirectPeers.value = _wifiDirectPeers.value.filter { it != peerId }
            TransportType.WIFI_AWARE -> _wifiAwarePeers.value = _wifiAwarePeers.value.filter { it != peerId }
            else -> {}
        }
        refreshStatus()
    }

    fun updateActiveTransports(transports: List<TransportType>) {
        _activeTransports.value = transports
    }

    /** Total peers across all transports */
    fun totalPeerCount(): Int =
        connectedPeers.value.size +
        relayPeers.value.size +
        wifiLanPeers.value.size +
        wifiDirectPeers.value.size +
        wifiAwarePeers.value.size

    private fun refreshStatus() {
        val total = totalPeerCount()
        val transports = _activeTransports.value
        val transportStr = when {
            transports.isEmpty() -> "BLE"
            else -> transports.joinToString("+") { it.emoji }
        }
        _bleStatus.value = if (total > 0) "Mesh: $total peers · $transportStr" else "Scanning…"
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    fun addOutgoingMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(text, isOutgoing = true)
        _isLoading.value = true
    }

    fun addLocalMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(text, isOutgoing = false)
    }

    fun reset() {
        _isConnected.value = false
        _bleStatus.value = "Stopped"
        _deviceName.value = "—"
        _connectedPeers.value = emptyList()
        _relayPeers.value = emptyList()
        _wifiLanPeers.value = emptyList()
        _wifiDirectPeers.value = emptyList()
        _wifiAwarePeers.value = emptyList()
        _activeTransports.value = emptyList()
        _isLoading.value = false
    }
}
