package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.library.jsonInt
import com.framemark.native.product.CardControlModel
import com.framemark.native.product.ControlBackend
import com.framemark.native.product.ControlSpec

/**
 * P3c's IR slot binding: which **bundle IR node path** a stock-card control drives.
 *
 * The vendor document names nodes `b0/bottom0/…`; the bundle the engine renders
 * names them `b0/g0/…`. The two are not isomorphic, and the engine only ever reads
 * the bundle spelling — so this table is the one place that re-keys a UI control
 * onto an engine key. Nothing downstream may invent a path.
 */
data class IrSlotEntry(
    val styleId: String,
    val controlId: String,
    val param: String,
    val nodes: List<String>,
    val classification: String,
    val confidence: String,
    val reason: String,
    val kind: String,
    val slotType: String?,
    val maxLength: Int?,
    val multiline: Boolean,
) {
    /** Engine key for the primary node, e.g. `params.slotText:b0/g0/g0`. */
    val primaryConsumer: String? get() = nodes.firstOrNull()?.let { consumerFor(it) }

    /** Remaining nodes of a shared target: the same value must land on each. */
    val companionConsumers: List<String> get() = nodes.drop(1).map { consumerFor(it) }

    private fun consumerFor(node: String): String = when (param) {
        "slotText" -> "params.slotText:$node"
        "slotOff" -> "params.slotOff:$node"
        "slotAlpha" -> "params.slotAlpha:$node"
        "slotGravity" -> "params.slotGravity:$node"
        "stockAnchor" -> "params.stockAnchor"
        "stockOffsetXPct" -> "params.stockOffsetXPct"
        "stockOffsetYPct" -> "params.stockOffsetYPct"
        "stockOffset" -> "params.stockOffsetXPct"
        "theme" -> "theme"
        "brandLogoFile" -> "params.brandLogoFile"
        else -> ""
    }
}

class IrSlotBinding private constructor(
    private val entries: Map<String, IrSlotEntry>,
    val stats: Stats,
) {
    data class Stats(
        val styles: Int,
        val entries: Int,
        val expressible: Int,
        val unsupported: Int,
        val needsRegen: Int,
    )

    fun entry(styleId: String?, controlId: String): IrSlotEntry? =
        styleId?.let { entries["$it::$controlId"] }

    operator fun contains(styleId: String?): Boolean =
        styleId != null && entries.keys.any { it.startsWith("$styleId::") }

    companion object {
        val EMPTY = IrSlotBinding(emptyMap(), Stats(0, 0, 0, 0, 0))

        fun load(json: String): IrSlotBinding {
            val root = Json.parse(json).jsonMap()
            val styles = root["styles"].jsonMap()
            val out = HashMap<String, IrSlotEntry>()
            var expressible = 0
            var unsupported = 0
            var needsRegen = 0
            for ((styleId, rawStyle) in styles) {
                val style = rawStyle.jsonMap()
                for (rawEntry in style["controls"].jsonList()) {
                    val map = rawEntry.jsonMap()
                    val controlId = map.jsonString("controlId") ?: continue
                    val nodes = map["nodes"].jsonList().mapNotNull { it as? String }
                    val classification = map.jsonString("classification") ?: "unknown"
                    when (classification) {
                        "expressible" -> expressible++
                        "unsupported" -> unsupported++
                        "needs-ir-regen" -> needsRegen++
                    }
                    val domain = map["domain"].jsonMap()
                    out["$styleId::$controlId"] = IrSlotEntry(
                        styleId = styleId,
                        controlId = controlId,
                        param = map.jsonString("param") ?: "",
                        nodes = nodes,
                        classification = classification,
                        confidence = map.jsonString("confidence") ?: "",
                        reason = map.jsonString("reason") ?: "",
                        kind = map.jsonString("kind") ?: "",
                        slotType = map.jsonString("slotType"),
                        maxLength = domain.jsonInt("maxLength"),
                        multiline = domain["multiline"] as? Boolean ?: false,
                    )
                }
            }
            return IrSlotBinding(out, Stats(styles.size, out.size, expressible, unsupported, needsRegen))
        }
    }
}

/**
 * Builds the editor's card model out of `product.ControlRegistry` plus the IR slot
 * binding, so membership, label, type, range and options all come from the audited
 * registry and the design key is the one the engine really reads.
 */
object RegistryInspectorFactory {

    const val DISABLED_REASON = "引擎暂不支持"

    /** What the factory decided per control, for the static "no dead control" gate. */
    data class Decision(
        val cardId: String,
        val controlId: String,
        val shown: Boolean,
        val disabled: Boolean,
        val consumer: String,
        val companions: List<String>,
        val reason: String,
    )

    data class Built(val model: InspectorCardModel, val decisions: List<Decision>)

