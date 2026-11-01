package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.jsonBool
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.ControlLabels
import com.framemark.native.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.round

/**
 * Data model for the U3 inspector.
 *
 * The inspector is generated per selected card; no control list is hard-coded.
 * Two frozen sources are joined:
 *
 *  - audited cards (69 hand cards): `capabilities.json` decides **membership**
 *    (`effective[]` vs `gated[]`) and carries the audited current value. Display
 *    metadata (label / options / range / declared default) comes from
 *    `inspector-meta.json`, a verbatim projection of the E1a source
 *    `_research/hand-controls/controls.hand.json` (see `tools/u3-gen-inspector-meta.mjs`).
 *    Every one of the 996 capability entries joins by `styleId + controlId`; a
 *    missing row stops the card with an explicit error instead of inventing a
 *    label.
 *  - unaudited stock cards (106): `controls.json` rows for the card's
 *    `systemStyle` are the declared set. Seven stock styles have no rows at all;
 *    those cards show the same neutral empty state as any other card without a
 *    renderable control.
 *
 * Gated controls and controls carrying `gated: true` / `visible: false` are
 * dropped from the rendered set (no dead controls) but stay in the capability
 * counts. Raw source identifiers never reach the UI: every label either carries
 * a human label in the source, maps through [HumanLabels], or the control is
 * dropped. No audit wording is produced here — the UI only sees display data.
 */
enum class InspectorSection(val title: String) {
    Border("边框"),
    Logo("Logo"),
    Text("文字"),
    Layout("布局"),
    Theme("主题"),
}

enum class ControlType { Switch, Slider, Select, Text, Color, Brand }

sealed interface ControlValue {
    data class Flag(val value: Boolean) : ControlValue
    data class Num(val value: Float) : ControlValue
    data class Str(val value: String) : ControlValue
}

data class ControlOption(val value: String, val display: String)

data class InspectorControl(
    /** Stable per-card key for local state; never displayed. */
    val key: String,
    val controlId: String,
    /** Design field this control writes (`params.*`, `text.*`, `theme`); engine binding only. */
    val param: String = "",
    /** Human display label; empty only for a control that is not rendered. */
    val label: String,
    val kind: String,
    val section: InspectorSection,
    val type: ControlType,
    val initial: ControlValue,
    val min: Float?,
    val max: Float?,
    val step: Float?,
    val options: List<ControlOption>,
    val multiline: Boolean,
    /** Exact declared default from the E1a source; null when the source has none. */
    val declaredDefault: String?,
    /** Audited current value (capabilities) or the row default (stock). */
    val currentText: String?,
    val autoMatch: Boolean?,
    val unit: String?,
    val hidden: Boolean,
    /**
     * Extra design fields that carry the same value. A `slotOff` switch hides a
     * **node set**, so one control writes one key per node; the engine contract
     * forbids hiding a whole container by path prefix.
     */
    val companions: List<String> = emptyList(),
    /** Non-null means the control is shown but cannot take effect yet (engine gap). */
    val disabledReason: String? = null,
)

data class InspectorSectionModel(
    val section: InspectorSection,
    val controls: List<InspectorControl>,
)

data class InspectorCardModel(
    val cardId: String,
    val cardName: String,
    val audited: Boolean,
    val effectiveCount: Int?,
    val gatedCount: Int?,
    val declaredCount: Int?,
    val effectiveIds: List<String>,
    val gatedIds: List<String>,
    val visibleIds: List<String>,
    val hiddenIds: List<String>,
    val controls: List<InspectorControl>,
    val sections: List<InspectorSectionModel>,
) {
    val visibleCount: Int get() = visibleIds.size

    /** True when this card has no renderable control; the editor shows a short empty state. */
    val empty: Boolean get() = visibleIds.isEmpty()
}

/**
 * Platform-supplied controls that the frozen audit rows do not carry (Android
 * reads them from the style JSON the renderer actually uses). Hints never
 * override a visible frozen control that already has choices.
 */
interface InspectorControlHints {
    fun extraControls(cardId: String): List<InspectorControl>
}

object InspectorHints {
    var provider: InspectorControlHints? = null
}

sealed interface InspectorLoadState {
    data object Idle : InspectorLoadState
    data object Loading : InspectorLoadState
    data class Ready(
        /** The audited word list; null once a target builds its panel from the registry. */
        val snapshot: InspectorSnapshot? = null,
        /** P3f: the registry + IR slot binding pair the desktop inspector is built from. */
        val registry: RegistryInspectorLoad? = null,
    ) : InspectorLoadState
    data class Failed(val message: String) : InspectorLoadState
}

