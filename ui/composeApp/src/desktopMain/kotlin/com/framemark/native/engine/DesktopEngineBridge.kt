package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.EngineArgs
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.product.PreviewTier
import com.framemark.native.workflow.isWritableTextSlotKey
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt
import com.framemark.native.product.FmLog

data class EngineRenderResult(
    val path: String,
    val width: Int,
    val height: Int,
    val ms: Long,
    val rawWidth: Int,
    val rawHeight: Int,
    val photoRect: DoubleArray,
    val textSlots: List<TextSlot>,
    val warnings: List<String>,
    val responseJson: String,
    /**
     * Engine-reported `photo_src`: the source-photo pixels the render sampled
     * (x, y, w, h) in the photo's **display** (EXIF-applied) frame — the same frame
     * `PhotoOrientation.displaySize` reports, never rescaled with the output. Null
     * when the engine did not report it, which is the signal that the Kotlin crop
     * fallback must run.
     */
    val photoSrc: DoubleArray? = null,
)

/** Compatibility import for existing desktop callers; the model is now shared. */
typealias TextSlot = com.framemark.native.workflow.TextSlot

/**
 * render-v1 bridge over the current engine binaries (process transport).
 *
 * The UI and the contract verifier call the same [render] path; the verifier
 * additionally reads the traced request/response documents it writes.
 */
