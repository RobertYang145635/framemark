package com.framemark.native.engine

/**
 * X1 Android skeleton: loads the engine `.so` and calls it once so a device run
 * proves the JNI path. Rendering stays on the placeholder workflow until the
 * engine core is linked (X2); this object never pretends to render anything.
 */
object NativeEngineProbe {
    private var status: String? = null

    fun load(): String {
        status?.let { return it }
        val next = try {
            System.loadLibrary("framemark_engine")
            "loaded"
        } catch (t: Throwable) {
            "failed: ${t.message}"
        }
        status = next
        return next
    }

    /** Version string from the .so, or null when the library is not loaded. */
    fun ping(): String? = runCatching { nativePing() }.getOrNull()

    fun abiBits(): Int = runCatching { nativeAbiBits() }.getOrDefault(0)

    /** True only when the Rust renderer is linked (arm64 build), not the stub. */
    fun renderLinked(): Boolean = ping()?.startsWith("fm-android-engine") == true

    /**
     * Renders one IR style into [outPng]. Returns null on success, an error
     * message otherwise (missing symbol on unsupported ABIs included).
     */
    fun renderDesign(
        style: String,
        photo: String,
        photoOverride: String = "",
        exif: String,
        assetsJson: String,
        fontsDir: String,
        brandsDir: String,
        fallbackDir: String,
        theme: String,
        ratio: String,
        /** render-v1 design document as JSON; empty keeps the pre-wire no-op path. */
        designJson: String = "",
        overlayPath: String = "",
        overlayX: Float = 0f,
        overlayY: Float = 0f,
        overlayW: Float = 1f,
        overlayH: Float = 1f,
        overlayOpacity: Float = 1f,
        overlayAngle: Float = 0f,
        outPng: String,
        maxEdge: Int,
    ): String? = runCatching {
        nativeRenderDesign(
            style, photo, photoOverride, exif, assetsJson, fontsDir, brandsDir, fallbackDir, theme, ratio,
            designJson, overlayPath, overlayX, overlayY, overlayW, overlayH, overlayOpacity, overlayAngle,
            outPng, maxEdge,
        )
    }.getOrElse { "engine call failed: ${it.message}" }

    private external fun nativeRenderDesign(
        style: String,
        photo: String,
        photoOverride: String,
        exif: String,
        assetsJson: String,
        fontsDir: String,
        brandsDir: String,
        fallbackDir: String,
        theme: String,
        ratio: String,
        designJson: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        outPng: String,
        maxEdge: Int,
    ): String?

    private external fun nativePing(): String

    private external fun nativeAbiBits(): Int

    /**
     * Hand-template renderer: cards.json + card id + data dirs + out path.
     * [photoPath] overrides the card's manifest photo like e4-hand `--photo`;
     * empty keeps the bundled sample.
     */
    fun renderHand(
        cardsPath: String,
        cardId: String,
        handDir: String,
        fontsDir: String,
        extraFontsDir: String,
        fallbackCjk: String,
        photoPath: String,
        photoOverride: String = "",
        overlayPath: String = "",
        overlayX: Float = 0f,
        overlayY: Float = 0f,
        overlayW: Float = 1f,
        overlayH: Float = 1f,
        overlayOpacity: Float = 1f,
        overlayAngle: Float = 0f,
        /** render-v1 design document; empty keeps the bundled card design. */
        designJson: String = "",
        outPng: String,
        maxEdge: Int,
    ): String? = runCatching {
        nativeRenderHand(
            cardsPath, cardId, handDir, fontsDir, extraFontsDir, fallbackCjk, photoPath, photoOverride,
            overlayPath, overlayX, overlayY, overlayW, overlayH, overlayOpacity, overlayAngle, designJson,
            outPng, maxEdge,
        )
    }.getOrElse { "hand engine call failed: ${it.message}" }

    private external fun nativeRenderHand(
        cardsPath: String,
        cardId: String,
        handDir: String,
        fontsDir: String,
        extraFontsDir: String,
        fallbackCjk: String,
        photoPath: String,
        photoOverride: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        designJson: String,
        outPng: String,
        maxEdge: Int,
    ): String?

    /** Render and encode JPEG/WebP from the same RGBA raster in one native call. */
    fun renderExportDesign(
        style: String,
        photo: String,
        photoOverride: String,
        exif: String,
        assetsJson: String,
        fontsDir: String,
        brandsDir: String,
        fallbackDir: String,
        theme: String,
        ratio: String,
        designJson: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        geomPath: String,
        maxEdge: Int,
        iccPath: String,
        exifSrc: String,
        outPath: String,
        reportPath: String,
        webp: Boolean,
        lossless: Boolean,
        quality: Int,
        removeGps: Boolean = true,
        keepThumbnail: Boolean = true,
    ): String = runCatching {
        nativeRenderExportDesign(
            style, photo, photoOverride, exif, assetsJson, fontsDir, brandsDir, fallbackDir,
            theme, ratio, designJson, overlayPath, overlayX, overlayY, overlayW, overlayH,
            overlayOpacity, overlayAngle, geomPath, maxEdge, iccPath, exifSrc, outPath,
            reportPath, if (webp) 1 else 0, if (lossless) 1 else 0, quality.toFloat(),
            if (removeGps) 1 else 0, if (keepThumbnail) 1 else 0,
        )
    }.getOrElse { exportFailure(it.message ?: "render/export call failed") }

