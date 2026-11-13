package com.framemark.native.workflow

import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.CropGeometry
import com.framemark.native.product.PhotoCrop
import com.framemark.native.product.PhotoCropParams
import com.framemark.native.product.cropJpegTo
import com.framemark.native.product.photoSourceSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog

/** Structured export failure shown to the user; the code follows ARCH §7.2. */
class EngineFailure(
    val code: String,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception("[$code] $userMessage", cause)

/**
 * The pipeline stages one export really steps through, and the progress-bar value
 * each **completion** means (motion batch 2, 2026-11-14).
 *
 * The engine renders a frame in a single blocking call and reports no byte-level
 * progress, so a truthful determinate bar has exactly these stops — the fraction is
 * "stages completed / 4", **not** a time estimate. The render dominates the wall
 * clock; the bar refuses to invent motion inside it (motion spec §3.1 #8: an
 * indeterminate shimmer is decoration on a value the user is waiting for).
 *
 * The stages are invoked from `NativeEngine.exportCard` on the caller's coroutine
 * context, so a UI callback may write Compose state directly.
 */
enum class ExportStage(val fraction: Float) {
    /** Export accepted: naming, the temp file and the engine call are ahead. */
    Started(0f),

    /** The engine finished writing the frame (fallback pre-crop / HDR wrap follow). */
    Rendered(0.25f),

    /** Bytes measured and fingerprinted; the commit gate is next. */
    Prepared(0.5f),

    /** The file is committed at its final name. */
    Committed(0.75f),

    /** Publication (share / album write) returned; the export is done. */
    Published(1f),
}

/**
 * The design inputs the engine actually consumes (render-v1 `design` object).
 *
 * [logoBrand] is the brand-picker key, not image bytes: resolving it to a logo
 * asset is platform work (the desktop reads it out of the runtime's logo set),
 * so the shared model stays free of any asset path.
 */
data class DesignSnapshot(
    val cardId: String,
    val styleId: String?,
    val params: Map<String, Any?>,
    val text: Map<String, Any?>,
    val logoBrand: String? = null,
)

/** Axis-aligned rect in image pixels, top-left origin (see [NativeImage.photoRect]). */
data class ImageRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

/**
 * Where a native render landed, plus the engine-reported duration.
 *
 * [photoRect] is the engine's photo draw box in **pixels of this image**
 * (top-left origin, same coordinate space as [width]×[height]), so the UI can
 * place an overlay relative to the photo without knowing the canvas scale.
 * Null when the platform/engine did not report it (desktop today).
 *
 * [photoSrc] is the region of the **source photo file** the engine really
 * sampled, x/y/w/h in source pixels (top-left origin). It is the engine's own
 * answer to "which part of the photo survived the crop", so no caller has to
 * re-derive the crop and a pre-crop in Kotlin can never compound with it. Null
 * when the engine did not report it (an older binary); the export then falls
 * back to the Kotlin crop computation.
 */
data class NativeImage(
    val path: String,
    val width: Int,
    val height: Int,
    val ms: Long,
    val photoRect: ImageRect? = null,
    /** User-visible caveat about this render (e.g. a template-ratio crop). */
    val note: String? = null,
    /** Renderer-reported text rows in this image's pixel coordinate space. */
    val textSlots: List<TextSlot> = emptyList(),
    /** Source pixels the renderer sampled for the photo; see [NativeImage.photoSrc]. */
    val photoSrc: ImageRect? = null,
)

/**
 * render-v1 `overlay`: a user watermark drawn above the photo and below the
 * template's border/text. The rect is normalised to the photo draw box; the
 * image is contained and centred inside it and [opacity] multiplies its alpha.
 */
data class OverlaySpec(
    val path: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val opacity: Float,
    /** Degrees clockwise around the dst centre; 0 keeps the legacy byte path. */
    val angle: Float = 0f,
)

/**
 * Platform renderer behind the frozen render-v1 contract. The desktop installs
 * an implementation that drives the native engine binaries; platforms without
 * one keep the placeholder workflow (the sheets keep saying 示例).
 */
interface NativeRenderApi {
    fun supports(cardId: String): Boolean

    /** False when the platform renders previews only (Android X1: no export encoder yet). */
    fun supportsExport(cardId: String): Boolean = supports(cardId)

    /** True when the export encoder can emit WebP (desktop E5 worker 0.2.0+). */
    fun supportsWebpExport(): Boolean = false

    /** Platform publication performed only after the local file has committed. */
    suspend fun exportCommitted(path: String): String? = null

    suspend fun render(
        cardId: String,
        design: DesignSnapshot,
        mode: String,
        maxEdge: Int?,
        format: SampleFormat?,
        quality: Int?,
        longEdge: Int?,
        outputPath: String?,
        webpLossless: Boolean = true,
        overlay: OverlaySpec? = null,
        /**
         * Per-call photo override (batch pairing). Null keeps the current
         * [PhotoSelection]; EXIF/orientation rules apply to the override exactly
         * like a normally picked photo.
         */
        photoPath: String? = null,
        /**
         * True only for the bounded preview tier that tracks a pointer during a
         * drag. Engines may decode/render that frame from a reduced source, so it
         * must never be set for a final frame, an export or an evidence render.
         */
        interactive: Boolean = false,
    ): NativeImage
}

object NativeEngine {
    fun supports(cardId: String): Boolean = WorkflowEnv.nativeRender?.supports(cardId) == true

    fun supportsExport(cardId: String): Boolean = WorkflowEnv.nativeRender?.supportsExport(cardId) == true

    fun supportsWebpExport(): Boolean = WorkflowEnv.nativeRender?.supportsWebpExport() == true

    /**
     * One export through the real engine. There is no placeholder fallback on any
     * platform: a missing engine, an unwired card or an unsupported format fails
     * with a structured [EngineFailure] the export sheet shows to the user.
     *
     * [onStage] reports the pipeline stages the export really completes ([ExportStage]);
     * it is optional so the batch queue and the desktop dialog keep their existing
     * single-call behaviour.
     */
    suspend fun export(
        card: LibraryCard,
        settings: ExportSettings,
        design: DesignSnapshot,
        overlay: OverlaySpec? = null,
        photoPath: String? = null,
        nameIndex: Int = 1,
        photoDisplayName: String? = null,
        outputDirectory: String? = null,
        onOutputPrepared: ((path: String, size: Long, sha256: String) -> Boolean)? = null,
        onStage: ((ExportStage) -> Unit)? = null,
    ): ExportResult {
        val api = WorkflowEnv.nativeRender
        val platform = WorkflowEnv.platform
        if (platform == null) throw EngineFailure("FM-E-IO-003", "本地存储不可用，无法导出")
        if (api == null) throw EngineFailure("FM-E-ENGINE-001", "渲染组件未安装或无法启动，无法导出")
        if (!api.supportsExport(card.id)) {
            throw EngineFailure("FM-E-ENGINE-002", "这个边框暂时还不支持导出，请换一个边框试试")
        }
        if (settings.format == ExportFormat.WebP && !api.supportsWebpExport()) {
            throw EngineFailure("FM-E-EXPORT-001", "当前版本不支持 WebP 导出，请改用 JPEG 或 PNG")
        }
        return exportCard(
            cardId = card.id,
            cardName = card.name,
            styleId = card.systemStyle,
            settings = settings,
            design = design,
            overlay = overlay,
            photoPath = photoPath,
            nameIndex = nameIndex,
            photoDisplayName = photoDisplayName,
            outputDirectory = outputDirectory,
            onOutputPrepared = onOutputPrepared,
            onStage = onStage,
        )
    }

    /**
     * Real-engine export addressed by card metadata instead of a [LibraryCard].
     * Used by the batch queue after a restart, when the library card object may
     * not be loaded yet. Throws [EngineFailure] when the engine cannot export this
     * card; the batch item then becomes retryable instead of a placeholder.
     */
    suspend fun exportCard(
        cardId: String,
        cardName: String,
        styleId: String?,
        settings: ExportSettings,
        design: DesignSnapshot,
        overlay: OverlaySpec? = null,
        photoPath: String? = null,
        nameIndex: Int = 1,
        photoDisplayName: String? = null,
        outputDirectory: String? = null,
        onOutputPrepared: ((path: String, size: Long, sha256: String) -> Boolean)? = null,
        onStage: ((ExportStage) -> Unit)? = null,
    ): ExportResult {
        val api = WorkflowEnv.nativeRender
            ?: throw EngineFailure("FM-E-ENGINE-001", "渲染组件未安装或无法启动，无法导出")
        val platform = WorkflowEnv.platform
            ?: throw EngineFailure("FM-E-IO-003", "本地存储不可用，无法导出")
        if (!api.supportsExport(cardId)) {
            throw EngineFailure("FM-E-ENGINE-002", "这张边框未接入桌面引擎，无法导出")
        }
        onStage?.invoke(ExportStage.Started)
        val initialPath = ExportNaming.exportPath(
            platform = platform,
            pattern = settings.filenamePattern,
            cardName = cardName,
            photoName = ExportNaming.photoNameFor(
                settings.filenamePattern,
                ExportNaming.photoStem(photoDisplayName ?: photoPath),
                cardId,
            ),
            extension = settings.format.extension,
            index = nameIndex,
            outputDirectory = outputDirectory,
        )
        var temporaryPath = withContext(Dispatchers.IO) {
            temporaryPath(platform, initialPath, settings.format.extension)
        }
        var committedPath: String? = null
        // Every temporary this call owns, deleted in the `finally` below on success,
        // failure and cancellation alike. A committed export temp has already been
        // moved away by `commitExport`, so deleting its old name is a no-op.
        val scratchPaths = LinkedHashSet<String>()

        suspend fun renderTo(exportPath: String, sourcePhoto: String?): NativeImage = api.render(
            cardId = cardId,
            design = design,
            mode = "export",
            maxEdge = null,
            format = settings.format.toSampleFormat(),
            quality = settings.quality,
            longEdge = settings.effectiveLongEdge,
            outputPath = exportPath,
            webpLossless = settings.webpLossless,
            overlay = overlay,
            photoPath = sourcePhoto,
        )

        suspend fun renderOrFail(exportPath: String, sourcePhoto: String?): NativeImage = try {
            renderTo(exportPath, sourcePhoto)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            // Never relabel a cancelled run as an engine failure: the batch queue
            // depends on seeing the real cancellation.
            throw c
        } catch (t: Throwable) {
            if (t is EngineFailure) throw t
            // `userMessage` is what the export dialog and the phone notice print, so it
            // cannot be `t.message`: an engine-side throwable carries English text and
            // can name the runtime binary (`IR 引擎缺失（bin/e2b.exe）`). The raw text
            // goes to the log (existing tag — a new `FM_` literal is forbidden), and the
            // cause keeps it for the stack trace.
            FmLog.warn("FM_NATIVE_EXPORT") { "render failed ${t::class.simpleName}: ${t.message}" }
            throw EngineFailure("FM-E-ENGINE-002", "这张照片没能渲染成功，请重试。", t)
        }

        try {
            // ---- user crop -------------------------------------------------
            // The engine applies `photoCropX/Y/Zoom` itself and reports the source
            // rectangle it sampled (`NativeImage.photoSrc`), so this path must not
            // pre-crop the photo on top of it — two crops would compound. The
            // Kotlin pre-crop survives only as the fallback for an engine that does
            // not report that rectangle (an older binary): there it is the only way
            // the crop can reach the renderer at all.
            val crop = PhotoCropParams.read(design.params)
            var renderPhotoPath = photoPath
            var appliedCrop = PhotoCrop.Default
            // Only the fallback pre-crop produces one; the engine path reports its
            // own rectangle through `NativeImage.photoSrc` instead.
            var cropRect: DoubleArray? = null

            suspend fun preCropFor(boxWidth: Double, boxHeight: Double): Boolean {
                // Starts from the original photo again, so a failure below leaves the
                // frame drawn from the photo it really used.
                renderPhotoPath = photoPath
                cropRect = null
                if (crop.isDefault) return false
                val source = photoPath?.takeIf { it.isNotBlank() } ?: return false
                val size = withContext(Dispatchers.IO) { photoSourceSize(source) } ?: return false
                if (size.size < 2 || size[0] <= 0 || size[1] <= 0) return false
                if (!CropGeometry.cropsSource(size[0], size[1], boxWidth, boxHeight)) return false
                val rect = CropGeometry.region(size[0], size[1], boxWidth, boxHeight, crop)
                    ?: return false
                val croppedPath = withContext(Dispatchers.IO) { cropTemporaryPath(platform, initialPath) }
                // Registered before the write so a cancelled or failed write is cleaned
                // up by the same `finally` that would have deleted a finished copy.
                scratchPaths += croppedPath
                if (!withContext(Dispatchers.IO) { cropJpegTo(source, rect, croppedPath) }) return false
                renderPhotoPath = croppedPath
                cropRect = rect
                return true
            }

            var image = renderOrFail(temporaryPath, renderPhotoPath)

            // Fallback only: an engine that reports `photoSrc` already applied the
            // crop, and the photo it was handed must stay the original file.
            if (!crop.isDefault && image.photoSrc == null) {
                // The rendered box is the truth: crop to the box the engine really
                // drew, so the fallback needs no recorded preview aspect and can
                // never be planned against a stale one.
                val box = image.photoRect
                if (box != null && box.w > 0f && box.h > 0f &&
                    preCropFor(box.w.toDouble(), box.h.toDouble())
                ) {
                    val secondPath = withContext(Dispatchers.IO) {
                        temporaryPath(platform, initialPath, settings.format.extension)
                    }
                    scratchPaths += temporaryPath
                    temporaryPath = secondPath
                    image = renderOrFail(secondPath, renderPhotoPath)
                    // Only a crop that really reached the renderer may reach the gain map.
                    appliedCrop = crop
                }
            }

            // Stage marker: the engine has returned its frame (the fallback pre-crop,
            // the HDR wrap and the commit are what remains).
            onStage?.invoke(ExportStage.Rendered)

            // Ultra HDR: the engine has written the SDR rendition. When the settings ask
            // for HDR and the source photo carries a reusable gain map, the temporary
            // file is wrapped in place *before* it is committed, so the commit keeps its
            // atomic semantics and any failure below simply leaves the SDR bytes.
            if (settings.preserveHdr &&
                settings.format == com.framemark.native.product.ExportFormat.Jpeg
            ) {
                val rect = image.photoRect
                if (rect != null && !photoPath.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        val source = runCatching { platform.readBytes(photoPath) }.getOrNull()
                        val sdr = runCatching { platform.readBytes(temporaryPath) }.getOrNull()
                        val wrapped = if (source != null && sdr != null) {
                            runCatching {
                                com.framemark.native.product.UltraHdrExport.wrap(
                                    source = source,
                                    sdrJpeg = sdr,
                                    canvasWidth = image.width,
                                    canvasHeight = image.height,
                                    photoRect = doubleArrayOf(
                                        rect.x.toDouble(), rect.y.toDouble(),
                                        rect.w.toDouble(), rect.h.toDouble(),
                                    ),
                                    // The region the SDR render was cropped to, so
                                    // highlights land where the pixels are.
                                    crop = appliedCrop,
                                    // The engine's own answer wins: it is the region it
                                    // really sampled. It only describes the original
                                    // photo when Kotlin did not hand the renderer a
                                    // pre-cropped copy — a rect in that copy's pixels
                                    // cannot be applied to the original bytes.
                                    photoSrc = if (cropRect == null) {
                                        image.photoSrc?.let { src ->
                                            doubleArrayOf(
                                                src.x.toDouble(), src.y.toDouble(),
                                                src.w.toDouble(), src.h.toDouble(),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                        when {
                            wrapped == null -> FmLog.info("FM_ULTRA_HDR_SKIP") {
                                "card=$cardId reason=no-reusable-gain-map"
                            }
                            !platform.writeBytes(temporaryPath, wrapped) -> FmLog.warn("FM_ULTRA_HDR_WRITE_FAIL") {
                                "card=$cardId kept as SDR"
                            }
                            else -> FmLog.info("FM_ULTRA_HDR_READY") {
                                "card=$cardId bytes=${wrapped.size} canvas=${image.width}x${image.height}"
                            }
                        }
                    }
                }
            }

            val prepared = withContext(Dispatchers.IO) {
                val bytes = platform.fileSize(temporaryPath)
                if (bytes <= 0L || !platform.exists(temporaryPath)) {
                    throw EngineFailure("FM-E-IO-003", "渲染结果没有写入临时文件")
                }
                bytes to platform.fileFingerprint(temporaryPath)
            }
            val bytes = prepared.first
            val sha256 = prepared.second
            if (onOutputPrepared != null && sha256.isNullOrBlank()) {
                throw EngineFailure("FM-E-IO-003", "无法校验待提交成片，已停止导出")
            }
            onStage?.invoke(ExportStage.Prepared)

            var collisionNumber = 1
            var candidate = initialPath
            var attempts = 0
            while (attempts++ < 100) {
                // A present path is an ordinary name collision. Never use it as
                // evidence of success or ask the renderer to overwrite it.
                if (withContext(Dispatchers.IO) { platform.exists(candidate) }) {
                    collisionNumber++
                    candidate = ExportNaming.candidatePath(
                        platform = platform,
                        pattern = settings.filenamePattern,
                        cardName = cardName,
                        photoName = ExportNaming.photoNameFor(
                            settings.filenamePattern,
                            ExportNaming.photoStem(photoDisplayName ?: photoPath),
                            cardId,
                        ),
                        extension = settings.format.extension,
                        index = nameIndex,
                        collisionNumber = collisionNumber,
                        outputDirectory = outputDirectory,
                    )
                    continue
                }

                // Crash window W3-09: render temp ready, receipt callback not yet called.
                crashCheckpoint(platform, "prereceipt")

                if (onOutputPrepared != null && !onOutputPrepared(candidate, bytes, sha256!!)) {
                    throw EngineFailure("FM-E-IO-003", "批量导出回执未能保存，成片尚未提交")
                }

                // Crash window W3-10: receipt persisted, commitExport not yet called.
                crashCheckpoint(platform, "receipt")

                val committed = try {
                    withContext(Dispatchers.IO) { platform.commitExport(temporaryPath, candidate) }
                } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    throw EngineFailure("FM-E-IO-003", "无法提交导出文件：${t.message ?: "存储错误"}", t)
                }
                if (committed) {
                    committedPath = candidate
                    break
                }

                collisionNumber++
                candidate = ExportNaming.candidatePath(
                    platform = platform,
                    pattern = settings.filenamePattern,
                    cardName = cardName,
                    photoName = ExportNaming.photoNameFor(
                        settings.filenamePattern,
                        ExportNaming.photoStem(photoDisplayName ?: photoPath),
                        cardId,
                    ),
                    extension = settings.format.extension,
                    index = nameIndex,
                    collisionNumber = collisionNumber,
                    outputDirectory = outputDirectory,
                )
            }
            val path = committedPath ?: throw EngineFailure("FM-E-IO-003", "可用文件名冲突过多，未覆盖任何已有文件")
            onStage?.invoke(ExportStage.Committed)
            val publishNote = try {
                api.exportCommitted(path)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (t: Throwable) {
                "本地文件已保存；后续发布失败：${t.message ?: "未知错误"}"
            }
            // Published either way: the local file is committed and the export is done;
            // a publication failure is a note, not a failed save.
            onStage?.invoke(ExportStage.Published)
            val note = listOfNotNull(image.note, publishNote).filter { it.isNotBlank() }.joinToString("；").ifBlank { null }
            val outputBytes = withContext(Dispatchers.IO) { platform.fileSize(path) }
            FmLog.info("FM_NATIVE_EXPORT") {
                "card=$cardId path=$path ${image.width}x${image.height} ms=${image.ms} " +
                    "crop=${cropRect?.joinToString { it.toInt().toString() } ?: "-"}"
            }
            return ExportResult(
                path = path,
                fileName = path.substringAfterLast('/').substringAfterLast('\\'),
                bytes = outputBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                width = image.width,
                height = image.height,
                formatLabel = settings.format.label,
                note = note,
                source = ExportSource(photoPath, design, settings, overlay, nameIndex,
                    outputDirectory ?: platform.exportDir, photoDisplayName),
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                platform.deleteExportTemporary(temporaryPath)
                scratchPaths.forEach { platform.deleteExportTemporary(it) }
            }
        }
    }

    private fun temporaryPath(platform: WorkflowPlatform, finalPath: String, extension: String): String {
        val stem = finalPath.removeSuffix(".$extension")
        repeat(16) {
            val random = kotlin.random.Random.nextInt().toUInt().toString(16)
            val candidate = "$stem.fm-tmp-$random.$extension"
            if (!platform.exists(candidate)) return candidate
        }
        throw EngineFailure("FM-E-IO-003", "无法创建唯一临时导出路径")
    }

    /**
     * Scratch name for the pre-cropped photo copy: it lives next to the export
     * (same directory, so no extra mount is needed) and is never committed.
     */
    private fun cropTemporaryPath(platform: WorkflowPlatform, nearPath: String): String {
        val stem = nearPath.substringBeforeLast('.')
        repeat(16) {
            val random = kotlin.random.Random.nextInt().toUInt().toString(16)
            val candidate = "$stem.fm-crop-$random.jpg"
            if (!platform.exists(candidate)) return candidate
        }
        throw EngineFailure("FM-E-IO-003", "无法创建唯一临时裁切路径")
    }

    /**
     * W3 crash-window checkpoint (debug-only).  When `LaunchOptions.crashWindow`
     * equals [stage], write a marker file to `LaunchOptions.crashMarkerDir` and
     * spin so an external process kill can be proven to land inside the window.
     * Does nothing when the crash-window hook is absent (normal and release builds).
     */
    internal suspend fun crashCheckpoint(platform: WorkflowPlatform, stage: String) {
        val window = LaunchOptions.crashWindow ?: return
        if (window != stage) return
        val markerDir = LaunchOptions.crashMarkerDir ?: return
        withContext(Dispatchers.IO) {
            val markerPath = "$markerDir/marker-$stage"
            platform.writeTextFile(markerPath, "$stage ${System.currentTimeMillis()}")
            FmLog.info("FM_CRASH_WINDOW") { "$stage marker=$markerPath" }
            if (LaunchOptions.crashResumeOnMarkerRemoval) {
                while (platform.exists(markerPath)) Thread.sleep(100)
            } else {
                while (true) Thread.sleep(200)
            }
        }
    }

    /**
     * Public entry-point for the `beforeDone` checkpoint from `PhotoBatch.kt`.
     * Delegates to [crashCheckpoint]; does nothing unless `LaunchOptions.crashWindow`
     * equals [stage].
     */
    suspend fun crashCheckpointBefore(platform: WorkflowPlatform, stage: String) =
        crashCheckpoint(platform, stage)

    /** Preview render for the stage; null when the card or platform is not wired. */
    suspend fun preview(
        card: LibraryCard,
        design: DesignSnapshot,
        maxEdge: Int,
        overlay: OverlaySpec? = null,
        photoPath: String? = null,
        interactive: Boolean = false,
    ): NativeImage? {
        val api = WorkflowEnv.nativeRender ?: return null
        if (!api.supports(card.id)) return null
        val image = api.render(
            cardId = card.id,
            design = design,
            mode = "preview",
            maxEdge = maxEdge,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = null,
            overlay = overlay,
            photoPath = photoPath,
            interactive = interactive,
        )
        if (!interactive) return image
        // Decision 2026-11-14 (palette cards): the interactive tier may decode the
        // source photo at a bounded edge, so its report is a *sample* — a 色卡
        // template's hex labels differ from the final frame while every geometry
        // field stays identical. Mark the whole report provisional at this one choke
        // point; `TextSlotRegistry` then keeps the last final values, and the 文字
        // page omits a read-only value that never became final.
        return image.copy(textSlots = image.textSlots.map { it.copy(provisional = true) })
    }

    /**
     * Design values keyed by the engine's own field names. Only controls the user
     * actually changed are sent, so the untouched defaults stay the engine's.
     */
    /** Same mapping as the session overload, but from a photo's stored values. */
    fun designOf(card: LibraryCard, model: InspectorCardModel?, values: Map<String, Any>): DesignSnapshot {
        val params = LinkedHashMap<String, Any?>()
        val text = LinkedHashMap<String, Any?>()
        model?.controls?.forEach { control ->
            val value = values[control.key] ?: return@forEach
            val declared = when (val initial = control.initial) {
                is com.framemark.native.inspector.ControlValue.Flag -> initial.value
                is com.framemark.native.inspector.ControlValue.Num -> initial.value
                is com.framemark.native.inspector.ControlValue.Str -> initial.value
            }
            if (value == declared) return@forEach
            when {
                control.param.startsWith("params.") -> params[control.param.removePrefix("params.")] = value
                control.param.startsWith("text.") -> text[control.param.removePrefix("text.")] = value
                control.param == "theme" -> params["theme"] = value
            }
        }
        return DesignSnapshot(cardId = card.id, styleId = card.systemStyle, params = params, text = text)
    }

    fun designOf(card: LibraryCard, model: InspectorCardModel?, session: DesignSession): DesignSnapshot {
        val params = LinkedHashMap<String, Any?>()
        val text = LinkedHashMap<String, Any?>()
        if (model != null) {
            val overrides = session.overrides(card.id)
            model.controls.forEach { control ->
                val value = overrides[control.key] ?: return@forEach
                when {
                    control.param.startsWith("params.") -> params[control.param.removePrefix("params.")] = value
                    control.param.startsWith("text.") -> text[control.param.removePrefix("text.")] = value
                    control.param == "theme" -> params["theme"] = value
                }
            }
        }
        return DesignSnapshot(card.id, card.systemStyle, params, text)
    }
}