class DesktopEngineBridge(
    val catalog: EngineCatalog = EngineCatalog.load(),
    e2bExe: String? = null,
    e4handExe: String? = null,
    workDir: String? = null,
) {
    private val layout = RuntimePaths.layout()
    private val e2bExe: String = e2bExe ?: env("FM_E2B_EXE") ?: layout.e2b?.path
        ?: throw EngineConfigException("FM-E-ENGINE-001", "IR 引擎缺失（bin/e2b.exe）")
    private val e4handExe: String = e4handExe ?: env("FM_E4HAND_EXE") ?: layout.e4hand?.path
        ?: throw EngineConfigException("FM-E-ENGINE-001", "hand 引擎缺失（bin/e4hand.exe）")
    private val work = File(
        workDir ?: env("FM_BRIDGE_WORK") ?: (System.getProperty("java.io.tmpdir") + "/framemark-x1"),
    ).apply { mkdirs() }
    private val workingDir: File = RuntimePaths.engineWorkingDir(layout)
    private val e5 = E5Worker()

    private data class PreviewWorkerKey(
        val backend: String,
        val cardId: String,
        val photoPath: String,
    )

    private class PreviewWorkerConnection(
        val key: PreviewWorkerKey,
        private val process: Process,
    ) {
        private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

        val isAlive: Boolean
            get() = process.isAlive

        fun request(requestArgs: List<String>): RunResult {
            writer.write(BridgeJson.write(requestArgs))
            writer.newLine()
            writer.flush()
            val output = StringBuilder()
            while (true) {
                val line = reader.readLine()
                    ?: return RunResult(-1, (output.toString() + "\nworker EOF").takeLast(65_536))
                output.appendLine(line)
                when {
                    line.startsWith("FM_PREVIEW_WORKER_STAGE") ||
                        line.startsWith("FM_ENGINE_PREVIEW_SIZE") -> println(line)
                }
                if (line.startsWith("FM_PREVIEW_WORKER_DONE")) {
                    val code = Regex("""\bcode=(-?\d+)""")
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: -1
                    return RunResult(code, output.toString().takeLast(65_536))
                }
            }
        }

        fun close() {
            // Kill first: closing a stream may take its read/write monitor and
            // strand a worker request blocked in BufferedReader.readLine().
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            runCatching { writer.close() }
            runCatching { reader.close() }
        }
    }

    private val previewWorkerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "framemark-preview-worker").apply { isDaemon = true }
    }
    private val previewWorkerLock = Any()
    private var previewWorker: PreviewWorkerConnection? = null

    init {
        sweepWorkDir()
        Runtime.getRuntime().addShutdownHook(Thread({
            synchronized(previewWorkerLock) {
                previewWorker?.close()
                previewWorker = null
            }
        }, "framemark-preview-worker-shutdown"))
    }

    fun supports(cardId: String): Boolean = catalog.supports(cardId)

    fun cardIds(): List<String> = catalog.cards().map { it.cardId }

    fun render(
        cardId: String,
        params: Map<String, Any?>,
        text: Map<String, Any?>,
        mode: String,
        maxEdge: Int?,
        format: String?,
        quality: Int?,
        longEdge: Int?,
        outputPath: String?,
        webpLossless: Boolean = true,
        overlay: OverlaySpec? = null,
        photoPath: String? = null,
        exifSource: String? = null,
        logo: EngineLogo? = null,
        /** Brand-picker key (`logos.json` brand key) for IR cards; e2b reads it as `params.brandLogoFile`. */
        brandPick: String? = null,
        /**
         * Stage preview tier that tracks a pointer. IR previews may decode the
         * source photo at [previewSourceEdge] for this frame only; final frames,
         * exports and every evidence render keep the full-resolution path.
         */
        interactive: Boolean = false,
    ): EngineRenderResult {
        require(mode == "preview" || mode == "export") { "mode must be preview|export, got $mode" }
        val card = catalog.card(cardId)
            ?: throw EngineConfigException("FM-E-ENGINE-002", "卡片未接入桌面引擎", cardId = cardId)
        val id = UUID.randomUUID().toString().take(8)
        val raw = File(work, "raw-$id.png")
        val report = File(work, "report-$id.json")
        if (card.backend == "ir" && (raw.exists() || report.exists())) {
            throw EngineConfigException(
                "FM-E-RES-003",
                "IR 引擎临时文件路径冲突，拒绝复用既有结果",
                cardId = cardId,
                expectedPath = report.absolutePath,
            )
        }

        try {
            val photoAbs = photoPath?.let { File(it).absoluteFile.path } ?: catalog.photoPath
            if (!File(photoAbs).isFile) {
                throw EngineConfigException("FM-E-INPUT-001", "照片不存在或不可读", cardId = cardId, expectedPath = photoAbs)
            }
            val exifAbs = when {
                exifSource != null -> File(exifSource).absoluteFile.path
                photoPath != null -> photoAbs
                else -> catalog.exifSource
            }
            if (!File(exifAbs).isFile) {
                throw EngineConfigException("FM-E-INPUT-001", "EXIF 源不存在或不可读", cardId = cardId, expectedPath = exifAbs)
            }
            val overlayArgs = overlayArgs(overlay)

            val started = System.nanoTime()
            var nativeFit = false
            val argv = when (card.backend) {
                "ir" -> {
                    val args = mutableListOf(
                        e2bExe,
                        "--style", catalog.engineArg(card.style ?: error("card $cardId has no style file")),
                        "--photo", if (photoPath != null) photoAbs else catalog.engineArg(catalog.photoPackagePath),
                        "--exif-source", if (exifSource != null || photoPath != null) {
                            exifAbs
                        } else {
                            catalog.engineArg(catalog.exifPackagePath)
                        },
                        "--fonts", packageDataArg("fonts"),
                        "--decoded", packageDataArg("decoded"),
                        "--assets", packageDataArg("assets"),
                        "--t1b", packageDataArg("t1b/assets", fallbackRelative = "assets"),
                        "--out", raw.path,
                        "--report", report.path,
                    )
                    val irParams = if (brandPick != null && params["brandLogoFile"] == null) {
                        params + ("brandLogoFile" to brandPick)
                    } else {
                        params
                    }
                    prepareIrDesign(card, irParams, text, id)?.let { args += listOf("--design", it) }
                    // AU1-05: one argv element must never be splittable. `irParams` can
                    // carry a value that bypassed the preset codec (UI state, a
                    // hand-written project.json), and a quote inside it makes the OS hand
                    // the engine several arguments — so only an argv-safe literal goes
                    // out, and the engine keeps its own default otherwise.
                    EngineArgs.themeForArgv(params["theme"] as? String) { rejected ->
                        FmLog.info("FM_THEME_REJECTED") { "card=$cardId value=${rejected.take(64)} mode=drop" }
                    }?.let { args += listOf("--theme", it) }
                    args += overlayArgs
                    args
                }
                "hand" -> {
                    val cardsArg = prepareHandInputs(card, params, text, id, logo)
                    val args = mutableListOf(
                        e4handExe, "render",
                        "--card", card.engineId,
                        "--cards", cardsArg,
                        "--hand", dataArg(layout.handDir ?: throw EngineConfigException("FM-E-RES-001", "缺少 data/hand")),
                        "--fonts", dataArg(layout.handFontsDir ?: throw EngineConfigException("FM-E-RES-001", "缺少 data/fonts")),
                        "--out", raw.path,
                        "--report", report.path,
                    )
                    layout.handExtraFontsDir?.let { args += listOf("--extra-fonts", dataArg(it)) }
                    if (photoPath != null) args += listOf("--photo", photoAbs)
                    // A2: an export with no requested long edge is 原尺寸 — keep the
                    // whole photo and grow the template instead of cropping to its
                    // ratio. `nativeFit` remembers the flag so a template that cannot
                    // hold the photo can be re-run on the design path below.
                    if (mode == "export" && longEdge == null) {
                        nativeFit = true
                        args += listOf("--fit", "native")
                    }
                    args += overlayArgs
                    args
                }
                else -> throw EngineConfigException(
                    "FM-E-ENGINE-002",
                    "未知 backend '${card.backend}'",
                    cardId = cardId,
                )
            }

            if (mode == "preview" && maxEdge != null) {
                if (maxEdge <= 0) {
                    throw EngineConfigException("FM-E-INPUT-001", "预览长边必须为正整数", cardId = cardId)
                }
                // The native renderer now owns preview resizing. Passing the target
                // here avoids allocating and encoding a full-resolution raw canvas.
                argv += listOf("--preview-max-edge", maxEdge.toString())
                FmLog.info("FM_ENGINE_PREVIEW_SIZE") { "card=$cardId maxEdge=$maxEdge" }
                // Interactive tier: the engine decodes the photo at ~2x the
                // preview canvas instead of full resolution. The canvas, layout,
                // crop, logo and watermark geometry are unchanged (the engine
                // keeps solving in full-resolution photo pixels); only the
                // sampled pixels are smaller. Both backends take the flag: the IR
                // renderer decodes the photo through the codec, the hand renderer
                // decodes it and scales the canvas, so each one gets the same
                // pure edge request.
                if (interactive) {
                    val sourceEdge = PreviewTier.sourceEdge(maxEdge)
                    argv += listOf("--preview-source-edge", sourceEdge.toString())
                    FmLog.info("FM_ENGINE_PREVIEW_SIZE") {
                        "card=$cardId interactive=true edge=$maxEdge sourceEdge=$sourceEdge"
                    }
                }
            }

            val previewWorkerKey = if (mode == "preview") {
                PreviewWorkerKey(card.backend, card.cardId, photoAbs)
            } else {
                null
            }
            var run = if (previewWorkerKey != null) {
                FmLog.info("FM_ENGINE_TRANSPORT") {
                    "mode=worker card=$cardId backend=${card.backend} " +
                        "maxEdge=${maxEdge ?: "-"}"
                }
                runPreviewWorker(argv, cardId, previewWorkerKey)
            } else {
                exec(argv, cardId)
            }
            var nativeFitFellBack = false
            if (run.code != 0 && nativeFit && run.output.contains("E4H-FIT-NATIVE-")) {
                // The template's photo box cannot hold the whole photo (fixed-ratio
                // side/square layouts). Keep the template ratio, and carry the
                // caveat into the result instead of failing the export.
                FmLog.info("FM_NATIVE_FIT_FALLBACK") { "card=$cardId reason=${run.output.lines().firstOrNull { it.contains("E4H-FIT-NATIVE-") }?.take(200)}" }
                val retry = argv.toMutableList()
                val at = retry.indexOf("--fit")
                if (at >= 0) {
                    retry.removeAt(at)
                    retry.removeAt(at)
                }
                nativeFitFellBack = true
                run = exec(retry, cardId)
            }
            val engineMs = ((System.nanoTime() - started) / 1_000_000L)
            if (run.code != 0 || !raw.isFile) {
                throw EngineConfigException(
                    "FM-E-ENGINE-002",
                    "引擎渲染失败（exit ${run.code}）",
                    cardId = cardId,
                    cause = IllegalStateException(run.output.takeLast(1200)),
                )
            }

            FmLog.info("FM_ENGINE_RAW") { "path=${raw.path}" }
            // IR export is not successful unless this invocation produced a readable
            // report. Refuse a missing or malformed report rather than inferring success
            // from the raw PNG.
            val rep = if (card.backend == "ir") {
                try {
                    if (!report.isFile) throw IllegalStateException("IR report was not created")
                    parseReport(card, report)
                } catch (e: EngineConfigException) {
                    throw e
                } catch (e: Exception) {
                    throw EngineConfigException(
                        "FM-E-RES-003",
                        "IR 引擎报告缺失或不可解析",
                        cardId = cardId,
                        expectedPath = report.absolutePath,
                        cause = e,
                    )
                }
            } else {
                parseReport(card, report)
            }
            if (rep.requiredResourceErrors.isNotEmpty()) {
                throw EngineConfigException(
                    "FM-E-RES-002",
                    "IR 引擎报告包含必需素材缺失项",
                    cardId = cardId,
                    cause = IllegalStateException(rep.requiredResourceErrors.take(8).joinToString("; ")),
                )
            }
            val rawEdge = max(rep.width, rep.height)
            // Preview dimensions are finalized by the native engine. Applying maxEdge
            // again here would both add ImageIO work and scale the report twice.
            val requested = if (mode == "export") longEdge else null
            // D-1: 「指定长边」是**归一**，不是「只缩不放」。以前这里要求
            // `requested in 1 until rawEdge`，于是 1600×1245 的画布选 2048 导出仍然是
            // 1600×1245，而顶栏/弹层按长边算出 2048 —— 三处互相矛盾。现在与
            // `OutputSize.applyLongEdge`（右栏/顶栏/手机/弹层共用）逐字一致：
            // 目标 ≠ 当前长边就按 `目标/当前` 缩放，放大也用同一个 bicubic 重采样。
            val scale = if (requested != null && requested > 0 && requested != rawEdge) {
                requested.toDouble() / rawEdge
            } else {
                1.0
            }
            val outW = (rep.width * scale).roundToInt().coerceAtLeast(1)
            val outH = (rep.height * scale).roundToInt().coerceAtLeast(1)
            if (mode == "export") {
                FmLog.info("FM_EXPORT_SCALE") {
                    "card=$cardId raw=${rep.width}x${rep.height} " +
                        "requested=${requested ?: "-"} scale=${r3(scale)} out=${outW}x${outH}"
                }
            }

            val target = File(
                outputPath ?: File(work, "preview-$cardId-$id.${if (mode == "preview") "png" else format ?: "png"}").path,
            )
            target.parentFile?.mkdirs()
            val warnings = mutableListOf<String>()
            if (nativeFitFellBack) warnings += "该模板按版式比例裁切（不支持完整原尺寸）"
            if (brandPick != null && card.backend == "ir") {
                warnings += "品牌字标按所选品牌替换（IR 引擎品牌槽）"
            }
            val pngOut = format == null || format.equals("png", ignoreCase = true)
            if (mode == "preview") {
                // Preview rendering is its own path: engine -> downscale -> PNG. The
                // export encoder (E5 worker) is never involved in a preview.
                if (scale == 1.0 && target.path != raw.path) {
                    raw.copyTo(target, overwrite = true)
                } else {
                    val source = PhotoSizes.readSubsampled(raw, outW, outH)
                        ?: error("engine output is not a decodable image: ${raw.path}")
                    writePng(if (scale == 1.0) source else scaleImage(source, outW, outH), target)
                }
            } else if (pngOut && scale == 1.0 && target.path != raw.path) {
                // AU1-03: a full-size PNG export is already exactly the engine's bytes.
                // Move the file instead of decoding a 233 MB canvas into the JVM heap
                // just to re-encode the same pixels.
                moveOrCopy(raw, target)
                warnings += "PNG export re-uses the engine canvas (no re-encode)"
            } else {
                // Subsampled read: decode straight to ~the export size instead of the
                // whole canvas (the old `ImageIO.read(raw)` peak).
                val source = PhotoSizes.readSubsampled(raw, outW, outH)
                    ?: error("engine output is not a decodable image: ${raw.path}")
                val scaled = if (scale == 1.0) source else scaleImage(source, outW, outH)
                val webp = format.equals("webp", ignoreCase = true)
                if (pngOut) {
                    // E5 worker is JPEG-only; PNG stays on ImageIO until E5 exposes
                    // its L6 encoder through a separate process (GAP).
                    writePng(scaled, target)
                    warnings += "PNG export encoded by ImageIO (E5 worker protocol v1 is JPEG-only)"
                } else {
                    val exif = JpegExif.tiffBlock(File(exifAbs), outW, outH)
                    if (exif == null) warnings += "EXIF block not found in $exifAbs"
                    if (webp) {
                        e5.encodeWebp(scaled, target, work, exif, lossless = webpLossless, quality = 100)
                        warnings += if (webpLossless) "WebP VP8L lossless" else "WebP VP8 q100 lossy"
                    } else {
                        val jpegQuality = (quality ?: 100).coerceIn(60, 100)
                        e5.encodeJpeg(scaled, target, work, exif, quality = jpegQuality)
                    }
                }
            }
            val slots = bindTextSlots(card, rep, scale, warnings)

            val requestJson = normalizedRequest(card, params, text, mode, maxEdge, outW, outH, photoAbs)
            val responseJson = responseJson(
                path = target.path,
                width = outW,
                height = outH,
                photoRect = rep.photoRect.map { it * scale }.toDoubleArray(),
                slots = slots,
                ms = engineMs,
                warnings = warnings,
            )
            trace(card, mode, requestJson, responseJson)
            return EngineRenderResult(
                path = target.path,
                width = outW,
                height = outH,
                ms = engineMs,
                rawWidth = rep.width,
                rawHeight = rep.height,
                photoRect = rep.photoRect.map { it * scale }.toDoubleArray(),
                textSlots = slots,
                warnings = warnings,
                responseJson = responseJson,
                // Source-photo pixels: deliberately not multiplied by `scale`,
                // which only maps the canvas to the requested output size.
                photoSrc = rep.photoSrc,
            )
        } finally {
            // AU1-02: engine scratch files are deleted on both the success and
            // the failure path. Nothing downstream reads them after this call —
            // the finished pixels are already in `target` — so a render no longer
            // leaves a canvas-sized PNG plus a report behind on every export.
            discardTemp(raw)
            discardTemp(report)
        }
    }

    // -----------------------------------------------------------------------
    // slot binding: node identity, never array position for declared paths
    // -----------------------------------------------------------------------

    /** Binds report metadata only through explicit stable renderer identities. */
    private fun bindTextSlots(
        card: EngineCard,
        rep: RawReport,
        scale: Double,
        warnings: MutableList<String>,
    ): List<TextSlot> {
        fun slotFrom(id: String, draw: RawText, unmapped: Boolean, optional: Boolean, nodePath: String?) : TextSlot {
            val key = when (card.backend) {
                "ir" -> draw.path?.takeIf(::isWritableTextSlotKey)
                    ?: draw.key?.takeIf { draw.stable && isWritableTextSlotKey(it) }
                "hand" -> draw.key?.takeIf { draw.stable && isWritableTextSlotKey(it) }
                else -> null
            }
            return TextSlot(
                id = id,
                text = draw.text,
                x = draw.x * scale,
                y = draw.y * scale,
                w = draw.w * scale,
                h = draw.h * scale,
                fontSize = draw.fontSize?.let { it * scale },
                baseline = draw.baseline?.let { it * scale },
                lines = draw.lines,
                present = draw.present,
                unmapped = unmapped,
                optional = optional,
                nodePath = nodePath,
                key = key,
                raw = draw.raw,
                hidden = draw.hidden,
                stable = key != null,
                located = draw.located,
            )
        }

        fun absent(slot: EngineSlot): TextSlot {
            val key = slot.nodePath?.takeIf { card.backend == "ir" && isWritableTextSlotKey(it) }
            return TextSlot(
                id = slot.slotId,
                text = "",
                x = 0.0,
                y = 0.0,
                w = 0.0,
                h = 0.0,
                fontSize = null,
                baseline = null,
                lines = 0,
                present = false,
                unmapped = false,
                optional = slot.optional,
                nodePath = slot.nodePath,
                key = key,
                raw = "",
                hidden = false,
                stable = key != null,
                located = false,
            )
        }

        val declared = card.slots
        val bound = LinkedHashMap<String, TextSlot>()
        val extras = ArrayList<TextSlot>()
        val pathMode = card.backend == "ir" && (declared.any { it.nodePath != null } || rep.texts.any { it.path != null })

        when {
            pathMode -> {
                val byPath = HashMap<String, RawText>()
                for (text in rep.texts) text.path?.let { byPath[it] = text }
                val declaredPaths = declared.mapNotNull { it.nodePath }.toHashSet()
                for (slot in declared) {
                    val draw = slot.nodePath?.let { byPath[it] }
                    if (draw == null) {
                        warnings += "slot '${slot.slotId}' (${slot.nodePath}) has no report declaration (present=false)"
                    } else {
                        bound[slot.slotId] = slotFrom(slot.slotId, draw, false, slot.optional, slot.nodePath)
                    }
                }
                rep.texts.forEachIndexed { index, draw ->
                    val path = draw.path
                    if (path != null && path in declaredPaths) return@forEachIndexed
                    val id = path ?: draw.key ?: "draw#${draw.seq ?: index}"
                    warnings += "text draw '$id' has no catalog slot declaration; retaining explicit path metadata"
                    extras += slotFrom(id, draw, true, false, path)
                }
            }
            card.backend == "hand" -> {
                val byKey = rep.texts.filter { it.stable && isWritableTextSlotKey(it.key) }
                    .associateBy { it.key }
                val claimed = mutableSetOf<String>()
                for (slot in declared) {
                    val draw = byKey[slot.slotId]
                    if (draw == null) {
                        warnings += "hand slot '${slot.slotId}' has no explicit stable-key draw (present=false)"
                    } else {
                        val key = draw.key.orEmpty()
                        claimed += key
                        bound[slot.slotId] = slotFrom(key, draw, false, slot.optional, null)
                    }
                }
                rep.texts.forEachIndexed { index, draw ->
                    val key = draw.key
                    if (draw.stable && isWritableTextSlotKey(key)) {
                        if (key in claimed) return@forEachIndexed
                        warnings += "stable hand text '$key' has no catalog slot entry; retaining report key"
                        extras += slotFrom(key.orEmpty(), draw, false, false, null)
                    } else {
                        val id = "draw#${draw.seq ?: index}"
                        warnings += "hand text draw '$id' has no explicit stable key (not editable)"
                        extras += slotFrom(id, draw, true, false, null)
                    }
                }
            }
            else -> {
                if (declared.isNotEmpty()) warnings += "legacy string slots bound by array index; report-only rows are not editable"
                declared.forEachIndexed { index, slot ->
                    val draw = rep.texts.getOrNull(index)
                    if (draw == null) {
                        warnings += "legacy slot '${slot.slotId}' has no draw (present=false)"
                    } else {
                        bound[slot.slotId] = slotFrom(slot.slotId, draw, false, slot.optional, slot.nodePath)
                    }
                }
                rep.texts.drop(declared.size).forEachIndexed { offset, draw ->
                    val id = draw.path ?: draw.key ?: "draw#${draw.seq ?: (declared.size + offset)}"
                    warnings += "text draw '$id' has no stable declared slot"
                    extras += slotFrom(id, draw, true, false, draw.path)
                }
            }
        }

        return buildList {
            declared.forEach { slot -> add(bound[slot.slotId] ?: absent(slot)) }
            addAll(extras)
        }
    }
    // -----------------------------------------------------------------------
    // design / hand inputs
    // -----------------------------------------------------------------------

    /**
     * IR engines read `design.text.fontScale` from `--design`; params/text ride
     * along verbatim so P3 can consume them without a bridge change. An empty
     * design emits no `--design` argument (the pre-wire byte-identical path).
     */
    private fun prepareIrDesign(
        card: EngineCard,
        params: Map<String, Any?>,
        text: Map<String, Any?>,
        id: String,
    ): String? {
        if (params.isEmpty() && text.isEmpty()) return null
        val inner = linkedMapOf<String, Any?>(
            "cardId" to card.cardId,
            "styleId" to card.engineId,
            "params" to params,
            "text" to text,
        )
        val doc = linkedMapOf<String, Any?>("design" to inner)
        val temp = File(work, "design-ir-$id.json")
        temp.writeText(BridgeJson.write(doc))
        return temp.path
    }

    /**
     * Hand cards read params/text from the card's design document, so a non-empty
     * design writes a temp design + a temp cards file pointing at it. A brand-picker
     * choice rides the same document as `design.logo` (`{name,width,height,dataUrl}`),
     * which e4hand's `load_brand_logo` prefers over the card's capture-time logo.
     */
    private fun prepareHandInputs(
        card: EngineCard,
        params: Map<String, Any?>,
        text: Map<String, Any?>,
        id: String,
        logo: EngineLogo? = null,
    ): String {
        val cardsFile = catalog.abs(card.cardsFile ?: error("card ${card.cardId} has no cardsFile"))
        val designFile = catalog.abs(card.design ?: error("card ${card.cardId} has no design file"))
        if (params.isEmpty() && text.isEmpty() && logo == null) return catalog.engineArg(card.cardsFile!!)

        val designDoc = Json.parse(File(designFile).readText()).jsonMap().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val inner = (designDoc["design"] as? Map<String, Any?>)?.toMutableMap()
            ?: error("design document has no 'design' object: $designFile")
        @Suppress("UNCHECKED_CAST")
        val designParams = (inner["params"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        @Suppress("UNCHECKED_CAST")
        val designText = (inner["text"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        designParams.putAll(params)
        designText.putAll(text)
        inner["params"] = designParams
        inner["text"] = designText
        if (logo != null) {
            inner["logo"] = linkedMapOf(
                "name" to logo.name,
                "width" to logo.width,
                "height" to logo.height,
                "dataUrl" to logo.dataUrl,
            )
        }
        designDoc["design"] = inner
        val tempDesign = File(work, "design-$id.json")
        tempDesign.writeText(BridgeJson.write(designDoc))

        val cardsDoc = Json.parse(File(cardsFile).readText()).jsonMap().toMutableMap()
        val cards = cardsDoc["cards"].jsonList().map { entry ->
            val map = entry.jsonMap().toMutableMap()
            if (map["card"] == card.engineId) map["design"] = tempDesign.path
            map
        }
        cardsDoc["cards"] = cards
        val tempCards = File(work, "cards-$id.json")
        tempCards.writeText(BridgeJson.write(cardsDoc))
        return tempCards.path
    }

    // -----------------------------------------------------------------------
    // engine report -> geometry
    // -----------------------------------------------------------------------

    private class RawText(
        val text: String,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val fontSize: Double?,
        val baseline: Double?,
        val lines: Int,
        /** e2b `items[].path`; null for e4hand draws (no node address). */
        val path: String? = null,
        /** e4hand paint sequence, used only to name an unmapped draw. */
        val seq: Long? = null,
        val key: String? = null,
        val raw: String = text,
        val stable: Boolean = false,
        val hidden: Boolean = false,
        val present: Boolean = true,
        val located: Boolean = false,
    )

    private class RawReport(
        val width: Int,
        val height: Int,
        val photoRect: DoubleArray,
        val texts: List<RawText>,
        val requiredResourceErrors: List<String> = emptyList(),
        /** Engine-reported `photo_src` in display (EXIF-applied) pixels; null when absent. */
        val photoSrc: DoubleArray? = null,
    )

    /**
     * `photo_src` (`[x, y, w, h]` in the photo's display frame) from either report
     * shape. Absent or unusable values are null, never an error: an older engine
     * simply does not report it, and the caller then re-derives the crop.
     */
    private fun parsePhotoSrc(doc: Map<String, Any?>): DoubleArray? {
        val values = doc["photo_src"].jsonList()
        if (values.size != 4) return null
        val rect = values.map { (it as? Number)?.toDouble() ?: return null }
        if (rect.any { !it.isFinite() }) return null
        if (rect[2] <= 0.0 || rect[3] <= 0.0) return null
        if (rect[0] < 0.0 || rect[1] < 0.0) return null
        return rect.toDoubleArray()
    }

    private fun parseReport(card: EngineCard, report: File): RawReport {
        val doc = Json.parse(report.readText()).jsonMap()
        return when (card.backend) {
            "ir" -> {
                val canvas = doc["canvas"] as? Map<*, *> ?: error("report canvas missing or invalid")
                fun positiveDimension(key: String): Int {
                    val value = (canvas[key] as? Number)?.toDouble()
                        ?: error("report canvas.$key missing or invalid")
                    if (!value.isFinite() || value <= 0.0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) {
                        error("report canvas.$key must be a positive integer")
                    }
                    return value.toInt()
                }
                val w = positiveDimension("w")
                val h = positiveDimension("h")
                val photoValues = doc["photo"] as? List<*> ?: error("report photo missing or invalid")
                if (photoValues.size != 4) error("report photo must contain x, y, width, and height")
                val photo = photoValues.mapIndexed { index, value ->
                    (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
                        ?: error("report photo[$index] missing or invalid")
                }
                if (photo[2] <= 0.0 || photo[3] <= 0.0) error("report photo dimensions must be positive")
                // Desktop reports currently use root `items`; accept the Android
                // geom shape too so report fixtures and shared tooling can feed
                // either nesting without inventing geometry.
                val rawItems = (doc["items"] as? List<*>) ?: doc["bands"].jsonList().flatMap { band ->
                    band.jsonMap()["items"].jsonList()
                }
                val items = rawItems.mapIndexedNotNull { index, item ->
                    val map = item as? Map<*, *> ?: error("report items[$index] must be an object")
                    val kind = map["kind"] as? String
                    if (kind.isNullOrBlank()) error("report items[$index].kind missing or invalid")
                    @Suppress("UNCHECKED_CAST")
                    map as Map<String, Any?>
                }
                // `gaps` mixes informational env/rule/layout notes with failures.
                // Gate only on report items that prove a required image or selected
                // wordmark could not be drawn. `brand_slot_empty` is app-rule output.
                val resourceErrors = items.mapNotNull { m ->
                    val kind = m["kind"]?.toString()
                    if (kind != "image_missing" && kind != "brand_slot_missing") return@mapNotNull null
                    val asset = m["asset"]?.toString() ?: m["file"]?.toString() ?: "<unknown>"
                    val path = m["path"]?.toString()?.let { " path=$it" }.orEmpty()
                    "$kind asset=$asset$path"
                }
                fun rawItemText(m: Map<String, Any?>): RawText? {
                    if (m["kind"] != "text") return null
                    val rect = m["rect"] as? List<*> ?: emptyList<Any?>()
                    val nums = (0..3).map { index ->
                        (rect.getOrNull(index) as? Number)?.toDouble()?.takeIf { it.isFinite() }
                    }
                    val located = nums.size == 4 && nums.all { it != null } && nums[2]!! > 0.0 && nums[3]!! > 0.0
                    val lines = m["lines"].jsonList().mapNotNull { it?.toString() }
                    val text = (m["text"] as? String) ?: lines.joinToString("\n")
                    val path = m["path"] as? String
                    return RawText(
                        text = text,
                        x = nums[0] ?: 0.0,
                        y = nums[1] ?: 0.0,
                        w = nums[2] ?: 0.0,
                        h = nums[3] ?: 0.0,
                        fontSize = (m["fs"] as? Number)?.toDouble(),
                        baseline = (m["baseline"] as? Number)?.toDouble(),
                        lines = lines.size.coerceAtLeast(1),
                        path = path,
                        key = m["key"] as? String ?: path,
                        raw = m["raw"] as? String ?: text,
                        stable = (m["stable"] as? Boolean ?: isWritableTextSlotKey(path)) && isWritableTextSlotKey(path),
                        hidden = m["hidden"] as? Boolean ?: false,
                        present = m["present"] as? Boolean ?: true,
                        located = located,
                    )
                }
                val draws = items.mapNotNull(::rawItemText)
                val drawByPath = draws.mapNotNull { draw -> draw.path?.let { it to draw } }.toMap()
                val declarations = doc["textSlots"].jsonList().map { item ->
                    val m = item.jsonMap()
                    val path = m["path"] as? String
                    val geometry = path?.let(drawByPath::get)
                    val text = m["text"] as? String ?: geometry?.text.orEmpty()
                    val key = m["key"] as? String ?: path
                    val stable = (m["stable"] as? Boolean ?: isWritableTextSlotKey(path)) && isWritableTextSlotKey(path)
                    RawText(
                        text = text,
                        x = geometry?.x ?: 0.0,
                        y = geometry?.y ?: 0.0,
                        w = geometry?.w ?: 0.0,
                        h = geometry?.h ?: 0.0,
                        fontSize = geometry?.fontSize,
                        baseline = geometry?.baseline,
                        lines = geometry?.lines ?: text.count { it == '\n' }.plus(1),
                        path = path,
                        key = key,
                        raw = m["raw"] as? String ?: geometry?.raw ?: text,
                        stable = stable,
                        hidden = m["hidden"] as? Boolean ?: false,
                        present = m["present"] as? Boolean ?: geometry?.present ?: false,
                        located = geometry?.located ?: false,
                    )
                }
                val declaredPaths = declarations.mapNotNull { it.path }.toSet()
                val extras = draws.filter { it.path !in declaredPaths }
                val texts = declarations + extras
                RawReport(w, h, photo.toDoubleArray(), texts, resourceErrors, parsePhotoSrc(doc))
            }
            "hand" -> {
                val canvas = doc["canvas"].jsonList()
                val w = (canvas.getOrNull(0) as? Number)?.toInt() ?: error("report canvas missing")
                val h = (canvas.getOrNull(1) as? Number)?.toInt() ?: error("report canvas missing")
                val photo = doc["photo_rect"].jsonList().map { (it as? Number)?.toDouble() ?: 0.0 }
                val texts = doc["report"].jsonMap()["texts"].jsonList().map { item ->
                    val m = item.jsonMap()
                    val rect = m["rect"].jsonMap()
                    val x = (rect["x"] as? Number)?.toDouble()
                    val y = (rect["y"] as? Number)?.toDouble()
                    val rectW = (rect["w"] as? Number)?.toDouble()
                    val rectH = (rect["h"] as? Number)?.toDouble()
                    val located = listOf(x, y, rectW, rectH).all { it != null && it.isFinite() } && rectW!! > 0.0 && rectH!! > 0.0
                    RawText(
                        text = m["s"]?.toString().orEmpty(),
                        x = x ?: 0.0,
                        y = y ?: 0.0,
                        w = rectW ?: 0.0,
                        h = rectH ?: 0.0,
                        fontSize = (m["size"] as? Number)?.toDouble(),
                        baseline = null,
                        lines = 1,
                        seq = (m["seq"] as? Number)?.toLong(),
                        key = m["key"] as? String,
                        raw = m["raw"] as? String ?: m["s"]?.toString().orEmpty(),
                        stable = m["stable"] as? Boolean ?: false,
                        hidden = m["hidden"] as? Boolean ?: false,
                        present = m["present"] as? Boolean ?: true,
                        located = located,
                    )
                }
                RawReport(w, h, photo.toDoubleArray(), texts, photoSrc = parsePhotoSrc(doc))
            }
            else -> error("unknown backend ${card.backend}")
        }
    }

    // -----------------------------------------------------------------------
    // contract documents
    // -----------------------------------------------------------------------

    private fun normalizedRequest(
        card: EngineCard,
        params: Map<String, Any?>,
        text: Map<String, Any?>,
        mode: String,
        maxEdge: Int?,
        outW: Int,
        outH: Int,
        photoAbs: String,
    ): String {
        val design = linkedMapOf<String, Any?>(
            "cardId" to card.cardId,
            "styleId" to (card.engineId),
            "params" to params,
            "text" to text,
            "elements" to card.slots.map { linkedMapOf("kind" to "text", "slot" to it.slotId) },
        )
        if (mode == "export") design["canvas"] = linkedMapOf("w" to outW, "h" to outH)
        val defaultPhoto = photoAbs == catalog.photoPath
        // AU1-01: header-only size (prime-first, then a memoised header read) —
        // never `ImageIO.read` of the whole photo just to fill this document.
        val size = if (defaultPhoto) null else PhotoSizes.of(photoAbs)
        val photo = linkedMapOf<String, Any?>(
            "path" to photoAbs,
            "w" to (catalog.photoW.takeIf { defaultPhoto && it > 0 } ?: size?.first ?: 0),
            "h" to (catalog.photoH.takeIf { defaultPhoto && it > 0 } ?: size?.second ?: 0),
        )
        val req = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "design" to design,
            "photo" to photo,
            "mode" to mode,
        )
        if (mode == "preview" && maxEdge != null) req["maxEdge"] = maxEdge
        return BridgeJson.write(req)
    }

    private fun responseJson(
        path: String,
        width: Int,
        height: Int,
        photoRect: DoubleArray,
        slots: List<TextSlot>,
        ms: Long,
        warnings: List<String>,
    ): String {
        val res = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "image" to linkedMapOf("path" to path, "w" to width, "h" to height),
            "photoRect" to linkedMapOf(
                "x" to r3(photoRect.getOrElse(0) { 0.0 }),
                "y" to r3(photoRect.getOrElse(1) { 0.0 }),
                "w" to r3(photoRect.getOrElse(2) { 0.0 }),
                "h" to r3(photoRect.getOrElse(3) { 0.0 }),
            ),
            "textSlots" to slots.map { slot ->
                linkedMapOf<String, Any?>(
                    "id" to slot.id,
                    "text" to slot.text,
                    "x" to r3(slot.x),
                    "y" to r3(slot.y),
                    "w" to r3(slot.w),
                    "h" to r3(slot.h),
                ).apply {
                    // Optional v1 fields: omit rather than send null (the schema
                    // types them as numbers when present).
                    if (slot.fontSize != null) put("fontSize", r3(slot.fontSize))
                    if (slot.baseline != null) put("baseline", r3(slot.baseline))
                    put("lines", slot.lines)
                    put("present", slot.present)
                    put("unmapped", slot.unmapped)
                    if (slot.optional) put("optional", true)
                    slot.nodePath?.let { put("nodePath", it) }
                }
            },
            "ms" to ms,
            "engine" to "native",
        )
        if (warnings.isNotEmpty()) res["warnings"] = warnings
        return BridgeJson.write(res)
    }

    private fun trace(card: EngineCard, mode: String, request: String, response: String) {
        val dir = env("FM_BRIDGE_TRACE_DIR")?.takeIf { it.isNotBlank() } ?: return
        val tag = env("FM_BRIDGE_TRACE_TAG")?.takeIf { it.isNotBlank() } ?: "${card.cardId}.$mode"
        val out = File(dir).apply { mkdirs() }
        File(out, "$tag.request.json").writeText(request)
        File(out, "$tag.response.json").writeText(response)
    }

    // -----------------------------------------------------------------------
    // process / image helpers
    // -----------------------------------------------------------------------

    private class RunResult(val code: Int, val output: String)

    private fun closePreviewWorker(connection: PreviewWorkerConnection? = null) {
        synchronized(previewWorkerLock) {
            val current = previewWorker
            if (current != null && (connection == null || current === connection)) {
                current.close()
                previewWorker = null
            }
        }
    }

    private fun runPreviewWorker(
        argv: List<String>,
        cardId: String,
        key: PreviewWorkerKey,
    ): RunResult {
        val startArgs = listOf(argv.first(), "preview-server")
        var lastFailure: RunResult? = null
        repeat(2) { attempt ->
            val connection = try {
                synchronized(previewWorkerLock) {
                    val current = previewWorker
                    if (current != null && current.key == key && current.isAlive) {
                        current
                    } else {
                        current?.close()
                        FmLog.info("FM_PREVIEW_WORKER_START") { "backend=${key.backend} card=$cardId attempt=$attempt" }
                        val process = ProcessBuilder(startArgs)
                            .directory(workingDir)
                            .redirectErrorStream(true)
                            .start()
                        PreviewWorkerConnection(key, process).also { previewWorker = it }
                    }
                }
            } catch (t: Throwable) {
                lastFailure = RunResult(-1, "worker start failed: ${t.message ?: t::class.simpleName}")
                return@repeat
            }

            val pending = previewWorkerExecutor.submit<RunResult> {
                connection.request(argv.drop(1))
            }
            try {
                val result = pending.get(60, TimeUnit.SECONDS)
                if (result.code == 0) return result
                lastFailure = result
            } catch (e: InterruptedException) {
                // The request task continues draining the worker stream so the
                // persistent worker remains usable; only this caller is stale.
                Thread.currentThread().interrupt()
                throw e
            } catch (e: TimeoutException) {
                lastFailure = RunResult(-1, "preview worker timeout")
            } catch (t: Throwable) {
                val cause = t.cause?.message ?: t.message ?: t::class.simpleName
                lastFailure = RunResult(-1, "preview worker failure: $cause")
            }
            closePreviewWorker(connection)
        }
        FmLog.info("FM_PREVIEW_WORKER_FALLBACK") {
            "backend=${key.backend} card=$cardId " +
                "reason=${lastFailure?.output?.lineSequence()?.lastOrNull()?.take(240)}"
        }
        return exec(argv, cardId)
    }
    private fun exec(argv: List<String>, cardId: String): RunResult {
        FmLog.info("FM_ENGINE_EXEC") { "${argv.joinToString(" ")}" }
        val result = ProcessRunner.run(argv, workingDir)
        return RunResult(result.code, result.output)
    }

    /**
     * Resolve an engine resource root under the selected runtime's data tree only.
     * Canonical containment rejects `..` and symlink escapes. The current package
     * has a flattened `data/assets` tree but no `data/t1b/assets`, so that packaged
     * tree is the legacy t1b fallback until a dedicated tree is shipped.
     */
    private fun packageDataArg(relative: String, fallbackRelative: String? = null): String {
        val root = layout.dataRoot.canonicalFile.toPath()
        for (candidate in listOfNotNull(relative, fallbackRelative)) {
            val resolved = File(root.toFile(), candidate).canonicalFile.toPath()
            if (!resolved.startsWith(root) || !resolved.toFile().isDirectory) continue
            return root.relativize(resolved).toString().replace('\\', '/')
        }
        throw EngineConfigException(
            "FM-E-RES-001",
            "包内引擎资源目录缺失或越界",
            expectedPath = File(layout.dataRoot, relative).absolutePath,
        )
    }

    /** Engine argument relative to CWD (`data/…` → `…`); falls back to the absolute path. */
    private fun dataArg(dir: File): String {
        val dataRoot = layout.dataRoot.absoluteFile
        val abs = dir.absoluteFile
        val prefix = dataRoot.path + File.separator
        return if (abs.path.startsWith(prefix)) {
            abs.path.removePrefix(prefix).replace('\\', '/')
        } else {
            abs.path
        }
    }

    private fun overlayArgs(overlay: OverlaySpec?): List<String> {
        if (overlay == null) return emptyList()
        if (!File(overlay.path).isFile) {
            throw EngineConfigException("FM-E-RES-001", "水印素材缺失", expectedPath = overlay.path)
        }
        val rect = floatArrayOf(overlay.x, overlay.y, overlay.w, overlay.h)
        rect.forEachIndexed { index, value ->
            if (!value.isFinite() || value < 0f || value > 1f) {
                throw EngineConfigException(
                    "FM-E-RES-001",
                    "水印矩形越界（index $index = $value，必须在 0..1）",
                    expectedPath = overlay.path,
                )
            }
        }
        if (!overlay.opacity.isFinite() || overlay.opacity < 0f || overlay.opacity > 1f) {
            throw EngineConfigException("FM-E-RES-001", "水印透明度越界（${overlay.opacity}）")
        }
        if (!overlay.angle.isFinite() || overlay.angle < -180f || overlay.angle > 180f) {
            throw EngineConfigException("FM-E-RES-001", "水印角度越界（${overlay.angle}）")
        }
        return buildList {
            add("--overlay"); add(overlay.path)
            add("--overlay-rect"); add(rect.joinToString(",") { it.toString() })
            add("--overlay-opacity"); add(overlay.opacity.toString())
            if (overlay.angle != 0f) {
                add("--overlay-angle"); add(overlay.angle.toString())
            }
        }
    }

    private fun scaleImage(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writePng(image: BufferedImage, target: File) {
        ImageIO.write(image, "png", target)
    }

    /** Delete one engine scratch file (AU1-02); failures are logged, never thrown. */
    private fun discardTemp(file: File) {
        TempJanitor.discard(file)
    }

    /** Move [source] onto [target] without decoding it; falls back to a copy. */
    private fun moveOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: Throwable) {
            // Cross-volume or a lock on the target: a byte copy is still cheaper
            // than decode + re-encode, and the original is removed by the caller.
            FmLog.info("FM_PHOTO_MOVE_FALLBACK") { "from=${source.name} error=${e.message ?: e::class.simpleName}" }
            source.copyTo(target, overwrite = true)
        }
    }

    /**
     * Remove leftovers from earlier runs (AU1-02).
     *
     * A crashed or killed process cannot run its `finally`, so the work directory
     * is swept once at construction. Anything younger than
     * [TempJanitor.DEFAULT_STALE_MS] may belong to another live instance, so only
     * the age decides.
     */
    private fun sweepWorkDir() {
        val result = TempJanitor.sweep(work)
        if (result.removed > 0) {
            FmLog.info("FM_TEMP_SWEEP") { "dir=${work.absolutePath} files=${result.removed} bytes=${result.bytes} kept=${result.kept}" }
        }
    }

    private companion object {
        fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        fun r3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
    }
}
