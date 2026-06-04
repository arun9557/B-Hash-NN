package com.bnn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BnnMeshState — singleton BLE callback + StateFlows.
 * Shared between BnnForegroundService (writes) and BnnViewModel (reads).
 * This decouples BLE lifetime from the Activity/ViewModel lifecycle.
 */
class BnnMeshState : BLECallback {

    // ── Messages ───────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Peers ──────────────────────────────────────────────────────────────
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    private val _relayPeers = MutableStateFlow<List<String>>(emptyList())
    val relayPeers: StateFlow<List<String>> = _relayPeers.asStateFlow()

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
        _isLoading.value = false
    }
}
