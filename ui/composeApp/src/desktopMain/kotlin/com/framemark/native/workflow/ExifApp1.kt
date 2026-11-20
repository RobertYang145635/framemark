package com.framemark.native.workflow

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode

/**
 * 转码导入的**拍摄信息搬运**：把原图的 EXIF 写成 APP1 塞进转码出来的 JPEG。
 *
 * 为什么必须搬：桌面导入 TIFF / WebP 时，引擎（Skia/D3D 那条链）不认这两种容器，
 * 所以导入会先把像素转成 JPEG 副本，工程里存的是副本路径。副本是重新编码出来的，
 * 相机厂商、机型、光圈、快门、ISO、焦距全没了 —— 于是「字标跟随照片品牌」拿不到品牌、
 * 机型行和参数行全空（审计 2026-11-20，RGB TIFF 复现）。
 *
 * 做法：不碰像素，只把标签重新组装成一段标准 EXIF（APP1 + TIFF），插在 JPEG 的 SOI
 * 后面。下游（Rust 引擎、Kotlin 的品牌探测）读到的就是原图那份拍摄信息，谁都不用改。
 *
 *  - TIFF：用 JDK 的 TIFF 元数据树读标签（`javax_imageio_tiff_image_1.0`）；
 *  - WebP：RIFF 里的 `EXIF` 块本身就是一段 TIFF，原样搬；
 *  - 失败一律返回 null（导入照常成功，只是没有拍摄信息）——缺元数据不能挡住用户。
 */
internal object ExifApp1 {

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7

    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_FNUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_PIXEL_X = 0xA002
    private const val TAG_PIXEL_Y = 0xA003
    private const val TAG_FOCAL_35MM = 0xA405
    private const val TAG_LENS_MODEL = 0xA434

    /** APP1 载荷上限：段长度是 16 位，且要留 `Exif\0\0` 与长度字节的位置。 */
    private const val MAX_APP1 = 65533

    /**
     * 原图（TIFF / WebP）的拍摄信息 → 可直接插进 JPEG 的 APP1 段（含 `FF E1` 与长度）。
     * 读不到 / 太大 → null。
     */
    fun fromSource(file: File): ByteArray? = runCatching {
        val block = when (file.extension.lowercase()) {
            "tif", "tiff" -> tiffFromImageIo(file)
            "webp" -> tiffFromWebp(file)
            else -> null
        } ?: return null
        app1Segment(block)
    }.getOrNull()

    /** 把 APP1 段插到 SOI 之后；[jpeg] 不是 JPEG 或已有 APP1 时原样返回。 */
    fun inject(jpeg: ByteArray, app1: ByteArray): ByteArray {
        if (app1.isEmpty() || jpeg.size < 4) return jpeg
        if (!(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())) return jpeg
        // 已经带 EXIF 就不动（幂等：重复导入同一张不会叠出两段）。
        if (hasApp1(jpeg)) return jpeg
        val out = ByteArray(jpeg.size + app1.size)
        jpeg.copyInto(out, 0, 0, 2)
        app1.copyInto(out, 2)
        jpeg.copyInto(out, 2 + app1.size, 2, jpeg.size)
        return out
    }

