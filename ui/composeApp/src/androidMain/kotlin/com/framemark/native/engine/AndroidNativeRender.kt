package com.framemark.native.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.framemark.native.LaunchOptions
import com.framemark.native.product.requireReadablePhotoOverride
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Android [NativeRenderApi]: every card the bundled runtime can render — all
 * 106 IR styles and all 69 hand templates (175 total). Previews stay PNGs from
 * the renderer; exports go through the E5 chain (Skia q100 4:4:4 + 456B ICC +
 * EXIF thumbnail) and land in the app's external Pictures dir, mirrored into
 * MediaStore.
 */
class AndroidNativeRender(context: Context) : NativeRenderApi {

    private val app = context.applicationContext

    private val data: EngineData.Snapshot by lazy { EngineData.snapshot(app) }

    private val byUiCard: Map<String, EngineData.Style> by lazy {
        data.styles.values
            .filter { !it.uiCardId.isNullOrBlank() }
            .associateBy { it.uiCardId!! }
    }

    /** All IR cards wired to the native renderer, keyed by library card id. */
    val wiredIrStyles: Map<String, EngineData.Style> get() = byUiCard

    /**
     * Cards that keep the X1-ANDROID6 inspector hints (theme/ratio). The hint
     * set stays exactly at the six cards the UI round verified; wiring the rest
     * of the library must not change their editor content.
     */
    val hintedIrStyles: Map<String, EngineData.Style>
        get() = byUiCard.filterValues { it.id in HINT_STYLES }

    private val handCards: Set<String> by lazy { data.handCardIds.toSet() }

    override fun supports(cardId: String): Boolean =
        NativeEngineProbe.load() == "loaded" && NativeEngineProbe.renderLinked() &&
            (byUiCard.containsKey(cardId) || handCards.contains(cardId))

    override fun supportsExport(cardId: String): Boolean = supports(cardId)

    /** libwebp 1.5.0 is linked into the export bridge on arm64 (VP8L + VP8). */
    override fun supportsWebpExport(): Boolean = NativeEngineProbe.load() == "loaded" && NativeEngineProbe.webpLinked()

