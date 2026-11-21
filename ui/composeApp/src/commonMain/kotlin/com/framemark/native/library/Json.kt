package com.framemark.native.library

/**
 * Minimal JSON reader for the bundled E1a data.
 *
 * The UI only needs objects/arrays/strings/numbers/booleans/null, so this avoids
 * adding kotlinx-serialization (and a compiler plugin) just to read two frozen
 * files. Malformed input throws [IllegalArgumentException] with the offset.
 */
internal class JsonReader(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        require(index == text.length) { "trailing data at $index" }
        return value
    }

    private fun readValue(): Any? {
        return when (val c = peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect("{")
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') { index++; return out }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(":")
            skipWhitespace()
            out[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> { index++; return out }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect("[")
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') { index++; return out }
        while (true) {
            skipWhitespace()
            out.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> { index++; return out }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        expect("\"")
        val sb = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            when (val c = text[index++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (index >= text.length) fail("unterminated escape")
                    when (val e = text[index++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("short \\u escape")
                            val hex = text.substring(index, index + 4)
                            index += 4
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Double {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
        return text.substring(start, index).toDoubleOrNull() ?: fail("bad number")
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) index++
    }

    private fun peek(): Char {
        if (index >= text.length) fail("unexpected end of input")
        return text[index]
    }

    private fun expect(literal: String) {
        if (!text.startsWith(literal, index)) fail("expected '$literal'")
        index += literal.length
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("JSON $message at offset $index")
}

internal object Json {
    fun parse(text: String): Any? = JsonReader(text).parse()
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.jsonMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

internal fun Any?.jsonList(): List<Any?> = this as? List<Any?> ?: emptyList()

internal fun Map<String, Any?>.jsonMap(key: String): Map<String, Any?> = this[key].jsonMap()

internal fun Map<String, Any?>.jsonString(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.jsonBool(key: String): Boolean? = this[key] as? Boolean

internal fun Map<String, Any?>.jsonInt(key: String): Int? = (this[key] as? Number)?.toInt()
