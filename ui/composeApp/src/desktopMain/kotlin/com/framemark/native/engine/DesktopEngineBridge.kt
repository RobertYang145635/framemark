package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.workflow.OverlaySpec
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

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
)

data class TextSlot(
    val id: String,
    val text: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val fontSize: Double?,
    val baseline: Double?,
    val lines: Int,
    /** Declared node with no draw for this photo (never dropped from the model). */
    val present: Boolean = true,
    /** Report draw with no declared node; [id] is the report path (or draw seq). */
    val unmapped: Boolean = false,
    val optional: Boolean = false,
    /** Engine node path (`b…/g…`) the slot was declared with. */
    val nodePath: String? = null,
)

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
                (params["theme"] as? String)?.takeIf { it.isNotBlank() }?.let { args += listOf("--theme", it) }
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

        var run = exec(argv, cardId)
        var nativeFitFellBack = false
        if (run.code != 0 && nativeFit && run.output.contains("E4H-FIT-NATIVE-")) {
            // The template's photo box cannot hold the whole photo (fixed-ratio
            // side/square layouts). Keep the template ratio, and carry the
            // caveat into the result instead of failing the export.
            println("FM_NATIVE_FIT_FALLBACK card=$cardId reason=${run.output.lines().firstOrNull { it.contains("E4H-FIT-NATIVE-") }?.take(200)}")
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

        println("FM_ENGINE_RAW path=${raw.path}")
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
        val requested = if (mode == "preview") maxEdge else longEdge
        val scale = if (requested != null && requested in 1 until rawEdge) requested.toDouble() / rawEdge else 1.0
        val outW = (rep.width * scale).roundToInt().coerceAtLeast(1)
        val outH = (rep.height * scale).roundToInt().coerceAtLeast(1)

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
                val source = ImageIO.read(raw) ?: error("engine output is not a decodable image: ${raw.path}")
                writePng(if (scale == 1.0) source else scaleImage(source, outW, outH), target)
            }
        } else {
            val source = ImageIO.read(raw) ?: error("engine output is not a decodable image: ${raw.path}")
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
        )
    }

    // -----------------------------------------------------------------------
    // slot binding: node identity, never array position for declared paths
    // -----------------------------------------------------------------------

    /**
     * Binds engine draw reports to the manifest's text node declarations.
     *
     * - IR: `rep.texts[].path` → `slot.nodePath`; a photo whose EXIF adds or
     *   drops nodes can never shift another slot. Declared-but-not-drawn slots
     *   stay in the result with `present=false`; draws without a declaration
     *   are appended with `unmapped=true` (id = report path).
     * - hand: e4hand reports no path, so the frozen `sampleText` is the
     *   identity. Slots whose text changed on this photo are matched by the
     *   remaining order only when the counts agree; otherwise they are
     *   `present=false` / `unmapped=true` instead of being silently shifted.
     * - legacy string manifests: index binding (old behaviour).
     */
    private fun bindTextSlots(
        card: EngineCard,
        rep: RawReport,
        scale: Double,
        warnings: MutableList<String>,
    ): List<TextSlot> {
        fun slotFrom(id: String, draw: RawText, unmapped: Boolean, optional: Boolean, nodePath: String?) =
            TextSlot(
                id = id,
                text = draw.text,
                x = draw.x * scale,
                y = draw.y * scale,
                w = draw.w * scale,
                h = draw.h * scale,
                fontSize = draw.fontSize?.let { it * scale },
                baseline = draw.baseline?.let { it * scale },
                lines = draw.lines,
                present = true,
                unmapped = unmapped,
                optional = optional,
                nodePath = nodePath,
            )

        fun absent(slot: EngineSlot) = TextSlot(
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
        )

        val declared = card.slots
        val bound = LinkedHashMap<String, TextSlot>()
        val extras = ArrayList<TextSlot>()
        val pathMode = card.backend == "ir" && declared.any { it.nodePath != null }
        val textMode = card.backend == "hand" && declared.any { it.sampleText != null }

        when {
            pathMode -> {
                val byPath = HashMap<String, RawText>()
                for (t in rep.texts) t.path?.let { byPath[it] = t }
                val declaredPaths = declared.mapNotNull { it.nodePath }.toHashSet()
                for (s in declared) {
                    val draw = s.nodePath?.let { byPath[it] }
                    if (draw == null) {
                        warnings += "slot '${s.slotId}' (${s.nodePath}) has no draw for this photo (present=false)"
                    } else {
                        bound[s.slotId] = slotFrom(s.slotId, draw, false, s.optional, s.nodePath)
                    }
                }
                rep.texts.forEachIndexed { i, draw ->
                    val p = draw.path
                    if (p != null && p in declaredPaths) return@forEachIndexed
                    val id = p ?: "draw#${draw.seq ?: i}"
                    warnings += "text draw '$id' has no declared slot (unmapped)"
                    extras += slotFrom(id, draw, true, false, p)
                }
            }
            textMode -> {
                val used = BooleanArray(rep.texts.size)
                val pending = ArrayList<EngineSlot>()
                for (s in declared) {
                    val sample = s.sampleText
                    val idx = if (sample == null) {
                        -1
                    } else {
                        rep.texts.indices.firstOrNull { !used[it] && rep.texts[it].text == sample } ?: -1
                    }
                    if (idx >= 0) {
                        used[idx] = true
                        bound[s.slotId] = slotFrom(s.slotId, rep.texts[idx], false, false, null)
                    } else {
                        pending += s
                    }
                }
                val rest = rep.texts.indices.filter { !used[it] }
                if (pending.size == rest.size) {
                    if (pending.isNotEmpty()) {
                        warnings += "${pending.size} hand slot(s) differ from the sample text; bound by remaining order"
                    }
                    for ((k, s) in pending.withIndex()) {
                        val idx = rest[k]
                        used[idx] = true
                        bound[s.slotId] = slotFrom(s.slotId, rep.texts[idx], false, false, null)
                    }
                } else {
                    for (s in pending) {
                        warnings += "hand slot '${s.slotId}' has no draw for this photo (present=false)"
                    }
                    for (idx in rest) {
                        val draw = rep.texts[idx]
                        val id = "draw#${draw.seq ?: idx}"
                        warnings += "text draw '$id' has no declared slot (unmapped)"
                        extras += slotFrom(id, draw, true, false, null)
                    }
                }
            }
            else -> {
                if (declared.isNotEmpty()) warnings += "legacy string slots bound by array index"
                declared.forEachIndexed { i, s ->
                    val draw = rep.texts.getOrNull(i)
                    if (draw == null) {
                        warnings += "legacy slot '${s.slotId}' has no draw for this photo (present=false)"
                    } else {
                        bound[s.slotId] = slotFrom(s.slotId, draw, false, false, null)
                    }
                }
                rep.texts.drop(declared.size).forEachIndexed { k, draw ->
                    val id = draw.path ?: "draw#${draw.seq ?: (declared.size + k)}"
                    warnings += "text draw '$id' has no declared slot (unmapped)"
                    extras += slotFrom(id, draw, true, false, draw.path)
                }
            }
        }
        val out = ArrayList<TextSlot>(declared.size + extras.size)
        for (s in declared) out += bound[s.slotId] ?: absent(s)
        out += extras
        return out
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
    )

    private class RawReport(
        val width: Int,
        val height: Int,
        val photoRect: DoubleArray,
        val texts: List<RawText>,
        val requiredResourceErrors: List<String> = emptyList(),
    )

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
                val rawItems = doc["items"] as? List<*> ?: error("report items missing or invalid")
                val items = rawItems.mapIndexed { index, item ->
                    val map = item as? Map<*, *> ?: error("report items[$index] must be an object")
                    if (map["kind"] !is String || (map["kind"] as String).isBlank()) {
                        error("report items[$index].kind missing or invalid")
                    }
                    @Suppress("UNCHECKED_CAST")
                    (map as Map<String, Any?>)
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
                val texts = items.mapNotNull { m ->
                    if (m["kind"] != "text") return@mapNotNull null
                    val rect = m["rect"].jsonList().map { (it as? Number)?.toDouble() ?: 0.0 }
                    RawText(
                        text = m["lines"].jsonList().joinToString("\n") { it?.toString().orEmpty() },
                        x = rect.getOrElse(0) { 0.0 },
                        y = rect.getOrElse(1) { 0.0 },
                        w = rect.getOrElse(2) { 0.0 },
                        h = rect.getOrElse(3) { 0.0 },
                        fontSize = (m["fs"] as? Number)?.toDouble(),
                        baseline = (m["baseline"] as? Number)?.toDouble(),
                        lines = m["lines"].jsonList().size.coerceAtLeast(1),
                        path = m["path"] as? String,
                    )
                }
                RawReport(w, h, photo.toDoubleArray(), texts, resourceErrors)
            }
            "hand" -> {
                val canvas = doc["canvas"].jsonList()
                val w = (canvas.getOrNull(0) as? Number)?.toInt() ?: error("report canvas missing")
                val h = (canvas.getOrNull(1) as? Number)?.toInt() ?: error("report canvas missing")
                val photo = doc["photo_rect"].jsonList().map { (it as? Number)?.toDouble() ?: 0.0 }
                val texts = doc["report"].jsonMap()["texts"].jsonList().map { item ->
                    val m = item.jsonMap()
                    val rect = m["rect"].jsonMap()
                    RawText(
                        text = m["s"]?.toString().orEmpty(),
                        x = (rect["x"] as? Number)?.toDouble() ?: 0.0,
                        y = (rect["y"] as? Number)?.toDouble() ?: 0.0,
                        w = (rect["w"] as? Number)?.toDouble() ?: 0.0,
                        h = (rect["h"] as? Number)?.toDouble() ?: 0.0,
                        fontSize = (m["size"] as? Number)?.toDouble(),
                        baseline = null,
                        lines = 1,
                        seq = (m["seq"] as? Number)?.toLong(),
                    )
                }
                RawReport(w, h, photo.toDoubleArray(), texts)
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
        val size = if (defaultPhoto) null else imageSize(photoAbs)
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

    private fun exec(argv: List<String>, cardId: String): RunResult {
        println("FM_ENGINE_EXEC ${argv.joinToString(" ")}")
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
        return listOf(
            "--overlay", overlay.path,
            "--overlay-rect", rect.joinToString(",") { it.toString() },
            "--overlay-opacity", overlay.opacity.toString(),
        )
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

    private fun imageSize(path: String): Pair<Int, Int> {
        val image = ImageIO.read(File(path)) ?: return 0 to 0
        return image.width to image.height
    }

    private companion object {
        fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        fun r3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
    }
}
