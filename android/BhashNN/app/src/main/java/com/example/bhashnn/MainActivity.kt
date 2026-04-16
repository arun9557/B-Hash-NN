package com.bnn.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity(), BLECallback {

    // ── Views ─────────────────────────────────────────────────────
    private lateinit var recyclerView:  RecyclerView
    private lateinit var etInput:       EditText
    private lateinit var btnSend:       ImageButton
    private lateinit var tvStatus:      TextView
    private lateinit var tvDeviceName:  TextView
    private lateinit var statusDot:     View
    private lateinit var btnToggleBle:  Button
    private lateinit var progressBar:   ProgressBar

    // ── Chat adapter ───────────────────────────────────────────────
    private lateinit var chatAdapter: ChatAdapter

    // ── BLE manager ───────────────────────────────────────────────
    private lateinit var bleManager: BLEManager
    private var bleRunning = false

    // ── Required BLE permissions ──────────────────────────────────
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    // ── Permission launcher ────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startBle()
        } else {
            showToast("BLE permissions denied. App cannot function without them.")
        }
    }

    // ── Bluetooth enable launcher ──────────────────────────────────
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBle()
        } else {
            showToast("Bluetooth must be enabled to use B#NN.")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerView()
        setupInputListeners()

        bleManager = BLEManager(this, this)

        // Auto-start BLE on launch
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bleRunning) bleManager.stop()
    }

    // ══════════════════════════════════════════════════════════════
    //  VIEW SETUP
    // ══════════════════════════════════════════════════════════════

    private fun bindViews() {
        recyclerView  = findViewById(R.id.recyclerView)
        etInput       = findViewById(R.id.etInput)
        btnSend       = findViewById(R.id.btnSend)
        tvStatus      = findViewById(R.id.tvStatus)
        tvDeviceName  = findViewById(R.id.tvDeviceName)
        statusDot     = findViewById(R.id.statusDot)
        btnToggleBle  = findViewById(R.id.btnToggleBle)
        progressBar   = findViewById(R.id.progressBar)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true   // newest messages at bottom
        }
    }

    private fun setupInputListeners() {
        // Send button click
        btnSend.setOnClickListener { sendUserMessage() }

        // Done key on keyboard also sends
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendUserMessage()
                true
            } else false
        }

        // Toggle BLE on/off
        btnToggleBle.setOnClickListener {
            if (bleRunning) stopBle() else checkPermissionsAndStart()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SEND MESSAGE
    // ══════════════════════════════════════════════════════════════

    private fun sendUserMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // Add to chat as outgoing
        addMessage(text, isOutgoing = true)
        etInput.setText("")
        hideKeyboard()

        // Show typing indicator while waiting for AI
        showTypingIndicator(true)

        // Send via BLE
        bleManager.sendPrompt(text)
    }

    private fun addMessage(text: String, isOutgoing: Boolean) {
        chatAdapter.addMessage(ChatMessage(text, isOutgoing))
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val last = chatAdapter.getLastIndex()
        if (last >= 0) recyclerView.smoothScrollToPosition(last)
    }

    private fun showTypingIndicator(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ══════════════════════════════════════════════════════════════
    //  BLE CALLBACK IMPLEMENTATION
    // ══════════════════════════════════════════════════════════════

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            statusDot.setBackgroundResource(R.drawable.dot_connected)
            tvStatus.text    = "Connected"
            tvDeviceName.text = deviceName
            btnToggleBle.text = "Stop BLE"
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusDot.setBackgroundResource(R.drawable.dot_disconnected)
            tvStatus.text     = "Advertising…"
            tvDeviceName.text = "Waiting for server"
            showTypingIndicator(false)
        }
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread {
            showTypingIndicator(false)
            addMessage(message, isOutgoing = false)
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            tvStatus.text = status
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            showTypingIndicator(false)
            showToast(error)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  BLE START / STOP
    // ══════════════════════════════════════════════════════════════

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        // Check Bluetooth is enabled
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!btManager.adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startBle()
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        bleManager.start()
        bleRunning = true
        btnToggleBle.text = "Stop BLE"
        statusDot.setBackgroundResource(R.drawable.dot_disconnected)
        tvStatus.text     = "Advertising…"
        tvDeviceName.text = "Waiting for server"
    }

    private fun stopBle() {
        bleManager.stop()
        bleRunning = false
        btnToggleBle.text = "Start BLE"
        statusDot.setBackgroundResource(R.drawable.dot_disconnected)
        tvStatus.text     = "Stopped"
        tvDeviceName.text = "—"
    }

    // ══════════════════════════════════════════════════════════════
    //  UTILITY
    // ══════════════════════════════════════════════════════════════

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
