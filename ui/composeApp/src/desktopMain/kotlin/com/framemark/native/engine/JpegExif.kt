package com.framemark.native.engine

import com.framemark.native.product.ExifMake
import java.io.File

/**
 * Extracts and sanitizes the TIFF payload from a JPEG APP1 `Exif\u0000\u0000` segment.
 * The returned block has no GPS IFD, has orientation normalized to 1, and can
 * carry the dimensions of the newly encoded image. Invalid/truncated TIFF data
 * is omitted instead of being copied through unchanged.
 *
 * Also answers the one read the renderer needs from a photo's own header:
 * [make] (the EXIF `Make`, i.e. the camera brand the watermark logo follows).
 * Same TIFF walk as the sanitizer, so both platforms agree on which block counts
 * (`product.ExifMake` is the shared parser; this is only the desktop file access).
 */
object JpegExif {
    /** The header the EXIF block lives in; a 50 MP photo must not be read whole. */
    private const val HEAD_BYTES = 512 * 1024

    /**
     * IFD0 `Make`（0x010F）of [photo], or null when the file has no readable EXIF.
     *
     * 渲染器用它在**每一帧**决定字标（设计里没有显式选择时）。读不懂就是「这张照片
     * 没有品牌」，调用方退回卡片自带字标 —— 不是错误，也不画空白。
     */
    fun make(photo: File): String? = try {
        if (!photo.isFile) null else ExifMake.read(head(photo))
    } catch (_: Exception) {
        null
    }

    private fun head(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    }
    /**
     * Returns the first EXIF TIFF block in [jpeg], with GPS removed. [width] and
     * [height] are optional output-image dimensions; supplied dimensions replace
     * the TIFF image dimensions and any EXIF pixel dimensions.
     */
    fun tiffBlock(jpeg: File, width: Int? = null, height: Int? = null): ByteArray? {
        if (!jpeg.isFile || width != null && width <= 0 || height != null && height <= 0) return null
        return try {
            val bytes = jpeg.readBytes()
            val tiff = findExifTiff(bytes) ?: return null
            TiffSanitizer(tiff, width, height).rewrite()
        } catch (_: Exception) {
            null
        }
    }

