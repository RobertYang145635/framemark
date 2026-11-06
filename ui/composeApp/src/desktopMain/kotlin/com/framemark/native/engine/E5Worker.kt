package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException

data class WorkerEncodeResult(
    val path: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val encodeMs: Double,
    val rowsFed: Int,
    val quality: Int,
    val subsampling: String?,
    val iccBytes: Int,
    val exifTiffBytes: Int,
    val format: String?,
    val webpLossless: Boolean?,
    val chunks: List<String>,
)

/**
 * E5 export encoder (protocol v1): a separate process fed RGB rows on stdin.
 *
 * The worker must stay its own process — linking mozjpeg into this JVM/Skia app
 * is fatal (JPEG struct mismatch). D-027/D-028: q100, 4:4:4, standard Huffman,
 * 456 B sRGB ICC + an EXIF TIFF block. Worker 0.2.0 adds `--format webp`
 * (libwebp 1.5.0, VP8L lossless or VP8 q100) with the same ICC/EXIF strategy.
 */
class E5Worker(
    private val exe: String = env("FM_E5_WORKER") ?: RuntimePaths.layout().e5Worker?.path
        ?: error("e5-worker.exe not found (set FM_E5_WORKER)"),
    val iccPath: String = env("FM_E5_ICC") ?: RuntimePaths.layout().e5Icc?.path
        ?: error("srgb-456.icc not found (set FM_E5_ICC)"),
) {
    fun available(): Boolean = File(exe).isFile && File(iccPath).isFile

    fun encodeJpeg(
        image: BufferedImage,
        out: File,
        scratch: File,
        exifTiff: ByteArray?,
        quality: Int = 100,
    ): WorkerEncodeResult = encode(image, out, scratch, exifTiff, format = "jpeg", lossless = null, quality = quality)

    fun encodeWebp(
        image: BufferedImage,
        out: File,
        scratch: File,
        exifTiff: ByteArray?,
        lossless: Boolean = true,
        quality: Int = 100,
    ): WorkerEncodeResult = encode(image, out, scratch, exifTiff, format = "webp", lossless = lossless, quality = quality)

    private fun encode(
        image: BufferedImage,
        out: File,
        scratch: File,
        exifTiff: ByteArray?,
        format: String,
        lossless: Boolean?,
        quality: Int,
    ): WorkerEncodeResult {
        require(available()) { "E5 worker or sRGB profile missing ($exe / $iccPath)" }
        val w = image.width
        val h = image.height
        require(w in 1..65535 && h in 1..65535) { "canvas ${w}x$h outside the worker range" }
        scratch.mkdirs()
        out.parentFile?.mkdirs()

        // Worker protocol v1 takes narrow (ANSI) paths for --out/--report: a
        // non-ASCII destination makes it write a GBK-mojibake file next to a
        // 0-byte target and exit 4 ("no SOF"). Encode into an ASCII scratch path
        // and move the bytes afterwards.
        val id = java.util.UUID.randomUUID().toString().take(8)
        val extension = if (format == "webp") "webp" else "jpg"
        val workerOut = File(scratch, "e5-out-$id.$extension")
        val report = File(scratch, "e5-report-$id.json")
        val exifFile = exifTiff?.let { block ->
            File(scratch, "e5-exif-$id.tiff").apply { writeBytes(block) }
        }
        val iccFile = File(scratch, "e5-icc-$id.icc").apply { File(iccPath).copyTo(this, overwrite = true) }
        val argv = mutableListOf(
            exe,
            "--w", w.toString(),
            "--h", h.toString(),
            "--quality", quality.toString(),
            "--icc", iccFile.path,
            "--out", workerOut.path,
            "--report", report.path,
        )
        if (exifFile != null) argv += listOf("--exif-tiff", exifFile.path)
        if (format == "webp") {
            argv += listOf("--format", "webp", "--webp-lossless", if (lossless == true) "1" else "0")
        }

        try {
            val process = ProcessRunner.run(argv) { output ->
                output.buffered(1 shl 20).use { stdin ->
                    val row = ByteArray(w * 3)
                    val argb = IntArray(w)
                    for (y in 0 until h) {
                        image.getRGB(0, y, w, 1, argb, 0, w)
                        var i = 0
                        for (x in 0 until w) {
                            val c = argb[x]
                            var r = (c shr 16) and 0xFF
                            var g = (c shr 8) and 0xFF
                            var b = c and 0xFF
                            val a = (c ushr 24) and 0xFF
                            if (a != 0xFF) {
                                // JPEG has no alpha: flatten onto white, same rule as the old path.
                                r = (r * a + 255 * (255 - a)) / 255
                                g = (g * a + 255 * (255 - a)) / 255
                                b = (b * a + 255 * (255 - a)) / 255
                            }
                            row[i++] = r.toByte()
                            row[i++] = g.toByte()
                            row[i++] = b.toByte()
                        }
                        stdin.write(row)
                    }
                }
            }
            if (process.code != 0 || !workerOut.isFile || workerOut.length() == 0L) {
                throw IllegalStateException("E5 worker failed (exit ${process.code}): ${process.output.takeLast(600)}")
            }
            java.nio.file.Files.move(
                workerOut.toPath(),
                out.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )

            val rep = Json.parse(report.readText()).jsonMap()
            val canvas = rep["canvas"].jsonMap()
            val result = rep["output"].jsonMap()
            val rowsFed = (rep["rowsFed"] as? Number)?.toInt() ?: -1
            val subsampling = result.jsonString("subsampling")
            val iccBytes = (rep["iccBytes"] as? Number)?.toInt() ?: 0
            val exifBytes = ((rep["exifTiffBytes"] ?: rep["exifBytes"]) as? Number)?.toInt() ?: 0
            val encodeMs = (rep["ms"].jsonMap()["encode"] as? Number)?.toDouble() ?: 0.0
            val reportedQuality = (rep["quality"] as? Number)?.toInt()
            require(reportedQuality == quality) { "E5 worker quality $reportedQuality, expected $quality" }
            val reportFormat = rep.jsonString("format")
            val reportLossless = rep["webpLossless"] as? Boolean
            val chunks = result["chunks"].jsonList().mapNotNull { it as? String }
            require(rowsFed == h) { "E5 worker fed $rowsFed rows, expected $h" }
            require((canvas["w"] as? Number)?.toInt() == w && (canvas["h"] as? Number)?.toInt() == h) {
                "E5 worker canvas mismatch"
            }
            if (format == "webp") {
                require(reportFormat == if (lossless == true) "webp-lossless" else "webp-lossy") {
                    "E5 worker WebP format $reportFormat, expected lossless=$lossless"
                }
                require(reportLossless == lossless) { "E5 worker lossless flag $reportLossless, expected $lossless" }
                require("ICCP" in chunks) { "E5 worker WebP is missing ICCP" }
            } else {
                require(subsampling == "4:4:4") { "E5 worker subsampling $subsampling, expected 4:4:4" }
            }
            println(
                "FM_E5_${if (format == "webp") "WEBP" else "ENCODE"} path=${out.path} ${w}x$h bytes=${out.length()} " +
                    "rows=$rowsFed ms=$encodeMs icc=$iccBytes exif=$exifBytes " +
                    (if (format == "webp") "lossless=$lossless chunks=${chunks.joinToString(",")}" else "subsampling=$subsampling") +
                    " quality=$quality",
            )
            return WorkerEncodeResult(
                path = out.path,
                bytes = out.length(),
                width = w,
                height = h,
                encodeMs = encodeMs,
                rowsFed = rowsFed,
                quality = reportedQuality,
                subsampling = subsampling,
                iccBytes = iccBytes,
                exifTiffBytes = exifBytes,
                format = reportFormat,
                webpLossless = reportLossless,
                chunks = chunks,
            )
        } finally {
            // AU1-02: the worker's scratch (encoded bytes, report, EXIF block,
            // ICC copy) is deleted on success and failure. `$out` already holds
            // the committed result, so nothing here is still needed.
            listOfNotNull(workerOut, report, exifFile, iccFile).forEach {
                if (it.exists() && !it.delete()) {
                    println("FM_E5_TEMP_DELETE_FAILED path=${it.path}")
                }
            }
        }
    }

    private companion object {
        fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
