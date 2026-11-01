package com.framemark.native.product

/**
 * Strict RFC 4648 base64 used by the embedded watermark assets.
 *
 * `kotlin.io.encoding.Base64` is still experimental on this toolchain and the
 * document format must not depend on an opt-in API. Decoding rejects invalid
 * characters, misplaced padding and wrong lengths instead of returning garbage.
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                (data[i + 2].toInt() and 0xff)
            out.append(ALPHABET[(n ushr 18) and 63])
                .append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63])
                .append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[(n ushr 18) and 63])
                    .append(ALPHABET[(n ushr 12) and 63])
                    .append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63])
                    .append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63])
                    .append('=')
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        require(text.length % 4 == 0) { "base64 长度必须是 4 的倍数（实际 ${text.length}）" }
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(text.length / 4 * 3 - padding)
        var outIndex = 0
        var i = 0
        while (i < text.length) {
            val last = i + 4 == text.length
            val c0 = valueAt(text, i)
            val c1 = valueAt(text, i + 1)
            val c2 = if (text[i + 2] == '=') -1 else valueAt(text, i + 2)
            val c3 = if (text[i + 3] == '=') -1 else valueAt(text, i + 3)
            if (!last && (c2 < 0 || c3 < 0)) {
                throw IllegalArgumentException("base64 填充出现在数据中间（偏移 $i）")
            }
            if (c2 < 0 && c3 >= 0) {
                throw IllegalArgumentException("base64 填充位置错误（偏移 $i）")
            }
            out[outIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (c2 >= 0) {
                out[outIndex++] = (((c1 and 0x0f) shl 4) or (c2 ushr 2)).toByte()
                if (c3 >= 0) {
                    out[outIndex++] = (((c2 and 0x03) shl 6) or c3).toByte()
                }
            }
            i += 4
        }
        return out
    }

    private fun valueAt(text: String, index: Int): Int {
        val c = text[index]
        val code = c.code
        if (code >= decodeTable.size) {
            throw IllegalArgumentException("base64 非法字符 '$c'（偏移 $index）")
        }
        val value = decodeTable[code]
        if (value < 0) throw IllegalArgumentException("base64 非法字符 '$c'（偏移 $index）")
        return value
    }
}