    private external fun nativeRenderExportDesign(
        style: String,
        photo: String,
        photoOverride: String,
        exif: String,
        assetsJson: String,
        fontsDir: String,
        brandsDir: String,
        fallbackDir: String,
        theme: String,
        ratio: String,
        designJson: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        geomPath: String,
        maxEdge: Int,
        iccPath: String,
        exifSrc: String,
        outPath: String,
        reportPath: String,
        webp: Int,
        lossless: Int,
        quality: Float,
        removeGps: Int,
        keepThumbnail: Int,
    ): String

    fun renderExportHand(
        cardsPath: String,
        cardId: String,
        handDir: String,
        fontsDir: String,
        extraFontsDir: String,
        fallbackCjk: String,
        photoPath: String,
        photoOverride: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        designJson: String,
        geomPath: String,
        maxEdge: Int,
        iccPath: String,
        exifSrc: String,
        outPath: String,
        reportPath: String,
        webp: Boolean,
        lossless: Boolean,
        quality: Int,
        removeGps: Boolean = true,
        keepThumbnail: Boolean = true,
    ): String = runCatching {
        nativeRenderExportHand(
            cardsPath, cardId, handDir, fontsDir, extraFontsDir, fallbackCjk, photoPath,
            photoOverride, overlayPath, overlayX, overlayY, overlayW, overlayH,
            overlayOpacity, overlayAngle, designJson, geomPath, maxEdge, iccPath,
            exifSrc, outPath, reportPath, if (webp) 1 else 0, if (lossless) 1 else 0,
            quality.toFloat(), if (removeGps) 1 else 0, if (keepThumbnail) 1 else 0,
        )
    }.getOrElse { exportFailure(it.message ?: "hand render/export call failed") }

    private external fun nativeRenderExportHand(
        cardsPath: String,
        cardId: String,
        handDir: String,
        fontsDir: String,
        extraFontsDir: String,
        fallbackCjk: String,
        photoPath: String,
        photoOverride: String,
        overlayPath: String,
        overlayX: Float,
        overlayY: Float,
        overlayW: Float,
        overlayH: Float,
        overlayOpacity: Float,
        overlayAngle: Float,
        designJson: String,
        geomPath: String,
        maxEdge: Int,
        iccPath: String,
        exifSrc: String,
        outPath: String,
        reportPath: String,
        webp: Int,
        lossless: Int,
        quality: Float,
        removeGps: Int,
        keepThumbnail: Int,
    ): String

    private fun exportFailure(message: String): String =
        "{\"ok\":false,\"error\":\"${message.replace("\\", "\\\\").replace("\"", "'")}\"}"

    /**
     * TIFF → PNG through the engine's decoder (the import path uses it so the
     * photo strip / BitmapFactory consumers get a format Android can read).
     * Returns null on success, an error message otherwise.
     */
    fun tiffToPng(tiffPath: String, outPng: String): String? = runCatching {
        nativeTiffToPng(tiffPath, outPng)
    }.getOrElse { "engine call failed: ${it.message}" }

    private external fun nativeTiffToPng(tiffPath: String, outPng: String): String?

    /**
     * E5 export chain on the rendered PNG: q100 4:4:4 JPEG + ICC + EXIF/thumbnail.
     * Returns the JSON envelope `{"ok":true,"report":{...}}` or
     * `{"ok":false,"error":"..."}`.
     */
    fun exportPng(
        pngPath: String,
        iccPath: String,
        exifSrc: String,
        outJpeg: String,
        reportJson: String,
        removeGps: Boolean = true,
        keepThumbnail: Boolean = true,
    ): String = runCatching {
        nativeExportPng(pngPath, iccPath, exifSrc, if (removeGps) 1 else 0, if (keepThumbnail) 1 else 0, outJpeg, reportJson)
    }.getOrElse { "{\"ok\":false,\"error\":\"${it.message?.replace("\"", "'") ?: "export call failed"}\"}" }

    private external fun nativeExportPng(
        pngPath: String,
        iccPath: String,
        exifSrc: String,
        removeGps: Int,
        keepThumbnail: Int,
        outJpeg: String,
        reportJson: String,
    ): String

    /**
     * WebP export (libwebp 1.5.0, bundled static): VP8L when [lossless], else
     * VP8 at [quality]; ICC + EXIF ride the RIFF chunks like the desktop worker.
     */
    fun exportWebp(
        pngPath: String,
        iccPath: String,
        exifSrc: String,
        outWebp: String,
        reportJson: String,
        lossless: Boolean,
        quality: Int = 100,
        removeGps: Boolean = true,
        keepThumbnail: Boolean = true,
    ): String = runCatching {
        nativeExportWebp(
            pngPath, iccPath, exifSrc, if (removeGps) 1 else 0, if (keepThumbnail) 1 else 0,
            if (lossless) 1 else 0, quality.toFloat(), outWebp, reportJson,
        )
    }.getOrElse { "{\"ok\":false,\"error\":\"${it.message?.replace("\"", "'") ?: "webp call failed"}\"}" }

    private external fun nativeExportWebp(
        pngPath: String,
        iccPath: String,
        exifSrc: String,
        removeGps: Int,
        keepThumbnail: Int,
        lossless: Int,
        quality: Float,
        outWebp: String,
        reportJson: String,
    ): String

    /**
     * True when this ABI's export bridge carries the WebP entry point. The probe
     * calls it with empty paths: a missing symbol throws UnsatisfiedLinkError
     * (caught here), a linked one answers an error envelope without doing work.
     */
    @Volatile
    private var webpSymbol: Boolean? = null

    fun webpLinked(): Boolean = webpSymbol ?: synchronized(this) {
        webpSymbol ?: runCatching {
            nativeExportWebp("", "", "", 0, 0, 1, 100f, "", "")
        }.isSuccess.also { webpSymbol = it }
    }
}
