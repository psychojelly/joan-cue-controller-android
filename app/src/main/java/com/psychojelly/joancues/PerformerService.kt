package com.psychojelly.joancues

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.ArrayDeque

/**
 * Performer mode: a foreground service that listens for OSC on :7000 (the
 * same port the headsets use) and drives the StemEngine — so the performer
 * tablet hears exactly the audio cues the operator fires at the group.
 *
 * Addresses mirror the Unity OscCueReceiver:
 *   /audio/cue <id>            trigger a cue (id or group key)
 *   /audio/seek <s>            global absolute seek
 *   /audio/jump <ds>           global relative jump
 *   /audio/seek/stem <n> <s>   per-stem seek
 *   /audio/jump/stem <n> <ds>  per-stem jump
 *   /audio/vol <n> <v> [spd]   per-stem volume
 *   /cue <n>                   visual cue — logged for awareness (no visuals here)
 */
class PerformerService : Service() {

    companion object {
        private const val TAG = "PerformerService"
        private const val CHANNEL_ID = "performer"
        private const val NOTIF_ID = 2
        const val OSC_PORT = 7000

        @Volatile var engine: StemEngine? = null; private set
        @Volatile var running = false; private set
        val recentLog = ArrayDeque<String>()   // newest first, max ~12

        fun log(line: String) {
            synchronized(recentLog) {
                recentLog.addFirst(line)
                while (recentLog.size > 12) recentLog.removeLast()
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, PerformerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PerformerService::class.java))
        }
    }

    private var socket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "joancues:performer").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "joancues:performerwifi").apply { acquire() }

            val eng = StemEngine(applicationContext)
            engine = eng
            running = true

            // Load CSV + preload stems in the background, then start listening.
            Thread {
                eng.loadConfigAndPreload()
                log("config: ${eng.status}")
            }.start()
            startListening(eng)
        }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    private fun startListening(eng: StemEngine) {
        listenThread = Thread {
            try {
                val sock = DatagramSocket(OSC_PORT)
                socket = sock
                log("listening on udp :$OSC_PORT")
                val buf = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val msg = OscDecoder.decode(packet.data, packet.length) ?: continue
                    handle(eng, msg)
                }
            } catch (e: Exception) {
                if (running) { Log.e(TAG, "listener died: ${e.message}"); log("listener error: ${e.message}") }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(eng: StemEngine, msg: OscDecoder.Message) {
        val v0 = msg.values.getOrNull(0)
        when (msg.address) {
            "/audio/cue" -> {
                val id = OscDecoder.asString(v0)
                if (id.isNotEmpty()) { log("audio cue  $id"); eng.triggerCue(id) }
            }
            "/audio/seek" -> eng.seekGlobal(OscDecoder.asFloat(v0))
            "/audio/jump" -> eng.jumpGlobal(OscDecoder.asFloat(v0))
            "/audio/seek/stem" -> eng.seekStem(OscDecoder.asString(v0), OscDecoder.asFloat(msg.values.getOrNull(1)))
            "/audio/jump/stem" -> eng.jumpStem(OscDecoder.asString(v0), OscDecoder.asFloat(msg.values.getOrNull(1)))
            "/audio/vol" -> eng.setStemVolume(
                OscDecoder.asString(v0),
                OscDecoder.asFloat(msg.values.getOrNull(1)),
                OscDecoder.asFloat(msg.values.getOrNull(2)))
            "/cue" -> log("visual cue ${OscDecoder.asString(v0)} (not rendered here)")
            else -> log("osc ${msg.address} ${msg.values.joinToString(" ")}")
        }
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        listenThread?.interrupt()
        engine?.release(); engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Performer Listener", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, PerformerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Performer listener running")
            .setContentText("Listening for cues on UDP :$OSC_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }
}
