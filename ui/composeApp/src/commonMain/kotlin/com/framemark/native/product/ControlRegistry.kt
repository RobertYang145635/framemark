package com.framemark.native.product

/** Which engine backend renders a card: stock IR style or a hand-written template. */
enum class ControlBackend { Ir, Hand }

/** The eight control types frozen by contracts/capabilities.schema.json. */
enum class ControlType { Slider, Toggle, Segmented, Select, Color, Text, Number, Stepper }

data class ControlOption(val value: String, val label: String)

/** Where the consumer path came from: the audited `param`, or a documented derivation. */
enum class ConsumerSource { Declared, Derived }

/**
 * One edit control of one card.
 *
 * [consumer] is the design field the control writes in the unified render request
 * (`theme`, `ratio`, `fontScale`, `logo`, `params.<key>`, `text.<key>`).
 * [routable] says the path lands on a request field; [consumed] says the current
 * engine actually reads it (P1b: IR only theme; hand params/text per the audit
 * verdict; logo is not wired through the desktop bridge yet).
 */
data class ControlSpec(
    val controlId: String,
    val type: ControlType?,
    val sourceKind: String,
    val label: String,
    val consumer: String,
    val consumerSource: ConsumerSource,
    val scope: String,
    val group: String?,
    val defaultValue: Any?,
    val currentValue: Any?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val options: List<ControlOption>,
    val unit: String?,
    val visible: Boolean,
    val gated: Boolean,
    val audited: Boolean,
    val routable: Boolean,
    val consumed: Boolean,
    val hiddenReason: String?,
    /** Raw audit slot role (`slotText`, `stockAnchor`, …); null for hand cards. */
    val sourceParam: String?,
)

data class CardControlModel(
    val cardId: String,
    val name: String,
    val category: String,
    val categoryName: String,
    val brand: String,
    val backend: ControlBackend,
    /** `taxonomy.systemStyle` for IR cards, the card id for hand cards. */
    val styleId: String?,
    val audited: Boolean,
    val controls: List<ControlSpec>,
    val effectiveCount: Int?,
    val gatedCount: Int?,
    /** Non-null exactly when no control is visible (explicit empty, never "missing"). */
    val emptyReason: String?,
) {
    val visibleControls: List<ControlSpec> get() = controls.filter { it.visible }
    val visibleCount: Int get() = visibleControls.size
}

data class CapabilityCounts(
    val rows: Int?,
    val effective: Int?,
    val gated: Int?,
    val auditedCards: Int?,
    val unauditedCards: Int?,
)

data class RegistryStats(
    val cards: Int,
    val auditedCards: Int,
    val unauditedCards: Int,
    val cardsWithVisible: Int,
    val emptyCards: Int,
    val capabilityRows: Int,
    val effectiveRows: Int,
    val gatedRows: Int,
    val declaredCounts: CapabilityCounts?,
    val platformRows: Int,
    val platformStyles: Int,
    val metaRowsJoined: Int,
    val vendoredJoined: Int,
    val handPanelRows: Int,
    val handPanelCardPairs: Int,
    val handPanelRangeMismatches: List<String>,
    val visibleControls: Int,
    val hiddenControls: Int,
    val gatedControls: Int,
    val routableControls: Int,
    val consumedControls: Int,
    val unmappedCards: List<String>,
)

data class ValidationIssue(
    val rule: String,
    val cardId: String?,
    val controlId: String?,
    val message: String,
) {
    override fun toString(): String =
        "[$rule]${cardId?.let { " $it" }.orEmpty()}${controlId?.let { "::$it" }.orEmpty()} $message"
}

