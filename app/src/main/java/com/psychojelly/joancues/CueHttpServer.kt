package com.psychojelly.joancues

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The Kotlin twin of server.py:
 *   GET  /            -> index.html (the cue controller) from app assets
 *   GET  /<file>      -> other assets (csv-editor.html, etc.)
 *   POST /send        -> {host, port, address, value} JSON -> one OSC/UDP packet
 *
 * Same value-typing rules as server.py:
 *   bool/int/float pass through; a string that looks like a clean integer is
 *   sent as an int (back-compat with receivers reading IntValue); any other
 *   string is sent as an OSC string (cue ids like "B_VQ_L01").
 */
class CueHttpServer(private val context: Context, port: Int = PORT) : NanoHTTPD(port) {

    companion object {
        const val PORT = 8765
        private const val TAG = "CueHttpServer"

        /** Master clock = this device's monotonic time, seconds (double). */
        fun masterNow(): Double = System.nanoTime() / 1e9

        /** Best-effort LAN IPv4 of this device — sent in /clock/master announces. */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
        } catch (e: Exception) { null }

        // ---- Debug observability (D0): /debug/* reports buffered for the page ----
        private val debugLock = Any()
        private val debugEvents = ArrayDeque<org.json.JSONObject>()
        private var debugSeq = 0L
        private const val DEBUG_RING = 500

        fun debugAdd(addr: String, args: List<Any>, from: String) {
            synchronized(debugLock) {
                debugSeq++
                debugEvents.addLast(org.json.JSONObject().apply {
                    put("seq", debugSeq)
                    put("t", masterNow())
                    put("addr", addr)
                    put("args", org.json.JSONArray(args))
                    put("from", from)
                })
                while (debugEvents.size > DEBUG_RING) debugEvents.removeFirst()
            }
        }

        private fun debugEventsJson(since: Long): String = synchronized(debugLock) {
            val arr = org.json.JSONArray()
            for (e in debugEvents) if (e.getLong("seq") > since) arr.put(e)
            org.json.JSONObject().apply {
                put("seq", debugSeq); put("now", masterNow()); put("events", arr)
            }.toString()
        }