    override suspend fun render(
        cardId: String,
        design: DesignSnapshot,
        mode: String,
        maxEdge: Int?,
        format: SampleFormat?,
        quality: Int?,
        longEdge: Int?,
        outputPath: String?,
        webpLossless: Boolean,
        overlay: OverlaySpec?,
        photoPath: String?,
    ): NativeImage = withContext(Dispatchers.IO) {
        require(mode == "preview" || mode == "export") { "mode must be preview|export, got $mode" }
        val started = SystemClock.elapsedRealtime()
        val overridePhoto = requireReadablePhotoOverride(photoPath) { path ->
            File(path).let { it.isFile && it.canRead() }
        }
        // 原尺寸 (no long edge asked for) means the photo's own pixels, not a fixed
        // cap: the canvas follows the source that is actually being rendered.
        // A2: an export without a long edge is the user's "原尺寸" — the whole photo,
// no template-ratio centre crop. The engine grows the canvas instead of
// resampling; templates that cannot hold the photo report E4H-FIT-NATIVE-*
// and fall back to the template ratio with a visible note.
val fitNative = mode == "export" && longEdge == null
        val edge = when {
            mode == "preview" -> maxEdge ?: 1280
            // Native canvas is photo + border, usually larger than the photo's
            // long edge; any max edge here would downscale the 1:1 photo.
            fitNative -> 0
            else -> longEdge ?: nativeLongEdge(overridePhoto, mode)
        }
        val png = File(app.cacheDir, "x1/${mode}-$cardId-${System.nanoTime()}.png")
        png.parentFile?.mkdirs()
        var note: String? = null
        val renderMs = if (byUiCard.containsKey(cardId)) {
            renderIr(cardId, design, edge, png, overlay, overridePhoto, fitNative)
        } else {
            val hand = renderHand(cardId, design, edge, png, overlay, overridePhoto, fitNative)
            note = hand.note
            hand.ms
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "engine wrote an unreadable PNG: ${png.absolutePath}" }
        // Engine photo draw box, rescaled to this image's pixels (overlay anchor).
        val photoRect = photoRectOf(File("${png.absolutePath}.geom.json"), bounds.outWidth, bounds.outHeight)

        if (mode == "preview") {
            println("FM_PHOTO_RECT card=$cardId rect=${photoRect ?: "<none>"} ${bounds.outWidth}x${bounds.outHeight}")
            return@withContext NativeImage(png.absolutePath, bounds.outWidth, bounds.outHeight, renderMs, photoRect)
        }

        // Evidence-only fault injection so the batch retry path can be exercised.
        if (LaunchOptions.batchFailCard == cardId) {
            LaunchOptions.batchFailCard = null
            throw IllegalStateException("injected export failure (evidence hook)")
        }

        val target = File(outputPath ?: defaultExportPath(cardId, format))
        target.parentFile?.mkdirs()
        val encodeMs: Long
        val report: JSONObject?
        if (format == SampleFormat.Png) {
            png.copyTo(target, overwrite = true)
            encodeMs = 0
            report = null
        } else {
            val exifSrc = exifSource(overridePhoto)
            val reportFile = File(target.parentFile, "${target.name}.report.json").absolutePath
            val startedEncode = SystemClock.elapsedRealtime()
            val envelope = if (format == SampleFormat.WebP) {
                NativeEngineProbe.exportWebp(
                    pngPath = png.absolutePath,
                    iccPath = data.icc.orEmpty(),
                    exifSrc = exifSrc,
                    outWebp = target.absolutePath,
                    reportJson = reportFile,
                    lossless = webpLossless,
                    quality = quality ?: 100,
                )
            } else {
                NativeEngineProbe.exportPng(
                    pngPath = png.absolutePath,
                    iccPath = data.icc.orEmpty(),
                    exifSrc = exifSrc,
                    outJpeg = target.absolutePath,
                    reportJson = reportFile,
                )
            }
            encodeMs = SystemClock.elapsedRealtime() - startedEncode
            val doc = JSONObject(envelope)
            require(doc.optBoolean("ok")) { "native export failed: ${doc.optString("error")}" }
            report = doc.optJSONObject("report")
        }
        val totalMs = SystemClock.elapsedRealtime() - started
        // NativeEngine publishes only after its no-clobber file commit succeeds.
        val galleryUri: String? = null

        if (!skipGalleryPublish) evidence(
            JSONObject()
                .put("card", cardId)
                .put("mode", mode)
                .put("png", png.absolutePath)
                .put("path", target.absolutePath)
                .put("galleryUri", galleryUri)
                .put("width", bounds.outWidth)
                .put("height", bounds.outHeight)
                .put("renderMs", renderMs)
                .put("encodeMs", encodeMs)
                .put("totalMs", totalMs)
                .put("report", report)
                .put("photoRect", photoRect?.let { listOf(it.x, it.y, it.w, it.h) })
                .put("peakRssKb", peakRssKb()),
        )
        NativeImage(target.absolutePath, bounds.outWidth, bounds.outHeight, totalMs, photoRect, note)
    }

    /**
     * Reads the engine geom's photo box and rescales it from canvas space to
     * this image's pixels: IR writes `photo`, hand writes `photo_rect`; both
     * carry `canvas`/`output`, and the actual decoded image size is authoritative.
     */
    private fun photoRectOf(geom: File, imageW: Int, imageH: Int): ImageRect? = runCatching {
        if (!geom.isFile) return null
        val doc = JSONObject(geom.readText())
        val canvas = doc.optJSONArray("canvas") ?: return null
        val cw = canvas.optDouble(0).toFloat()
        val ch = canvas.optDouble(1).toFloat()
        if (cw <= 0f || ch <= 0f) return null
        val rect = doc.optJSONArray("photo") ?: doc.optJSONArray("photo_rect") ?: return null
        if (rect.length() < 4) return null
        val sx = imageW / cw
        val sy = imageH / ch
        ImageRect(
            (rect.optDouble(0).toFloat() * sx),
            (rect.optDouble(1).toFloat() * sy),
            (rect.optDouble(2).toFloat() * sx),
            (rect.optDouble(3).toFloat() * sy),
        )
    }.getOrNull()

