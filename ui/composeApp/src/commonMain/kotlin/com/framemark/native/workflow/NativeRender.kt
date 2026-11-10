package com.framemark.native.workflow

import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
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
    ): ExportResult {
        val api = WorkflowEnv.nativeRender
        val platform = WorkflowEnv.platform
        if (platform == null) throw EngineFailure("FM-E-IO-003", "本地存储不可用，无法导出")
        if (api == null) throw EngineFailure("FM-E-ENGINE-001", "渲染组件未安装或无法启动，无法导出")
        if (!api.supportsExport(card.id)) {
            throw EngineFailure("FM-E-ENGINE-002", "这张边框未接入桌面引擎，无法导出")
        }
        if (settings.format == ExportFormat.WebP && !api.supportsWebpExport()) {
            throw EngineFailure("FM-E-EXPORT-001", "当前引擎不支持 WebP 导出，请改用 JPEG 或 PNG")
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
    ): ExportResult {
        val api = WorkflowEnv.nativeRender
            ?: throw EngineFailure("FM-E-ENGINE-001", "渲染组件未安装或无法启动，无法导出")
        val platform = WorkflowEnv.platform
            ?: throw EngineFailure("FM-E-IO-003", "本地存储不可用，无法导出")
        if (!api.supportsExport(cardId)) {
            throw EngineFailure("FM-E-ENGINE-002", "这张边框未接入桌面引擎，无法导出")
        }
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
        val temporaryPath = withContext(Dispatchers.IO) {
            temporaryPath(platform, initialPath, settings.format.extension)
        }
        var committedPath: String? = null
        val image = try {
            api.render(
                cardId = cardId,
                design = design,
                mode = "export",
                maxEdge = null,
                format = settings.format.toSampleFormat(),
                quality = settings.quality,
                longEdge = settings.effectiveLongEdge,
                outputPath = temporaryPath,
                webpLossless = settings.webpLossless,
                overlay = overlay,
                photoPath = photoPath,
            )
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            // Never relabel a cancelled run as an engine failure: the batch queue
            // depends on seeing the real cancellation.
            withContext(NonCancellable + Dispatchers.IO) { platform.deleteExportTemporary(temporaryPath) }
            throw c
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { platform.deleteExportTemporary(temporaryPath) }
            if (t is EngineFailure) throw t
            throw EngineFailure("FM-E-ENGINE-002", t.message ?: "渲染失败", t)
        }

        try {
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
            val publishNote = try {
                api.exportCommitted(path)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (t: Throwable) {
                "本地文件已保存；后续发布失败：${t.message ?: "未知错误"}"
            }
            val note = listOfNotNull(image.note, publishNote).filter { it.isNotBlank() }.joinToString("；").ifBlank { null }
            val outputBytes = withContext(Dispatchers.IO) { platform.fileSize(path) }
            FmLog.info("FM_NATIVE_EXPORT") { "card=$cardId path=$path ${image.width}x${image.height} ms=${image.ms}" }
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
            withContext(NonCancellable + Dispatchers.IO) { platform.deleteExportTemporary(temporaryPath) }
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
    ): NativeImage? {
        val api = WorkflowEnv.nativeRender ?: return null
        if (!api.supports(card.id)) return null
        return api.render(
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
        )
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
