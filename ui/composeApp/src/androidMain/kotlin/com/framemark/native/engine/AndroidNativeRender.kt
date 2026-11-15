package com.framemark.native.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.LogoBrandCatalog
import com.framemark.native.product.requireReadablePhotoOverride
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.product.PreviewTier
import com.framemark.native.product.PreviewHandoff
import com.framemark.native.workflow.ReportTextRow
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoLibrary
import com.framemark.native.workflow.bindReportTextSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import com.framemark.native.product.FmLog
import com.framemark.native.product.jsonWithSurrogatesEscaped

/**
 * Android [NativeRenderApi]: every card the bundled runtime can render — all
 * 111 IR styles and all 69 hand templates (180 total). Previews stay PNGs from
 * the renderer; exports go through the E5 chain (Skia q100 4:4:4 + 456B ICC +
 * EXIF thumbnail) and land in the app's external Pictures dir, mirrored into
 * MediaStore.
 */
class AndroidNativeRender(context: Context) : NativeRenderApi {

    private val app = context.applicationContext

    private val data: EngineData.Snapshot by lazy { EngineData.snapshot(app) }

    private data class InlineHandLogo(
        val name: String,
        val width: Int,
        val height: Int,
        val dataUrl: String,
    )

    private val handLogoCache = LinkedHashMap<String, InlineHandLogo>(8, 0.75f, true)
    private val handLogoCacheLock = Any()
    private val irLogoStageLock = Any()

    private val byUiCard: Map<String, EngineData.Style> by lazy {
        data.styles.values
            .filter { !it.uiCardId.isNullOrBlank() }
            .associateBy { it.uiCardId!! }
    }

    /** All IR cards wired to the native renderer, keyed by library card id. */
    val wiredIrStyles: Map<String, EngineData.Style> get() = byUiCard

