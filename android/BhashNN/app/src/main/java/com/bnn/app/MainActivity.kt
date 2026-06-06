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

    // ── Required BLE permissions ──────────────────────────────────────────────
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
            }
            return permissions.toTypedArray()
        }

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startBle()
        }
        // If denied, user can tap Start later — handled via BLE button in header
    }

    // ── Bluetooth enable launcher ─────────────────────────────────────────────
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startBle()
        }
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
                BnnChatScreen(viewModel = viewModel)
            }
        }

        // Auto-start BLE (will ask for permissions / BT enable as needed)
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles BLE lifecycle via onCleared()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BLE STARTUP FLOW
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called from:
     * - onCreate (auto-start)
     * - BLE Start button in the header → viewModel.startBle() is called after permission grant
     */
    fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!btManager.adapter.isEnabled) {
            @Suppress("DEPRECATION")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        viewModel.startBle()
    }
}
