package com.framemark.native.product

/**
 * HEIC / HEIF (ISO-BMFF) 元数据读取：EXIF 与 HDR 信号。
 *
 * Android 用 `BitmapFactory` 把 HEIC 解码成 8-bit Bitmap 时，容器里的 Exif 项不会跟着
 * 走（转成 PNG 后就丢了），引擎又只认 JPEG APP1。这里把 Exif 项从 `meta` 盒子里挖出来，
 * 由导入流程写成一个只含 APP1 的「EXIF 侧车」JPEG；引擎的既有解析器读侧车即可。
 *
 * 同时读出 HDR 信号（`hvcC` 位深、`colr/nclx` 传递函数、增益图 `auxC` URN、`tmap`），
 * 供导入日志与导出提示使用 —— 这些字段在解码后的位图里已经不存在了，只能从容器读。
 *
 * 纯 Kotlin、无 Android 依赖，所以桌面测试可以用合成的 HEIF 字节覆盖每个盒子。
 * 只解析本产品需要的部分：`iinf` / `iloc` / `ipco(hvcC, colr, auxC)` / `iref` / `tmap`。
 */
object Heif {

    /** 从 HEIF 文件里读出的 TIFF 块（含 IFD0，可能含 ExifIFD）。 */
    data class ExifBlock(val tiff: ByteArray, val orientation: Int)

    /** 容器层能读到的 HDR 线索；值缺失时为 0 / null / 空。 */
    data class HdrInfo(
        /** hvcC 的最大位深（8 / 10 / 12…），读不到为 0。 */
        val bitDepth: Int,
        /** `colr` 为 `nclx` 时的传递函数（16 = PQ，18 = HLG），否则为 null。 */
        val transfer: Int?,
        /** 增益图辅助图的 auxC URN（Apple / ISO 21496-1），没有为 null。 */
        val gainMapUrn: String?,
        /** 是否带 ISO 21496-1 的 `tmap` 盒。 */
        val hasToneMap: Boolean,
    ) {
        /**
         * HDR 判定：10-bit 及以上、PQ/HLG、或带增益图 / tmap。OPPO 的 10-bit HEIC
         * 没有增益图、`colr` 也只有 ICC，所以位深是唯一可靠的线索。
         */
        val isHdr: Boolean
            get() = bitDepth >= 10 || transfer == 16 || transfer == 18 || gainMapUrn != null || hasToneMap
    }

    private const val BOX_FTYP = "ftyp"
    private const val BOX_META = "meta"
    private const val BOX_IINF = "iinf"
    private const val BOX_INFE = "infe"
    private const val BOX_ILOC = "iloc"
    private const val BOX_IDAT = "idat"
    private const val BOX_IPRP = "iprp"
    private const val BOX_IPCO = "ipco"
    private const val BOX_HVCC = "hvcC"
    private const val BOX_COLR = "colr"
    private const val BOX_AUXC = "auxC"
    private const val BOX_TMAP = "tmap"

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

