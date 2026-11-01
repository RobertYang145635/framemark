package com.framemark.native.product

import com.framemark.native.library.Json
import kotlin.math.floor

/** A document that does not match its schema. Never partial: the loader reports it and stops. */
class ProductDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Deterministic JSON writing plus strict field access for the product documents.
 *
 * Reading reuses the bundled `library.Json` parser; writing is local because the
 * documents need stable key order (map keys sorted) so an unchanged model always
 * produces byte-identical files (idempotent auto-save) and readable diffs.
 */
internal object ProductJson {
    fun quote(text: String): String {
        val sb = StringBuilder(text.length + 8)
        sb.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun number(value: Double): String {
        require(value.isFinite()) { "非有限数值不能写入 JSON：$value" }
        if (value == 0.0) return "0"
        if (value == floor(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        return value.toString()
    }

    /** Floats go through their shortest decimal form, exactly like design values. */
    fun number(value: Float): String = number(value.toString().toDouble())

    fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is String -> quote(value)
        is Number -> number(value.toDouble())
        else -> throw IllegalArgumentException("不支持的设计值类型：${value::class.simpleName}")
    }

    fun encodeValues(values: Map<String, Any?>): String =
        values.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
            quote(key) + ":" + encodeValue(value)
        }

    fun decodeValues(raw: Any?, where: String): Map<String, Any?> {
        if (raw == null) return emptyMap()
        val map = raw as? Map<*, *> ?: throw ProductDecodeException("$where 不是对象")
        val out = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) ->
            if (key !is String) throw ProductDecodeException("$where 含非字符串键")
            when (value) {
                null -> Unit
                is Boolean, is String -> out[key] = value
                is Number -> {
                    val d = value.toDouble()
                    if (!d.isFinite()) throw ProductDecodeException("$where.$key 不是有限数值")
                    out[key] = d
                }
                else -> throw ProductDecodeException("$where.$key 不是标量（布尔/数字/字符串）")
            }
        }
        return out
    }

    fun parseRoot(text: String): Map<String, Any?> {
        val parsed = try {
            Json.parse(text)
        } catch (t: Throwable) {
            throw ProductDecodeException("JSON 解析失败：${t.message}", t)
        }
        return parsed as? Map<String, Any?> ?: throw ProductDecodeException("文档根节点不是对象")
    }
}

internal fun requireObject(raw: Any?, where: String): Map<String, Any?> =
    raw as? Map<String, Any?> ?: throw ProductDecodeException("$where 不是对象")

internal fun requireList(raw: Any?, where: String): List<Any?> =
    raw as? List<Any?> ?: throw ProductDecodeException("$where 不是数组")

internal fun Map<String, Any?>.requireString(key: String, where: String): String {
    val raw = this[key] ?: throw ProductDecodeException("$where.$key 缺失")
    return raw as? String ?: throw ProductDecodeException("$where.$key 不是字符串")
}

internal fun Map<String, Any?>.optionalString(key: String, where: String): String? = when (val raw = this[key]) {
    null -> null
    is String -> raw
    else -> throw ProductDecodeException("$where.$key 不是字符串")
}

internal fun Map<String, Any?>.requireInt(key: String, where: String): Int {
    val raw = this[key] ?: throw ProductDecodeException("$where.$key 缺失")
    return integral(raw, "$where.$key").toIntChecked("$where.$key")
}

internal fun Map<String, Any?>.optionalInt(key: String, where: String): Int? = when (val raw = this[key]) {
    null -> null
    else -> integral(raw, "$where.$key").toIntChecked("$where.$key")
}

internal fun Map<String, Any?>.requireLong(key: String, where: String): Long {
    val raw = this[key] ?: throw ProductDecodeException("$where.$key 缺失")
    return integral(raw, "$where.$key").toLongChecked("$where.$key")
}

internal fun Map<String, Any?>.optionalLong(key: String, where: String): Long? = when (val raw = this[key]) {
    null -> null
    else -> integral(raw, "$where.$key").toLongChecked("$where.$key")
}

internal fun Map<String, Any?>.requireFloat(key: String, where: String): Float {
    val raw = this[key] ?: throw ProductDecodeException("$where.$key 缺失")
    val number = raw as? Number ?: throw ProductDecodeException("$where.$key 不是数字")
    val d = number.toDouble()
    if (!d.isFinite()) throw ProductDecodeException("$where.$key 不是有限数值")
    return d.toFloat()
}

internal fun Map<String, Any?>.optionalDouble(key: String, where: String): Double? = when (val raw = this[key]) {
    null -> null
    is Number -> {
        val d = raw.toDouble()
        if (!d.isFinite()) throw ProductDecodeException("$where.$key 不是有限数值")
        d
    }
    else -> throw ProductDecodeException("$where.$key 不是数字")
}

internal fun Map<String, Any?>.requireBool(key: String, where: String): Boolean {
    val raw = this[key] ?: throw ProductDecodeException("$where.$key 缺失")
    return raw as? Boolean ?: throw ProductDecodeException("$where.$key 不是布尔值")
}

internal fun Map<String, Any?>.optionalBool(key: String, where: String): Boolean? = when (val raw = this[key]) {
    null -> null
    is Boolean -> raw
    else -> throw ProductDecodeException("$where.$key 不是布尔值")
}

private fun integral(raw: Any, where: String): Double {
    val number = raw as? Number ?: throw ProductDecodeException("$where 不是数字")
    val d = number.toDouble()
    if (!d.isFinite() || d != floor(d)) throw ProductDecodeException("$where 不是整数")
    return d
}

private fun Double.toIntChecked(where: String): Int {
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) throw ProductDecodeException("$where 超出整数范围")
    return toInt()
}

private fun Double.toLongChecked(where: String): Long {
    if (this < Long.MIN_VALUE.toDouble() || this > Long.MAX_VALUE.toDouble()) {
        throw ProductDecodeException("$where 超出整数范围")
    }
    return toLong()
}
