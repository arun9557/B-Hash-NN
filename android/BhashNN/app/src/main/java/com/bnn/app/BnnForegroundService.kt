package com.bnn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * BnnForegroundService — keeps BLE mesh running when the app is in background.
 * Uses BnnApp.meshState as the shared callback so ViewModel stays in sync.
 *
 * START: Intent(ctx, BnnForegroundService::class.java).also { startForegroundService(it) }
 * STOP:  Intent(ctx, BnnForegroundService::class.java).also { stopService(it) }
 */
class BnnForegroundService : Service() {

    companion object {
        const val CHANNEL_ID   = "bnn_mesh_channel"
        const val NOTIF_ID     = 1001
        const val ACTION_STOP  = "com.bnn.app.ACTION_STOP"

        fun startIntent(context: Context) =
            Intent(context, BnnForegroundService::class.java)

        fun stopIntent(context: Context) =
            Intent(context, BnnForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private val app get() = applicationContext as BnnApp

    // ══════════════════════════════════════════════════════════════════════
    //  Service lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately
        startForeground(NOTIF_ID, buildNotification())

        // Create or reuse BLEManager — use Application-level meshState as callback
        if (app.bleManager == null) {
            app.bleManager = BLEManager(applicationContext, app.meshState)
        }

        // Initialize and start the full MeshEngine (BLE + WiFi transports)
        if (app.meshEngine == null) {
            val engine = com.bnn.app.mesh.MeshEngine(
                myId = BnnDeviceIdentifier.get(applicationContext),
                callback = app.meshState
            )
            val bleTransport = BLETransportAdapter(app.bleManager!!)
            val transportManager = com.bnn.app.transport.TransportManager(
                context = applicationContext,
                myId = BnnDeviceIdentifier.get(applicationContext),
                routeTable = engine.routeTable,
                onIncomingPacket = { packet, fromPeer, transport ->
                    if (transport != com.bnn.app.transport.TransportType.BLE) {
                        app.meshState.onWifiPeerConnected(fromPeer, transport)
                    }
                    engine.onPacketReceived(packet, fromPeer, transport)
                }
            )
            transportManager.init(bleTransport)
            engine.attachTransportManager(transportManager)
            app.meshEngine = engine
        }
        app.meshEngine?.start()
        app.meshState.onStatusChanged("Mesh active · scanning…")

        return START_STICKY // Restart if killed by OS
    }

    override fun onDestroy() {
        super.onDestroy()
        app.meshEngine?.stop()
        app.bleManager?.stop()
        app.meshState.reset()
        app.bleManager = null
        app.meshEngine = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ══════════════════════════════════════════════════════════════════════
    //  Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "B#NN Mesh Network",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the B#NN AI mesh running in the background"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tap notification → open app
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action in notification
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("B#NN Mesh Active")
            .setContentText("AI mesh is running in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
