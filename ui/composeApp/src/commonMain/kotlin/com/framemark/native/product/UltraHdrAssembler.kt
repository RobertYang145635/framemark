package com.framemark.native.product

/**
 * Wraps an already-encoded SDR JPEG into a Google Ultra HDR file.
 *
 * The engine writes the SDR rendition (photo + border) as usual; this object
 * appends the gain map and the metadata that makes a file HDR capable, without
 * touching a single SDR pixel:
 *
 *  - **The gain map image** — a second JPEG appended to the file, produced by the
 *    caller at half the canvas size, with the border already filled with the
 *    per-channel 0 dB sentinel.
 *  - **GContainer XMP in the primary** — the `Container:Directory` that declares
 *    two items and the exact byte length of the gain map.
 *  - **MPF index (CIPA DC-x 007)** — the legacy route to the second image, which
 *    is what OPPO's own gallery encoder writes as well.
 *  - **ISO 21496-1 APP2** — copied verbatim from the source file, because that
 *    binary payload is encoder-specific and only the pixels are ours to change.
 *
 * Only the primary is rebuilt; the gain map body is inserted as-is, so no pixel
 * work happens here and the output is deterministic.
 */
object UltraHdrAssembler {

    private const val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/"

    /** Fixed size of the MPF TIFF block: header(8) + IFD(26) + two entries(32). */
    private const val TIFF_SIZE = 66

    /**
     * `[primary][gain map]` as one file.
     *
     * Returns null when the inputs cannot be represented (a metadata packet too
     * large for one APP segment, or a payload that is not a JPEG); the caller then
     * keeps the plain SDR file, which is always a valid fallback.
     */
    fun assemble(
        sdrJpeg: ByteArray,
        gainMapJpeg: ByteArray,
        metadata: UltraHdr.Metadata,
    ): ByteArray? {
        if (!isJpeg(gainMapJpeg)) return null
        if (metadata.xmp.isBlank()) return null
        // A base that already carries Ultra HDR metadata (a camera original used as
        // the SDR input, or a re-wrap of an export) would end up with two MPF
        // indexes and two XMP packets, and a decoder reads whichever comes first —
        // pointing at the previous gain map. Strip ours before inserting.
        val base = stripHdrMetadata(sdrJpeg) ?: return null

        // Gain map first: its final length goes into both the primary's GContainer
        // entry and the MPF entry, and it does not depend on the primary.
        val gainXmp = app1(XMP_PREFIX + metadata.xmp) ?: return null
        val gainIso = metadata.isoPayload?.let { app2(it) } ?: ByteArray(0)
        val gainMapSize = gainMapJpeg.size + gainXmp.size + gainIso.size

        val primaryXmp = app1(XMP_PREFIX + containerXmp(gainMapSize)) ?: return null
        val primaryIso = if (metadata.isoPayload != null) {
            app2(isoDeclaration()) ?: return null
        } else {
            ByteArray(0)
        }

        // The MPF segment has a fixed size once the entry count is 2, so the final
        // primary length can be computed before its content is built. That is what
        // breaks the apparent circularity between "where does the gain map start"
        // and "how long is the primary (which contains the answer)".
        val mpfSize = 2 + 2 + 4 + TIFF_SIZE
        val insertAt = insertPoint(base)
        val primarySize = base.size + primaryXmp.size + mpfSize + primaryIso.size
        val gainMapOffset = primarySize
        // marker(2) + length(2) + "MPF\0"(4) before the TIFF header.
        val tiffStart = insertAt + primaryXmp.size + 8
        val mpf = app2(
            "MPF".encodeToByteArray() + byteArrayOf(0) +
                tiff(primarySize, gainMapOffset - tiffStart, gainMapSize),
        ) ?: return null

        val out = ByteArray(primarySize + gainMapSize)
        var at = 0
        base.copyInto(out, at, 0, insertAt)
        at += insertAt
        primaryXmp.copyInto(out, at); at += primaryXmp.size
        mpf.copyInto(out, at); at += mpf.size
        primaryIso.copyInto(out, at); at += primaryIso.size
        base.copyInto(out, at, insertAt, base.size); at += base.size - insertAt
        // The gain map keeps its own SOI, then our metadata, then its encoded body.
        gainMapJpeg.copyInto(out, at, 0, 2); at += 2
        gainXmp.copyInto(out, at); at += gainXmp.size
        gainIso.copyInto(out, at); at += gainIso.size
        gainMapJpeg.copyInto(out, at, 2, gainMapJpeg.size)

        // Self-check with the reader that the tests pin: offsets, lengths and the
        // GContainer directory must all agree, or the file is not shipped.
        val parsed = UltraHdr.locate(out) ?: return null
        if (parsed.offset != gainMapOffset.toLong() || parsed.size != gainMapSize.toLong()) return null
        if (UltraHdr.containerLength(out) != gainMapSize.toLong()) return null
        return out
    }

