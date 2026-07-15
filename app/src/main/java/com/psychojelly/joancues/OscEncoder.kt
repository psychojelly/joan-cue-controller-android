package com.psychojelly.joancues

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal OSC 1.0 message encoder — the wire format python-osc produces.
 *
 * An OSC message is:
 *   address (null-padded string to 4-byte boundary)
 *   type tag string, e.g. ",i" (null-padded to 4-byte boundary)
 *   arguments, each padded to 4-byte boundaries, big-endian
 *
 * Supported types mirror server.py: Int (i), Float (f), Boolean (T/F — no
 * payload bytes), String (s).
 */
object OscEncoder {

    fun encode(address: String, value: Any): ByteArray = encode(address, listOf(value))

    /** Multi-argument encode. Doubles go as OSC 'd' (64-bit) for ms precision. */
    fun encode(address: String, values: List<Any>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(paddedString(address))
        val tags = StringBuilder(",")
        val args = ByteArrayOutputStream()
        for (value in values) {
            when (value) {
                is Boolean -> tags.append(if (value) 'T' else 'F')   // no payload bytes
                is Int -> { tags.append('i'); args.write(ByteBuffer.allocate(4).putInt(value).array()) }
                is Float -> { tags.append('f'); args.write(ByteBuffer.allocate(4).putFloat(value).array()) }
                is Double -> { tags.append('d'); args.write(ByteBuffer.allocate(8).putDouble(value).array()) }
                else -> { tags.append('s'); args.write(paddedString(value.toString())) }
            }
        }
        out.write(paddedString(tags.toString()))
        out.write(args.toByteArray())
        return out.toByteArray()
    }

    /** OSC strings are ASCII, null-terminated, padded with nulls to a multiple of 4. */
    private fun paddedString(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        val padded = ((raw.size / 4) + 1) * 4   // always at least one null terminator
        return raw.copyOf(padded)
    }
}