    private fun findExifTiff(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++ // marker fill bytes
            if (i >= bytes.size) return null
            val marker = bytes[i++].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return null // EOI / image data
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 2 > bytes.size) return null
            val length = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (length < 2 || length > bytes.size - i) return null
            val payloadStart = i + 2
            val payloadEnd = i + length
            if (marker == 0xE1 && payloadEnd - payloadStart >= EXIF_PREFIX.size &&
                EXIF_PREFIX.indices.all { bytes[payloadStart + it] == EXIF_PREFIX[it] }
            ) {
                return bytes.copyOfRange(payloadStart + EXIF_PREFIX.size, payloadEnd)
            }
            i = payloadEnd
        }
        return null
    }

    private class InvalidTiff : RuntimeException()

    private class Entry(
        val tag: Int,
        var type: Int,
        var count: Long,
        var bytes: ByteArray,
        val sourceStart: Long = -1,
        val sourceLength: Long = 0,
        var targets: List<Directory?>? = null,
    )

    private class Directory(
        val originalOffset: Long,
        val sensitive: Boolean,
        val hasNextIfd: Boolean,
        val entries: MutableList<Entry> = mutableListOf(),
        var next: Directory? = null,
        var thumbnail: ByteArray? = null,
        var thumbnailSourceStart: Long = -1,
        var thumbnailSourceLength: Long = 0,
        var outputOffset: Long = 0,
    )

    /** Strict, bounded TIFF reader plus a small TIFF directory/data rewriter. */
    private class TiffSanitizer(
        private val source: ByteArray,
        private val width: Int?,
        private val height: Int?,
    ) {
        private val littleEndian: Boolean
        private val directories = mutableListOf<Directory>()
        private val visited = mutableSetOf<Long>()
        private val sensitiveRanges = mutableListOf<LongRange>()
        private val directoryRanges = mutableListOf<Pair<Long, Long>>()
        private val root: Directory

        init {
            if (source.size < 8) throw InvalidTiff()
            littleEndian = when {
                source[0] == 'I'.code.toByte() && source[1] == 'I'.code.toByte() -> true
                source[0] == 'M'.code.toByte() && source[1] == 'M'.code.toByte() -> false
                else -> throw InvalidTiff()
            }
            if (u16(source, 2) != TIFF_MAGIC) throw InvalidTiff()
            val rootOffset = u32(source, 4)
            root = parseDirectory(rootOffset, sensitive = false, hasNextIfd = true)
            validateSensitiveAliasing()
            readThumbnails()
            sanitizeDirectories()
        }

        fun rewrite(): ByteArray {
            val outputDirectories = collectOutputDirectories()
            if (outputDirectories.isEmpty()) throw InvalidTiff()

            var cursor = TIFF_HEADER_SIZE.toLong()
            for (directory in outputDirectories) {
                if (directory.entries.size > 0xFFFF) throw InvalidTiff()
                directory.outputOffset = cursor
                cursor += 2L + directory.entries.size * TIFF_ENTRY_SIZE + (if (directory.hasNextIfd) 4L else 0L)
            }

            val entryDataOffsets = mutableMapOf<Entry, Long>()
            for (directory in outputDirectories) {
                for (entry in directory.entries) {
                    if (entryValueBytes(entry).size > INLINE_VALUE_SIZE) {
                        cursor = align2(cursor)
                        entryDataOffsets[entry] = cursor
                        cursor += entryValueBytes(entry).size
                    }
                }
            }

            val thumbnailOffsets = mutableMapOf<Directory, Long>()
            for (directory in outputDirectories) {
                val thumbnail = directory.thumbnail ?: continue
                cursor = align2(cursor)
                thumbnailOffsets[directory] = cursor
                cursor += thumbnail.size
            }

            for ((directory, offset) in thumbnailOffsets) {
                directory.entries.firstOrNull { it.tag == TAG_THUMBNAIL_OFFSET }?.bytes = encodeU32(offset)
                directory.entries.firstOrNull { it.tag == TAG_THUMBNAIL_LENGTH }?.bytes =
                    encodeU32(directory.thumbnail!!.size.toLong())
            }

            if (cursor > 0xFFFF_FFFFL || cursor > Int.MAX_VALUE) throw InvalidTiff()
            val out = ByteArray(cursor.toInt())
            out[0] = if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
            out[1] = out[0]
            putU16(out, 2, TIFF_MAGIC)
            putU32(out, 4, root.outputOffset)

            for (directory in outputDirectories) {
                val start = directory.outputOffset.toInt()
                putU16(out, start, directory.entries.size)
                var p = start + 2
                for (entry in directory.entries) {
                    putU16(out, p, entry.tag)
                    putU16(out, p + 2, entry.type)
                    putU32(out, p + 4, entry.count)
                    val value = entryValueBytes(entry)
                    if (value.size <= INLINE_VALUE_SIZE) {
                        value.copyInto(out, p + 8)
                    } else {
                        putU32(out, p + 8, entryDataOffsets.getValue(entry))
                        value.copyInto(out, entryDataOffsets.getValue(entry).toInt())
                    }
                    p += TIFF_ENTRY_SIZE
                }
                if (directory.hasNextIfd) {
                    val next = directory.next?.takeUnless { it.sensitive }?.outputOffset ?: 0L
                    putU32(out, p, next)
                }
            }

            for (directory in outputDirectories) {
                val thumbnail = directory.thumbnail ?: continue
                thumbnail.copyInto(out, thumbnailOffsets.getValue(directory).toInt())
            }
            return out
        }

        private fun parseDirectory(
            offset: Long,
            sensitive: Boolean,
            hasNextIfd: Boolean,
            depth: Int = 0,
        ): Directory {
            if (depth > MAX_DIRECTORY_DEPTH || offset < TIFF_HEADER_SIZE || offset > Int.MAX_VALUE || !visited.add(offset)) {
                throw InvalidTiff()
            }
            val start = offset.toInt()
            requireRange(offset, 2)
            val entryCount = u16(source, start)
            if (entryCount > MAX_DIRECTORY_ENTRIES) throw InvalidTiff()
            val entriesStart = offset + 2
            val entriesLength = entryCount.toLong() * TIFF_ENTRY_SIZE
            val nextPointerLength = if (hasNextIfd) 4L else 0L
            requireRange(entriesStart, entriesLength + nextPointerLength)

            val directoryLength = entriesLength + 2 + nextPointerLength
            directoryRanges += (offset to directoryLength)
            if (sensitive && directoryLength > 0) sensitiveRanges += offset..(offset + directoryLength - 1)

            val directory = Directory(offset, sensitive, hasNextIfd)
            directories += directory
            for (index in 0 until entryCount) {
                val entryOffset = (entriesStart + index * TIFF_ENTRY_SIZE).toInt()
                val tag = u16(source, entryOffset)
                val type = u16(source, entryOffset + 2)
                val count = u32(source, entryOffset + 4)
                val unitSize = typeSize(type) ?: throw InvalidTiff()
                if (count > source.size.toLong() / unitSize) throw InvalidTiff()
                val byteCount = count * unitSize
                if (byteCount > Int.MAX_VALUE) throw InvalidTiff()
                val valueStart = if (byteCount <= INLINE_VALUE_SIZE) {
                    entryOffset + 8L
                } else {
                    val dataOffset = u32(source, entryOffset + 8)
                    if (dataOffset < TIFF_HEADER_SIZE) throw InvalidTiff()
                    dataOffset
                }
                requireRange(valueStart, byteCount)
                val valueBytes = source.copyOfRange(valueStart.toInt(), valueStart.toInt() + byteCount.toInt())
                val entry = Entry(tag, type, count, valueBytes, valueStart, byteCount)
                directory.entries += entry
                if (sensitive && byteCount > 0 && byteCount > INLINE_VALUE_SIZE) {
                    sensitiveRanges += valueStart..(valueStart + byteCount - 1)
                }

                if (tag in POINTER_TAGS) {
                    if (type != TYPE_LONG && type != TYPE_IFD) throw InvalidTiff()
                    if (tag != TAG_SUB_IFDS && count != 1L) throw InvalidTiff()
                    if (count == 0L || count > MAX_SUB_IFDS.toLong()) throw InvalidTiff()
                    val isGps = tag == TAG_GPS_IFD
                    entry.targets = List(count.toInt()) { valueIndex ->
                        val targetOffset = u32(valueBytes, valueIndex * 4)
                        if (targetOffset == 0L) null else parseDirectory(
                            targetOffset,
                            sensitive = sensitive || isGps,
                            hasNextIfd = tag == TAG_SUB_IFDS,
                            depth = depth + 1,
                        )
                    }
                } else if (type == TYPE_IFD) {
                    // Private IFD pointers cannot be safely rebased without knowing their schema.
                    // They are dropped during sanitization rather than copied with stale offsets.
                }
            }

            if (hasNextIfd) {
                val nextOffset = u32(source, (entriesStart + entriesLength).toInt())
                if (nextOffset != 0L) {
                    directory.next = parseDirectory(nextOffset, sensitive, hasNextIfd = true, depth = depth + 1)
                }
            }
            return directory
        }

        private fun readThumbnails() {
            for (directory in directories) {
                val offsets = directory.entries.filter { it.tag == TAG_THUMBNAIL_OFFSET }
                val lengths = directory.entries.filter { it.tag == TAG_THUMBNAIL_LENGTH }
                if (offsets.isEmpty() && lengths.isEmpty()) continue
                if (offsets.size != 1 || lengths.size != 1) throw InvalidTiff()
                val offsetEntry = offsets.single()
                val lengthEntry = lengths.single()
                if (offsetEntry.type != TYPE_LONG || lengthEntry.type != TYPE_LONG ||
                    offsetEntry.count != 1L || lengthEntry.count != 1L
                ) throw InvalidTiff()
                val dataOffset = u32(offsetEntry.bytes, 0)
                val dataLength = u32(lengthEntry.bytes, 0)
                if (dataOffset == 0L && dataLength == 0L) {
                    directory.entries.removeAll { it.tag == TAG_THUMBNAIL_OFFSET || it.tag == TAG_THUMBNAIL_LENGTH }
                    continue
                }
                if (dataOffset < TIFF_HEADER_SIZE || dataLength == 0L) throw InvalidTiff()
                requireRange(dataOffset, dataLength)
                val thumbnail = source.copyOfRange(dataOffset.toInt(), (dataOffset + dataLength).toInt())
                // A thumbnail JPEG can itself contain an EXIF APP1 with another GPS IFD.
                // Drop that thumbnail if it carries EXIF; the main metadata remains safe.
                val overlapsGps = overlapsSensitive(dataOffset, dataLength)
                directory.thumbnail = if (hasExifPrefix(thumbnail) || overlapsGps) null else thumbnail
                directory.thumbnailSourceStart = dataOffset
                directory.thumbnailSourceLength = dataLength
                if (directory.thumbnail == null) {
                    directory.entries.removeAll { it.tag == TAG_THUMBNAIL_OFFSET || it.tag == TAG_THUMBNAIL_LENGTH }
                }
            }
        }

        private fun sanitizeDirectories() {
            for (directory in directories) {
                if (directory.sensitive) continue
                directory.entries.removeAll { entry ->
                    entry.tag == TAG_GPS_IFD ||
                        entry.tag in PRIVATE_OR_UNREBASED_TAGS ||
                        (entry.type == TYPE_IFD && entry.tag !in POINTER_TAGS)
                }

                // Normalize every existing orientation tag and collapse duplicates.
                for (entry in directory.entries.filter { it.tag == TAG_ORIENTATION }.drop(1)) {
                    directory.entries.remove(entry)
                }
                directory.entries.firstOrNull { it.tag == TAG_ORIENTATION }?.let {
                    it.type = TYPE_SHORT
                    it.count = 1
                    it.bytes = encodeU16(1)
                    it.targets = null
                }
            }
            if (root.entries.none { it.tag == TAG_ORIENTATION }) {
                root.entries += Entry(TAG_ORIENTATION, TYPE_SHORT, 1, encodeU16(1))
            }

            val exifDirectories = root.entries
                .filter { it.tag == TAG_EXIF_IFD }
                .flatMap { it.targets.orEmpty() }
                .filterNotNull()
                .filterNot { it.sensitive }

            if (width != null) {
                setScalar(root, TAG_IMAGE_WIDTH, width.toLong())
                exifDirectories.forEach { setScalar(it, TAG_PIXEL_X_DIMENSION, width.toLong()) }
            }
            if (height != null) {
                setScalar(root, TAG_IMAGE_HEIGHT, height.toLong())
                exifDirectories.forEach { setScalar(it, TAG_PIXEL_Y_DIMENSION, height.toLong()) }
            }

            for (directory in directories.filterNot { it.sensitive }) {
                if (directory.thumbnail != null && overlapsSensitive(directory.thumbnailSourceStart, directory.thumbnailSourceLength)) {
                    directory.thumbnail = null
                    directory.entries.removeAll { it.tag == TAG_THUMBNAIL_OFFSET || it.tag == TAG_THUMBNAIL_LENGTH }
                }
                directory.entries.sortBy { it.tag }
            }
        }

        private fun validateSensitiveAliasing() {
            if (sensitiveRanges.isEmpty()) return
            for ((directoryStart, directoryLength) in directoryRanges) {
                val sensitiveDirectory = directories.any { it.sensitive && it.originalOffset == directoryStart }
                if (!sensitiveDirectory && overlapsSensitive(directoryStart, directoryLength)) throw InvalidTiff()
            }
            for (directory in directories.filterNot { it.sensitive }) {
                for (entry in directory.entries) {
                    if (entry.sourceStart >= 0 && overlapsSensitive(entry.sourceStart, entry.sourceLength)) throw InvalidTiff()
                }
            }
        }

        private fun overlapsSensitive(start: Long, length: Long): Boolean {
            if (length <= 0) return false
            val end = start + length - 1
            return sensitiveRanges.any { range -> start <= range.last && end >= range.first }
        }

        private fun setScalar(directory: Directory, tag: Int, value: Long) {
            if (value <= 0 || value > 0xFFFF_FFFFL) throw InvalidTiff()
            val existing = directory.entries.filter { it.tag == tag }
            val type = if (existing.firstOrNull()?.type == TYPE_SHORT && value <= 0xFFFF) TYPE_SHORT else TYPE_LONG
            directory.entries.removeAll { it.tag == tag }
            directory.entries += Entry(
                tag = tag,
                type = type,
                count = 1,
                bytes = if (type == TYPE_SHORT) encodeU16(value.toInt()) else encodeU32(value),
            )
        }

        private fun collectOutputDirectories(): List<Directory> {
            val out = mutableListOf<Directory>()
            val seen = mutableSetOf<Directory>()
            fun visit(directory: Directory?) {
                if (directory == null || directory.sensitive || !seen.add(directory)) return
                out += directory
                for (entry in directory.entries) {
                    val targets = entry.targets ?: continue
                    if (targets.any { it?.sensitive == true }) continue
                    targets.forEach(::visit)
                }
                visit(directory.next)
            }
            visit(root)
            for (directory in out) {
                directory.entries.removeAll { entry ->
                    entry.targets?.any { it?.sensitive == true } == true
                }
            }
            return out
        }

        private fun entryValueBytes(entry: Entry): ByteArray {
            val targets = entry.targets ?: return entry.bytes
            val bytes = ByteArray(targets.size * 4)
            targets.forEachIndexed { index, target ->
                putU32(bytes, index * 4, target?.takeUnless { it.sensitive }?.outputOffset ?: 0L)
            }
            return bytes
        }

        private fun requireRange(offset: Long, length: Long) {
            if (offset < 0 || length < 0 || offset > source.size || length > source.size - offset) throw InvalidTiff()
        }

        private fun align2(value: Long): Long = if ((value and 1L) == 0L) value else value + 1

        private fun u16(bytes: ByteArray, offset: Int): Int {
            if (offset < 0 || offset > bytes.size - 2) throw InvalidTiff()
            val a = bytes[offset].toInt() and 0xFF
            val b = bytes[offset + 1].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }

        private fun u32(bytes: ByteArray, offset: Int): Long {
            if (offset < 0 || offset > bytes.size - 4) throw InvalidTiff()
            val a = bytes[offset].toLong() and 0xFF
            val b = bytes[offset + 1].toLong() and 0xFF
            val c = bytes[offset + 2].toLong() and 0xFF
            val d = bytes[offset + 3].toLong() and 0xFF
            return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            if (littleEndian) {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            } else {
                bytes[offset] = (value ushr 8).toByte()
                bytes[offset + 1] = value.toByte()
            }
        }

        private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
            if (littleEndian) {
                for (i in 0..3) bytes[offset + i] = (value ushr (8 * i)).toByte()
            } else {
                for (i in 0..3) bytes[offset + i] = (value ushr (8 * (3 - i))).toByte()
            }
        }

        private fun encodeU16(value: Int): ByteArray = ByteArray(2).also { putU16(it, 0, value) }
        private fun encodeU32(value: Long): ByteArray = ByteArray(4).also { putU32(it, 0, value) }

        private fun typeSize(type: Int): Long? = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1L
            TYPE_SHORT, TYPE_SSHORT -> 2L
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_IFD -> 4L
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8L
            else -> null
        }

        companion object {
            private const val TIFF_MAGIC = 42
            private const val TIFF_HEADER_SIZE = 8
            private const val TIFF_ENTRY_SIZE = 12
            private const val INLINE_VALUE_SIZE = 4
            private const val MAX_DIRECTORY_DEPTH = 16
            private const val MAX_DIRECTORY_ENTRIES = 4096
            private const val MAX_SUB_IFDS = 1024

            private const val TYPE_BYTE = 1
            private const val TYPE_ASCII = 2
            private const val TYPE_SHORT = 3
            private const val TYPE_LONG = 4
            private const val TYPE_RATIONAL = 5
            private const val TYPE_SBYTE = 6
            private const val TYPE_UNDEFINED = 7
            private const val TYPE_SSHORT = 8
            private const val TYPE_SLONG = 9
            private const val TYPE_SRATIONAL = 10
            private const val TYPE_FLOAT = 11
            private const val TYPE_DOUBLE = 12
            private const val TYPE_IFD = 13

            private const val TAG_IMAGE_WIDTH = 0x0100
            private const val TAG_IMAGE_HEIGHT = 0x0101
            private const val TAG_ORIENTATION = 0x0112
            private const val TAG_THUMBNAIL_OFFSET = 0x0201
            private const val TAG_THUMBNAIL_LENGTH = 0x0202
            private const val TAG_EXIF_IFD = 0x8769
            private const val TAG_GPS_IFD = 0x8825
            private const val TAG_PIXEL_X_DIMENSION = 0xA002
            private const val TAG_PIXEL_Y_DIMENSION = 0xA003
            private const val TAG_INTEROP_IFD = 0xA005
            private const val TAG_SUB_IFDS = 0x014A
            private val POINTER_TAGS = setOf(TAG_EXIF_IFD, TAG_GPS_IFD, TAG_INTEROP_IFD, TAG_SUB_IFDS)

            // Private notes/comments may contain coordinates; strip tables point outside
            // the APP1 payload and cannot be rebased safely into the returned block.
            private val PRIVATE_OR_UNREBASED_TAGS = setOf(
                0x927C, // MakerNote
                0x9286, // UserComment
                0x9C9C, // XPComment
                0x0111, 0x0117, // StripOffsets / StripByteCounts
                0x0120, 0x0121, // FreeOffsets / FreeByteCounts
                0x0144, 0x0145, // TileOffsets / TileByteCounts
            )
        }
    }

    private fun hasExifPrefix(bytes: ByteArray): Boolean {
        if (bytes.size < EXIF_PREFIX.size) return false
        for (i in 0..bytes.size - EXIF_PREFIX.size) {
            if (EXIF_PREFIX.indices.all { bytes[i + it] == EXIF_PREFIX[it] }) return true
        }
        return false
    }

    private val EXIF_PREFIX = byteArrayOf(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
    )
}
