package com.bnn.app

import android.app.Application

/**
 * BnnApp — Application class.
 * Holds the shared BnnMeshState singleton so both the foreground service
 * and the ViewModel can read/write the same BLE state without binding.
 */
class BnnApp : Application() {

    /** Shared BLE state — updated by BLEManager callbacks, observed by ViewModel */
    val meshState = BnnMeshState()

    /** Live BLEManager reference — owned here so it survives Activity restarts */
    var bleManager: BLEManager? = null

    override fun onCreate() {
        super.onCreate()
        // Load persisted settings on startup
        BnnThemePreference.load(this)
        BnnSettings.load(this)
    }
}
