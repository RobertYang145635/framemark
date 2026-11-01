package com.framemark.native.engine

import kotlin.math.abs

/**
 * Minimal JSON writer for the bridge (request/response traces and the temp
 * design/cards documents the hand renderer consumes). Objects keep insertion
 * order; numbers are rounded to 3 decimals so geometry stays readable.
 */
internal object BridgeJson {
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Float -> number(value.toDouble(), out)
            is Double -> number(value, out)
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.toList(), out)
            else -> quote(value.toString(), out)
        }
    }

    private fun number(d: Double, out: StringBuilder) {
        if (!d.isFinite()) {
            out.append("null")
            return
        }
        if (d == Math.floor(d) && abs(d) < 1e15) {
            out.append(d.toLong().toString())
        } else {
            // Shortest round-trip form: design values (e.g. canvasAspect 1.3045)
            // must survive the temp-design round trip exactly. Geometry is
            // rounded by the caller, not here.
            out.append(java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString())
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        s.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }
}