class ControlRegistry(
    val cards: List<CardControlModel>,
    val stats: RegistryStats,
    private val taxonomyOrder: List<String> = cards.map { it.cardId },
) {
    private val byId = cards.associateBy { it.cardId }

    fun card(cardId: String): CardControlModel? = byId[cardId]

    fun visibleControls(cardId: String): List<ControlSpec> = byId[cardId]?.visibleControls ?: emptyList()

    fun validate(): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun issue(rule: String, cardId: String?, controlId: String?, message: String) {
            issues += ValidationIssue(rule, cardId, controlId, message)
        }

        val ids = cards.map { it.cardId }
        if (ids.size != ids.toSet().size) issue("CARD_UNIQUE", null, null, "卡片 id 重复")
        if (ids != taxonomyOrder) issue("CARD_ORDER", null, null, "卡片顺序与 taxonomy 不一致")
        cards.forEach { card ->
            if (card.backend == ControlBackend.Ir && card.styleId.isNullOrBlank()) {
                issue("CARD_STYLE", card.cardId, null, "IR 卡缺少 systemStyle")
            }
            if (card.audited != (card.backend == ControlBackend.Hand)) {
                issue("CARD_BACKEND", card.cardId, null, "audited=${card.audited} 与 backend=${card.backend} 不一致")
            }
            if (card.visibleCount == 0 && card.emptyReason.isNullOrBlank()) {
                issue("CARD_EMPTY_REASON", card.cardId, null, "无可见控件却没有 emptyReason")
            }
            card.controls.forEach { control -> validateControl(card, control, ::issue) }
        }

        val declared = stats.declaredCounts
        if (stats.effectiveRows + stats.gatedRows != stats.capabilityRows) {
            issue(
                "CAP_CONSERVATION",
                null,
                null,
                "effective(${stats.effectiveRows}) + gated(${stats.gatedRows}) != rows(${stats.capabilityRows})",
            )
        }
        if (declared != null) {
            if (declared.rows != null && declared.rows != stats.capabilityRows) {
                issue("CAP_COUNT_ROWS", null, null, "capabilities.counts.auditRows=${declared.rows}，实际 ${stats.capabilityRows}")
            }
            if (declared.effective != null && declared.effective != stats.effectiveRows) {
                issue("CAP_COUNT_EFFECTIVE", null, null, "声明 effective=${declared.effective}，实际 ${stats.effectiveRows}")
            }
            if (declared.gated != null && declared.gated != stats.gatedRows) {
                issue("CAP_COUNT_GATED", null, null, "声明 gated=${declared.gated}，实际 ${stats.gatedRows}")
            }
        }
        return issues
    }

    private fun validateControl(
        card: CardControlModel,
        control: ControlSpec,
        issue: (String, String?, String?, String) -> Unit,
    ) {
        val id = control.controlId
        fun fail(rule: String, message: String) = issue(rule, card.cardId, id, message)

        if (control.gated && control.visible) fail("GATED_VISIBLE", "gated 控件被标成可见")
        if (control.visible) {
            if (control.consumer.isBlank()) fail("VISIBLE_NO_CONSUMER", "可见控件没有 consumer")
            if (!control.routable) fail("VISIBLE_UNROUTABLE", "可见控件的 consumer 落不到请求字段：${control.consumer}")
            if (control.type == null) fail("VISIBLE_NO_TYPE", "可见控件没有类型")
            if (control.label.isBlank()) fail("VISIBLE_NO_LABEL", "可见控件没有用户文案")
            if (control.label == id || control.label == control.consumer || control.label == control.sourceKind) {
                fail("LABEL_TECHNICAL", "label 是标识符：${control.label}")
            }
            val type = control.type
            if (type == ControlType.Slider || type == ControlType.Number || type == ControlType.Stepper) {
                if (control.defaultValue == null || control.min == null || control.max == null) {
                    fail("RANGE_INCOMPLETE", "范围控件缺少 default/min/max")
                }
                if (type == ControlType.Slider && control.step == null) {
                    fail("RANGE_INCOMPLETE", "slider 缺少 step")
                }
                val min = control.min
                val max = control.max
                if (min != null && max != null && min > max) fail("RANGE_INCOMPLETE", "min > max")
            }
            if (type == ControlType.Segmented || type == ControlType.Select) {
                if (control.options.isEmpty()) fail("OPTIONS_MISSING", "选项控件没有 options")
                control.options.forEach { option ->
                    if (option.label.isBlank()) fail("OPTION_LABEL", "选项 ${option.value} 没有文案")
                    // Brand names are proper nouns (vivo/OPPO/H2): key == label is the
                    // brand library's real display name, not a leaked schema id.
                    if (control.consumer != "logo" && option.label == option.value && !option.value.hasCjk()) {
                        fail("OPTION_LABEL", "选项 ${option.value} 的文案仍是标识符")
                    }
                }
                if (control.options.map { it.value }.toSet().size != control.options.size) {
                    fail("OPTIONS_DUPLICATE", "选项值重复")
                }
            }
        }
        if (control.consumer.isNotBlank() && !isRoutableConsumer(control.consumer)) {
            fail("CONSUMER_FORMAT", "consumer 路径非法：${control.consumer}")
        }
    }
}

