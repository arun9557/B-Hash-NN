package com.bnn.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BnnViewModel — bridges BnnMeshState + BnnForegroundService to Compose UI.
 * State is read from the Application-level BnnMeshState singleton so it stays
 * alive across Activity restarts and when the foreground service is running.
 */
class BnnViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<BnnApp>()
    private val mesh get() = app.meshState

    // ── Delegate StateFlows from shared mesh state ─────────────────────────
    val messages       get() = mesh.messages
    val connectedPeers get() = mesh.connectedPeers
    val relayPeers     get() = mesh.relayPeers
    val isConnected    get() = mesh.isConnected
    val bleStatus      get() = mesh.bleStatus
    val deviceName     get() = mesh.deviceName
    val isLoading      get() = mesh.isLoading

    // ── Local ViewModel-only state ─────────────────────────────────────────
    private val _bleRunning = MutableStateFlow(false)
    val bleRunning: StateFlow<Boolean> = _bleRunning.asStateFlow()

    private val _relayEnabled = MutableStateFlow(false)
    val relayEnabled: StateFlow<Boolean> = _relayEnabled.asStateFlow()

    private val _showPeerSheet = MutableStateFlow(false)
    val showPeerSheet: StateFlow<Boolean> = _showPeerSheet.asStateFlow()

    private val _showAboutSheet = MutableStateFlow(false)
    val showAboutSheet: StateFlow<Boolean> = _showAboutSheet.asStateFlow()

    // ── BLE Manager init ───────────────────────────────────────────────────

    fun initBleManager(context: Context) {
        if (app.bleManager == null) {
            app.bleManager = BLEManager(context.applicationContext, mesh)
        }
    }

    // ── BLE Control ────────────────────────────────────────────────────────

    fun startBle() {
        if (BnnSettings.runInBackground.value) {
            // Use foreground service — it manages BLEManager
            app.applicationContext.startForegroundService(
                BnnForegroundService.startIntent(app.applicationContext)
            )
        } else {
            // Direct — BLEManager in ViewModel (stops when app goes to background)
            if (app.bleManager == null) {
                app.bleManager = BLEManager(app.applicationContext, mesh)
            }
            app.bleManager?.start()
            mesh.onStatusChanged("Advertising…")
        }
        _bleRunning.value = true
    }

    fun stopBle() {
        app.applicationContext.stopService(BnnForegroundService.stopIntent(app.applicationContext))
        app.bleManager?.stop()
        app.bleManager = null
        mesh.reset()
        _bleRunning.value = false
        _relayEnabled.value = false
    }

    fun setRelayMode(enabled: Boolean) {
        if (_bleRunning.value) {
            app.bleManager?.setRelayMode(enabled)
            _relayEnabled.value = enabled
        }
    }

    // ── Messaging ──────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        mesh.addOutgoingMessage(text)
        app.bleManager?.sendPrompt(text)
    }

    fun addLocalMessage(text: String) {
        mesh.addLocalMessage(text)
    }

    // ── Sheet visibility ───────────────────────────────────────────────────

    fun showPeerSheet()  { _showPeerSheet.value = true }
    fun hidePeerSheet()  { _showPeerSheet.value = false }
    fun showAboutSheet() { _showAboutSheet.value = true }
    fun hideAboutSheet() { _showAboutSheet.value = false }

    val relayPeerCount: Int get() = relayPeers.value.size

    override fun onCleared() {
        super.onCleared()
        // If NOT using background service, stop BLE when ViewModel is cleared
        if (!BnnSettings.runInBackground.value && _bleRunning.value) {
            app.bleManager?.stop()
        }
    }
}