data class InspectorLoadResult(
    val snapshot: InspectorSnapshot,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Frozen source records
// ---------------------------------------------------------------------------

data class CapabilityEntry(
    val controlId: String,
    val param: String,
    val kind: String,
    val scope: String,
    val currentValue: Any?,
    val gatedFlag: Boolean?,
    val visibleFlag: Boolean?,
)

data class CapabilityCard(
    val styleId: String,
    val audited: Boolean,
    val effectiveCount: Int?,
    val gatedCount: Int?,
    val effective: List<CapabilityEntry>,
    val gated: List<CapabilityEntry>,
)

data class ControlRow(
    val rowId: String,
    val styleId: String,
    val controlId: String,
    val kind: String,
    val label: String,
    val group: String?,
    val param: String,
    /**
     * Engine node path from the same `controls.json`'s `records[].controls[].targetNode`.
     * The flattened `rows[]` do not repeat it, but without it the UI can only hand the
     * bare role (`slotOff`) to the design, which the render request cannot route.
     */
    val targetNode: String? = null,
    val defaultValue: Any?,
    val domain: Map<String, Any?>,
    val optionsRaw: Any?,
    val min: Float?,
    val max: Float?,
    val step: Float?,
    val unit: String?,
    val multiline: Boolean,
    val gatedFlag: Boolean?,
    val visibleFlag: Boolean?,
)

data class MetaControl(
    val id: String,
    val label: String,
    val kind: String,
    val param: String,
    val scope: String,
    val group: String?,
    val defaultValue: Any?,
    val currentValue: Any?,
    val domain: Map<String, Any?>,
    val optionsRaw: Any?,
    val min: Float?,
    val max: Float?,
    val step: Float?,
    val unit: String?,
)

data class InspectorSnapshot(
    val capabilities: Map<String, CapabilityCard>,
    val platformRows: Map<String, List<ControlRow>>,
    val handMeta: Map<String, Map<String, MetaControl>>,
    val capabilityCards: Int,
    val auditedCards: Int,
    val platformRowCount: Int,
    val metaRecords: Int,
    val metaControls: Int,
    val brandOptions: List<ControlOption> = emptyList(),
)

// ---------------------------------------------------------------------------
// Loader
// ---------------------------------------------------------------------------

object InspectorDataSource {
    const val CAPABILITIES_RESOURCE = "files/data/capabilities.json"
    const val CONTROLS_RESOURCE = "files/data/controls.json"
    const val META_RESOURCE = "files/data/inspector-meta.json"
    const val LOGOS_RESOURCE = "files/data/logos.json"

    private var cached: InspectorSnapshot? = null

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): InspectorLoadResult {
        cached?.let { return InspectorLoadResult(it) }
        return try {
            val capabilitiesText = Res.readBytes(CAPABILITIES_RESOURCE).decodeToString()
            val controlsText = Res.readBytes(CONTROLS_RESOURCE).decodeToString()
            val metaText = Res.readBytes(META_RESOURCE).decodeToString()
            val logosText = Res.readBytes(LOGOS_RESOURCE).decodeToString()
            val snapshot = parse(capabilitiesText, controlsText, metaText, logosText)
            cached = snapshot
            InspectorLoadResult(snapshot)
        } catch (t: Throwable) {
            InspectorLoadResult(
                snapshot = emptySnapshot(),
                error = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    fun parse(
        capabilitiesText: String,
        controlsText: String,
        metaText: String,
        logosText: String = "{}",
    ): InspectorSnapshot {
        val capabilitiesDoc = Json.parse(capabilitiesText).jsonMap()
        val capabilityCards = capabilitiesDoc["cards"].jsonList().mapNotNull { raw ->
            val m = raw.jsonMap()
            val styleId = m.jsonString("styleId") ?: return@mapNotNull null
            CapabilityCard(
                styleId = styleId,
                audited = m.jsonBool("audited") ?: false,
                effectiveCount = m.jsonInt("effectiveCount"),
                gatedCount = m.jsonInt("gatedCount"),
                effective = m["effective"].jsonList().mapNotNull(::parseCapabilityEntry),
                gated = m["gated"].jsonList().mapNotNull(::parseCapabilityEntry),
            )
        }

        val controlsDoc = Json.parse(controlsText).jsonMap()
        // `records[].controls[].targetNode` is the only place the engine node path is
        // written down; the flattened `rows[]` copied every other field but this one.
        // The packaged file keeps those records inside `sources[].doc`, so both places
        // are scanned.
        val targetNodes = LinkedHashMap<String, String>()
        fun collectTargetNodes(doc: Map<String, Any?>) {
            doc["records"].jsonList().forEach { rawRec ->
                val rec = rawRec.jsonMap()
                val style = rec.jsonString("styleId") ?: return@forEach
                rec["controls"].jsonList().forEach { rawCtl ->
                    val ctl = rawCtl.jsonMap()
                    val id = ctl.jsonString("id") ?: return@forEach
                    ctl.jsonString("targetNode")?.takeIf { it.isNotBlank() }
                        ?.let { targetNodes["$style::$id"] = it }
                }
            }
        }
        collectTargetNodes(controlsDoc)
        controlsDoc["sources"].jsonList().forEach { rawSrc ->
            collectTargetNodes(rawSrc.jsonMap()["doc"].jsonMap())
        }
        val rows = controlsDoc["rows"].jsonList().mapNotNull { raw ->
            val m = raw.jsonMap()
            val rowId = m.jsonString("rowId") ?: return@mapNotNull null
            val styleId = m.jsonString("styleId") ?: return@mapNotNull null
            if (m.jsonString("scope") != "platform") return@mapNotNull null
            val controlId = m.jsonString("controlId") ?: return@mapNotNull null
            val domain = m["domain"].jsonMap()
            ControlRow(
                rowId = rowId,
                styleId = styleId,
                controlId = controlId,
                kind = m.jsonString("kind") ?: "text",
                label = m.jsonString("label") ?: controlId,
                group = m.jsonString("group"),
                param = m.jsonString("param") ?: "",
                targetNode = targetNodes["$styleId::$controlId"],
                defaultValue = m["default"],
                domain = domain,
                optionsRaw = m["options"],
                min = domain.float("min") ?: m.float("min"),
                max = domain.float("max") ?: m.float("max"),
                step = domain.float("step") ?: m.float("step"),
                unit = domain.jsonString("unit") ?: m.jsonString("unit"),
                multiline = domain.jsonBool("multiline") ?: false,
                gatedFlag = m.jsonBool("gated"),
                visibleFlag = m.jsonBool("visible"),
            )
        }

        val metaDoc = Json.parse(metaText).jsonMap()
        val handMeta = LinkedHashMap<String, Map<String, MetaControl>>()
        metaDoc["records"].jsonList().forEach { raw ->
            val m = raw.jsonMap()
            val styleId = m.jsonString("styleId") ?: return@forEach
            val byId = LinkedHashMap<String, MetaControl>()
            m["controls"].jsonList().forEach { rawControl ->
                val c = rawControl.jsonMap()
                val id = c.jsonString("id") ?: return@forEach
                val domain = c["domain"].jsonMap()
                byId[id] = MetaControl(
                    id = id,
                    label = c.jsonString("label") ?: id,
                    kind = c.jsonString("kind") ?: "text",
                    param = c.jsonString("param") ?: "",
                    scope = c.jsonString("scope") ?: "",
                    group = c.jsonString("group"),
                    defaultValue = c["default"],
                    currentValue = c["currentValue"],
                    domain = domain,
                    optionsRaw = c["options"],
                    min = domain.float("min") ?: c.float("min"),
                    max = domain.float("max") ?: c.float("max"),
                    step = domain.float("step") ?: c.float("step"),
                    unit = domain.jsonString("unit") ?: c.jsonString("unit"),
                )
            }
            handMeta[styleId] = byId
        }

        val platformRows = rows.groupBy { it.styleId }
        return InspectorSnapshot(
            capabilities = capabilityCards.associateBy { it.styleId },
            platformRows = platformRows,
            handMeta = handMeta,
            capabilityCards = capabilityCards.size,
            auditedCards = capabilityCards.count { it.audited },
            platformRowCount = rows.size,
            metaRecords = handMeta.size,
            metaControls = handMeta.values.sumOf { it.size },
            brandOptions = parseBrandOptions(logosText),
        )
    }

    /** Mirrors ControlRegistry's rule and exposes only canonical brands[].key values. */
    internal fun parseBrandOptions(logosText: String): List<ControlOption> = buildList {
        add(ControlOption("auto", "自动匹配"))
        Json.parse(logosText).jsonMap()["brands"].jsonList().forEach { raw ->
            val brand = raw.jsonMap()
            val key = brand.jsonString("key")?.takeIf { it.isNotBlank() } ?: return@forEach
            val label = brand.jsonString("cn")?.takeIf { it.isNotBlank() }
                ?: brand.jsonString("label")?.takeIf { it.isNotBlank() }
                ?: key
            add(ControlOption(key, label))
        }
    }

    private fun parseCapabilityEntry(raw: Any?): CapabilityEntry? {
        val m = raw.jsonMap()
        val controlId = m.jsonString("controlId") ?: return null
        return CapabilityEntry(
            controlId = controlId,
            param = m.jsonString("param") ?: "",
            kind = m.jsonString("kind") ?: "text",
            scope = m.jsonString("scope") ?: "",
            currentValue = m["currentValue"],
            gatedFlag = m.jsonBool("gated"),
            visibleFlag = m.jsonBool("visible"),
        )
    }

    private fun emptySnapshot() = InspectorSnapshot(
        capabilities = emptyMap(),
        platformRows = emptyMap(),
        handMeta = emptyMap(),
        capabilityCards = 0,
        auditedCards = 0,
        platformRowCount = 0,
        metaRecords = 0,
        metaControls = 0,
        brandOptions = emptyList(),
    )
}

private fun Map<String, Any?>.float(key: String): Float? = (this[key] as? Number)?.toFloat()

// ---------------------------------------------------------------------------
// Card model builder (shared by the UI and the desktop verify entry point)
// ---------------------------------------------------------------------------

object InspectorModelFactory {
    fun build(card: LibraryCard, snapshot: InspectorSnapshot): InspectorCardModel {
        val base = buildFrozen(card, snapshot)
        val hints = InspectorHints.provider?.extraControls(card.id).orEmpty()
        return collapseRepeats(mergeHints(base, hints))
    }

    /**
     * The audit emits one row per style group (`…g0`, `…g0#2`, `…g1`), so a stock
     * card can list the same switch 3-5 times under one label. Rows that carry the
     * same label, target and choices are the same control for the user: keep the
     * first and drop the rest (X1 stock cards, e.g. `xiaomi-tag:9` date line).
     */
    private fun collapseRepeats(model: InspectorCardModel): InspectorCardModel {
        val seen = HashSet<String>()
        val kept = ArrayList<InspectorControl>(model.controls.size)
        for (control in model.controls) {
            val key = buildString {
                append(control.label).append('|')
                append(control.param).append('|')
                append(control.kind).append('|')
                append(control.type.name).append('|')
                control.options.forEach { append(it.value).append('=').append(it.display).append(',') }
                append('|').append(control.initial.toString())
            }
            if (!seen.add(key)) continue
            kept += control
        }
        if (kept.size == model.controls.size) return model
        val visible = kept.filterNot { it.hidden }
        return model.copy(
            controls = kept,
            visibleIds = visible.map { it.controlId },
            hiddenIds = kept.filter { it.hidden }.map { it.controlId },
            sections = sections(visible),
        )
    }

    private fun buildFrozen(card: LibraryCard, snapshot: InspectorSnapshot): InspectorCardModel {
        val capability = snapshot.capabilities[card.id]
            ?: return emptyModel(card, audited = false)
        return if (capability.audited) {
            buildAudited(card, capability, snapshot)
        } else {
            buildUnaudited(card, capability, snapshot)
        }
    }

    private fun mergeHints(
        model: InspectorCardModel,
        hints: List<InspectorControl>,
    ): InspectorCardModel {
        if (hints.isEmpty()) return model
        val controls = model.controls.toMutableList()
        val additions = mutableListOf<InspectorControl>()
        for (hint in hints) {
            val sameParam = controls.filter { it.param == hint.param && it.param.isNotBlank() }
            val visible = sameParam.filterNot { it.hidden }
            // Replace a frozen Select only when it is unusable: no choices, or
            // choices whose labels are raw ids the UI had to mask as 未设置.
            val unusable = visible.all { control ->
                control.type == ControlType.Select &&
                    (control.options.isEmpty() || control.options.all { it.display == "未设置" })
            }
            // A frozen Select that never got a value but already lists exactly the
            // hint's choices is safe to swap: the hint only adds the declared
            // default and clearer labels (白色/黑色 → 浅色/深色).
            val sameChoices = visible.size == 1 &&
                visible[0].options.map { it.value }.toSet() == hint.options.map { it.value }.toSet() &&
                (visible[0].initial as? ControlValue.Str)?.value.isNullOrBlank()
            if (!unusable && !sameChoices) continue
            controls.removeAll(visible)
            additions += hint
        }
        if (additions.isEmpty()) return model
        val all = controls + additions
        val visible = all.filterNot { it.hidden }
        return model.copy(
            controls = all,
            visibleIds = visible.map { it.controlId },
            hiddenIds = all.filter { it.hidden }.map { it.controlId },
            sections = sections(visible),
        )
    }

    private fun buildAudited(
        card: LibraryCard,
        capability: CapabilityCard,
        snapshot: InspectorSnapshot,
    ): InspectorCardModel {
        val meta = snapshot.handMeta[card.id]
            ?: return emptyModel(
                card,
                audited = true,
                effectiveCount = capability.effectiveCount,
                gatedCount = capability.gatedCount,
            )
        val missing = capability.effective.filter { meta[it.controlId] == null }
        if (missing.isNotEmpty()) {
            return emptyModel(
                card,
                audited = true,
                effectiveCount = capability.effectiveCount,
                gatedCount = capability.gatedCount,
            )
        }

        val controls = capability.effective.map { entry ->
            val display = meta.getValue(entry.controlId)
            toControl(
                cardId = card.id,
                controlId = entry.controlId,
                kind = entry.kind,
                param = entry.param,
                scope = entry.scope,
                group = display.group,
                label = display.label,
                currentValue = entry.currentValue,
                declaredDefault = display.defaultValue,
                domain = display.domain,
                optionsRaw = display.optionsRaw,
                min = display.min,
                max = display.max,
                step = display.step,
                unit = display.unit,
                gated = false,
                hiddenByFlag = entry.visibleFlag == false || entry.gatedFlag == true,
                brandOptions = snapshot.brandOptions,
            )
        }
        val gatedControls = capability.gated.map { entry ->
            val display = meta[entry.controlId]
            toControl(
                cardId = card.id,
                controlId = entry.controlId,
                kind = entry.kind,
                param = entry.param,
                scope = entry.scope,
                group = display?.group,
                label = display?.label ?: entry.controlId,
                currentValue = entry.currentValue,
                declaredDefault = display?.defaultValue,
                domain = display?.domain ?: emptyMap(),
                optionsRaw = display?.optionsRaw,
                min = display?.min,
                max = display?.max,
                step = display?.step,
                unit = display?.unit,
                gated = true,
                hiddenByFlag = true,
                brandOptions = snapshot.brandOptions,
            )
        }
        val all = controls + gatedControls
        val visible = all.filterNot { it.hidden }
        val hidden = all.filter { it.hidden }
        val gatedCount = capability.gatedCount ?: capability.gated.size
        return InspectorCardModel(
            cardId = card.id,
            cardName = card.name,
            audited = true,
            effectiveCount = capability.effectiveCount ?: capability.effective.size,
            gatedCount = gatedCount,
            declaredCount = null,
            effectiveIds = capability.effective.map { it.controlId },
            gatedIds = capability.gated.map { it.controlId },
            visibleIds = visible.map { it.controlId },
            hiddenIds = hidden.map { it.controlId },
            controls = all,
            sections = sections(visible),
        )
    }

    private fun buildUnaudited(
        card: LibraryCard,
        capability: CapabilityCard,
        snapshot: InspectorSnapshot,
    ): InspectorCardModel {
        val styleId = card.systemStyle
        if (styleId.isNullOrBlank()) {
            return emptyModel(card, audited = false)
        }
        val rows = snapshot.platformRows[styleId].orEmpty()
        if (rows.isEmpty()) {
            return emptyModel(card, audited = false, declaredCount = 0)
        }
        val controls = rows.map { row ->
            // Hand the design the key the engine can route, not the raw role. The same
            // rule as product/ControlRegistry.irConsumer: text/toggle/gravity/slider need
            // the node path; theme/logo/anchor/offset are bare. Blank means this row has
            // no engine binding, so it is hidden rather than silently inert.
            val consumer = engineConsumer(row.kind, row.param, row.targetNode)
            toControl(
                cardId = card.id,
                controlId = row.controlId,
                kind = row.kind,
                param = consumer,
                scope = "",
                group = row.group,
                label = row.label,
                currentValue = null,
                declaredDefault = row.defaultValue,
                domain = row.domain,
                optionsRaw = row.optionsRaw,
                min = row.min,
                max = row.max,
                step = row.step,
                unit = row.unit,
                gated = false,
                hiddenByFlag = row.visibleFlag == false || row.gatedFlag == true || consumer.isBlank(),
                fallbackInitial = true,
                brandOptions = snapshot.brandOptions,
            )
        }
        val visible = controls.filterNot { it.hidden }
        val hidden = controls.filter { it.hidden }
        return InspectorCardModel(
            cardId = card.id,
            cardName = card.name,
            audited = false,
            effectiveCount = rows.size,
            gatedCount = null,
            declaredCount = rows.size,
            effectiveIds = rows.map { it.controlId },
            gatedIds = emptyList(),
            visibleIds = visible.map { it.controlId },
            hiddenIds = hidden.map { it.controlId },
            controls = controls,
            sections = sections(visible),
        )
    }

    /**
     * A card with nothing safe to show (no joined source, no declared rows, or no
     * human label for any row). The editor renders one neutral line; no error text
     * and no retry affordance is produced from data shape.
     */
    private fun emptyModel(
        card: LibraryCard,
        audited: Boolean,
        effectiveCount: Int? = null,
        gatedCount: Int? = null,
        declaredCount: Int? = null,
    ): InspectorCardModel = InspectorCardModel(
        cardId = card.id,
        cardName = card.name,
        audited = audited,
        effectiveCount = effectiveCount,
        gatedCount = gatedCount,
        declaredCount = declaredCount,
        effectiveIds = emptyList(),
        gatedIds = emptyList(),
        visibleIds = emptyList(),
        hiddenIds = emptyList(),
        controls = emptyList(),
        sections = emptyList(),
    )

    private fun sections(visible: List<InspectorControl>): List<InspectorSectionModel> {
        return listOf(InspectorSection.Border, InspectorSection.Logo, InspectorSection.Text, InspectorSection.Layout, InspectorSection.Theme)
            .map { section ->
                InspectorSectionModel(
                    section = section,
                    controls = visible.filter { it.section == section },
                )
            }
    }

    private fun toControl(
        cardId: String,
        controlId: String,
        kind: String,
        param: String,
        scope: String,
        group: String?,
        label: String,
        currentValue: Any?,
        declaredDefault: Any?,
        domain: Map<String, Any?>,
        optionsRaw: Any?,
        min: Float?,
        max: Float?,
        step: Float?,
        unit: String?,
        gated: Boolean,
        hiddenByFlag: Boolean,
        fallbackInitial: Boolean = false,
        brandOptions: List<ControlOption>,
    ): InspectorControl {
        val type = when (kind) {
            "toggle" -> ControlType.Switch
            "slider", "xyOffset" -> ControlType.Slider
            "anchorGrid", "gravitySegment", "modeSegment", "themeSwatches" -> ControlType.Select
            "brandPicker" -> ControlType.Brand
            "colorField" -> ControlType.Color
            else -> ControlType.Text
        }
        val options = if (kind == "brandPicker") brandOptions
        else parseOptions(optionsRaw, domain["options"].jsonList(), kind)
        val multiline = domain.jsonBool("multiline") ?: false
        val initial = initialValue(type, currentValue, declaredDefault, min, max, step, fallbackInitial)
        val humanLabel = HumanLabels.forControl(label, controlId)
        val section = inspectorSectionFor(kind, param, scope, group, humanLabel ?: label)
        return InspectorControl(
            key = "$cardId::$controlId",
            controlId = controlId,
            param = param,
            label = humanLabel.orEmpty(),
            kind = kind,
            section = section,
            type = type,
            initial = initial,
            min = min,
            max = max,
            step = step,
            options = options,
            multiline = multiline,
            declaredDefault = formatValue(declaredDefault),
            currentText = formatValue(currentValue),
            autoMatch = domain.jsonBool("autoMatch"),
            unit = displayUnit(unit),
            hidden = gated || hiddenByFlag || humanLabel == null,
        )
    }

    private fun initialValue(
        type: ControlType,
        currentValue: Any?,
        declaredDefault: Any?,
        min: Float?,
        max: Float?,
        step: Float?,
        fallbackInitial: Boolean,
    ): ControlValue {
        return when (type) {
            ControlType.Switch -> {
                val flag = (currentValue as? Boolean)
                    ?: when ((declaredDefault as? String)?.lowercase()) {
                        "on", "true" -> true
                        "off", "false" -> false
                        else -> null
                    }
                    ?: false
                ControlValue.Flag(flag)
            }
            ControlType.Slider -> {
                val number = (currentValue as? Number)?.toFloat()
                    ?: (declaredDefault as? Number)?.toFloat()
                val lo = min
                val hi = max
                val value = when {
                    number != null -> number
                    lo != null && hi != null && fallbackInitial -> {
                        val raw = lo + (hi - lo) / 2f
                        quantize(raw, lo, hi, step)
                    }
                    lo != null -> lo
                    else -> 0f
                }
                ControlValue.Num(if (lo != null) value.coerceIn(lo, hi ?: lo) else value)
            }
            ControlType.Select, ControlType.Color, ControlType.Text -> {
                val text = currentValue?.toString()
                    ?: declaredDefault?.toString()
                    ?: ""
                ControlValue.Str(if (type == ControlType.Text) HumanLabels.forText(text) else text)
            }
            ControlType.Brand -> {
                val text = currentValue?.toString()
                    ?: declaredDefault?.toString()
                    ?: ""
                ControlValue.Str(HumanLabels.forBrand(text))
            }
        }
    }

    private fun quantize(value: Float, min: Float, max: Float, step: Float?): Float {
        if (step == null || step <= 0f) return value.coerceIn(min, max)
        val steps = ((value - min) / step).toInt()
        return (min + steps * step).coerceIn(min, max)
    }

    private fun parseOptions(
        optionsRaw: Any?,
        plainOptions: List<Any?>,
        kind: String,
    ): List<ControlOption> {
        val labelled = optionsRaw.jsonList().mapNotNull { raw ->
            val m = raw.jsonMap()
            val value = m.jsonString("value") ?: return@mapNotNull null
            ControlOption(value = value, display = m.jsonString("label") ?: optionDisplay(value, kind))
        }
        if (labelled.isNotEmpty()) return labelled
        return plainOptions.mapNotNull { it as? String }.map { value ->
            ControlOption(value = value, display = optionDisplay(value, kind))
        }
    }

    private fun optionDisplay(value: String, kind: String): String =
        if (kind == "themeSwatches") HumanLabels.forThemeOption(value) else HumanLabels.forOption(value)

internal fun inspectorSectionFor(
        kind: String,
        param: String,
        scope: String,
        group: String?,
        label: String,
    ): InspectorSection {
        if (kind == "themeSwatches" || param == "theme" || param.startsWith("theme")) {
            return InspectorSection.Theme
        }
        if (kind == "brandPicker" || scope == "design-logo") {
            return InspectorSection.Logo
        }
        // Text-row switches (e.g. showLogo) live in the text section even though
        // their param contains "logo"; only a standalone logo control goes to Logo.
        if (scope == "design-text" || param.startsWith("text.")) {
            return if (group == "layout") InspectorSection.Layout else InspectorSection.Text
        }
        if (param.contains("logo", ignoreCase = true) ||
            label.contains("字标") || label.contains("Logo", ignoreCase = true)
        ) {
            return InspectorSection.Logo
        }
        if (kind in setOf("anchorGrid", "xyOffset", "gravitySegment", "modeSegment")) {
            return InspectorSection.Layout
        }
        if (group == "layout") {
            if (param.contains("frame") || param.contains("border") ||
                param.contains("shadow") || param.contains("inset")
            ) {
                return InspectorSection.Border
            }
            return InspectorSection.Layout
        }
        if (kind == "text" || kind == "toggle") {
            return if (group == "layout") InspectorSection.Layout else InspectorSection.Text
        }
        if (group == "content") return InspectorSection.Text
        if (label.contains("主题") || label.contains("颜色") || label.contains("色块")) {
            return InspectorSection.Theme
        }
        if (label.contains("字标") || label.contains("Logo", ignoreCase = true)) {
            return InspectorSection.Logo
        }
        if (label.contains("字") || label.contains("缩放") || label.contains("间距") ||
            label.contains("不透明") || label.contains("tracking", ignoreCase = true) ||
            label.contains("size", ignoreCase = true) || label.contains("alpha", ignoreCase = true)
        ) {
            return InspectorSection.Text
        }
        if (label.contains("边") || label.contains("框") ||
            label.contains("shadow", ignoreCase = true) || label.contains("frame", ignoreCase = true)
        ) {
            return InspectorSection.Border
        }
        return InspectorSection.Layout
    }

    fun formatValue(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) "on" else "off"
        is Number -> formatNumber(value.toDouble())
        else -> value.toString()
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val rounded = round(value * 1000.0) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    private fun displayUnit(unit: String?): String? = when (unit) {
        null, "", "unitless", "alpha" -> null
        "pct", "percent" -> "%"
        "multiplier" -> "×"
        "count" -> "个"
        "pctOfLongEdge" -> "% 长边"
        else -> unit
    }
}

/**
 * Display vocabulary for values that the frozen sources carry as identifiers.
 *
 * This is a display-only layer: it never changes membership or values. A control
 * whose source label is an identifier and cannot be mapped here is dropped by
 * [InspectorModelFactory] instead of showing the raw key to a user.
 */
internal object HumanLabels {
    private val controls: Map<String, String> = mapOf(
        "footerPct" to "底部留白",
        "marginPct" to "外边距",
        "radiusPct" to "圆角半径",
        "shadow" to "阴影强度",
        "blur" to "模糊强度",
        "sidePadPct" to "左右留白",
        "topPadPct" to "顶部留白",
        "logoPct" to "字标大小",
        "paletteGapPct" to "色块间距",
        "palettePct" to "色块大小",
        "paletteRowPct" to "色块行高",
        "brandHeightPct" to "字标高度",
        "brandInsetPct" to "字标内缩",
        "brandTopPct" to "字标顶部位置",
        "cardHeightPct" to "卡片高度",
        "cardInsetPct" to "卡片内缩",
        "cardMattePct" to "卡片留白",
        "cardRadiusPct" to "卡片圆角",
        "contentSide" to "内容位置",
        "rowBoxHPct" to "参数行高度",
        "rowBoxWPct" to "参数行宽度",
        "rowLabelPct" to "参数标签字号",
        "rowPitchPct" to "参数行间距",
        "rowValueGapPct" to "参数值间距",
        "rowValuePct" to "参数值字号",
        "rowsTopPct" to "参数区顶部位置",
        "textInsetPct" to "文字内缩",
        "brandXPct" to "字标横向位置",
        "brandY" to "字标纵向位置",
        "exifY" to "参数行纵向位置",
        "sticker" to "贴纸文字",
        "stickerRightPct" to "贴纸右边距",
        "stickerScalePct" to "贴纸缩放",
        "insetPct" to "内缩",
        "footerOffsetPct" to "底栏偏移",
        "borderPct" to "边框宽度",
        "signature" to "签名文字",
        "frame" to "相框文字",
        "gridFirstXPct" to "网格起点 X",
        "gridFirstYPct" to "网格起点 Y",
        "gridPitchXPct" to "网格列间距",
        "gridPitchYPct" to "网格行间距",
        "labelPct" to "标签字号",
        "panelBottomPct" to "面板底部位置",
        "panelHeightPct" to "面板高度",
        "panelRadiusPct" to "面板圆角",
        "panelWidthPct" to "面板宽度",
        "swatchCols" to "色块列数",
        "swatchPct" to "色块大小",
        "swatchRows" to "色块行数",
        "cardWidthPct" to "卡片宽度",
        "glassLayout" to "玻璃布局",
        "pillOrder" to "胶囊顺序",
        "contentW" to "内容宽度",
        "logoGap" to "字标间距",
        "sepX" to "分隔线位置",
        "brandTextSizePct" to "品牌字号",
    )

    private val options: Map<String, String> = mapOf(
        "white" to "白色",
        "paper" to "纸白",
        "cream" to "奶油",
        "stone" to "石色",
        "ink" to "墨色",
        "black" to "黑色",
        "left" to "居左",
        "right" to "居右",
        "center" to "居中",
        "none" to "无",
        "lumo" to "Lumo",
        "hasselblad" to "哈苏",
        "HASSELBLAD" to "哈苏",
        "zeiss" to "蔡司",
        "leica" to "徕卡",
        "FUJIFILM" to "富士",
        "h2" to "H2",
        "h3" to "H3",
        "h4" to "H4",
        "l1" to "L1",
        "l2" to "L2",
        "l3" to "L3",
        "exif" to "参数",
        "brand-exif" to "品牌 + 参数",
        "brand" to "品牌",
        "profile-chip" to "品牌胶囊",
        "simple" to "简约",
        "overlay" to "叠加",
        "classic" to "经典",
        "header" to "页头",
        "chip" to "胶囊",
        "footer" to "页脚",
        "text-left" to "文字居左",
        "text-right" to "文字居右",
        "split" to "分栏",
        "pill" to "胶囊",
        "palette" to "色板",
        "vendor" to "厂商默认",
        "title" to "标题",
        "model" to "机型",
        "logo" to "字标",
        "brand-placement" to "品牌摆位",
        "inside" to "照片内",
        "in-photo" to "照片内",
        "stack" to "堆叠",
        "single" to "单张",
        "reference" to "参考",
        "bar" to "条状",
        "vertical" to "竖排",
        "horizontal" to "横排",
        "tl" to "左上",
        "tr" to "右上",
        "bl" to "左下",
        "br" to "右下",
        "graduation1" to "毕业样式 1",
        "graduation2" to "毕业样式 2",
        "leftTop" to "左上",
        "leftCenter" to "左中",
        "leftBottom" to "左下",
        "centerTop" to "上中",
        "centerBottom" to "下中",
        "rightTop" to "右上",
        "rightCenter" to "右中",
        "rightBottom" to "右下",
        "bottomHorCenter" to "底部居中",
    )

    private val brands: Map<String, String> = mapOf(
        "auto" to "自动匹配",
        "laika" to "徕卡",
        "laika_long" to "徕卡长标",
        "hasu_long_black" to "哈苏长标 · 黑",
        "hasu_long_white" to "哈苏长标 · 白",
        "hassel_h_black" to "哈苏 H · 黑",
        "hassel_wordmark_black" to "哈苏字标 · 黑",
        "lumo_black" to "Lumo · 黑",
        "fushi_white" to "富士 · 白",
        "zeiss" to "蔡司",
    )

    /** Stock labels wrap an internal line key in 「」; map it to a human line name. */
    private val lineTokens: Map<String, String> = mapOf(
        "modelLine" to "机型",
        "dateLine" to "日期",
        "locationLine" to "地点",
        "exifCompositeLine" to "参数",
        "customLine" to "自定义",
        "model" to "机型",
        "dateLine+locationLine" to "日期与地点",
        "greetingLine" to "问候",
        "imageParams" to "图片参数",
        "literalText" to "文字",
        "template" to "模板",
    )

    private val bracketToken = Regex("「([^」]*)」")

    /** Returns a human label, or null when the source only has an identifier. */
    fun forControl(sourceLabel: String, controlId: String): String? {
        val raw = sourceLabel.trim()
        if (raw.isEmpty()) return null
        if (raw.hasCjk()) return sanitize(raw)
        controls[raw]?.let { return it }
        val idTail = controlId.substringAfterLast(':').substringAfterLast('.')
        controls[idTail]?.let { return it }
        controls[raw.substringAfterLast(':').substringAfterLast('.')]?.let { return it }
        return null
    }

    /**
     * Removes 「internalKey」 fragments from an otherwise Chinese stock label.
     * Returns null when nothing readable is left, so the control is dropped.
     */
    private fun sanitize(raw: String): String? {
        val cleaned = bracketToken.replace(raw) { match ->
            val key = match.groupValues[1].substringBefore('[').trim()
            lineTokens[key] ?: ""
        }.replace(Regex("\\s+"), " ").trim()
        if (!cleaned.hasCjk()) return null
        return cleaned
    }

    /** Unknown identifiers remain distinguishable from an unselected value. */
    fun forOption(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return raw
        options[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return "未知选项（$raw）"
    }

    /** Uses the shared registry vocabulary for themes and labels unknown IDs explicitly. */
    fun forThemeOption(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return "未选择"
        val display = ControlLabels.forOption(raw)
        return if (display == null || display == "未设置") "未知主题（$raw）" else display
    }

    /** A free-text control may carry an enum token; keep prose, humanise keys. */
    fun forText(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        options[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        if (Regex("^[a-z][a-z0-9_-]*$").matches(raw)) return "未设置"
        return raw
    }

    /** Brand logos are asset keys in the frozen source; show the brand name only. */
    fun forBrand(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        brands[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return "未设置"
    }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }
}

/**
 * The design key the render request can route for one stock-card row. Mirrors
 * `product/ControlRegistry.irConsumer` so the frozen phone panel and the registry
 * agree on the key domain; a blank result means the row has no engine binding.
 */
private fun engineConsumer(kind: String, param: String, targetNode: String?): String = when (kind) {
    "themeSwatches" -> "theme"
    "brandPicker" -> "logo"
    "text" -> targetNode?.let { "params.slotText:$it" } ?: ""
    "toggle" -> targetNode?.let { "params.slotOff:$it" } ?: ""
    "gravitySegment" -> targetNode?.let { "params.slotGravity:$it" } ?: ""
    "slider" -> targetNode?.let { "params.$param:$it" } ?: ""
    "anchorGrid" -> "params.stockAnchor"
    "xyOffset" -> when (param) {
        "stockOffsetXPct" -> "params.stockOffsetXPct"
        "stockOffsetYPct" -> "params.stockOffsetYPct"
        else -> ""
    }
    else -> ""
}
