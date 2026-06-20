package com.bnn.app

import android.app.Application
import com.bnn.app.mesh.MeshEngine

/**
 * BnnApp — Application class.
 * Holds the shared BnnMeshState + MeshEngine singletons so both the foreground service
 * and the ViewModel can read/write the same state without binding.
 */
class BnnApp : Application() {

    /** Shared BLE/Mesh state — updated by MeshEngine callbacks, observed by ViewModel */
    val meshState = BnnMeshState()

    /** Live BLEManager reference — kept for backward-compat with direct BLE path */
    var bleManager: BLEManager? = null

    /**
     * MeshEngine — the central routing hub for BLE + WiFi hybrid mesh.
     * Null until initialized by BnnViewModel.initMeshEngine().
     */
    var meshEngine: MeshEngine? = null

    override fun onCreate() {
        super.onCreate()
        // Load persisted settings on startup
        BnnThemePreference.load(this)
        BnnSettings.load(this)
    }
}