    private fun renderIr(
        cardId: String,
        design: DesignSnapshot,
        edge: Int,
        out: File,
        overlay: OverlaySpec?,
        overridePhoto: String?,
        fitNative: Boolean,
    ): Long {
        val card = byUiCard.getValue(cardId)
        // The standard photo arg stays the current global; the explicit override
        // (when present) wins inside the engine. EXIF follows whichever is used.
        val (photo, _) = photoAndExif()
        val exif = exifSource(overridePhoto)
        val theme = (design.params["theme"] as? String).orEmpty()
        val ratio = when (val raw = design.params["ratio"]) {
            is String -> raw
            is Number -> raw.toString()
            else -> ""
        }.let { if (it == "original") "" else it }
        val started = SystemClock.elapsedRealtime()
        val designJson = designJsonOf(design, fitNative)
        val error = NativeEngineProbe.renderDesign(
            style = card.style,
            photo = photo,
            photoOverride = overridePhoto.orEmpty(),
            exif = exif,
            assetsJson = card.assetsJson,
            fontsDir = data.fonts,
            brandsDir = data.brands,
            fallbackDir = data.fallback,
            theme = theme,
            ratio = ratio,
            designJson = designJson,
            overlayPath = overlay?.path.orEmpty(),
            overlayX = overlay?.x ?: 0f,
            overlayY = overlay?.y ?: 0f,
            overlayW = overlay?.w ?: 1f,
            overlayH = overlay?.h ?: 1f,
            overlayOpacity = overlay?.opacity ?: 1f,
            outPng = out.absolutePath,
            maxEdge = edge,
        )
        if (error != null) throw IllegalStateException(error)
        val ms = SystemClock.elapsedRealtime() - started
        println(
            "FM_ANDROID_RENDER card=$cardId ${edge}px ms=$ms theme='$theme' ratio='$ratio' " +
                "fit=${if (fitNative) "native" else "design"} " +
                "design=${designJson.take(160)} " +
                "overlay=${overlay?.path ?: "<none>"} photo=${overridePhoto ?: photo} " +
                "globalPhoto=$photo out=${out.absolutePath}",
        )
        return ms
    }

