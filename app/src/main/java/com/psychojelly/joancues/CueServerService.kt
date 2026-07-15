package com.psychojelly.joancues

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import java.net.NetworkInterface

/**
 * Foreground service that owns the cue server for the whole show.
 *
 * Foreground = Android treats it as user-visible work and will not kill it
 * for battery optimization (the fatal flaw of the Termux approach). It also
 * holds a partial wake lock and a low-latency Wi-Fi lock so cues keep
 * flowing with the screen off.
 */
class CueServerService : Service() {

    companion object {
        private const val TAG = "CueServerService"
        private const val CHANNEL_ID = "cue_server"
        private const val NOTIF_ID = 1

        @Volatile var running = false; private set

        fun start(context: Context) {
            val intent = Intent(context, CueServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private var server: CueHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var clockSocket: java.net.DatagramSocket? = null
    private var clockThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            // Locks: CPU stays awake; Wi-Fi stays in low-latency mode.
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "joancues:server").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "joancues:wifi").apply { acquire() }

            server = CueHttpServer(applicationContext).also { it.start() }
            startClockResponder()
            running = true
            Log.i(TAG, "Cue server started on :${CueHttpServer.PORT} (+clock :9001)")
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY   // restart if the system ever does reclaim us
    }

    /** UDP :9001 — answers /clock/ping [seq] with /clock/pong [seq, masterTime]. */
    private fun startClockResponder() {
        clockThread = Thread {
            try {
                val sock = java.net.DatagramSocket(9001)
                clockSocket = sock
                val buf = ByteArray(512)
                while (!Thread.currentThread().isInterrupted) {
                    val p = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val msg = OscDecoder.decode(p.data, p.length) ?: continue
                    if (msg.address != "/clock/ping") continue
                    val seq = (msg.values.getOrNull(0) as? Int) ?: 0
                    val reply = OscEncoder.encode("/clock/pong", listOf(seq, CueHttpServer.masterNow()))
                    sock.send(java.net.DatagramPacket(reply, reply.size, p.address, p.port))
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "clock responder: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        try { clockSocket?.close() } catch (_: Exception) {}
        clockThread?.interrupt()
        server?.stop(); server = null; running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "Cue server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Cue Server",
                NotificationManager.IMPORTANCE_LOW)   // silent, no sound/vibration
            channel.description = "Keeps the Joan of the City cue server running"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val ip = localIp() ?: "this device"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Joan cue server running")
            .setContentText("http://$ip:${CueHttpServer.PORT} — other devices can connect here")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    /** Best-effort LAN IPv4, so the notification shows where thin clients connect. */
    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    } catch (e: Exception) { null }
}
