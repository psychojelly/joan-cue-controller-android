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

    fun encode(address: String, value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(paddedString(address))
        when (value) {
            is Boolean -> {
                out.write(paddedString(if (value) ",T" else ",F"))
                // T/F carry no argument bytes.
            }
            is Int -> {
                out.write(paddedString(",i"))
                out.write(ByteBuffer.allocate(4).putInt(value).array())
            }
            is Float -> {
                out.write(paddedString(",f"))
                out.write(ByteBuffer.allocate(4).putFloat(value).array())
            }
            is Double -> {
                out.write(paddedString(",f"))
                out.write(ByteBuffer.allocate(4).putFloat(value.toFloat()).array())
            }
            else -> {
                out.write(paddedString(",s"))
                out.write(paddedString(value.toString()))
            }
        }
        return out.toByteArray()
    }

    /** OSC strings are ASCII, null-terminated, padded with nulls to a multiple of 4. */
    private fun paddedString(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        val padded = ((raw.size / 4) + 1) * 4   // always at least one null terminator
        return raw.copyOf(padded)
    }
}
