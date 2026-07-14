package com.psychojelly.joancues

import java.nio.ByteBuffer

/**
 * Minimal OSC 1.0 message decoder — the receive-side twin of OscEncoder.
 * Parses address, type tags, and arguments (i, f, s, d, h, T, F).
 */
object OscDecoder {

    data class Message(val address: String, val values: List<Any>)

    fun decode(packet: ByteArray, length: Int): Message? {
        return try {
            val buf = ByteBuffer.wrap(packet, 0, length)
            val address = readString(buf) ?: return null
            if (!address.startsWith("/")) return null

            val tags = readString(buf) ?: return Message(address, emptyList())
            val values = mutableListOf<Any>()
            for (tag in tags.drop(1)) {   // skip the leading ','
                when (tag) {
                    'i' -> values.add(buf.int)
                    'f' -> values.add(buf.float)
                    'd' -> values.add(buf.double)
                    'h' -> values.add(buf.long)
                    's' -> readString(buf)?.let { values.add(it) }
                    'T' -> values.add(true)
                    'F' -> values.add(false)
                    // unknown tags: stop rather than misalign
                    else -> return Message(address, values)
                }
            }
            Message(address, values)
        } catch (e: Exception) {
            null
        }
    }

    /** Mirrors the Unity receiver's TryString: any value can serve as a cue id. */
    fun asString(v: Any?): String = when (v) {
        null -> ""
        is Float -> if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
        is Double -> if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
        else -> v.toString()
    }

    fun asFloat(v: Any?, fallback: Float = 0f): Float = when (v) {
        is Float -> v
        is Double -> v.toFloat()
        is Int -> v.toFloat()
        is Long -> v.toFloat()
        is String -> v.toFloatOrNull() ?: fallback
        else -> fallback
    }

    private fun readString(buf: ByteBuffer): String? {
        if (!buf.hasRemaining()) return null
        val start = buf.position()
        var end = start
        while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
        if (end >= buf.limit()) return null
        val bytes = ByteArray(end - start)
        buf.position(start); buf.get(bytes)
        // consume padding: strings are padded with nulls to a 4-byte boundary
        val consumed = (end - start) + 1
        val padded = ((consumed + 3) / 4) * 4
        buf.position(start + padded.coerceAtMost(buf.limit() - start))
        return String(bytes, Charsets.UTF_8)
    }
}
