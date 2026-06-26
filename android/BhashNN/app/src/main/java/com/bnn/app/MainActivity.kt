package com.bnn.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnn.app.ui.BnnChatScreen
import com.bnn.app.ui.theme.BnnTheme

/**
 * MainActivity — entry point.
 * Handles Android permissions and BT enable flow.
 * All UI state is owned by BnnViewModel; this Activity just calls setContent.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BnnViewModel by viewModels()
    private var promptedBt = false
    private var promptedWifi = false

    // ── Required BLE and WiFi permissions ──────────────────────────────────────
    private val requiredPermissions: Array<String>
        get() {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                permissions.add(Manifest.permission.BLUETOOTH)
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            return permissions.toTypedArray()
        }

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndStart()
    }

    // ── Bluetooth enable launcher ─────────────────────────────────────────────
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissionsAndStart()
    }

    // ── WiFi enable launcher ──────────────────────────────────────────────────
    private val enableWifiLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissionsAndStart()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Give ViewModel the Application context for BLEManager (safe — Application outlives Activity)
        viewModel.initBleManager(applicationContext)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            BnnTheme(darkTheme = darkTheme) {
                BnnChatScreen(
                    viewModel = viewModel,
                    onStartMesh = { startMeshFlow() }
                )
            }
        }

        // Auto-start mesh (will ask for permissions / BT and WiFi enable as needed)
        startMeshFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles BLE lifecycle via onCleared()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MESH STARTUP FLOW
    // ══════════════════════════════════════════════════════════════════════════

    fun startMeshFlow() {
        promptedBt = false
        promptedWifi = false
        checkPermissionsAndStart()
    }

    /**
     * Called from:
     * - onCreate (auto-start) via startMeshFlow
     * - Start button in the header via startMeshFlow
     */
    fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        // 1. Check and prompt Bluetooth
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter != null && !btAdapter.isEnabled && !promptedBt) {
            promptedBt = true
            @Suppress("DEPRECATION")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        // 2. Check and prompt WiFi
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled && !promptedWifi) {
            promptedWifi = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                enableWifiLauncher.launch(Intent(android.provider.Settings.Panel.ACTION_WIFI))
            } else {
                @Suppress("DEPRECATION")
                try {
                    wifiManager.isWifiEnabled = true
                    viewModel.startBle()
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    enableWifiLauncher.launch(intent)
                }
            }
            return
        }

        viewModel.startBle()
    }
}
