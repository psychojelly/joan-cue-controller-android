package com.psychojelly.joancues

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
                session.method == Method.POST && session.uri == "/send" -> handleSend(session)
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
        val value = coerceValue(body.opt("value") ?: 1)

        val packet = OscEncoder.encode(addr, value)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
        }
        Log.i(TAG, "OSC -> $host:$port  $addr  $value")

        return cors(newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}"))
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