    /**
     * The primary's GContainer directory plus the Ultra HDR format signal.
     *
     * `hdrgm:Version="1.0"` in the primary is how a decoder recognises the format
     * at all; the `Container:Directory` is the modern route to the second image,
     * and the per-item `Item:Length` must be exact.
     */
    internal fun containerXmp(gainMapSize: Int): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("    <rdf:Description rdf:about=\"\"\n")
        append("        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
        append("        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
        append("        xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n")
        append("        hdrgm:Version=\"1.0\">\n")
        append("      <Container:Directory>\n")
        append("        <rdf:Seq>\n")
        append("          <rdf:li rdf:parseType=\"Resource\">\n")
        append("            <Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>\n")
        append("          </rdf:li>\n")
        append("          <rdf:li rdf:parseType=\"Resource\">\n")
        append("            <Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" ")
        append("Item:Length=\"$gainMapSize\"/>\n")
        append("          </rdf:li>\n")
        append("        </rdf:Seq>\n")
        append("      </Container:Directory>\n")
        append("    </rdf:Description>\n")
        append("  </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
    }

    // ------------------------------------------------------------------ pieces

    /** `urn:iso:std:iso:ts:21496:-1` padded to the 32 bytes every sample carries. */
    private fun isoDeclaration(): ByteArray {
        val urn = UltraHdr.ISO_URN.encodeToByteArray()
        val payload = ByteArray(32)
        urn.copyInto(payload)
        return payload
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray? {
        val length = payload.size + 2
        if (length > 0xFFFF) return null
        val out = ByteArray(4 + payload.size)
        out[0] = 0xFF.toByte()
        out[1] = marker.toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun app1(text: String): ByteArray? = segment(0xE1, text.encodeToByteArray())

    private fun app2(payload: ByteArray): ByteArray? = segment(0xE2, payload)

    /**
     * MPF Index IFD: two images. The primary sits at file start and stores offset
     * 0; the gain map's offset is relative to the TIFF header, which is the detail
     * every implementation gets wrong once.
     */
    private fun tiff(primarySize: Int, gainOffsetField: Int, gainSize: Int): ByteArray {
        val b = ByteArray(TIFF_SIZE)
        b[0] = 0x4D; b[1] = 0x4D; b[3] = 0x2A.toByte()
        put32(b, 4, 8)                    // IFD0 at tiff+8
        put16(b, 8, 2)                    // two tags
        put16(b, 10, 0xB001); put16(b, 12, 4); put32(b, 14, 1); put32(b, 18, 2)
        put16(b, 22, 0xB002); put16(b, 24, 7); put32(b, 26, 32); put32(b, 30, 34)
        put32(b, 34, 0x00030000)          // primary: representative image, JPEG
        put32(b, 38, primarySize)
        put32(b, 42, 0)                   // 0 means "this file"
        put32(b, 50, 0)                   // gain map: no special attributes
        put32(b, 54, gainSize)
        put32(b, 58, gainOffsetField)
        return b
    }

    /**
     * First byte of the primary that belongs *after* the metadata: insert before
     * the first non-APPn, non-COM segment (in practice the DQT), keeping every APP
     * segment ahead of the coding tables as the JPEG convention expects.
     */
    private fun insertPoint(jpeg: ByteArray): Int {
        var i = 2
        while (i + 1 < jpeg.size) {
            if (u8(jpeg, i) != 0xFF) return i
            val marker = u8(jpeg, i + 1)
            if (marker == 0xD8 || marker == 0xD9 || marker == 0xDA) return i
            if (marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val isApp = marker in 0xE0..0xEF || marker == 0xFE
            if (!isApp) return i
            if (i + 3 >= jpeg.size) return i
            val length = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
            if (length < 2) return i
            i += 2 + length
        }
        return i.coerceAtMost(jpeg.size)
    }

    private fun isJpeg(b: ByteArray): Boolean = b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    /**
     * The base without any Ultra HDR metadata it may already carry: an existing MPF
     * index, our ISO 21496-1 declaration, and an XMP packet that describes a gain
     * map. An unrelated XMP packet (camera description) is left alone.
     */
    private fun stripHdrMetadata(jpeg: ByteArray): ByteArray? {
        if (!isJpeg(jpeg)) return null
        val out = ArrayList<Byte>(jpeg.size)
        out.add(jpeg[0])
        out.add(jpeg[1])
        var i = 2
        while (i + 1 < jpeg.size) {
            if (u8(jpeg, i) != 0xFF) {
                // Entropy-coded data or something unexpected: keep the rest verbatim.
                for (k in i until jpeg.size) out.add(jpeg[k])
                return out.toByteArray()
            }
            val marker = u8(jpeg, i + 1)
            if (marker == 0xD8 || marker == 0xD9 || marker == 0xDA) {
                for (k in i until jpeg.size) out.add(jpeg[k])
                return out.toByteArray()
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                out.add(jpeg[i]); out.add(jpeg[i + 1]); i += 2; continue
            }
            if (i + 3 >= jpeg.size) return null
            val length = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
            if (length < 2 || i + 2 + length > jpeg.size) return null
            val from = i + 4
            val to = i + 2 + length
            val drop = when (marker) {
                0xE2 -> startsWith(jpeg, from, to, "MPF") ||
                    startsWith(jpeg, from, to, UltraHdr.ISO_URN)
                0xE1 -> startsWith(jpeg, from, to, XMP_PREFIX) && describesGainMap(jpeg, from, to)
                else -> false
            }
            if (!drop) for (k in i until i + 2 + length) out.add(jpeg[k])
            i += 2 + length
        }
        return out.toByteArray()
    }

    private fun startsWith(b: ByteArray, from: Int, to: Int, prefix: String): Boolean {
        val p = prefix.encodeToByteArray()
        if (from + p.size > to) return false
        for (k in p.indices) if (b[from + k] != p[k]) return false
        return true
    }

    /** True when an XMP payload carries the Ultra HDR signal or a container directory. */
    private fun describesGainMap(b: ByteArray, from: Int, to: Int): Boolean {
        val text = b.decodeToString(from, to)
        return text.contains("hdrgm:Version") || text.contains("Container:Directory")
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}