    fun build(
        cardId: String,
        cardName: String,
        category: String,
        categoryName: String,
        spec: CardControlModel,
        binding: IrSlotBinding,
    ): Built {
        val decisions = ArrayList<Decision>(spec.controls.size)
        val controls = ArrayList<InspectorControl>(spec.controls.size)
        for (control in spec.controls) {
            val entry = if (spec.backend == ControlBackend.Ir) binding.entry(spec.styleId, control.controlId) else null
            val verdict = decide(spec.backend, control, entry)
            decisions += Decision(
                cardId = cardId,
                controlId = control.controlId,
                shown = verdict.shown,
                disabled = verdict.disabled,
                consumer = verdict.consumer,
                companions = verdict.companions,
                reason = verdict.reason,
            )
            if (!verdict.shown) continue
            controls += toControl(cardId, control, verdict)
        }
        val sections = InspectorSection.entries.mapNotNull { section ->
            val rows = controls.filter { it.section == section }
            if (rows.isEmpty()) null else InspectorSectionModel(section, rows)
        }
        val visibleIds = controls.map { it.controlId }
        val hiddenIds = spec.controls.map { it.controlId }.filterNot { it in visibleIds }
        val model = InspectorCardModel(
            cardId = cardId,
            cardName = cardName,
            audited = spec.audited,
            isIr = spec.backend == ControlBackend.Ir,
            effectiveCount = spec.effectiveCount,
            gatedCount = spec.gatedCount,
            declaredCount = spec.controls.size,
            effectiveIds = visibleIds,
            gatedIds = spec.controls.filter { it.gated }.map { it.controlId },
            visibleIds = visibleIds,
            hiddenIds = hiddenIds,
            controls = controls,
            sections = sections,
        )
        return Built(model, decisions)
    }

    private data class Verdict(
        val shown: Boolean,
        val disabled: Boolean,
        val consumer: String,
        val companions: List<String>,
        val reason: String,
    )

    /**
     * The dead-control policy, in one place:
     * gated → never shown; `unsupported` → not shown; `needs-ir-regen` → shown but
     * disabled with a reason; an IR control with no binding → shown but disabled
     * (we refuse to guess a node path).
     */
    private fun decide(backend: ControlBackend, control: ControlSpec, entry: IrSlotEntry?): Verdict {
        if (control.gated) return Verdict(false, false, control.consumer, emptyList(), "gated")
        if (!control.visible) {
            return Verdict(false, false, control.consumer, emptyList(), control.hiddenReason ?: "hidden")
        }
        if (entry == null) {
            // Hand cards carry the audited param already; IR cards must be bound.
            if (backend == ControlBackend.Hand) {
                return Verdict(true, false, control.consumer, emptyList(), "hand-audited")
            }
            return Verdict(
                shown = true,
                disabled = true,
                consumer = control.consumer,
                companions = emptyList(),
                reason = "no-ir-binding",
            )
        }
        return when (entry.classification) {
            "unsupported" -> Verdict(false, false, entry.primaryConsumer ?: control.consumer, emptyList(), "unsupported")
            "needs-ir-regen" -> Verdict(
                shown = true,
                disabled = true,
                consumer = entry.primaryConsumer ?: control.consumer,
                companions = entry.companionConsumers,
                reason = "needs-ir-regen",
            )
            else -> Verdict(
                shown = true,
                disabled = false,
                consumer = entry.primaryConsumer ?: control.consumer,
                companions = entry.companionConsumers,
                reason = "bound:${entry.confidence.ifBlank { "n/a" }}",
            )
        }
    }

    private fun toControl(cardId: String, control: ControlSpec, verdict: Verdict): InspectorControl {
        val type = when (control.type) {
            com.framemark.native.product.ControlType.Toggle -> ControlType.Switch
            com.framemark.native.product.ControlType.Slider,
            com.framemark.native.product.ControlType.Number,
            com.framemark.native.product.ControlType.Stepper,
            -> ControlType.Slider
            com.framemark.native.product.ControlType.Segmented,
            com.framemark.native.product.ControlType.Select,
            -> if (control.consumer == "logo" || control.sourceKind == "brandPicker") ControlType.Brand else ControlType.Select
            com.framemark.native.product.ControlType.Color -> ControlType.Color
            com.framemark.native.product.ControlType.Text -> ControlType.Text
            null -> ControlType.Text
        }
        val section = InspectorModelFactory.inspectorSectionFor(
            kind = control.sourceKind,
            param = verdict.consumer,
            scope = control.scope,
            group = control.group,
            label = control.label,
        )
        val initial = when (val default = control.defaultValue ?: control.currentValue) {
            is Boolean -> ControlValue.Flag(default)
            is Number -> ControlValue.Num(default.toFloat())
            null -> when (type) {
                ControlType.Switch -> ControlValue.Flag(false)
                ControlType.Slider -> ControlValue.Num(control.min?.toFloat() ?: 0f)
                else -> ControlValue.Str("")
            }
            else -> ControlValue.Str(default.toString())
        }
        val declared = control.defaultValue?.toString()
        val maxValue = control.max?.toFloat()
        return InspectorControl(
            key = "$cardId::${control.controlId}",
            controlId = control.controlId,
            param = verdict.consumer,
            label = control.label,
            kind = control.sourceKind,
            section = section,
            type = type,
            initial = initial,
            min = control.min?.toFloat(),
            max = maxValue,
            step = control.step?.toFloat(),
            options = control.options.map { ControlOption(it.value, it.label) },
            multiline = false,
            declaredDefault = declared,
            currentText = control.currentValue?.toString() ?: declared,
            autoMatch = null,
            // P0: never pass the raw engine unit through — map it to display copy.
            unit = FmUnits.suffixFor(control.unit, maxValue, verdict.consumer),
            unitScale = FmUnits.scaleFor(control.unit, maxValue, verdict.consumer),
            hidden = false,
            companions = verdict.companions,
            disabledReason = if (verdict.disabled) DISABLED_REASON else null,
        )
    }
}
