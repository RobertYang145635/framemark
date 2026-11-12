package com.framemark.native.product

/**
 * Reader for Google Ultra HDR (gain map) JPEGs, plus the one number a frame app
 * must get right: the encoded gain-map value that means "0 dB".
 *
 * A conforming file is an ordinary JPEG with a second JPEG appended and indexed
 * by MPF (CIPA DC-x 007). The second image is a *gain map*: a per-pixel
 * multiplier, stored in log space, that turns the SDR primary into the HDR
 * rendition. Legacy viewers ignore the appended image, so the format stays
 * backward compatible.
 *
 * ## Why the border value is neither 0 nor 255
 *
 * A composited border carries no HDR content: its gain must be exactly 1.0
 * (0 dB) so it renders identically on SDR and HDR displays. The gain map stores
 * gains through a normalised encoding, so "1.0" is *not* 0 and not 255 — it
 * depends on the file's own `GainMapMin` / `GainMapMax` / `Gamma`:
 *
 * ```
 * normalized = (0 - GainMapMin) / (GainMapMax - GainMapMin)   // log2(1.0) == 0
 * recovery   = clamp(normalized, 0, 1) ^ Gamma
 * encoded    = floor(recovery * 255 + 0.5)
 * ```
 *
 * Filling 0 applies the file's *minimum* gain (about -0.5 to -1 stop for a
 * typical camera file) to the whole border; filling 255 applies the maximum.
 * Both are visible on an HDR display. The formula is pinned against a real OPPO
 * gallery export whose border is one flat colour — see `UltraHdrTest`.
 *
 * Everything here is pure (bytes in, data out) so the fragile binary offsets
 * stay unit-testable without touching a filesystem.
 */
object UltraHdr {

    /** `hdrgm` values for one channel. A single-Real file broadcasts to all three. */
    data class Channel(val min: Float, val max: Float, val gamma: Float)

    /**
     * Where the gain map lives and what a 0 dB pixel looks like in it.
     *
     * [offset] is the absolute byte offset of the appended JPEG (equal to the
     * primary image's byte length for every encoder observed), [size] its byte
     * length, [sentinel] the per-RGB-channel encoded 0 dB value (0..255).
     */
    data class GainMap(
        val offset: Long,
        val size: Long,
        val sentinel: List<Int>,
        val channels: List<Channel>,
        val version: String?,
    ) {
        /** True when all channels share one sentinel value (monochrome gain map). */
        val isMonochrome: Boolean get() = sentinel.distinct().size == 1
    }