/** True when [consumer] lands on a field of the unified request design. */
fun isRoutableConsumer(consumer: String): Boolean = when {
    consumer == "theme" || consumer == "ratio" || consumer == "fontScale" || consumer == "logo" -> true
    consumer.startsWith("params.") -> consumer.removePrefix("params.").isNotBlank() && !consumer.any { it.isWhitespace() }
    consumer.startsWith("text.") -> consumer.removePrefix("text.").isNotBlank() && !consumer.any { it.isWhitespace() }
    else -> false
}

/** Writes a control value onto the design field the control declares. */
fun PhotoDesign.applyConsumer(consumer: String, value: Any?): PhotoDesign = when {
    consumer == "theme" -> withTheme(value?.toString())
    consumer == "ratio" -> withRatio(value?.toString())
    consumer == "fontScale" -> withFontScale((value as? Number)?.toDouble() ?: value?.toString()?.toDouble())
    consumer == "logo" -> withLogo(value?.toString())
    consumer.startsWith("params.") -> withParam(consumer.removePrefix("params."), value)
    consumer.startsWith("text.") -> withText(consumer.removePrefix("text."), value)
    else -> throw IllegalArgumentException("无法落到请求字段的 consumer：$consumer")
}

// ---------------------------------------------------------------------------
// Loader
// ---------------------------------------------------------------------------

object ControlRegistryLoader {
    /**
     * Raw documents. Every field is injected so the core stays platform-neutral and
     * Common-code only; tests read the real files from disk.
     */
    data class Sources(
        val taxonomyJson: String,
        val capabilitiesJson: String,
        val inspectorMetaJson: String,
        val controlsJson: String,
        val vendoredControlsJson: List<String>,
        val logosJson: String,
    )

    fun load(sources: Sources): ControlRegistry {
        val taxonomy = parseTaxonomy(sources.taxonomyJson)
        val capabilities = parseCapabilities(sources.capabilitiesJson)
        val metaByStyle = parseMeta(sources.inspectorMetaJson)
        val metaByParamTail = metaByStyle.mapValues { (_, byId) ->
            byId.values.associateBy { it.param.substringAfterLast('.') }
        }
        val controls = parseControls(sources.controlsJson, metaByParamTail)
        val targetNodes = parseVendored(sources.vendoredControlsJson)
        val brandOptions = parseBrandOptions(sources.logosJson)

        val irRowsByStyle = controls.platformRows.groupBy { it.styleId }
        var metaJoined = 0
        var vendoredJoined = 0
        val cards = ArrayList<CardControlModel>(taxonomy.size)

        for (tax in taxonomy) {
            val capability = capabilities.byStyle[tax.id]
            val model = if (capability?.audited == true) {
                metaJoined += capability.effective.count { metaByStyle[capability.styleId]?.containsKey(it.controlId) == true }
                metaJoined += capability.gated.count { metaByStyle[capability.styleId]?.containsKey(it.controlId) == true }
                buildHandCard(tax, capability, metaByStyle[capability.styleId].orEmpty(), brandOptions)
            } else {
                val styleId = tax.systemStyle
                val rows = styleId?.let { irRowsByStyle[it] }.orEmpty()
                rows.forEach { row ->
                    if (targetNodes.containsKey("${row.styleId}::${row.controlId}")) vendoredJoined++
                }
                buildIrCard(tax, rows, targetNodes, brandOptions)
            }
            cards += model
        }

        val effectiveRows = capabilities.byStyle.values.filter { it.audited }.sumOf { it.effective.size }
        val gatedRows = capabilities.byStyle.values.filter { it.audited }.sumOf { it.gated.size }
        val visible = cards.sumOf { it.visibleCount }
        val allControls = cards.sumOf { it.controls.size }
        val gatedControls = cards.sumOf { card -> card.controls.count { it.gated } }
        val stats = RegistryStats(
            cards = cards.size,
            auditedCards = cards.count { it.audited },
            unauditedCards = cards.count { !it.audited },
            cardsWithVisible = cards.count { it.visibleCount > 0 },
            emptyCards = cards.count { it.visibleCount == 0 },
            capabilityRows = effectiveRows + gatedRows,
            effectiveRows = effectiveRows,
            gatedRows = gatedRows,
            declaredCounts = capabilities.counts,
            platformRows = controls.platformRows.size,
            platformStyles = controls.platformRows.map { it.styleId }.toSet().size,
            metaRowsJoined = metaJoined,
            vendoredJoined = vendoredJoined,
            handPanelRows = controls.panelRows,
            handPanelCardPairs = controls.panelCardPairs,
            handPanelRangeMismatches = controls.panelRangeMismatches,
            visibleControls = visible,
            hiddenControls = allControls - visible,
            gatedControls = gatedControls,
            routableControls = cards.sumOf { card -> card.controls.count { it.routable } },
            consumedControls = cards.sumOf { card -> card.controls.count { it.consumed } },
            unmappedCards = cards.filter { it.backend == ControlBackend.Ir && it.controls.isEmpty() }.map { it.cardId },
        )
        return ControlRegistry(cards, stats, taxonomy.map { it.id })
    }

