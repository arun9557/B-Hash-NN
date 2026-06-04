package com.bnn.app

import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B#NN ViewModel — bridges BLEManager callbacks to Compose StateFlows.
 * All UI state lives here; MainActivity only handles permissions.
 */
class BnnViewModel(application: android.app.Application) :
    AndroidViewModel(application), BLECallback {

    // ── Messages ───────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Peers ──────────────────────────────────────────────────────────────
    /** Direct BLE gateway peers */
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    /** Relay (mesh) peers when relay mode is ON */
    private val _relayPeers = MutableStateFlow<List<String>>(emptyList())
    val relayPeers: StateFlow<List<String>> = _relayPeers.asStateFlow()

    // ── Connection status ──────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _bleStatus = MutableStateFlow("Initialising…")
    val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

    private val _deviceName = MutableStateFlow("Waiting for server")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    // ── Relay ──────────────────────────────────────────────────────────────
    private val _relayEnabled = MutableStateFlow(false)
    val relayEnabled: StateFlow<Boolean> = _relayEnabled.asStateFlow()

    // ── Loading (AI thinking) ──────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── BLE running state ──────────────────────────────────────────────────
    private val _bleRunning = MutableStateFlow(false)
    val bleRunning: StateFlow<Boolean> = _bleRunning.asStateFlow()

    // ── Peer list sheet visibility ─────────────────────────────────────────
    private val _showPeerSheet = MutableStateFlow(false)
    val showPeerSheet: StateFlow<Boolean> = _showPeerSheet.asStateFlow()

    // ── Internal BLE manager reference ────────────────────────────────────
    private var _bleManager: BLEManager? = null

    fun initBleManager(context: Context) {
        if (_bleManager == null) {
            _bleManager = BLEManager(context.applicationContext, this)
        }
    }

    // ── BLE control ───────────────────────────────────────────────────────

    fun startBle() {
        _bleManager?.start()
        _bleRunning.value = true
        _bleStatus.value = "Advertising…"
        _deviceName.value = "Waiting for server"
    }

    fun stopBle() {
        _bleManager?.stop()
        _bleRunning.value = false
        _isConnected.value = false
        _bleStatus.value = "Stopped"
        _deviceName.value = "—"
        _relayEnabled.value = false
        _connectedPeers.value = emptyList()
        _relayPeers.value = emptyList()
        _isLoading.value = false
    }

    fun setRelayMode(enabled: Boolean) {
        if (_bleRunning.value) {
            _bleManager?.setRelayMode(enabled)
            _relayEnabled.value = enabled
            if (!enabled) _relayPeers.value = emptyList()
        }
    }

    // ── Messaging ─────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        val msg = ChatMessage(text, isOutgoing = true)
        _messages.value = _messages.value + msg
        _isLoading.value = true
        _bleManager?.sendPrompt(text)
    }

    // ── Peer sheet ─────────────────────────────────────────────────────────

    fun showPeerSheet() { _showPeerSheet.value = true }
    fun hidePeerSheet() { _showPeerSheet.value = false }

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

    override fun onMessageReceived(message: String) {
        _isLoading.value = false
        _messages.value = _messages.value + ChatMessage(message, isOutgoing = false)
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

    val relayPeerCount: Int get() = _relayPeers.value.size

    override fun onCleared() {
        super.onCleared()
        if (_bleRunning.value) _bleManager?.stop()
    }
}