    private fun hasApp1(jpeg: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (u8(jpeg, i) != 0xFF) return false
            val marker = u8(jpeg, i + 1)
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) return false
            val length = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
            if (length < 2 || i + 2 + length > jpeg.size) return false
            if (marker == 0xE1) return true
            i += 2 + length
        }
        return false
    }

    // -- APP1 段 ---------------------------------------------------------------

    private fun app1Segment(tiff: ByteArray): ByteArray? {
        // 段长度 = 2（长度本身）+ 6（"Exif\0\0"）+ TIFF 长度
        val length = 8 + tiff.size
        if (length > MAX_APP1) return null
        val out = ByteArray(2 + length)
        out[0] = 0xFF.toByte()
        out[1] = 0xE1.toByte()
        out[2] = ((length shr 8) and 0xFF).toByte()
        out[3] = (length and 0xFF).toByte()
        out[4] = 'E'.code.toByte()
        out[5] = 'x'.code.toByte()
        out[6] = 'i'.code.toByte()
        out[7] = 'f'.code.toByte()
        out[8] = 0
        out[9] = 0
        tiff.copyInto(out, 10)
        return out
    }

    // -- WebP：EXIF 块本身就是 TIFF -------------------------------------------

    private fun tiffFromWebp(file: File): ByteArray? {
        val bytes = file.readBytes()
        if (bytes.size < 12) return null
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WEBP") return null
        var i = 12
        while (i + 8 <= bytes.size) {
            val fourcc = ascii(bytes, i, 4)
            val size = u8(bytes, i + 4) or (u8(bytes, i + 5) shl 8) or
                (u8(bytes, i + 6) shl 16) or (u8(bytes, i + 7) shl 24)
            if (size < 0 || i + 8 + size > bytes.size) return null
            if (fourcc == "EXIF") {
                val start = i + 8
                val prefixed = size >= 6 && ascii(bytes, start, 4) == "Exif"
                val tiff = if (prefixed) bytes.copyOfRange(start + 6, start + size)
                else bytes.copyOfRange(start, start + size)
                return tiff.takeIf { looksLikeTiff(it) }
            }
            i += 8 + size + (size and 1)
        }
        return null
    }

    // -- TIFF：JDK 元数据树 → 重新组装成紧凑 EXIF -----------------------------

    private fun tiffFromImageIo(file: File): ByteArray? = runCatching {
        val stream = ImageIO.createImageInputStream(file) ?: return null
        stream.use {
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = stream
                reader.read(0)
                val metadata = reader.getImageMetadata(0) ?: return null
                val root = metadata.getAsTree("javax_imageio_tiff_image_1.0") as? IIOMetadataNode
                    ?: return null
                val tags = readTiffTags(root)
                buildTiff(tags)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** 树 → tag → (类型, 原始值字节)。只收我们搬得动、且水印/字标用得上的那些。 */
    private fun readTiffTags(root: IIOMetadataNode): Map<Int, TagValue> {
        val wanted = setOf(
            TAG_MAKE, TAG_MODEL, TAG_DATETIME, TAG_EXPOSURE_TIME, TAG_FNUMBER, TAG_ISO,
            TAG_DATETIME_ORIGINAL, TAG_FOCAL_LENGTH, TAG_FOCAL_35MM, TAG_LENS_MODEL,
        )
        val out = LinkedHashMap<Int, TagValue>()
        fun walk(node: IIOMetadataNode) {
            var child = node.firstChild
            while (child != null) {
                if (child is IIOMetadataNode) {
                    if (child.nodeName == "TIFFField") {
                        val tag = child.getAttribute("number").toIntOrNull()
                        if (tag != null && tag in wanted) {
                            val value = tagValue(child)
                            if (value != null) out[tag] = value
                        }
                    } else if (child.nodeName == "TIFFIFD") {
                        walk(child)
                    }
                }
                child = child.nextSibling
            }
        }
        walk(root)
        return out
    }

    private fun tagValue(field: IIOMetadataNode): TagValue? {
        val child = field.firstChild as? IIOMetadataNode ?: return null
        return when (child.nodeName) {
            "TIFFAsciis" -> {
                val text = StringBuilder()
                var node = child.firstChild
                while (node != null) {
                    if (node is IIOMetadataNode && node.nodeName == "TIFFAscii") {
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(node.getAttribute("value"))
                    }
                    node = node.nextSibling
                }
                val raw = text.toString().trim()
                if (raw.isEmpty()) null else TagValue(TYPE_ASCII, asciiBytes(raw))
            }
            "TIFFShorts" -> {
                val values = mutableListOf<Int>()
                var node = child.firstChild
                while (node != null) {
                    if (node is IIOMetadataNode && node.nodeName == "TIFFShort") {
                        node.getAttribute("value").toIntOrNull()?.let { values += it }
                    }
                    node = node.nextSibling
                }
                if (values.isEmpty()) null else TagValue(TYPE_SHORT, shortsLE(values))
            }
            "TIFFLongs" -> {
                val v = (child.firstChild as? IIOMetadataNode)?.getAttribute("value")?.toLongOrNull()
                if (v == null) null else TagValue(TYPE_LONG, longLE(v))
            }
            "TIFFRationals" -> {
                val node = child.firstChild as? IIOMetadataNode ?: return null
                val value = node.getAttribute("value")
                val parts = value.split('/')
                val num = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
                val den = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 1L
                if (den == 0L) null else TagValue(TYPE_RATIONAL, rationalLE(num, den))
            }
            else -> null
        }
    }

    private data class TagValue(val type: Int, val bytes: ByteArray)

    /**
     * 组装一段小端 TIFF：IFD0（Make / Model / DateTime / Orientation=1 + Exif 指针）
     * 与 Exif 子 IFD（光圈 / 快门 / ISO / 焦距 / 等效焦距 / 镜头 / 原始时间）。
     *
     * Orientation 固定写 1：像素在转码时已经把方向烤进去了（`orient()`），再写原值
     * 会让下游转第二次 —— 与 Android 桥那次「双重旋转」是同一个坑。
     */
    private fun buildTiff(tags: Map<Int, TagValue>): ByteArray? {
        data class Entry(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

        val ifd0 = mutableListOf<Entry>()
        val exif = mutableListOf<Entry>()

        fun put(target: MutableList<Entry>, tag: Int, value: TagValue?) {
            if (value == null || value.bytes.isEmpty()) return
            val count = when (value.type) {
                TYPE_ASCII -> value.bytes.size
                TYPE_SHORT -> value.bytes.size / 2
                TYPE_LONG, TYPE_UNDEFINED, TYPE_BYTE -> value.bytes.size / 4
                TYPE_RATIONAL -> value.bytes.size / 8
                else -> return
            }
            target += Entry(tag, value.type, count, value.bytes)
        }

        put(ifd0, TAG_MAKE, tags[TAG_MAKE])
        put(ifd0, TAG_MODEL, tags[TAG_MODEL])
        put(ifd0, TAG_DATETIME, tags[TAG_DATETIME])
        ifd0 += Entry(TAG_ORIENTATION, TYPE_SHORT, 1, shortsLE(listOf(1)))
        put(exif, TAG_EXPOSURE_TIME, tags[TAG_EXPOSURE_TIME])
        put(exif, TAG_FNUMBER, tags[TAG_FNUMBER])
        put(exif, TAG_ISO, tags[TAG_ISO])
        put(exif, TAG_DATETIME_ORIGINAL, tags[TAG_DATETIME_ORIGINAL])
        put(exif, TAG_FOCAL_LENGTH, tags[TAG_FOCAL_LENGTH])
        put(exif, TAG_FOCAL_35MM, tags[TAG_FOCAL_35MM])
        put(exif, TAG_LENS_MODEL, tags[TAG_LENS_MODEL])
        if (ifd0.isEmpty() && exif.isEmpty()) return null

        // 布局：头 8 | IFD0 | ExifIFD | 数据区（>4 字节的值）
        val ifd0Entries = ifd0.sortedBy { it.tag }
        val exifEntries = exif.sortedBy { it.tag }
        val withPointer = ifd0Entries.toMutableList().apply {
            if (exifEntries.isNotEmpty()) add(Entry(TAG_EXIF_IFD, TYPE_LONG, 1, longLE(0)))
        }.sortedBy { it.tag }

        val ifd0Size = 2 + withPointer.size * 12 + 4
        val exifOffset = 8 + ifd0Size
        val exifSize = if (exifEntries.isEmpty()) 0 else 2 + exifEntries.size * 12 + 4
        var dataOffset = 8 + ifd0Size + exifSize
        val data = java.io.ByteArrayOutputStream()

        fun serialize(entries: List<Entry>, base: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(shortLE(entries.size))
            for (entry in entries) {
                out.write(shortLE(entry.tag))
                out.write(shortLE(entry.type))
                out.write(longLE(entry.count.toLong()))
                if (entry.payload.size <= 4) {
                    out.write(entry.payload)
                    repeat(4 - entry.payload.size) { out.write(0) }
                } else {
                    val offset = dataOffset + data.size()
                    out.write(longLE(offset.toLong()))
                    data.write(entry.payload)
                    if (data.size() and 1 == 1) data.write(0) // RATIONAL/SHORT 对齐
                }
            }
            out.write(longLE(0))
            return out.toByteArray()
        }

        // Exif 指针要先知道子 IFD 的绝对偏移，所以先算 ifd0 的字节数再回填。
        val ifd0Bytes = serialize(
            withPointer.map { entry ->
                if (entry.tag == TAG_EXIF_IFD) entry.copy(payload = longLE(exifOffset.toLong()))
                else entry
            },
            base = 0,
        )
        val exifBytes = if (exifEntries.isEmpty()) ByteArray(0) else serialize(exifEntries, base = exifOffset)
        val out = ByteArray(8 + ifd0Bytes.size + exifBytes.size + data.size())
        val header = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0)
        header.copyInto(out, 0)
        ifd0Bytes.copyInto(out, 8)
        exifBytes.copyInto(out, 8 + ifd0Bytes.size)
        data.toByteArray().copyInto(out, 8 + ifd0Bytes.size + exifBytes.size)
        return out.takeIf { it.size <= MAX_APP1 - 8 }
    }

    // -- 小工具 ---------------------------------------------------------------

    private fun looksLikeTiff(bytes: ByteArray): Boolean = bytes.size >= 8 &&
        ((u8(bytes, 0) == 'I'.code && u8(bytes, 1) == 'I'.code && u8(bytes, 2) == 42) ||
            (u8(bytes, 0) == 'M'.code && u8(bytes, 1) == 'M'.code && u8(bytes, 3) == 42))

    private fun asciiBytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1) + 0

    private fun shortsLE(values: List<Int>): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun shortLE(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun longLE(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun rationalLE(num: Long, den: Long): ByteArray = longLE(num) + longLE(den)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return bytes.copyOfRange(offset, offset + length).decodeToString()
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /** 把非 8 位 / 带透明通道的像素转成 JPEG 能写的 8 位 RGB。 */
    fun toRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB || image.type == BufferedImage.TYPE_3BYTE_BGR) {
            return image
        }
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = out.createGraphics()
        try {
            // 白底：带 alpha 的 TIFF 在 JPEG 里没有 alpha 通道，压在白纸上比压成黑块合理。
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, out.width, out.height)
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return out
    }
}