    /**
     * The render-v1 design document in the exact shape `e2b --design` takes
     * (`{"design":{cardId,styleId,params,text}}`). An empty params+text map emits
     * **no** document at all — that is the desktop's own no-op rule, so the
     * default-design path stays byte-identical instead of merely similar.
     */
    private fun designJsonOf(design: DesignSnapshot, fitNative: Boolean = false): String {
        val params = LinkedHashMap<String, Any?>()
        design.params.forEach { (k, v) -> if (v != null) params[k] = v }
        design.logoBrand?.takeIf { it.isNotBlank() }?.let { params["brandLogoFile"] = it }
        val text = LinkedHashMap<String, Any?>()
        design.text.forEach { (k, v) -> if (v != null) text[k] = v }
        // Evidence-only probe: merge an explicit document over the live design so the
        // JNI channel can be proven without depending on a wired inspector control.
        LaunchOptions.designJsonOverride?.let { raw ->
            runCatching {
                val ov = org.json.JSONObject(raw).optJSONObject("design") ?: org.json.JSONObject(raw)
                ov.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.get(k) } }
                ov.optJSONObject("text")?.let { t -> t.keys().forEach { k -> text[k] = t.get(k) } }
                println("FM_DESIGN_OVERRIDE params=${params.size} text=${text.size}")
            }.onFailure { println("FM_DESIGN_OVERRIDE_FAIL ${it.message}") }
        }
        if (params.isEmpty() && text.isEmpty() && !fitNative) return ""
        fun jsonOf(map: Map<String, Any?>): org.json.JSONObject {
            val o = org.json.JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            return o
        }
        val doc = org.json.JSONObject()
            .put("cardId", design.cardId)
            .put("styleId", design.styleId ?: org.json.JSONObject.NULL)
            .put("params", jsonOf(params))
            .put("text", jsonOf(text))
        if (fitNative) doc.put("fitMode", "native")
        return org.json.JSONObject().put("design", doc).toString()
    }

    private data class HandResult(val ms: Long, val note: String?)

    private fun renderHand(
        cardId: String,
        design: DesignSnapshot,
        edge: Int,
        out: File,
        overlay: OverlaySpec?,
        overridePhoto: String?,
        fitNative: Boolean,
    ): HandResult {
        val cards = data.handCardsJson ?: error("hand cards bundle missing")
        // The standard photo arg stays the current global; the explicit override
        // (when present) wins inside the engine. Empty keeps the bundled sample.
        val userPhoto = currentUserPhoto().orEmpty()
        val started = SystemClock.elapsedRealtime()
        fun call(useNative: Boolean, maxEdge: Int): String? = NativeEngineProbe.renderHand(
            cardsPath = cards,
            cardId = cardId,
            handDir = data.handDir.orEmpty(),
            fontsDir = data.fonts,
            extraFontsDir = data.handExtraFonts.orEmpty(),
            fallbackCjk = File(data.fallback, "cjk.ttf").absolutePath,
            photoPath = userPhoto,
            photoOverride = overridePhoto.orEmpty(),
            overlayPath = overlay?.path.orEmpty(),
            overlayX = overlay?.x ?: 0f,
            overlayY = overlay?.y ?: 0f,
            overlayW = overlay?.w ?: 1f,
            overlayH = overlay?.h ?: 1f,
            overlayOpacity = overlay?.opacity ?: 1f,
            designJson = designJsonOf(design, useNative),
            outPng = out.absolutePath,
            maxEdge = maxEdge,
        )
        var useNative = fitNative
        var maxEdge = edge
        var note: String? = null
        var error = call(useNative, maxEdge)
        if (error != null && useNative && error.startsWith("E4H-FIT-NATIVE-")) {
            // The template's photo box cannot hold the whole photo: keep the
            // template ratio instead of failing, and say so in the result.
            println("FM_NATIVE_FIT_FALLBACK card=$cardId reason=${error.take(160)}")
            note = "该模板按版式比例裁切（不支持完整原尺寸）"
            useNative = false
            maxEdge = if (edge == 0) nativeLongEdge(overridePhoto, "export") else edge
            error = call(useNative, maxEdge)
        }
        if (error != null) throw IllegalStateException(error)
        val ms = SystemClock.elapsedRealtime() - started
        println(
            "FM_ANDROID_RENDER hand card=$cardId ${maxEdge}px ms=$ms fit=${if (useNative) "native" else "design"} " +
                "note=${note ?: "<none>"} design=${designJsonOf(design, useNative).take(160)} " +
                "overlay=${overlay?.path ?: "<none>"} photo=${overridePhoto ?: userPhoto.ifEmpty { "<sample>" }} " +
                "globalPhoto=${userPhoto.ifEmpty { "<sample>" }} out=${out.absolutePath}",
        )
        return HandResult(ms, note)
    }

    /**
     * Long edge of the photo that is actually being rendered, so an export without a
     * requested long edge keeps the source pixels instead of a fixed 2048 canvas.
     * Bounds-only decoding — a 50 MP file must not be resident just to measure it.
     */
    private fun nativeLongEdge(overridePhoto: String?, mode: String): Int {
        val source = overridePhoto ?: currentUserPhoto() ?: data.photo
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        val edge = if (long > 0) long else 2048
        println("FM_NATIVE_EDGE mode=$mode src=${source.substringAfterLast('/')} sourceLong=$long canvas=$edge")
        return edge.coerceIn(64, 20_000)
    }

    /** Picked photo first, then the evidence-only `fmPhoto` override. */
    private fun currentUserPhoto(): String? =
        PhotoSelection.path?.takeIf { File(it).isFile }
            ?: LaunchOptions.userPhotoPath?.takeIf { File(it).isFile }

    /**
     * The picked photo wins over the evidence-only `fmPhoto` override; both win
     * over the bundled sample. EXIF follows the photo that is actually rendered:
     * a user picture whose own file carries no usable Exif block gets **no** EXIF
     * source, because the alternative — the bundled sample's EXIF — used to bake
     * somebody else's camera make/model/exposure into the user's own frame, both
     * in pixels and in the exported file's APP1. The engine resolves an empty
     * path as `e3.exif=absent` and leaves the EXIF tokens blank.
     */
    private fun photoAndExif(): Pair<String, String> {
        val userPhoto = currentUserPhoto() ?: return data.photo to data.exif
        return userPhoto to exifFor(userPhoto)
    }

    /** EXIF source for the photo that will actually be rendered (override aware). */
    private fun exifSource(override: String? = null): String {
        val base = override?.takeIf { File(it).isFile } ?: currentUserPhoto() ?: return data.exif
        return exifFor(base)
    }

    /**
     * Only the bundled sample photo may use the bundled EXIF. A JPEG that really
     * carries an `Exif\0\0` APP1 is its own source (an APP1 without an ExifIFD is
     * still its own source — the engine finds no exposure sub-IFD and blanks the
     * tokens, which is the P3e contract, not something the shell may paper over).
     */
    private fun exifFor(photoPath: String): String {
        if (File(photoPath).absolutePath == File(data.photo).absolutePath) return data.exif
        val file = File(photoPath)
        val own = ExifProbe.isJpeg(file) && ExifProbe.hasApp1(file)
        println("FM_EXIF_SOURCE photo=${file.name} ownApp1=$own src=${if (own) file.name else "<absent>"}")
        return if (own) photoPath else ""
    }

    private fun defaultExportPath(cardId: String, format: SampleFormat?): String {
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: app.filesDir, "FrameMark")
        dir.mkdirs()
        val ext = format?.extension ?: "jpg"
        return File(dir, "$cardId-${System.currentTimeMillis()}.$ext").absolutePath
    }

    /**
     * Copy the finished image into MediaStore (Pictures/FrameMark) so it shows in
     * the gallery; the app-external file stays the exported artifact we report.
     * The MIME type follows the real bytes, so PNG lands in the album too — a
     * "saved" PNG that the gallery cannot see is not a delivered result.
     */
    private fun publishToGallery(file: File): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrameMark")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = app.contentResolver.openOutputStream(uri) ?: error("相册文件不可写")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(app.contentResolver.update(uri, values, null, null) > 0) { "相册发布未完成" }
        } catch (failure: Throwable) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            throw failure
        }
        println("FM_GALLERY_PUBLISH mime=$mime name=${file.name} uri=$uri")
        uri.toString()
    }.getOrNull()

    override suspend fun exportCommitted(path: String): String? = withContext(Dispatchers.IO) {
        if (skipGalleryPublish) return@withContext null
        if (android.os.Build.VERSION.SDK_INT < 29) return@withContext "本地已保存；当前 Android 版本仅支持本地文件及分享"
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return@withContext "本地文件不可读，未写入相册"
        if (publishToGallery(file) != null) "已写入相册"
        else "本地已保存；相册写入失败，可单独重试"
    }

    private fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLong() ?: -1L
    }.getOrDefault(-1L)

    private fun evidence(row: JSONObject) {
        runCatching {
            val dir = File(app.getExternalFilesDir(null), "x1-export")
            dir.mkdirs()
            File(dir, "evidence.jsonl").appendText(row.toString() + "\n")
        }
        println("FM_ANDROID_EXPORT $row")
    }

    companion object {
        /** Evidence-only: matrix runs keep files out of MediaStore and the export log. */
        @Volatile
        var skipGalleryPublish: Boolean = false

        /**
         * Styles that keep the theme/ratio inspector hints introduced in
         * X1-ANDROID6 (the UI round signed off on exactly these six editors).
         */
        private val HINT_STYLES = setOf(
            "oppo-gallery:lumo_style_1",
            "oppo-gallery:personalize_retroCamera_1",
            "vivo-zeiss:inside",
            "vivo-stamp:frame",
            "xiaomi-tag:9",
            "xiaomi-classic:22",
        )
    }
}