    /** 文件头是不是 HEIF：`ftyp` 盒 + 已知 brand。 */
    fun isHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (ascii(bytes, 4, 4) != BOX_FTYP) return false
        val brand = ascii(bytes, 8, 4)
        if (brand in HEIF_BRANDS) return true
        // 兼容 brand 列表里的 heic/heix（如 `mif1` 主 brand + `heic` 兼容）。
        val end = minOf(bytes.size, boxEnd(bytes, 0) ?: 0)
        var at = 16
        while (at + 4 <= end) {
            if (ascii(bytes, at, 4) in HEIF_BRANDS) return true
            at += 4
        }
        return false
    }

    /** Exif 项 → TIFF 块；没有 Exif 项或结构不完整时为 null。 */
    fun exifBlock(bytes: ByteArray): ExifBlock? {
        val payload = itemPayload(bytes, "Exif") ?: return null
        val tiff = tiffFromExifItem(payload) ?: return null
        return ExifBlock(tiff = tiff, orientation = orientationOf(tiff))
    }

    /** 只读 HDR 信号；非 HEIF 返回 null。 */
    fun hdrInfo(bytes: ByteArray): HdrInfo? {
        if (!isHeif(bytes)) return null
        val meta = findBox(bytes, 0, bytes.size, BOX_META) ?: return null
        // meta 是 FullBox：4 字节 version/flags 之后才是子盒。
        val metaStart = meta.dataStart + 4
        val metaEnd = meta.end

        var bitDepth = 0
        var transfer: Int? = null
        var gainMapUrn: String? = null
        var hasToneMap = false

        val iprp = findBox(bytes, metaStart, metaEnd, BOX_IPRP)
        if (iprp != null) {
            val ipco = findBox(bytes, iprp.dataStart, iprp.end, BOX_IPCO)
            if (ipco != null) {
                var at = ipco.dataStart
                while (at + 8 <= ipco.end) {
                    val box = readBoxHeader(bytes, at, ipco.end) ?: break
                    when (box.type) {
                        BOX_HVCC -> {
                            val depth = hvcBitDepth(bytes, box.dataStart, box.end)
                            if (depth > bitDepth) bitDepth = depth
                        }
                        BOX_COLR -> {
                            val t = nclxTransfer(bytes, box.dataStart, box.end)
                            if (t != null) transfer = t
                        }
                        BOX_AUXC -> {
                            val urn = auxUrn(bytes, box.dataStart, box.end)
                            if (urn != null && gainMapUrn == null) gainMapUrn = urn
                        }
                    }
                    at = box.end
                }
            }
        }
        // auxC 也可能直接放进 meta 的其它位置；tmap 则可能挂在 meta 下任意处。
        if (gainMapUrn == null) gainMapUrn = findGainMapUrn(bytes, metaStart, metaEnd)
        if (hasBox(bytes, metaStart, metaEnd, BOX_TMAP)) hasToneMap = true

        return HdrInfo(bitDepth = bitDepth, transfer = transfer, gainMapUrn = gainMapUrn, hasToneMap = hasToneMap)
    }

    /** IFD0 Orientation（缺失 / 越界时为 1）。 */
    fun orientationOf(tiff: ByteArray): Int = runCatching {
        val le = endian(tiff) ?: return 1
        val ifd0 = u32(tiff, 4, le).toLong()
        if (ifd0 < 8 || ifd0 + 2 > tiff.size) return 1
        val at = ifd0.toInt()
        val count = u16(tiff, at, le)
        var p = at + 2
        repeat(count) {
            if (p + 12 > tiff.size) return 1
            val tag = u16(tiff, p, le)
            if (tag == 0x0112) {
                val value = u16(tiff, p + 8, le)
                return if (value in 1..8) value else 1
            }
            p += 12
        }
        1
    }.getOrDefault(1)

    /**
     * 把 IFD0 的 Orientation 改成 [value]（默认 1）：像素已经在导入时旋正，侧车必须
     * 声明「不再旋转」，否则引擎会把照片转第二次。找不到该标签时原样返回。
     */
    fun withOrientation(tiff: ByteArray, value: Int = 1): ByteArray {
        val le = endian(tiff) ?: return tiff
        val copy = tiff.copyOf()
        val ifd0 = u32(copy, 4, le).toLong()
        if (ifd0 < 8 || ifd0 + 2 > copy.size) return tiff
        val at = ifd0.toInt()
        val count = u16(copy, at, le)
        var p = at + 2
        repeat(count) {
            if (p + 12 > copy.size) return tiff
            if (u16(copy, p, le) == 0x0112) {
                val v = value.coerceIn(1, 8)
                if (le) {
                    copy[p + 8] = (v and 0xFF).toByte()
                    copy[p + 9] = ((v shr 8) and 0xFF).toByte()
                } else {
                    copy[p + 8] = ((v shr 8) and 0xFF).toByte()
                    copy[p + 9] = (v and 0xFF).toByte()
                }
                return copy
            }
            p += 12
        }
        return tiff
    }

    /**
     * 只含 Exif APP1 的极小 JPEG：`SOI + APP1("Exif\0\0" + TIFF) + EOI`。
     * 引擎的 APP1 解析器只认这个形状，所以侧车不需要真实图像数据。
     */
    fun exifSidecarJpeg(tiff: ByteArray, orientation: Int = 1): ByteArray {
        val block = withOrientation(tiff, orientation)
        val payload = ByteArray(6 + block.size)
        "Exif\u0000\u0000".encodeToByteArray().copyInto(payload, 0)
        block.copyInto(payload, 6)
        val len = payload.size + 2
        require(len <= 0xFFFF) { "EXIF block too large for one APP1 segment: $len" }
        val out = ByteArray(2 + 2 + 2 + payload.size + 2)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte(); out[3] = 0xE1.toByte()
        out[4] = ((len shr 8) and 0xFF).toByte(); out[5] = (len and 0xFF).toByte()
        payload.copyInto(out, 6)
        out[out.size - 2] = 0xFF.toByte(); out[out.size - 1] = 0xD9.toByte()
        return out
    }

    // ---- ISO-BMFF ------------------------------------------------------------

    private data class Box(val type: String, val headerStart: Int, val dataStart: Int, val end: Int)

    private fun readBoxHeader(b: ByteArray, at: Int, limit: Int): Box? {
        if (at + 8 > limit) return null
        val size = u32(b, at, false).toLong()
        val type = ascii(b, at + 4, 4)
        var dataStart = at + 8
        val end: Long = when {
            size == 1L -> {
                if (at + 16 > limit) return null
                dataStart = at + 16
                u64(b, at + 8)
            }
            size == 0L -> limit.toLong()
            else -> at + size
        }
        if (end < dataStart || end > limit) return null
        return Box(type, at, dataStart, end.toInt())
    }

    private fun findBox(b: ByteArray, start: Int, end: Int, type: String): Box? {
        var at = start
        while (at + 8 <= end) {
            val box = readBoxHeader(b, at, end) ?: return null
            if (box.type == type) return box
            at = box.end
        }
        return null
    }

    private fun hasBox(b: ByteArray, start: Int, end: Int, type: String): Boolean {
        if (findBox(b, start, end, type) != null) return true
        // meta 的子盒子嵌在 FullBox 头之后，findBox 已从 dataStart 起扫；tmap 可能在
        // 子层级（如 iprp），这里再对 meta 递归一层。
        var at = start
        while (at + 8 <= end) {
            val box = readBoxHeader(b, at, end) ?: return false
            if (box.type == type) return true
            if (box.type in setOf(BOX_IPRP, BOX_IPCO, "dinf")) {
                if (hasBox(b, box.dataStart, box.end, type)) return true
            }
            at = box.end
        }
        return false
    }

    private data class ItemLoc(val itemId: Int, val method: Int, val extents: List<LongArray>)

    /**
     * 取某个 item_type 的项内容。只支持 construction_method 0（文件偏移）与 1
     * （`idat`）；方法 2（其它项引用）在照片里不会出现在 Exif 上，直接返回 null。
     */
    private fun itemPayload(bytes: ByteArray, itemType: String): ByteArray? {
        val meta = findBox(bytes, 0, bytes.size, BOX_META) ?: return null
        val infos = itemInfos(bytes, meta.dataStart + 4, meta.end) ?: return null
        val targetId = infos.entries.firstOrNull { it.value == itemType }?.key ?: return null
        val loc = itemLocations(bytes, meta.dataStart + 4, meta.end).firstOrNull { it.itemId == targetId } ?: return null
        if (loc.method == 2) return null
        val idat = findBox(bytes, meta.dataStart + 4, meta.end, BOX_IDAT)
        val parts = ArrayList<ByteArray>(loc.extents.size)
        for (extent in loc.extents) {
            val base = extent[0]
            val off = extent[1]
            val len = extent[2]
            val start = when (loc.method) {
                1 -> (idat?.dataStart?.toLong() ?: return null) + base + off
                else -> base + off
            }
            if (start < 0 || len <= 0 || start + len > bytes.size) continue
            parts += bytes.copyOfRange(start.toInt(), (start + len).toInt())
        }
        if (parts.isEmpty()) return null
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun itemInfos(b: ByteArray, start: Int, end: Int): Map<Int, String>? {
        val iinf = findBox(b, start, end, BOX_IINF) ?: return null
        var at = iinf.dataStart
        if (at + 4 > iinf.end) return null
        val version = b[at].toInt() and 0xFF
        at += 4
        val count: Long = if (version == 0) {
            if (at + 2 > iinf.end) return null
            u16(b, at, false).toLong().also { at += 2 }
        } else {
            if (at + 4 > iinf.end) return null
            u32(b, at, false).toLong().also { at += 4 }
        }
        val out = LinkedHashMap<Int, String>()
        var read = 0L
        while (read < count && at + 8 <= iinf.end) {
            val box = readBoxHeader(b, at, iinf.end) ?: break
            if (box.type == BOX_INFE) {
                val infeVersion = b[box.dataStart].toInt() and 0xFF
                var p = box.dataStart + 4
                val id: Int
                when {
                    infeVersion >= 3 -> {
                        if (p + 6 > box.end) break
                        id = u32(b, p, false).toInt(); p += 4 + 2
                    }
                    infeVersion == 2 -> {
                        if (p + 4 > box.end) break
                        id = u16(b, p, false); p += 2 + 2
                    }
                    else -> {
                        // infe v0/v1 没有 item_type；老 HEIF 照片不会是 Exif 项。
                        at = box.end; read++; continue
                    }
                }
                if (p + 4 <= box.end) {
                    val type = ascii(b, p, 4)
                    out[id] = type
                }
            }
            at = box.end
            read++
        }
        return out
    }

    private fun itemLocations(b: ByteArray, start: Int, end: Int): List<ItemLoc> {
        val iloc = findBox(b, start, end, BOX_ILOC) ?: return emptyList()
        var p = iloc.dataStart
        if (p + 4 > iloc.end) return emptyList()
        val version = b[p].toInt() and 0xFF
        p += 4
        if (p + 2 > iloc.end) return emptyList()
        val offsetSize = (b[p].toInt() and 0xFF) shr 4
        val lengthSize = b[p].toInt() and 0x0F
        val baseOffsetSize = (b[p + 1].toInt() and 0xFF) shr 4
        val indexSize = b[p + 1].toInt() and 0x0F
        p += 2
        val count: Long = if (version < 2) {
            if (p + 2 > iloc.end) return emptyList()
            u16(b, p, false).toLong().also { p += 2 }
        } else {
            if (p + 4 > iloc.end) return emptyList()
            u32(b, p, false).toLong().also { p += 4 }
        }
        val out = ArrayList<ItemLoc>()
        var read = 0L
        while (read < count && p + 4 <= iloc.end) {
            val id = if (version < 2) {
                u16(b, p, false).also { p += 2 }
            } else {
                u32(b, p, false).toInt().also { p += 4 }
            }
            var method = 0
            if (version == 1 || version == 2) {
                if (p + 2 > iloc.end) break
                method = u16(b, p, false) and 0x0F
                p += 2
            }
            if (p + 2 > iloc.end) break
            p += 2 // data_reference_index
            val base = if (baseOffsetSize > 0) readSized(b, p, baseOffsetSize).also { p += baseOffsetSize } else 0L
            if (p + 2 > iloc.end) break
            val extents = u16(b, p, false).also { p += 2 }
            val list = ArrayList<LongArray>(extents)
            repeat(extents) {
                if ((version == 1 || version == 2) && method == 1 && indexSize > 0) {
                    if (p + indexSize > iloc.end) return@repeat
                    p += indexSize
                }
                val off = if (offsetSize > 0) readSized(b, p, offsetSize).also { p += offsetSize } else 0L
                val len = if (lengthSize > 0) readSized(b, p, lengthSize).also { p += lengthSize } else 0L
                list += longArrayOf(base, off, len)
            }
            out += ItemLoc(id, method, list)
            read++
        }
        return out
    }

    /** Exif 项：4 字节大端偏移到 TIFF 头。偏移基准各写入器不一致（从项首 / 从偏移字段之后），逐个候选试。 */
    private fun tiffFromExifItem(payload: ByteArray): ByteArray? {
        if (payload.size < 8) return null
        val offset = u32(payload, 0, false).toInt()
        val candidates = intArrayOf(offset, 4 + offset, 6 + offset, 10)
        for (raw in candidates) {
            if (raw < 0 || raw + 8 > payload.size) continue
            var at = raw
            // 偏移可能落在 "Exif\0\0" 上：TIFF 头在签名之后 6 字节。
            if (endian(payload, at) == null && at + 6 <= payload.size &&
                ascii(payload, at, 4) == "Exif"
            ) {
                at += 6
            }
            if (at + 8 <= payload.size && endian(payload, at) != null) {
                return payload.copyOfRange(at, payload.size)
            }
        }
        return null
    }

    private fun hvcBitDepth(b: ByteArray, start: Int, end: Int): Int {
        // hvcC: version(1) profile(1) compat(4) constraints(6) level(1)
        //       min_spatial(2) parallelism(1) chroma(1) luma-8(1) chroma-8(1)
        val luma = start + 17
        val chroma = start + 18
        if (chroma >= end) return 0
        val l = (b[luma].toInt() and 0x07) + 8
        val c = (b[chroma].toInt() and 0x07) + 8
        return maxOf(l, c)
    }

    private fun nclxTransfer(b: ByteArray, start: Int, end: Int): Int? {
        if (start + 10 > end) return null
        if (ascii(b, start, 4) != "nclx") return null
        return u16(b, start + 6, false)
    }

    private fun auxUrn(b: ByteArray, start: Int, end: Int): String? {
        // auxC 是 FullBox：4 字节 version/flags 之后是 null 结尾的 URN。
        if (start + 4 >= end) return null
        val q = start + 4
        val stop = (q until end).firstOrNull { b[it].toInt() == 0 } ?: end
        return b.decodeToString(q, stop)
    }

    private fun findGainMapUrn(b: ByteArray, start: Int, end: Int): String? {
        val needle1 = "urn:com:apple:photo:2018:aux:hdrgainmap"
        val needle2 = "urn:iso:std:iso:ts:21496:-1"
        for (needle in listOf(needle1, needle2)) {
            val idx = indexOf(b, needle.encodeToByteArray(), start, end)
            if (idx >= 0) return needle
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int, end: Int): Int {
        if (needle.isEmpty()) return -1
        var i = start
        val last = minOf(end, haystack.size) - needle.size
        while (i <= last) {
            var k = 0
            while (k < needle.size && haystack[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    // ---- bytes ---------------------------------------------------------------

    private fun endian(b: ByteArray, at: Int = 0): Boolean? {
        if (at + 2 > b.size) return null
        return when {
            b[at] == 'I'.code.toByte() && b[at + 1] == 'I'.code.toByte() -> true
            b[at] == 'M'.code.toByte() && b[at + 1] == 'M'.code.toByte() -> false
            else -> null
        }
    }

    private fun u16(b: ByteArray, at: Int, le: Boolean): Int {
        if (at + 2 > b.size) return 0
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun u32(b: ByteArray, at: Int, le: Boolean): Long {
        if (at + 4 > b.size) return 0
        var v = 0L
        if (le) {
            for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        } else {
            for (i in 0 until 4) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        }
        return v
    }

    private fun u64(b: ByteArray, at: Int): Long {
        if (at + 8 > b.size) return 0
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun readSized(b: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) {
            if (at + i >= b.size) break
            v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        }
        return v
    }

    private fun ascii(b: ByteArray, at: Int, len: Int): String {
        if (at < 0 || at + len > b.size) return ""
        return b.decodeToString(at, at + len)
    }

    private fun boxEnd(b: ByteArray, at: Int): Int? {
        if (at + 8 > b.size) return null
        val size = u32(b, at, false)
        return if (size in 8..b.size.toLong()) (at + size).toInt() else b.size
    }
}