    /** User images are only wired into the hand dataUrl path; IR is packaged-only. */
    fun supportsUserLogo(cardId: String): Boolean = handCards.contains(cardId)

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
        interactive: Boolean,
    ): NativeImage = PreviewHandoff.withPreviewHandoff(
        // P2 (audit): a blocking JNI render can be cancelled while it runs. When
        // that happens withContext completes the block (PNG + geom already written)
        // and then throws; the preview consumer never receives a NativeImage to
        // release. Offering the path for the duration of the call makes cancellation
        // delete both files. On the normal path the handoff is cleared, so the
        // consumer still owns them. Export targets are never offered.
        release = { path ->
            File(path).delete()
            File("$path.geom.json").delete()
        },
    ) { offer ->
        withContext(Dispatchers.IO) {
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
            if (mode == "export" && (format == SampleFormat.Jpeg || format == SampleFormat.WebP)) {
                if (LaunchOptions.batchFailCard == cardId) {
                    LaunchOptions.batchFailCard = null
                    throw IllegalStateException("injected export failure (evidence hook)")
                }
                val target = File(outputPath ?: defaultExportPath(cardId, format))
                target.parentFile?.mkdirs()
                val scratchDir = File(app.cacheDir, "x1").apply { mkdirs() }
                val scratch = File.createTempFile("export-", "", scratchDir)
                val reportFile = File("${scratch.absolutePath}.report.json")
                val geom = File("${scratch.absolutePath}.geom.json")
                try {
                    val direct = renderDirectExport(
                    cardId, design, edge, target, reportFile, geom, overlay, overridePhoto,
                    fitNative, format, quality ?: 100, webpLossless,
                )
                val doc = JSONObject(direct.envelope)
                require(doc.optBoolean("ok")) { "native export failed: ${doc.optString("error")}" }
                val report = doc.optJSONObject("report")
                val imageW = report?.optInt("w", 0) ?: 0
                val imageH = report?.optInt("h", 0) ?: 0
                require(imageW > 0 && imageH > 0 && target.isFile && target.length() > 0L) {
                    "native export produced no image: ${target.absolutePath}"
                }
                val photoRect = photoRectOf(geom, imageW, imageH)
                // The engine applied `photoCropX/Y/Zoom` itself: report the source
                // region it sampled so the export never pre-crops a second time.
                val photoSrc = photoSrcOf(geom)
                val textSlots = textSlotsOf(
                    geom = geom,
                    imageW = imageW,
                    imageH = imageH,
                    backend = if (byUiCard.containsKey(cardId)) "ir" else "hand",
                )
                val totalMs = SystemClock.elapsedRealtime() - started
                if (!skipGalleryPublish) evidence(
                    JSONObject()
                        .put("card", cardId)
                        .put("mode", mode)
                        .put("png", JSONObject.NULL)
                        .put("path", target.absolutePath)
                        .put("galleryUri", JSONObject.NULL)
                        .put("width", imageW)
                        .put("height", imageH)
                        .put("renderMs", report?.optLong("renderMs", -1L) ?: -1L)
                        .put("encodeMs", report?.optLong("msEncode", -1L) ?: -1L)
                        .put("totalMs", totalMs)
                        .put("report", report)
                        .put("photoRect", photoRect?.let { listOf(it.x, it.y, it.w, it.h) })
                        .put("peakRssKb", peakRssKb()),
                )
                return@withContext NativeImage(
                    target.absolutePath,
                    imageW,
                    imageH,
                    totalMs,
                    photoRect,
                    direct.note,
                    textSlots,
                    photoSrc = photoSrc,
                )
                } finally {
                    geom.delete()
                    reportFile.delete()
                    scratch.delete()
                }
            }
            val exportTarget = if (mode == "export") {
                File(outputPath ?: defaultExportPath(cardId, format)).also { it.parentFile?.mkdirs() }
            } else null
            val png = exportTarget ?: File(app.cacheDir, "x1/${mode}-$cardId-${System.nanoTime()}.png")
            png.parentFile?.mkdirs()
            val geom = File("${png.absolutePath}.geom.json")
            try {
            var note: String? = null
            val renderMs = if (byUiCard.containsKey(cardId)) {
                renderIr(
                    cardId, design, edge, png, overlay, overridePhoto, fitNative,
                    interactive = interactive && mode == "preview",
                )
            } else {
                val hand = renderHand(
                    cardId, design, edge, png, overlay, overridePhoto, fitNative,
                    interactive = interactive && mode == "preview",
                )
                note = hand.note
                hand.ms
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "engine wrote an unreadable PNG: ${png.absolutePath}" }
            // Engine photo draw box, rescaled to this image's pixels (overlay anchor).
            val photoRect = photoRectOf(geom, bounds.outWidth, bounds.outHeight)
            // Source pixels the engine sampled — already in the photo's **display**
            // (EXIF-applied) frame, the same space the crop params use.
            val photoSrc = photoSrcOf(geom)
            // Renderer-reported text rows, bound exactly like the desktop path so the
            // 文字 tab can group this render by canvas position.
            val textSlots = textSlotsOf(
                geom = geom,
                imageW = bounds.outWidth,
                imageH = bounds.outHeight,
                backend = if (byUiCard.containsKey(cardId)) "ir" else "hand",
            )

            if (mode == "preview") {
                FmLog.info("FM_PHOTO_RECT") { "card=$cardId rect=${photoRect ?: "<none>"} ${bounds.outWidth}x${bounds.outHeight}" }
                FmLog.info("FM_TEXT_SLOTS") { "card=$cardId rows=${textSlots.size}" }
                // Hand the already-written pair to the cancellation guard.
                // Reaching this line means both files exist; if the coroutine is
                // cancelled after withContext finishes the block, the guard
                // deletes them instead of leaving an owner-less pair.
                offer(png.absolutePath)
                return@withContext NativeImage(
                    png.absolutePath,
                    bounds.outWidth,
                    bounds.outHeight,
                    renderMs,
                    photoRect,
                    textSlots = textSlots,
                    photoSrc = photoSrc,
                )
            }

            // Evidence-only fault injection so the batch retry path can be exercised.
            if (LaunchOptions.batchFailCard == cardId) {
                LaunchOptions.batchFailCard = null
                throw IllegalStateException("injected export failure (evidence hook)")
            }

            val target = exportTarget ?: error("export result has no target")
            target.parentFile?.mkdirs()
            val encodeMs: Long
            val report: JSONObject?
            if (format == SampleFormat.Png) {
                if (png.absolutePath != target.absolutePath) png.copyTo(target, overwrite = true)
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
            NativeImage(target.absolutePath, bounds.outWidth, bounds.outHeight, totalMs, photoRect, note, textSlots, photoSrc = photoSrc)
            } catch (t: Throwable) {
                // A-02: a preview render that threw never reaches its consumer, so the
                // per-request PNG and its geom sidecar have no owner — delete them here.
                // Export targets are left in place: the commit/retry path owns those.
                if (exportTarget == null) {
                    png.delete()
                    geom.delete()
                }
                throw t
            } finally {
                // The geom of a *successful* preview stays: the QA evidence runners call
                // the renderer directly and read the newest sidecar after the call. The
                // production consumer deletes it together with the PNG (PreviewScratch).
                if (exportTarget != null) geom.delete()
            }
        }
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

    /**
     * Reads the engine geom's `photo_src`: the region of the **source photo** the
     * renderer really sampled, `[x, y, w, h]` in display (EXIF-applied) pixels —
     * the frame `PhotoOrientation.displaySize` reports, not the stored raster.
     *
     * Unlike [photoRectOf] this must not be rescaled: it lives in the photo's own
     * display frame, not in this image's canvas. Both bridges write it at the geom
     * root next to the photo box (`bridge/android`'s IR renderer from its own crop
     * maths, `bridge/android-hand` from the draw report), and it is what tells the
     * export that the engine — not Kotlin — applied `photoCropX/Y/Zoom`, so no
     * second pre-crop may be added on top. Null when the running engine binary
     * predates the field.
     */
    private fun photoSrcOf(geom: File): ImageRect? = runCatching {
        if (!geom.isFile) return null
        val rect = JSONObject(geom.readText()).optJSONArray("photo_src") ?: return null
        if (rect.length() < 4) return null
        val values = (0..3).map { rect.optDouble(it, Double.NaN) }
        if (values.any { !it.isFinite() }) return null
        if (values[2] <= 0.0 || values[3] <= 0.0) return null
        // Same contract as the desktop parser: a rect that starts off the file
        // cannot describe what was drawn, so the export falls back to its own
        // recomputation instead of cutting the gain map at a negative origin.
        if (values[0] < 0.0 || values[1] < 0.0) return null
        ImageRect(
            values[0].toFloat(),
            values[1].toFloat(),
            values[2].toFloat(),
            values[3].toFloat(),
        )
    }.getOrNull()

    /**
     * Binds the engine geom to text rows in this image's pixels.
     *
     * Two shapes reach this point: the hand bridge nests the engine report under
     * `report` (`texts[]`, rect as an object) and the IR bridge writes the report
     * fields at the geom root (`items[]` — flat or grouped in `bands[]` — with
     * `rect` as an array, plus a top-level `textSlots[]` that keeps hidden or
     * absent rows addressable).
     */
    private fun textSlotsOf(geom: File, imageW: Int, imageH: Int, backend: String): List<TextSlot> = runCatching {
        if (!geom.isFile) return emptyList()
        val doc = JSONObject(geom.readText())
        val canvas = doc.optJSONArray("canvas") ?: return emptyList()
        val cw = canvas.optDouble(0)
        if (!cw.isFinite() || cw <= 0.0) return emptyList()
        val report = doc.optJSONObject("report") ?: doc
        val scale = imageW / cw
        val rows = ArrayList<ReportTextRow>()

        fun finiteOrNull(value: Double): Double? = value.takeIf { it.isFinite() }

        fun collectItems(array: org.json.JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("kind") != "text") continue
                val rect = item.optJSONArray("rect")
                val lines = item.optJSONArray("lines")
                val text = item.optString("text", "").ifEmpty {
                    buildString {
                        if (lines != null) for (j in 0 until lines.length()) {
                            if (j > 0) append('\n')
                            append(lines.optString(j, ""))
                        }
                    }
                }
                rows += ReportTextRow(
                    text = text,
                    raw = item.optString("raw", text),
                    key = item.optString("key", "").takeIf { it.isNotEmpty() },
                    path = item.optString("path", "").takeIf { it.isNotEmpty() },
                    x = rect?.optDouble(0, 0.0) ?: 0.0,
                    y = rect?.optDouble(1, 0.0) ?: 0.0,
                    w = rect?.optDouble(2, 0.0) ?: 0.0,
                    h = rect?.optDouble(3, 0.0) ?: 0.0,
                    fontSize = finiteOrNull(item.optDouble("fs", Double.NaN)),
                    baseline = finiteOrNull(item.optDouble("baseline", Double.NaN)),
                    lines = lines?.length()?.coerceAtLeast(1) ?: 1,
                    present = true,
                    stable = true,
                    hidden = item.optBoolean("hidden", false),
                )
            }
        }

        collectItems(report.optJSONArray("items"))
        report.optJSONArray("bands")?.let { bands ->
            for (i in 0 until bands.length()) collectItems(bands.optJSONObject(i)?.optJSONArray("items"))
        }

        report.optJSONArray("texts")?.let { texts ->
            for (i in 0 until texts.length()) {
                val t = texts.optJSONObject(i) ?: continue
                val rect = t.optJSONObject("rect")
                rows += ReportTextRow(
                    text = t.optString("s", ""),
                    raw = t.optString("raw", t.optString("s", "")),
                    key = t.optString("key", "").takeIf { it.isNotEmpty() },
                    path = t.optString("path", "").takeIf { it.isNotEmpty() },
                    x = rect?.optDouble("x", 0.0) ?: t.optDouble("x", 0.0),
                    y = rect?.optDouble("y", 0.0) ?: t.optDouble("y", 0.0),
                    w = rect?.optDouble("w", 0.0) ?: 0.0,
                    h = rect?.optDouble("h", 0.0) ?: 0.0,
                    fontSize = finiteOrNull(t.optDouble("size", Double.NaN)),
                    baseline = finiteOrNull(t.optDouble("baseline", Double.NaN)),
                    lines = 1,
                    present = t.optBoolean("present", true),
                    stable = t.optBoolean("stable", false),
                    hidden = t.optBoolean("hidden", false),
                )
            }
        }

        // Declared rows that were not drawn stay addressable so a hidden row can
        // be restored by identity instead of disappearing from the panel.
        val drawnPaths = rows.mapNotNull { it.path }.toSet()
        (report.optJSONArray("textSlots") ?: doc.optJSONArray("textSlots"))?.let { declared ->
            for (i in 0 until declared.length()) {
                val slot = declared.optJSONObject(i) ?: continue
                val path = slot.optString("path", "").takeIf { it.isNotEmpty() } ?: continue
                if (path in drawnPaths) continue
                rows += ReportTextRow(
                    text = "",
                    raw = slot.optString("raw", slot.optString("text", "")),
                    key = slot.optString("key", "").takeIf { it.isNotEmpty() },
                    path = path,
                    x = 0.0,
                    y = 0.0,
                    w = 0.0,
                    h = 0.0,
                    present = slot.optBoolean("present", false),
                    stable = slot.optBoolean("stable", true),
                    hidden = slot.optBoolean("hidden", false),
                )
            }
        }

        bindReportTextSlots(rows, scale, backend)
    }.getOrDefault(emptyList())

    private data class DirectExportResult(val envelope: String, val note: String?)

    /** One native call: draw the RGBA raster, then encode it without a PNG stopover. */
    private suspend fun renderDirectExport(
        cardId: String,
        design: DesignSnapshot,
        edge: Int,
        target: File,
        reportFile: File,
        geom: File,
        overlay: OverlaySpec?,
        overridePhoto: String?,
        fitNative: Boolean,
        format: SampleFormat,
        quality: Int,
        webpLossless: Boolean,
    ): DirectExportResult {
        val exifSrc = exifSource(overridePhoto)
        if (byUiCard.containsKey(cardId)) {
            val card = byUiCard.getValue(cardId)
            val requestedBrand = design.params["brandLogoFile"] as? String
            if (UserLogoLibrary.isUserLogo(design.logoBrand) || UserLogoLibrary.isUserLogo(requestedBrand)) {
                throw com.framemark.native.workflow.EngineFailure(
                    "FM-E-LOGO-007",
                    "用户 Logo 目前仅支持 hand 边框；IR 模板请从品牌库选择字标",
                )
            }
            val (photo, _) = photoAndExif()
            val theme = (design.params["theme"] as? String).orEmpty()
            val ratio = when (val raw = design.params["ratio"]) {
                is String -> raw
                is Number -> raw.toString()
                else -> ""
            }.let { if (it == "original") "" else it }
            val rendererLogoKey = rendererLogoKey(design.logoBrand)
            val renderLogo = if (design.logoBrand?.equals("auto", ignoreCase = true) == true) {
                "auto"
            } else rendererLogoKey
            val designJson = designJsonOf(design.copy(logoBrand = renderLogo), fitNative)
            val envelope = NativeEngineProbe.renderExportDesign(
                style = card.style,
                photo = photo,
                photoOverride = overridePhoto.orEmpty(),
                exif = exifSrc,
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
                overlayAngle = overlay?.angle ?: 0f,
                geomPath = geom.absolutePath,
                maxEdge = edge,
                iccPath = data.icc.orEmpty(),
                exifSrc = exifSrc,
                outPath = target.absolutePath,
                reportPath = reportFile.absolutePath,
                webp = format == SampleFormat.WebP,
                lossless = webpLossless,
                quality = quality,
            )
            FmLog.info("FM_ANDROID_EXPORT_DIRECT") { "engine=ir card=$cardId out=${target.absolutePath}" }
            return DirectExportResult(envelope, null)
        }

        val cards = data.handCardsJson ?: error("hand cards bundle missing")
        val selectedLogo = design.logoBrand
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?.let { key ->
                if (UserLogoLibrary.isUserLogo(key)) inlineUserLogo(key)
                else if (LogoBrandCatalog.cutForAssetKey(key) != null) inlineHandLogo(key)
                else if (key.startsWith("ver:")) {
                    throw com.framemark.native.workflow.EngineFailure(
                        "FM-E-LOGO-001",
                        "所选 Logo 版本未包含在当前应用资源中",
                    )
                } else null
            }
        val userPhoto = currentUserPhoto().orEmpty()
        fun call(useNative: Boolean, maxEdge: Int): String = NativeEngineProbe.renderExportHand(
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
            overlayAngle = overlay?.angle ?: 0f,
            designJson = designJsonOf(design, useNative, selectedLogo),
            geomPath = geom.absolutePath,
            maxEdge = maxEdge,
            iccPath = data.icc.orEmpty(),
            exifSrc = exifSrc,
            outPath = target.absolutePath,
            reportPath = reportFile.absolutePath,
            webp = format == SampleFormat.WebP,
            lossless = webpLossless,
            quality = quality,
        )
        var useNative = fitNative
        var maxEdge = edge
        var note: String? = null
        var envelope = call(useNative, maxEdge)
        val first = JSONObject(envelope)
        val firstError = first.optString("error", "")
        if (!first.optBoolean("ok") && useNative && firstError.startsWith("E4H-FIT-NATIVE-")) {
            FmLog.info("FM_NATIVE_FIT_FALLBACK") { "card=$cardId reason=${firstError.take(160)}" }
            note = "该模板按版式比例裁切（不支持完整原尺寸）"
            useNative = false
            maxEdge = if (edge == 0) nativeLongEdge(overridePhoto, "export") else edge
            envelope = call(useNative, maxEdge)
        }
        FmLog.info("FM_ANDROID_EXPORT_DIRECT") { "engine=hand card=$cardId out=${target.absolutePath}" }
        return DirectExportResult(envelope, note)
    }

    private suspend fun renderIr(
        cardId: String,
        design: DesignSnapshot,
        edge: Int,
        out: File,
        overlay: OverlaySpec?,
        overridePhoto: String?,
        fitNative: Boolean,
        interactive: Boolean = false,
    ): Long {
        val card = byUiCard.getValue(cardId)
        val requestedBrand = design.params["brandLogoFile"] as? String
        if (UserLogoLibrary.isUserLogo(design.logoBrand) || UserLogoLibrary.isUserLogo(requestedBrand)) {
            throw com.framemark.native.workflow.EngineFailure(
                "FM-E-LOGO-007",
                "用户 Logo 目前仅支持 hand 边框；IR 模板请从品牌库选择字标",
            )
        }
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
        val rendererLogoKey = rendererLogoKey(design.logoBrand)
        val renderLogo = if (design.logoBrand?.equals("auto", ignoreCase = true) == true) {
            "auto"
        } else {
            rendererLogoKey
        }
        val designJson = designJsonOf(design.copy(logoBrand = renderLogo), fitNative)
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
            overlayAngle = overlay?.angle ?: 0f,
            outPng = out.absolutePath,
            maxEdge = edge,
            previewSourceEdge = if (interactive) PreviewTier.sourceEdge(edge) else 0,
        )
        if (error != null) throw IllegalStateException(error)
        val ms = SystemClock.elapsedRealtime() - started
        FmLog.info("FM_ANDROID_RENDER") {
            "card=$cardId ${edge}px ms=$ms theme='$theme' ratio='$ratio' " +
                "fit=${if (fitNative) "native" else "design"} " +
                "design=${designJson.take(160)} " +
                "overlay=${overlay?.path ?: "<none>"} photo=${overridePhoto ?: photo} " +
                "globalPhoto=$photo out=${out.absolutePath}"
        }
        return ms
    }

    /**
     * The render-v1 design document in the exact shape `e2b --design` takes
     * (`{"design":{cardId,styleId,params,text}}`). An empty params+text map emits
     * **no** document at all — that is the desktop's own no-op rule, so the
     * default-design path stays byte-identical instead of merely similar.
     */
    private fun designJsonOf(
        design: DesignSnapshot,
        fitNative: Boolean = false,
        handLogo: InlineHandLogo? = null,
    ): String {
        val params = LinkedHashMap<String, Any?>()
        val explicitAuto = design.logoBrand?.equals("auto", ignoreCase = true) == true
        design.params.forEach { (k, v) ->
            val staleLogo = k == "brandLogoFile" &&
                (explicitAuto || (v as? String)?.equals("auto", ignoreCase = true) == true)
            if (v != null && !staleLogo) params[k] = v
        }
        design.logoBrand?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?.let { params["brandLogoFile"] = it }
        val text = LinkedHashMap<String, Any?>()
        design.text.forEach { (k, v) -> if (v != null) text[k] = v }
        // Evidence-only probe: merge an explicit document over the live design so the
        // JNI channel can be proven without depending on a wired inspector control.
        LaunchOptions.designJsonOverride?.let { raw ->
            runCatching {
                val ov = org.json.JSONObject(raw).optJSONObject("design") ?: org.json.JSONObject(raw)
                ov.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.get(k) } }
                ov.optJSONObject("text")?.let { t -> t.keys().forEach { k -> text[k] = t.get(k) } }
                FmLog.info("FM_DESIGN_OVERRIDE") { "params=${params.size} text=${text.size}" }
            }.onFailure { FmLog.warn("FM_DESIGN_OVERRIDE_FAIL") { "${it.message}"} }
        }
        if (params.isEmpty() && text.isEmpty() && !fitNative && handLogo == null) return ""
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
        handLogo?.let { logo ->
            doc.put("logo", JSONObject()
                .put("name", logo.name)
                .put("width", logo.width)
                .put("height", logo.height)
                .put("dataUrl", logo.dataUrl))
        }
        if (fitNative) doc.put("fitMode", "native")
        // The payload crosses JNI, whose modified UTF-8 cannot carry characters
        // outside the BMP; escaping the surrogate halves keeps it ASCII and the
        // engine's own JSON parser rebuilds the text. Without this, one emoji in a
        // text slot failed every render with "design_json is not UTF-8".
        return jsonWithSurrogatesEscaped(org.json.JSONObject().put("design", doc).toString())
    }

    private data class HandResult(val ms: Long, val note: String?)

    private suspend fun renderHand(
        cardId: String,
        design: DesignSnapshot,
        edge: Int,
        out: File,
        overlay: OverlaySpec?,
        overridePhoto: String?,
        fitNative: Boolean,
        interactive: Boolean,
    ): HandResult {
        val cards = data.handCardsJson ?: error("hand cards bundle missing")
        val selectedLogo = design.logoBrand
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?.let { key ->
                if (UserLogoLibrary.isUserLogo(key)) inlineUserLogo(key)
                else if (LogoBrandCatalog.cutForAssetKey(key) != null) inlineHandLogo(key)
                else if (key.startsWith("ver:")) {
                    throw com.framemark.native.workflow.EngineFailure(
                        "FM-E-LOGO-001",
                        "所选 Logo 版本未包含在当前应用资源中",
                    )
                } else null
            }
        // The standard photo arg stays the current global; the explicit override
        // (when present) wins inside the engine. Empty keeps the bundled sample.
        val userPhoto = currentUserPhoto().orEmpty()
        val started = SystemClock.elapsedRealtime()
        // Interactive tier: the engine may decode the source photo at ~2x the
        // preview canvas instead of full resolution. The canvas, layout, crop,
        // logo and watermark geometry are unchanged (the engine keeps solving in
        // full-resolution photo pixels); only the sampled pixels are smaller. The
        // edge request is the same pure decision the IR tier uses.
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
            overlayAngle = overlay?.angle ?: 0f,
            designJson = designJsonOf(design, useNative, selectedLogo),
            outPng = out.absolutePath,
            maxEdge = maxEdge,
            previewSourceEdge = if (interactive) PreviewTier.sourceEdge(maxEdge) else 0,
        )
        var useNative = fitNative
        var maxEdge = edge
        var note: String? = null
        var error = call(useNative, maxEdge)
        if (error != null && useNative && error.startsWith("E4H-FIT-NATIVE-")) {
            // The template's photo box cannot hold the whole photo: keep the
            // template ratio instead of failing, and say so in the result.
            FmLog.info("FM_NATIVE_FIT_FALLBACK") { "card=$cardId reason=${error?.take(160)}" }
            note = "该模板按版式比例裁切（不支持完整原尺寸）"
            useNative = false
            maxEdge = if (edge == 0) nativeLongEdge(overridePhoto, "export") else edge
            error = call(useNative, maxEdge)
        }
        if (error != null) throw IllegalStateException(error)
        val ms = SystemClock.elapsedRealtime() - started
        FmLog.info("FM_ANDROID_RENDER") {
            "hand card=$cardId ${maxEdge}px ms=$ms fit=${if (useNative) "native" else "design"} " +
                "note=${note ?: "<none>"} design=${designJsonOf(design, useNative, selectedLogo).take(160)} " +
                "overlay=${overlay?.path ?: "<none>"} photo=${overridePhoto ?: userPhoto.ifEmpty { "<sample>" }} " +
                "globalPhoto=${userPhoto.ifEmpty { "<sample>" }} out=${out.absolutePath}"
        }
        return HandResult(ms, note)
    }

    /** Resolve the exact packaged catalog cut and inline a PNG for the existing hand Design.logo contract. */
    private suspend fun inlineUserLogo(designValue: String): InlineHandLogo {
        // The revision is part of the cache key: deleting the global entry (or
        // importing a replacement) must never leave the previous image active.
        val cacheKey = "$designValue@${UserLogoLibrary.revision}"
        synchronized(handLogoCacheLock) { handLogoCache[cacheKey] }?.let { return it }
        val id = UserLogoLibrary.idOf(designValue)
            ?: throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-004", "用户 Logo 标识无效")
        val asset = UserLogoEnv.repository?.load(id)
            ?: throw com.framemark.native.workflow.EngineFailure(
                "FM-E-LOGO-005",
                "所选用户 Logo 未收录；请重新导入或选择其它 Logo",
            )
        if (asset.bytes.size > UserLogoLibrary.MAX_BYTES ||
            asset.logo.width > UserLogoLibrary.MAX_EDGE ||
            asset.logo.height > UserLogoLibrary.MAX_EDGE
        ) {
            throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-006", "用户 Logo 超过大小限制")
        }
        val source = BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size)
            ?: throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-002", "用户 Logo 图片无法解码")
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val pngBytes = try {
            ByteArrayOutputStream().use { output ->
                check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG encoding returned false"
                }
                output.toByteArray()
            }
        } catch (error: Exception) {
            throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-003", "用户 Logo 图片转换失败", error)
        } finally {
            source.recycle()
        }
        val result = InlineHandLogo(
            name = "user-${asset.logo.sha256.take(12)}",
            width = width,
            height = height,
            dataUrl = "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP),
        )
        return synchronized(handLogoCacheLock) {
            handLogoCache[cacheKey] ?: result.also {
                handLogoCache[cacheKey] = it
                while (handLogoCache.size > 8) handLogoCache.remove(handLogoCache.keys.first())
            }
        }
    }

    private suspend fun inlineHandLogo(assetKey: String): InlineHandLogo {
        synchronized(handLogoCacheLock) { handLogoCache[assetKey] }?.let { return it }
        val sourceBytes = try {
            LogoBrandCatalog.readCutBytes(assetKey)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw com.framemark.native.workflow.EngineFailure(
                "FM-E-LOGO-001",
                "所选 Logo 资源缺失或不可读",
                error,
            )
        }
        val source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-002", "所选 Logo 图片无法解码")
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val pngBytes = try {
            ByteArrayOutputStream().use { output ->
                check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG encoding returned false"
                }
                output.toByteArray()
            }
        } catch (error: Exception) {
            throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-003", "所选 Logo 图片转换失败", error)
        } finally {
            source.recycle()
        }
        val name = logoEngineKey(assetKey)
        val result = InlineHandLogo(
            name = name,
            width = width,
            height = height,
            dataUrl = "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP),
        )
        return synchronized(handLogoCacheLock) {
            handLogoCache[assetKey] ?: result.also {
                handLogoCache[assetKey] = it
                while (handLogoCache.size > 8) handLogoCache.remove(handLogoCache.keys.first())
            }
        }
    }

    /** Exact catalog cuts are staged under the same collision-safe key used by desktop. */
    private suspend fun rendererLogoKey(brandKey: String?): String? {
        val key = brandKey?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) } ?: return null
        val cut = LogoBrandCatalog.cutForAssetKey(key)
        if (cut == null) {
            if (key.startsWith("ver:")) {
                throw com.framemark.native.workflow.EngineFailure(
                    "FM-E-LOGO-001",
                    "所选 Logo 版本未包含在当前应用资源中",
                )
            }
            return key // Preserve legacy brand-only values and EXIF matching behavior.
        }
        val sourceBytes = try {
            LogoBrandCatalog.readCutBytes(key)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw com.framemark.native.workflow.EngineFailure(
                "FM-E-LOGO-001",
                "所选 Logo 资源缺失或不可读",
                error,
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-002", "所选 Logo 图片无法解码")
        val width = bitmap.width
        val height = bitmap.height
        val pngBytes = try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG encoding returned false"
                }
                output.toByteArray()
            }
        } catch (error: Exception) {
            throw com.framemark.native.workflow.EngineFailure("FM-E-LOGO-003", "所选 Logo 图片转换失败", error)
        } finally {
            bitmap.recycle()
        }
        val alias = logoEngineKey(key)
        val directory = File(data.brands)
        val target = File(directory, "$alias.png")
        val pngHash = sha256(pngBytes)
        synchronized(irLogoStageLock) {
            if (!target.isFile || !sha256(target.readBytes()).equals(pngHash, ignoreCase = true)) {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw com.framemark.native.workflow.EngineFailure(
                        "FM-E-LOGO-004",
                        "无法创建 Logo 运行时资源目录",
                    )
                }
                val temporary = File(directory, "$alias.png.tmp-${System.nanoTime()}")
                try {
                    temporary.outputStream().use { it.write(pngBytes) }
                    if (target.exists() && !target.delete()) {
                        throw IllegalStateException("Could not replace invalid staged logo")
                    }
                    if (!temporary.renameTo(target)) {
                        throw IllegalStateException("Could not commit staged logo")
                    }
                } catch (error: Exception) {
                    temporary.delete()
                    throw com.framemark.native.workflow.EngineFailure(
                        "FM-E-LOGO-004",
                        "无法保存所选 Logo 运行时资源",
                        error,
                    )
                }
            }
        }
        FmLog.info("FM_ANDROID_LOGO") {
            "assetKey=$key alias=$alias sourceSha256=${sha256(sourceBytes)} " +
                "pngSha256=$pngHash size=${width}x${height} exact=true"
        }
        return alias
    }

    private fun logoEngineKey(assetKey: String): String = "fmlogo_${sha256(assetKey.toByteArray(Charsets.UTF_8))}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
        FmLog.info("FM_NATIVE_EDGE") { "mode=$mode src=${source.substringAfterLast('/')} sourceLong=$long canvas=$edge" }
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
        if (own) {
            FmLog.info("FM_EXIF_SOURCE") { "photo=${file.name} ownApp1=true src=${file.name}" }
            return photoPath
        }
        // HEIC 导入时像素被烤成 PNG，容器里的 Exif 项改写成了同名的侧车 JPEG
        // （`files/exif/<photo>.jpg`，只含 APP1）。引擎只认 JPEG APP1，就交给它。
        val sidecar = exifSidecarFor(file)
        val usable = sidecar != null && ExifProbe.isJpeg(sidecar) && ExifProbe.hasApp1(sidecar)
        FmLog.info("FM_EXIF_SOURCE") {
            "photo=${file.name} ownApp1=false sidecar=${sidecar?.name ?: "-"} usable=$usable " +
                "src=${if (usable) sidecar?.name else "<absent>"}"
        }
        return if (usable) sidecar!!.absolutePath else ""
    }

    /** `files/photos/user-x.png` → `files/exif/user-x.jpg`；不存在时 null。 */
    private fun exifSidecarFor(photo: File): File? {
        val filesDir = photo.parentFile?.parentFile ?: return null
        val candidate = File(File(filesDir, "exif"), photo.nameWithoutExtension + ".jpg")
        return candidate.takeIf { it.isFile && it.length() > 0 }
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
        FmLog.info("FM_GALLERY_PUBLISH") { "mime=$mime name=${file.name} uri=$uri" }
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
        FmLog.info("FM_ANDROID_EXPORT") { "$row" }
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
