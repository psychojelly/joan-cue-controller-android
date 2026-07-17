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
            ensureHeartbeatThread()   // safety-net roster beacon (5 s once a master is known)
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
                    // The device that sends us cues IS the clock master.
                    packet.address?.hostAddress?.let { MasterClock.setMaster(it) }
                    handle(eng, msg)
                }
            } catch (e: Exception) {
                if (running) { Log.e(TAG, "listener died: ${e.message}"); log("listener error: ${e.message}") }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Dedupe for redundant scheduled sends: (cueId|playAt), most recent ~32. */
    private val seenScheduled = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 32
    }

    // ---- Debug observability (device side): reports to master :9002 ---------
    /** Low-rate always-on roster beacon, parity with Unity's DebugReporter. */
    private val SAFETY_HEARTBEAT = true
    @Volatile private var debugEnabled = false
    @Volatile private var heartbeatEnabled = false
    private var hbThread: Thread? = null
    private val deviceId: String by lazy {
        val model = android.os.Build.MODEL.replace(' ', '_')
        val ipTail = localIpTail()
        "$model-$ipTail"
    }

    private fun localIpTail(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress?.substringAfterLast('.') ?: "0"
    } catch (e: Exception) { "0" }

    /** Battery % (0-100) or -1f if unreadable — heartbeat vitals. */
    private fun batteryPct(): Float = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) pct.toFloat() else -1f
    } catch (_: Exception) { -1f }

    private fun batteryCharging(): Boolean = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.isCharging
    } catch (_: Exception) { false }

    /** Fire-and-forget OSC to the master's :9002 debug listener. */
    private fun sendDebug(addr: String, vararg args: Any) {
        val master = MasterClock.master ?: return
        Thread {
            try {
                val packet = OscEncoder.encode(addr, args.toList())
                DatagramSocket().use {
                    it.send(DatagramPacket(packet, packet.size,
                        java.net.InetAddress.getByName(master), 9002))
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun setHeartbeat(on: Boolean) {
        heartbeatEnabled = on
        ensureHeartbeatThread()
    }

    /**
     * One heartbeat thread for both modes — parity with Unity's DebugReporter:
     * 1 s while the operator has the roster on, 5 s safety-net otherwise (so
     * the roster always answers "is this tablet alive?" once a master is
     * known, even mid-show with debug off). Set SAFETY_HEARTBEAT = false for
     * the old strictly-zero-traffic behavior. Exits with the service.
     */
    private fun ensureHeartbeatThread() {
        if (hbThread?.isAlive == true) return
        hbThread = Thread {
            while (running) {
                if (MasterClock.master != null && (heartbeatEnabled || SAFETY_HEARTBEAT)) {
                    val eng = engine
                    // Trailing vitals (fps, battery%, charging) — parity with the
                    // Unity heartbeat. A background service has no renderer, so
                    // fps is -1 (consumers hide negatives); battery is the useful
                    // one for performer tablets.
                    sendDebug("/debug/hb", deviceId,
                        MasterClock.now() ?: -1.0,
                        eng?.lastCueId ?: "",
                        eng?.activeStems()?.size ?: 0,
                        MasterClock.offsetMs ?: -1.0,
                        -1.0f, batteryPct(), if (batteryCharging()) 1 else 0)
                }
                Thread.sleep(if (heartbeatEnabled) 1000L else 5000L)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(eng: StemEngine, msg: OscDecoder.Message) {
        val v0 = msg.values.getOrNull(0)
        when (msg.address) {
            "/audio/cue" -> {
                val id = OscDecoder.asString(v0)
                if (id.isEmpty()) return
                val playAt = msg.values.getOrNull(1) as? Double
                if (playAt != null) {
                    // NEW way: scheduled start. Dedupe the 3x redundant sends.
                    val key = "$id|$playAt"
                    synchronized(seenScheduled) {
                        if (seenScheduled.containsKey(key)) return
                        seenScheduled[key] = true
                    }
                    val now = MasterClock.now()
                    val inMs = if (now != null) ((playAt - now) * 1000).toLong() else null
                    log("audio cue  $id  scheduled ${if (inMs != null) "in ${inMs}ms" else "(unsynced: now)"}")
                    if (debugEnabled) sendDebug("/debug/rx", deviceId, id, now ?: -1.0, playAt)
                    eng.triggerCueAt(id, playAt)
                } else {
                    // OLD way: immediate, exactly as before.
                    log("audio cue  $id"); eng.triggerCue(id)
                    if (debugEnabled) sendDebug("/debug/rx", deviceId, id, MasterClock.now() ?: -1.0, 0.0)
                }
            }
            "/audio/seek" -> eng.seekGlobal(OscDecoder.asFloat(v0))
            "/audio/jump" -> eng.jumpGlobal(OscDecoder.asFloat(v0))
            "/audio/seek/stem" -> eng.seekStem(OscDecoder.asString(v0), OscDecoder.asFloat(msg.values.getOrNull(1)))
            "/audio/jump/stem" -> eng.jumpStem(OscDecoder.asString(v0), OscDecoder.asFloat(msg.values.getOrNull(1)))
            "/audio/vol" -> eng.setStemVolume(
                OscDecoder.asString(v0),
                OscDecoder.asFloat(msg.values.getOrNull(1)),
                OscDecoder.asFloat(msg.values.getOrNull(2)))
            "/audio/reload" -> {
                log("reload requested — refetching CSV + stems")
                Thread { eng.loadConfigAndPreload(); log("config: ${eng.status}") }.start()
            }
            "/debug/enable" -> {
                val on = OscDecoder.asFloat(v0) != 0f
                debugEnabled = on
                log("debug reporting ${if (on) "ON" else "OFF"}")
                if (on) sendDebug("/debug/hello", deviceId, "performer")
            }
            "/debug/heartbeat" -> {
                val on = OscDecoder.asFloat(v0) != 0f
                log("heartbeat ${if (on) "ON" else "OFF"}")
                setHeartbeat(on)
            }
            "/cue" -> log("visual cue ${OscDecoder.asString(v0)} (not rendered here)")
            else -> log("osc ${msg.address} ${msg.values.joinToString(" ")}")
        }
    }

    override fun onDestroy() {
        running = false
        MasterClock.stop()
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
