package com.psychojelly.joancues

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * NTP-style clock-sync client (Phase 0 of AUDIO-SYNC-HANDOFF.md).
 *
 * Pings the master's :9001 every few seconds:
 *   t0 = local send time, master replies its time ts, t1 = local receive time
 *   one sample: offset = ts - (t0 + t1)/2, quality = rtt = t1 - t0
 * Keeps a window of samples and applies the median offset of the lowest-RTT
 * ones — moving devices produce noisy samples and this filter rejects them.
 *
 * The master's IP is learned automatically: PerformerService reports the
 * sender address of every cue it receives.
 */
object MasterClock {

    private const val TAG = "MasterClock"
    private const val CLOCK_PORT = 9001
    private const val PING_INTERVAL_MS = 4000L
    private const val WINDOW = 16
    private const val BEST_N = 4

    private data class Sample(val offset: Double, val rtt: Double)

    @Volatile private var masterIp: String? = null
    @Volatile private var appliedOffset: Double? = null
    private val samples = ArrayDeque<Sample>()
    private var thread: Thread? = null
    private var seq = 0

    fun localNow(): Double = System.nanoTime() / 1e9

    /** Master time now, or null while unsynced. */
    fun now(): Double? = appliedOffset?.let { localNow() + it }
    val isSynced: Boolean get() = appliedOffset != null

    /** Human-readable state for the status board. */
    fun status(): String {
        val ip = masterIp ?: return "clock: no master yet"
        val off = appliedOffset ?: return "clock: syncing to $ip…"
        return "clock: synced to $ip (offset %.1f ms, %d samples)".format(off * 1000, samples.size)
    }

    /** Called by PerformerService with the sender IP of each received cue. */
    fun setMaster(ip: String) {
        if (ip == masterIp) return
        synchronized(samples) { samples.clear() }
        appliedOffset = null
        masterIp = ip
        Log.i(TAG, "master -> $ip")
        if (thread == null) start()
    }

    private fun start() {
        thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                masterIp?.let { ping(it) }
                try { Thread.sleep(PING_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        thread?.interrupt(); thread = null
        masterIp = null; appliedOffset = null
        synchronized(samples) { samples.clear() }
    }

    private fun ping(ip: String) {
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1000
                val mySeq = ++seq
                val req = OscEncoder.encode("/clock/ping", listOf(mySeq))
                val t0 = localNow()
                sock.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), CLOCK_PORT))
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val t1 = localNow()
                val msg = OscDecoder.decode(p.data, p.length) ?: return
                if (msg.address != "/clock/pong") return
                if ((msg.values.getOrNull(0) as? Int) != mySeq) return   // stale reply
                val ts = (msg.values.getOrNull(1) as? Double) ?: return
                addSample(Sample(ts - (t0 + t1) / 2.0, t1 - t0))
            }
        } catch (e: Exception) {
            // timeouts while roaming are normal; the last good offset stays valid
        }
    }

    private fun addSample(s: Sample) {
        synchronized(samples) {
            samples.addLast(s)
            while (samples.size > WINDOW) samples.removeFirst()
            val best = samples.sortedBy { it.rtt }.take(BEST_N).map { it.offset }.sorted()
            if (best.isNotEmpty()) {
                val median = best[best.size / 2]
                // Slew toward the estimate rather than jumping.
                val cur = appliedOffset
                appliedOffset = if (cur == null) median else cur + (median - cur) * 0.3
            }
        }
    }
}