        // ---- Headset snapshots (JPEG over HTTP — too big for OSC/UDP) --------
        // Latest per device id, in memory. Parity with server.py.
        private val snapshotLock = Any()
        private val snapshots = HashMap<String, ByteArray>()
        private const val SNAPSHOT_MAX = 8_000_000
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
                session.method == Method.GET && session.uri == "/debug/events" -> {
                    val since = session.parms["since"]?.toLongOrNull() ?: 0L
                    cors(newFixedLengthResponse(Response.Status.OK, "application/json", debugEventsJson(since)))
                }
                session.method == Method.POST && session.uri == "/send" -> handleSend(session)
                session.method == Method.GET && session.uri == "/debug/snapshot" -> {
                    val id = session.parms["id"] ?: ""
                    val jpg = synchronized(snapshotLock) { snapshots[id] }
                    if (jpg == null)
                        cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No snapshot for that device yet"))
                    else
                        cors(newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                            jpg.inputStream(), jpg.size.toLong()).apply {
                            addHeader("Cache-Control", "no-store")
                        })
                }
                session.method == Method.POST && session.uri == "/debug/snapshot" -> {
                    val id = session.parms["id"]?.ifEmpty { null } ?: "device"
                    val length = session.headers["content-length"]?.toIntOrNull() ?: 0
                    if (length <= 0 || length > SNAPSHOT_MAX) {
                        cors(newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                            "{\"ok\":false,\"error\":\"bad snapshot size\"}"))
                    } else {
                        val jpg = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = session.inputStream.read(jpg, read, length - read)
                            if (n <= 0) break
                            read += n
                        }
                        synchronized(snapshotLock) { snapshots[id] = jpg }
                        debugAdd("/debug/snapshot", listOf(id, jpg.size),
                            session.remoteIpAddress ?: "?")
                        cors(newFixedLengthResponse(Response.Status.OK, "application/json",
                            "{\"ok\":true,\"bytes\":${jpg.size}}"))
                    }
                }
                else -> serveAsset(session.uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"ok\":false,\"error\":\"${e.message}\"}"))
        }
    }

    // ---- POST /send -> OSC/UDP ---------------------------------------------

    private fun handleSend(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        val body = JSONObject(String(buf, 0, read, Charsets.UTF_8))

        val host = body.optString("host", "127.0.0.1")
        val port = body.optInt("port", 7000)
        val addr = body.optString("address", "/cue")
        var value = coerceValue(body.opt("value") ?: 1)

        // "/clock/master" with value "auto": substitute this device's LAN IP —
        // the page announces the master to configured devices every few seconds
        // (roster bootstrap; parity with server.py).
        if (addr == "/clock/master" && value == "auto") {
            value = localIp() ?: return cors(newFixedLengthResponse(
                Response.Status.OK, "application/json",
                "{\"ok\":false,\"error\":\"no local ip\"}"))
        }

        // NEW way (sync mode): schedule audio cues on the master clock.
        // leadMs present + /audio/* address -> append playAt (master monotonic
        // seconds, OSC double) and send 3x at 50ms spacing; receivers dedupe by
        // (cueId, playAt). Without leadMs -> old behavior, byte-identical.
        val leadMs = if (body.has("leadMs")) body.optDouble("leadMs", 400.0) else null
        val scheduled = leadMs != null && addr.startsWith("/audio/")

        val sentAt = masterNow()
        if (scheduled) {
            val playAt = sentAt + leadMs!! / 1000.0
            val packet = OscEncoder.encode(addr, listOf(value, playAt))
            DatagramSocket().use { socket ->
                // Announce the master's IP so receivers (Unity) know where to
                // send /clock/ping. The performer app auto-learns from packet
                // source; extOSC can't, so we tell it explicitly.
                localIp()?.let { ip ->
                    val announce = OscEncoder.encode("/clock/master", ip)
                    socket.send(DatagramPacket(announce, announce.size, InetAddress.getByName(host), port))
                }
                repeat(3) { i ->
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
                    if (i < 2) Thread.sleep(50)
                }
            }
            Log.i(TAG, "OSC -> $host:$port  $addr  $value  playAt=+${leadMs}ms x3")
        } else {
            val packet = OscEncoder.encode(addr, value)
            DatagramSocket().use { socket ->
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
            }
            Log.i(TAG, "OSC -> $host:$port  $addr  $value")
        }

        // Parity with server.py: return the master-clock send time (and playAt
        // for scheduled sends) so the page can stamp its SENT lines on the same
        // scale as the devices' replies.
        val resp = JSONObject().apply {
            put("ok", true)
            put("sentAt", sentAt)
            if (scheduled) put("playAt", sentAt + leadMs!! / 1000.0)
        }
        return cors(newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()))
    }

    /** server.py's type-preservation rules. */
    private fun coerceValue(raw: Any): Any = when (raw) {
        is Boolean -> raw
        is Int -> raw
        is Long -> raw.toInt()
        is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toInt() else raw
        is String -> {
            val stripped = raw.trim()
            if (stripped.removePrefix("-").isNotEmpty() &&
                stripped.removePrefix("-").all { it.isDigit() }) stripped.toInt() else stripped
        }
        else -> raw.toString()
    }

    // ---- Static assets ------------------------------------------------------

    private fun serveAsset(uri: String): Response {
        val path = when (val clean = uri.trimStart('/')) {
            "" -> "index.html"
            else -> clean
        }
        return try {
            val stream = context.assets.open("web/$path")
            cors(newChunkedResponse(Response.Status.OK, mimeFor(path), stream))
        } catch (e: Exception) {
            cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $path"))
        }
    }

    private fun mimeFor(path: String) = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return r
    }
}