    // ------------------------------------------------------------------ bytes

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int): Int =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    // --------------------------------------------------------------- segments

    private data class Segment(val marker: Int, val payloadStart: Int, val payloadEnd: Int)

    /** Marker segments from [start] up to (and including) SOS. Empty when not a JPEG. */
    private fun segments(bytes: ByteArray, start: Int, end: Int): List<Segment> {
        if (start < 0 || start + 1 >= end) return emptyList()
        if (u8(bytes, start) != 0xFF || u8(bytes, start + 1) != 0xD8) return emptyList()
        val out = ArrayList<Segment>(24)
        var i = start + 2
        var guard = 0
        while (i + 1 < end && guard++ < MAX_SEGMENTS) {
            if (u8(bytes, i) != 0xFF) return emptyList()
            val marker = u8(bytes, i + 1)
            if (marker == 0xD9 || marker == 0xDA) return out
            if (marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (i + 4 > end) return out
            val length = be16(bytes, i + 2)
            if (length < 2 || i + 2 + length > end) return out
            out += Segment(marker, i + 4, i + 2 + length)
            i += 2 + length
        }
        return out
    }

    private fun hasPrefix(bytes: ByteArray, seg: Segment, prefix: String, from: Int = 0): Boolean {
        val p = prefix.encodeToByteArray()
        val at = seg.payloadStart + from
        if (at + p.size > seg.payloadEnd) return false
        for (k in p.indices) if (bytes[at + k] != p[k]) return false
        return true
    }

    private fun xmpOf(bytes: ByteArray, segs: List<Segment>): String? {
        val seg = segs.firstOrNull { it.marker == 0xE1 && hasPrefix(bytes, it, XMP_PREFIX) } ?: return null
        val from = seg.payloadStart + XMP_PREFIX.length
        if (from >= seg.payloadEnd) return null
        return bytes.decodeToString(from, seg.payloadEnd)
    }

    // -------------------------------------------------------------- MPF index

    private data class MpfEntry(val size: Long, val absolute: Long)

    /**
     * `MP Entry` list of the MPF index, with absolute offsets.
     *
     * Two details are easy to get wrong and are pinned by the tests: entry
     * offsets are relative to the **TIFF header** (not the file), and the
     * primary image's entry stores 0, meaning "the file itself".
     */
    private fun mpfEntries(bytes: ByteArray, segs: List<Segment>): List<MpfEntry>? {
        val seg = segs.firstOrNull { it.marker == 0xE2 && hasPrefix(bytes, it, "MPF") } ?: return null
        val tiff = seg.payloadStart + 4
        if (tiff + 8 > seg.payloadEnd) return null
        val little = u8(bytes, tiff) == 0x49
        if (!little && u8(bytes, tiff) != 0x4D) return null
        val r16 = { at: Int -> if (little) le16(bytes, at) else be16(bytes, at) }
        val r32 = { at: Int -> if (little) le32(bytes, at) else be32(bytes, at) }
        // TIFF magic 42, read with the file's own endianness (MM or II).
        if (r16(tiff + 2) != 42) return null

        val ifd0 = r32(tiff + 4) + tiff
        if (ifd0 < 0 || ifd0 + 2 > bytes.size) return null
        val count = r16(ifd0)
        if (count <= 0 || count > MAX_IFD_ENTRIES) return null
        var entriesAt = -1
        var images = 0
        for (e in 0 until count) {
            val at = ifd0 + 2 + e * 12
            if (at + 12 > bytes.size) return null
            when (r16(at)) {
                0xB001 -> images = r32(at + 8)
                0xB002 -> entriesAt = r32(at + 8)
            }
        }
        if (entriesAt < 0 || images !in 1..MAX_IMAGES) return null

        val out = ArrayList<MpfEntry>(images)
        for (i in 0 until images) {
            val at = tiff + entriesAt + i * 16
            if (at + 12 > bytes.size) return null
            val size = r32(at + 4).toLong() and 0xFFFFFFFFL
            val offset = r32(at + 8).toLong() and 0xFFFFFFFFL
            out += MpfEntry(size, if (i == 0 && offset == 0L) 0L else offset + tiff)
        }
        return out
    }

    // ----------------------------------------------------------------- hdrgm

    /**
     * Numeric `hdrgm` properties of a gain-map XMP packet. A property may be a
     * single Real or an ordered array of three Reals, so each value comes back
     * as a list of 1 or 3 floats. Non-numeric properties (Version,
     * BaseRenditionIsHDR) are ignored here and read separately.
     */
    internal fun parseHdrgm(xmp: String): Map<String, List<Float>>? {
        if (xmp.isEmpty()) return null
        val out = LinkedHashMap<String, List<Float>>()
        for (m in ATTRIBUTE.findAll(xmp)) {
            val name = m.groupValues[1]
            if (name !in ARRAY_PROPERTIES || out.containsKey(name)) continue
            m.groupValues[2].toFloatOrNull()?.let { out[name] = listOf(it) }
        }
        for (name in ARRAY_PROPERTIES) {
            if (out.containsKey(name)) continue
            val block = Regex("<hdrgm:$name>([\\s\\S]*?)</hdrgm:$name>").find(xmp) ?: continue
            val values = LI.findAll(block.groupValues[1])
                .mapNotNull { it.groupValues[1].trim().toFloatOrNull() }
                .toList()
            if (values.isNotEmpty()) out[name] = values
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** `hdrgm:Version` verbatim (`"1.0"` is the only version defined so far). */
    internal fun versionOf(xmp: String): String? =
        Regex("hdrgm:Version\\s*=\\s*\"([^\"]*)\"").find(xmp)?.groupValues?.get(1)

    private fun List<Float>?.at(channel: Int): Float? = when {
        this == null -> null
        size == 1 -> this[0]
        size == 3 -> this[channel]
        else -> null
    }

    // -------------------------------------------------------------- sentinel

    /**
     * Per-channel encoded value for 0 dB, or null when the metadata cannot
     * produce one (missing `GainMapMax`, `max <= min`, non-positive gamma) —
     * the spec then asks a decoder to ignore the gain map, and so do we.
     */
    fun sentinel(properties: Map<String, List<Float>>): List<Int>? {
        val max = properties["GainMapMax"] ?: return null
        val min = properties["GainMapMin"] ?: listOf(0f)
        val gamma = properties["Gamma"] ?: listOf(1f)
        val out = ArrayList<Int>(3)
        for (c in 0 until 3) {
            val lo = min.at(c) ?: return null
            val hi = max.at(c) ?: return null
            val g = gamma.at(c) ?: return null
            val span = hi - lo
            if (!(span > 0f) || !(g > 0f) || !g.isFinite()) return null
            val normalized = ((0.0 - lo) / span).coerceIn(0.0, 1.0)
            val recovery = Math.pow(normalized, g.toDouble())
            out += kotlin.math.floor(recovery * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return out
    }

    // ---------------------------------------------------------------- locate

    /**
     * Gain map location + 0 dB sentinel of a whole JPEG.
     *
     * Returns null for a non-Ultra-HDR file and for any file whose metadata is
     * missing or out of range, so the caller exports plain SDR.
     */
    fun locate(bytes: ByteArray): GainMap? {
        if (bytes.size < 4) return null
        val base = segments(bytes, 0, bytes.size)
        if (base.isEmpty()) return null
        val entries = mpfEntries(bytes, base) ?: return null
        if (entries.size < 2) return null

        val gain = entries[1]
        if (gain.size <= 0 || gain.absolute <= 0 || gain.absolute > Int.MAX_VALUE) return null
        val from = gain.absolute.toInt()
        val to = gain.absolute + gain.size
        if (to > bytes.size || to > Int.MAX_VALUE) return null
        if (u8(bytes, from) != 0xFF || u8(bytes, from + 1) != 0xD8) return null

        val gainSegs = segments(bytes, from, to.toInt())
        val xmp = xmpOf(bytes, gainSegs) ?: return null
        val properties = parseHdrgm(xmp) ?: return null
        val sentinel = sentinel(properties) ?: return null

        val channels = ArrayList<Channel>(3)
        for (c in 0 until 3) {
            channels += Channel(
                min = properties["GainMapMin"].at(c) ?: 0f,
                max = properties["GainMapMax"].at(c) ?: return null,
                gamma = properties["Gamma"].at(c) ?: 1f,
            )
        }
        return GainMap(
            offset = gain.absolute,
            size = gain.size,
            sentinel = sentinel,
            channels = channels,
            version = versionOf(xmp),
        )
    }

    /** `Item:Length` of the GContainer `GainMap` entry, when the primary declares one. */
    fun containerLength(bytes: ByteArray): Long? {
        val xmp = xmpOf(bytes, segments(bytes, 0, bytes.size)) ?: return null
        return GAINMAP_ITEM.find(xmp)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Frame size of the primary image, from its SOF.
     *
     * The caller needs it twice: to derive the source rectangle the renderer's
     * centre-anchored crop kept (`UltraHdrExport.sourceRect`) and to rescale that
     * rectangle when the appended gain map is stored at a different resolution.
     */
    fun frameSize(bytes: ByteArray): Pair<Int, Int>? {
        for (s in segments(bytes, 0, bytes.size)) {
            val isSof = s.marker in 0xC0..0xCF &&
                s.marker != 0xC4 && s.marker != 0xC8 && s.marker != 0xCC
            if (!isSof) continue
            if (s.payloadStart + 5 > s.payloadEnd) return null
            return be16(bytes, s.payloadStart + 3) to be16(bytes, s.payloadStart + 1)
        }
        return null
    }

    /**
     * The metadata a re-encapsulation has to carry over verbatim: the gain map's
     * own XMP packet and its ISO 21496-1 APP2 payload.
     *
     * Copying these instead of re-deriving them is deliberate. The gain semantics
     * (min/max/gamma per channel, offsets, capacity) describe *this* gain map, and
     * our export only moves its pixels around geometrically; re-deriving them
     * would risk a mismatch for zero benefit. The ISO payload in particular is a
     * binary structure whose layout varies between encoders.
     */
    data class Metadata(val xmp: String, val isoPayload: ByteArray?)

    fun metadata(bytes: ByteArray, gainMap: GainMap): Metadata? {
        val segs = segments(bytes, gainMap.offset.toInt(), (gainMap.offset + gainMap.size).toInt())
        val xmp = xmpOf(bytes, segs) ?: return null
        val iso = segs.firstOrNull { it.marker == 0xE2 && hasPrefix(bytes, it, ISO_URN) }
            ?.let { bytes.copyOfRange(it.payloadStart, it.payloadEnd) }
        return Metadata(xmp, iso)
    }

    // -------------------------------------------------------------- constants

    private const val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/"
    internal const val ISO_URN = "urn:iso:std:iso:ts:21496:-1"
    private const val MAX_SEGMENTS = 512
    private const val MAX_IFD_ENTRIES = 64
    private const val MAX_IMAGES = 16

    private val ARRAY_PROPERTIES = listOf("GainMapMin", "GainMapMax", "Gamma", "OffsetSDR", "OffsetHDR")

    // Escaped (not raw) strings: a raw string cannot end with a quote.
    private val ATTRIBUTE = Regex("hdrgm:([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]*)\"")
    private val LI = Regex("<rdf:li>([^<]*)</rdf:li>")
    private val GAINMAP_ITEM =
        Regex("Item:Semantic\\s*=\\s*\"GainMap\"[\\s\\S]{0,200}?Item:Length\\s*=\\s*\"(\\d+)\"")
}