    // ---- card builders -----------------------------------------------------

    private fun buildHandCard(
        tax: TaxCard,
        capability: CapCard,
        meta: Map<String, MetaControl>,
        brands: List<ControlOption>,
    ): CardControlModel {
        val controls = ArrayList<ControlSpec>(capability.effective.size + capability.gated.size)
        capability.effective.forEach { entry -> controls += handSpec(entry, meta[entry.controlId], gated = false, brands = brands) }
        capability.gated.forEach { entry -> controls += handSpec(entry, meta[entry.controlId], gated = true, brands = brands) }
        val visible = controls.count { it.visible }
        return CardControlModel(
            cardId = tax.id,
            name = tax.name,
            category = tax.category,
            categoryName = tax.categoryName,
            brand = tax.brand,
            backend = ControlBackend.Hand,
            styleId = tax.id,
            audited = true,
            controls = controls,
            effectiveCount = capability.effective.size,
            gatedCount = capability.gated.size,
            emptyReason = if (visible > 0) null else emptyReason(controls, "审计卡没有可见控件"),
        )
    }

    private fun buildIrCard(
        tax: TaxCard,
        rows: List<PlatformRow>,
        targetNodes: Map<String, String?>,
        brands: List<ControlOption>,
    ): CardControlModel {
        val controls = rows.map { row -> irSpec(row, targetNodes["${row.styleId}::${row.controlId}"], brands) }
        val visible = controls.count { it.visible }
        val reason = when {
            rows.isEmpty() -> "controls.json 没有 systemStyle=${tax.systemStyle} 的声明（blocked/无控件声明）"
            visible == 0 -> emptyReason(controls, "未审计卡没有可见控件")
            else -> null
        }
        return CardControlModel(
            cardId = tax.id,
            name = tax.name,
            category = tax.category,
            categoryName = tax.categoryName,
            brand = tax.brand,
            backend = ControlBackend.Ir,
            styleId = tax.systemStyle,
            audited = false,
            controls = controls,
            effectiveCount = rows.size.takeIf { it > 0 },
            gatedCount = null,
            emptyReason = reason,
        )
    }

