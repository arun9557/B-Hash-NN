package com.bnn.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.bnn.app.mesh.MeshEngine
import com.bnn.app.transport.TransportManager
import com.bnn.app.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BnnViewModel — bridges BnnMeshState + BnnForegroundService to Compose UI.
 * State is read from the Application-level BnnMeshState singleton so it stays
 * alive across Activity restarts and when the foreground service is running.
 *
 * Now supports full hybrid BLE + WiFi mesh via MeshEngine.
 */
class BnnViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<BnnApp>()
    private val mesh get() = app.meshState

    // ── Delegate StateFlows from shared mesh state ─────────────────────────
    val messages          get() = mesh.messages
    val connectedPeers    get() = mesh.connectedPeers
    val relayPeers        get() = mesh.relayPeers
    val isConnected       get() = mesh.isConnected
    val bleStatus         get() = mesh.bleStatus
    val deviceName        get() = mesh.deviceName
    val isLoading         get() = mesh.isLoading

    // WiFi peer state flows
    val wifiLanPeers      get() = mesh.wifiLanPeers
    val wifiDirectPeers   get() = mesh.wifiDirectPeers
    val wifiAwarePeers    get() = mesh.wifiAwarePeers
    val activeTransports  get() = mesh.activeTransports

    // ── Local ViewModel-only state ─────────────────────────────────────────
    private val _bleRunning = MutableStateFlow(false)
    val bleRunning: StateFlow<Boolean> = _bleRunning.asStateFlow()

    private val _relayEnabled = MutableStateFlow(false)
    val relayEnabled: StateFlow<Boolean> = _relayEnabled.asStateFlow()

    private val _showPeerSheet = MutableStateFlow(false)
    val showPeerSheet: StateFlow<Boolean> = _showPeerSheet.asStateFlow()

    private val _showAboutSheet = MutableStateFlow(false)
    val showAboutSheet: StateFlow<Boolean> = _showAboutSheet.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────

    fun initBleManager(context: Context) {
        if (app.bleManager == null) {
            app.bleManager = BLEManager(context.applicationContext, mesh)
        }
        initMeshEngine(context)
    }

    private fun initMeshEngine(context: Context) {
        if (app.meshEngine != null) return

        val engine = MeshEngine(
            myId = BnnDeviceIdentifier.get(context),
            callback = mesh
        )

        // BLE transport wraps existing BLEManager
        val ble = app.bleManager ?: BLEManager(context.applicationContext, mesh).also {
            app.bleManager = it
        }
        val bleTransport = BLETransportAdapter(ble)

        val transportManager = TransportManager(
            context = context.applicationContext,
            myId = BnnDeviceIdentifier.get(context),
            routeTable = engine.routeTable,
            onIncomingPacket = { packet, fromPeer, transport ->
                // Update WiFi peer state in UI
                if (transport != TransportType.BLE) {
                    mesh.onWifiPeerConnected(fromPeer, transport)
                }
                engine.onPacketReceived(packet, fromPeer, transport)
            }
        )

        // Hook WiFi peer connect/disconnect to meshState
        transportManager.also { tm ->
            // We access peer events through TransportManager callbacks which
            // are set in registerTransport → onPeerConnected/Disconnected
        }

        transportManager.init(bleTransport)
        engine.attachTransportManager(transportManager)
        app.meshEngine = engine
    }

    // ── BLE/Mesh Control ────────────────────────────────────────────────────

    fun startBle() {
        // Ensure MeshEngine is initialized before starting
        val ctx = app.applicationContext
        if (app.meshEngine == null) {
            initMeshEngine(ctx)
        }

        if (BnnSettings.runInBackground.value) {
            ctx.startForegroundService(
                BnnForegroundService.startIntent(ctx)
            )
        } else {
            val engine = app.meshEngine
            if (engine != null) {
                engine.start()
            } else {
                // Absolute fallback: start raw BLE if engine creation failed
                app.bleManager?.start()
                mesh.onStatusChanged("Advertising…")
            }
        }
        _bleRunning.value = true
    }

    fun stopBle() {
        app.applicationContext.stopService(BnnForegroundService.stopIntent(app.applicationContext))
        app.meshEngine?.stop()
        app.bleManager?.stop()
        app.bleManager = null
        app.meshEngine = null
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
        val engine = app.meshEngine
        if (engine != null) {
            engine.sendPrompt(text)
        } else {
            // Legacy BLE-only path
            app.bleManager?.sendPrompt(text)
        }
    }

    fun addLocalMessage(text: String) {
        mesh.addLocalMessage(text)
    }

    // ── Sheet visibility ───────────────────────────────────────────────────

    fun showPeerSheet()  { _showPeerSheet.value = true }
    fun hidePeerSheet()  { _showPeerSheet.value = false }
    fun showAboutSheet() { _showAboutSheet.value = true }
    fun hideAboutSheet() { _showAboutSheet.value = false }

    // ── Derived stats ──────────────────────────────────────────────────────

    val relayPeerCount: Int get() = relayPeers.value.size
    val totalPeerCount: Int get() = mesh.totalPeerCount()

    /** Human-readable transport summary for the UI header */
    fun transportSummary(): String {
        val active = activeTransports.value
        if (active.isEmpty()) return "BLE"
        return active.joinToString(" + ") { it.displayName }
    }

    override fun onCleared() {
        super.onCleared()
        if (!BnnSettings.runInBackground.value && _bleRunning.value) {
            app.meshEngine?.stop()
            app.bleManager?.stop()
        }
    }
}