    private fun emptyReason(controls: List<ControlSpec>, fallback: String): String {
        if (controls.isEmpty()) return fallback
        val byReason = controls.filterNot { it.visible }
            .groupingBy { it.hiddenReason ?: "unknown" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${it.value}" }
        return "$fallback（$byReason）"
    }

    private fun handSpec(
        entry: CapEntry,
        meta: MetaControl?,
        gated: Boolean,
        brands: List<ControlOption>,
    ): ControlSpec {
        val consumer = handConsumer(entry.param)
        val type = typeOf(entry.kind)
        val rawLabel = meta?.label ?: entry.controlId
        val label = ControlLabels.forControl(rawLabel, entry.controlId)
        val min = meta?.let { numberOrNull(it.domain["min"]) ?: it.min }
        val max = meta?.let { numberOrNull(it.domain["max"]) ?: it.max }
        val step = meta?.let { numberOrNull(it.domain["step"]) ?: it.step }
        val defSource = meta?.defaultValue ?: meta?.currentValue
        val defaultValue = normalizeDefault(type, entry.kind, defSource, min)
        val options = if (consumer == "logo") brands else optionsFor(type, entry.kind, meta?.domain?.get("options"), meta?.optionsRaw, brands)
        return finishSpec(
            controlId = entry.controlId,
            kind = entry.kind,
            type = type,
            rawLabel = rawLabel,
            label = label,
            consumer = consumer,
            consumerSource = ConsumerSource.Declared,
            scope = entry.scope,
            group = meta?.group,
            defaultValue = defaultValue,
            currentValue = normalizeDefault(type, entry.kind, entry.currentValue, min),
            min = min,
            max = max,
            step = step,
            options = options,
            unit = meta?.unit,
            gated = gated,
            audited = true,
            sourceParam = entry.param,
        )
    }

    private fun irSpec(
        row: PlatformRow,
        targetNode: String?,
        brands: List<ControlOption>,
    ): ControlSpec {
        val type = typeOf(row.kind)
        val consumer = irConsumer(row.kind, row.param, targetNode)
        val label = ControlLabels.sanitize(row.label)
        val min = numberOrNull(row.domain["min"])
        val max = numberOrNull(row.domain["max"])
        val step = numberOrNull(row.domain["step"])
        val defaultValue = normalizeDefault(type, row.kind, row.default, min)
        val options = optionsFor(type, row.kind, row.domain["options"], row.optionsRaw, brands)
        return finishSpec(
            controlId = row.controlId,
            kind = row.kind,
            type = type,
            rawLabel = row.label,
            label = label,
            consumer = consumer,
            consumerSource = ConsumerSource.Derived,
            scope = when (consumer) {
                "logo" -> "design-logo"
                "theme" -> "template-param"
                else -> "template-param"
            },
            group = row.group,
            defaultValue = defaultValue,
            currentValue = null,
            min = min,
            max = max,
            step = step,
            options = options,
            unit = row.unit,
            gated = false,
            audited = false,
            sourceParam = row.param,
        )
    }

    private fun finishSpec(
        controlId: String,
        kind: String,
        type: ControlType?,
        rawLabel: String,
        label: String?,
        consumer: String,
        consumerSource: ConsumerSource,
        scope: String,
        group: String?,
        defaultValue: Any?,
        currentValue: Any?,
        min: Double?,
        max: Double?,
        step: Double?,
        options: List<ControlOption>?,
        unit: String?,
        gated: Boolean,
        audited: Boolean,
        sourceParam: String?,
    ): ControlSpec {
        val routable = isRoutableConsumer(consumer)
        val optionList = options.orEmpty()
        val optionLabelsMissing = options == null
        val hiddenReason = when {
            gated -> "gated"
            label == null -> "unlabeled"
            type == null -> "unknown-kind"
            consumer.isBlank() -> "no-consumer"
            !routable -> "unroutable"
            needsRange(type) && (defaultValue == null || min == null || max == null || (type == ControlType.Slider && step == null)) ->
                "incomplete-range"
            needsOptions(type) && optionList.isEmpty() -> "missing-options"
            optionLabelsMissing -> "option-label-missing"
            else -> null
        }
        val consumed = when {
            gated -> false
            consumer == "theme" -> true
            consumer == "fontScale" -> true
            consumer == "logo" -> false
            (consumer.startsWith("params.") || consumer.startsWith("text.")) && audited -> true
            else -> false
        }
        return ControlSpec(
            controlId = controlId,
            type = type,
            sourceKind = kind,
            label = label ?: rawLabel,
            consumer = consumer,
            consumerSource = consumerSource,
            scope = scope,
            group = group,
            defaultValue = defaultValue,
            currentValue = currentValue,
            min = min,
            max = max,
            step = step,
            options = optionList,
            unit = unit,
            visible = hiddenReason == null,
            gated = gated,
            audited = audited,
            routable = routable,
            consumed = consumed,
            hiddenReason = hiddenReason,
            sourceParam = sourceParam,
        )
    }

    // ---- field derivation --------------------------------------------------

    private fun typeOf(kind: String): ControlType? = when (kind) {
        "slider", "xyOffset" -> ControlType.Slider
        "toggle" -> ControlType.Toggle
        "themeSwatches", "modeSegment", "segmented" -> ControlType.Segmented
        "anchorGrid", "gravitySegment", "brandPicker", "select" -> ControlType.Select
        "colorField", "color" -> ControlType.Color
        "text" -> ControlType.Text
        "number" -> ControlType.Number
        "stepper" -> ControlType.Stepper
        else -> null
    }

    private fun needsRange(type: ControlType?): Boolean =
        type == ControlType.Slider || type == ControlType.Number || type == ControlType.Stepper

    private fun needsOptions(type: ControlType?): Boolean =
        type == ControlType.Segmented || type == ControlType.Select

    private fun handConsumer(param: String): String = when {
        param == "theme" -> "theme"
        param == "text.fontScale" -> "fontScale"
        param == "design.logo" -> "logo"
        param == "brand.brandLogoFile" || param == "brandLogoFile" || param == "params.brandLogoFile" -> "logo"
        param.startsWith("params.") || param.startsWith("text.") -> param
        else -> ""
    }

    /**
     * The reference app's `paramKeyOf` (framemark/src/renderer/src/core/controls/index.ts):
     * text/toggle/gravity/slider keys are `<role>:<targetNode>`, theme/logo/anchor/offset
     * use the bare role. `targetNode` only exists in the vendored documents, not in
     * engine/data/controls.json, hence the join in [load].
     */
    private fun irConsumer(kind: String, param: String, targetNode: String?): String = when (kind) {
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

    private fun normalizeDefault(type: ControlType?, kind: String, raw: Any?, min: Double?): Any? = when (type) {
        ControlType.Toggle -> boolOf(raw) ?: false
        ControlType.Slider, ControlType.Number, ControlType.Stepper ->
            numberOrNull(raw) ?: (if (kind == "slider") 1.0 else min ?: 0.0)
        ControlType.Segmented, ControlType.Select, ControlType.Color, ControlType.Text -> when {
            kind == "brandPicker" -> raw?.toString()?.takeIf { it.isNotBlank() } ?: "auto"
            kind == "gravitySegment" || kind == "anchorGrid" -> raw?.toString()?.takeIf { it.isNotBlank() } ?: "vendor"
            else -> raw?.toString() ?: ""
        }
        null -> raw
    }

    private fun optionsFor(
        type: ControlType?,
        kind: String,
        domainOptions: Any?,
        optionsRaw: Any?,
        brands: List<ControlOption>,
    ): List<ControlOption>? {
        if (kind == "brandPicker") return brands
        if (!needsOptions(type)) return emptyList()
        val labelled = parseLabelledOptions(optionsRaw)
        if (labelled != null && labelled.isNotEmpty()) return labelled
        val values = (domainOptions as? List<Any?>).orEmpty()
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<ControlOption>(values.size)
        for (raw in values) {
            val value = raw?.toString() ?: continue
            val label = ControlLabels.forOption(value) ?: return null
            out += ControlOption(value, label)
        }
        return out
    }

    private fun parseLabelledOptions(raw: Any?): List<ControlOption>? {
        val list = raw as? List<Any?> ?: return null
        if (list.isEmpty()) return null
        val out = ArrayList<ControlOption>(list.size)
        for (entry in list) {
            val map = entry as? Map<String, Any?> ?: return null
            val value = map["value"]?.toString() ?: return null
            val label = map["label"] as? String ?: return null
            if (label.isBlank()) return null
            out += ControlOption(value, label)
        }
        return out
    }

    // ---- parsing -----------------------------------------------------------

    private data class TaxCard(
        val id: String,
        val name: String,
        val category: String,
        val categoryName: String,
        val brand: String,
        val systemStyle: String?,
    )

    private data class CapEntry(
        val controlId: String,
        val param: String,
        val kind: String,
        val scope: String,
        val currentValue: Any?,
    )

    private data class CapCard(
        val styleId: String,
        val audited: Boolean,
        val effective: List<CapEntry>,
        val gated: List<CapEntry>,
    )

    private data class Capabilities(val byStyle: Map<String, CapCard>, val counts: CapabilityCounts?)

    private data class MetaControl(
        val id: String,
        val label: String,
        val kind: String,
        val param: String,
        val group: String?,
        val defaultValue: Any?,
        val currentValue: Any?,
        val domain: Map<String, Any?>,
        val optionsRaw: Any?,
        val min: Double?,
        val max: Double?,
        val step: Double?,
        val unit: String?,
    )

    private data class PlatformRow(
        val styleId: String,
        val controlId: String,
        val kind: String,
        val label: String,
        val group: String?,
        val param: String,
        val default: Any?,
        val domain: Map<String, Any?>,
        val optionsRaw: Any?,
        val unit: String?,
    )

    private data class ParsedControls(
        val platformRows: List<PlatformRow>,
        val panelRows: Int,
        val panelCardPairs: Int,
        val panelRangeMismatches: List<String>,
    )

    private fun parseTaxonomy(text: String): List<TaxCard> {
        val root = ProductJson.parseRoot(text)
        val cards = root["cards"] as? List<Any?> ?: throw ProductDecodeException("taxonomy.cards 缺失")
        return cards.mapIndexed { index, raw ->
            val map = requireObject(raw, "taxonomy.cards[$index]")
            TaxCard(
                id = map.requireString("id", "taxonomy.cards[$index]"),
                name = map.optionalString("name", "taxonomy.cards[$index]").orEmpty(),
                category = map.optionalString("category", "taxonomy.cards[$index]").orEmpty(),
                categoryName = map.optionalString("categoryName", "taxonomy.cards[$index]").orEmpty(),
                brand = map.optionalString("brand", "taxonomy.cards[$index]").orEmpty(),
                systemStyle = map.optionalString("systemStyle", "taxonomy.cards[$index]")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseCapabilities(text: String): Capabilities {
        val root = ProductJson.parseRoot(text)
        val cards = root["cards"] as? List<Any?> ?: throw ProductDecodeException("capabilities.cards 缺失")
        val byStyle = LinkedHashMap<String, CapCard>(cards.size)
        cards.forEach { raw ->
            val map = raw as? Map<String, Any?> ?: return@forEach
            val styleId = map["styleId"] as? String ?: return@forEach
            byStyle[styleId] = CapCard(
                styleId = styleId,
                audited = map["audited"] as? Boolean ?: false,
                effective = parseCapEntries(map["effective"]),
                gated = parseCapEntries(map["gated"]),
            )
        }
        val countsMap = root["counts"] as? Map<String, Any?> ?: emptyMap()
        val counts = CapabilityCounts(
            rows = (countsMap["auditRows"] as? Number)?.toInt(),
            effective = (countsMap["effective"] as? Number)?.toInt(),
            gated = (countsMap["gated"] as? Number)?.toInt(),
            auditedCards = (countsMap["auditedCards"] as? Number)?.toInt(),
            unauditedCards = (countsMap["unauditedCards"] as? Number)?.toInt(),
        )
        return Capabilities(byStyle, counts)
    }

    private fun parseCapEntries(raw: Any?): List<CapEntry> {
        val list = raw as? List<Any?> ?: return emptyList()
        return list.mapNotNull { entry ->
            val map = entry as? Map<String, Any?> ?: return@mapNotNull null
            val id = map["controlId"] as? String ?: return@mapNotNull null
            CapEntry(
                controlId = id,
                param = map["param"] as? String ?: "",
                kind = map["kind"] as? String ?: "text",
                scope = map["scope"] as? String ?: "",
                currentValue = map["currentValue"],
            )
        }
    }

    private fun parseMeta(text: String): Map<String, Map<String, MetaControl>> {
        val root = ProductJson.parseRoot(text)
        val records = root["records"] as? List<Any?> ?: throw ProductDecodeException("inspector-meta.records 缺失")
        val out = LinkedHashMap<String, Map<String, MetaControl>>(records.size)
        records.forEach { raw ->
            val record = raw as? Map<String, Any?> ?: return@forEach
            val styleId = record["styleId"] as? String ?: return@forEach
            val controls = record["controls"] as? List<Any?> ?: emptyList()
            val byId = LinkedHashMap<String, MetaControl>(controls.size)
            controls.forEach { rawControl ->
                val map = rawControl as? Map<String, Any?> ?: return@forEach
                val id = map["id"] as? String ?: return@forEach
                val domain = map["domain"] as? Map<String, Any?> ?: emptyMap()
                byId[id] = MetaControl(
                    id = id,
                    label = map["label"] as? String ?: id,
                    kind = map["kind"] as? String ?: "text",
                    param = map["param"] as? String ?: "",
                    group = map["group"] as? String,
                    defaultValue = map["default"],
                    currentValue = map["currentValue"],
                    domain = domain,
                    optionsRaw = map["options"],
                    min = numberOrNull(domain["min"]) ?: numberOrNull(map["min"]),
                    max = numberOrNull(domain["max"]) ?: numberOrNull(map["max"]),
                    step = numberOrNull(domain["step"]) ?: numberOrNull(map["step"]),
                    unit = (domain["unit"] as? String) ?: (map["unit"] as? String),
                )
            }
            out[styleId] = byId
        }
        return out
    }

    private fun parseControls(
        text: String,
        metaByParamTail: Map<String, Map<String, MetaControl>>,
    ): ParsedControls {
        val root = ProductJson.parseRoot(text)
        val rows = root["rows"] as? List<Any?> ?: emptyList()
        val platform = ArrayList<PlatformRow>(rows.size)
        rows.forEach { raw ->
            val map = raw as? Map<String, Any?> ?: return@forEach
            if ((map["scope"] as? String) != "platform") return@forEach
            val styleId = map["styleId"] as? String ?: return@forEach
            val controlId = map["controlId"] as? String ?: return@forEach
            val domain = map["domain"] as? Map<String, Any?> ?: emptyMap()
            platform += PlatformRow(
                styleId = styleId,
                controlId = controlId,
                kind = map["kind"] as? String ?: "text",
                label = map["label"] as? String ?: controlId,
                group = map["group"] as? String,
                param = map["param"] as? String ?: "",
                domain = domain,
                optionsRaw = map["options"],
                default = map["default"],
                unit = (domain["unit"] as? String) ?: (map["unit"] as? String),
            )
        }

        var panelRows = 0
        var panelPairs = 0
        val mismatches = mutableListOf<String>()
        val hand = root["hand"] as? Map<String, Any?>
        val panels = hand?.get("panels") as? List<Any?> ?: emptyList()
        panels.forEach { rawPanel ->
            val panel = rawPanel as? Map<String, Any?> ?: return@forEach
            val panelCards = (panel["cards"] as? List<Any?>).orEmpty().mapNotNull { it as? String }
            val panelControls = panel["controls"] as? List<Any?> ?: emptyList()
            panelRows += panelControls.size
            panelControls.forEach { rawControl ->
                val control = rawControl as? Map<String, Any?> ?: return@forEach
                val id = control["id"] as? String ?: return@forEach
                val pMin = numberOrNull(control["min"])
                val pMax = numberOrNull(control["max"])
                val pStep = numberOrNull(control["step"])
                for (cardId in panelCards) {
                    panelPairs++
                    // Cross-check only; the display metadata source stays inspector-meta.
                    val meta = metaByParamTail[cardId]?.get(id) ?: continue
                    if (meta.min != pMin || meta.max != pMax || meta.step != pStep) {
                        mismatches += "$cardId::$id meta=${meta.min}/${meta.max}/${meta.step} panel=$pMin/$pMax/$pStep"
                    }
                }
            }
        }
        return ParsedControls(platform, panelRows, panelPairs, mismatches)
    }

    private fun parseVendored(documents: List<String>): Map<String, String?> {
        val out = HashMap<String, String?>()
        documents.forEach { text ->
            val root = ProductJson.parseRoot(text)
            val records = root["records"] as? List<Any?> ?: return@forEach
            records.forEach { rawRecord ->
                val record = rawRecord as? Map<String, Any?> ?: return@forEach
                val styleId = record["styleId"] as? String ?: return@forEach
                val controls = record["controls"] as? List<Any?> ?: return@forEach
                controls.forEach { rawControl ->
                    val control = rawControl as? Map<String, Any?> ?: return@forEach
                    val id = control["id"] as? String ?: return@forEach
                    out["$styleId::$id"] = control["targetNode"] as? String
                }
            }
        }
        return out
    }

    private fun parseBrandOptions(text: String): List<ControlOption> {
        val root = ProductJson.parseRoot(text)
        val brands = root["brands"] as? List<Any?> ?: emptyList()
        val options = ArrayList<ControlOption>(brands.size + 1)
        options += ControlOption("auto", "自动匹配")
        brands.forEach { raw ->
            val map = raw as? Map<String, Any?> ?: return@forEach
            val key = map["key"] as? String ?: return@forEach
            val label = (map["cn"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["label"] as? String)?.takeIf { it.isNotBlank() }
                ?: key
            options += ControlOption(key, label)
        }
        return options
    }

    private fun numberOrNull(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun boolOf(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> null
        }
        is Number -> value.toDouble() != 0.0
        else -> null
    }
}
