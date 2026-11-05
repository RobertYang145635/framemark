package com.framemark.native.workflow

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorSectionModel

/**
 * Text geometry and edit identity reported by a renderer. Coordinates and font
 * metrics are in pixels of the corresponding [NativeImage].
 *
 * [located] is separate from the numeric rectangle for backwards compatibility:
 * render reports that do not carry geometry use zero placeholders but must never
 * be interpreted as a real top-left position.
 */
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
    /** Report draw with no declared node; [id] is the report path or sequence. */
    val unmapped: Boolean = false,
    val optional: Boolean = false,
    /** Engine IR node path (`b…/g…`) where available. */
    val nodePath: String? = null,
    /** Stable flat-parameter identity (`b…/g…` or `hand:<scope>:<id>`). */
    val key: String? = null,
    /** Unmodified source content, before any per-slot text override. */
    val raw: String = "",
    /** The renderer reports the row hidden while retaining its stable identity. */
    val hidden: Boolean = false,
    /** True only when [key] is a supported, explicit renderer identity. */
    val stable: Boolean = false,
    /** False when no trustworthy positive rectangle was reported. */
    val located: Boolean = false,
)

private val irTextSlotKey = Regex("^b\\d+(/g\\d+)*$")
private val handTextSlotKey = Regex("^hand:.+:.+$")

/** Stable keys are deliberately restricted to the two explicit renderer protocols. */
fun isWritableTextSlotKey(key: String?): Boolean = when {
    key == null -> false
    irTextSlotKey.matches(key) -> true
    handTextSlotKey.matches(key) -> true
    else -> false
}

fun TextSlot.isEditableTextSlot(): Boolean = stable && isWritableTextSlotKey(key)

/**
 * A row is editable only while the current renderer has a real draw, or when it
 * explicitly hides that draw and can offer the restore switch. Declarations with
 * no report (`present=false`, `hidden=false`) are absent from this frame.
 */
fun TextSlot.isCurrentTextSlot(): Boolean = isEditableTextSlot() && (present || hidden)

/**
 * Preserve only editable, stable rows after a failed render of the same
 * card/photo. The previous geometry belongs to another design state, so it is
 * deliberately discarded and grouped under 待定位 until a render succeeds.
 */
internal fun List<TextSlot>.asFailureRecoverySlots(): List<TextSlot> =
    asSequence()
        .filter(TextSlot::isCurrentTextSlot)
        .map { slot -> slot.copy(x = 0.0, y = 0.0, w = 0.0, h = 0.0, located = false) }
        .toList()

enum class TextSlotPosition(val label: String, val row: Int, val column: Int) {
    TopLeft("左上", 0, 0),
    TopCenter("顶部居中", 0, 1),
    TopRight("右上", 0, 2),
    MiddleLeft("左侧居中", 1, 0),
    Center("居中", 1, 1),
    MiddleRight("右侧居中", 1, 2),
    BottomLeft("左下", 2, 0),
    BottomCenter("底部居中", 2, 1),
    BottomRight("右下", 2, 2),
}

data class TextSlotRow(val slot: TextSlot, val order: Int)

data class TextSlotGroup(
    val position: TextSlotPosition?,
    val rows: List<TextSlotRow>,
) {
    val label: String get() = position?.label ?: "待定位"
}

/** A renderer snapshot belongs to one selected card/photo pair only. */
data class TextSlotSnapshot(
    val cardId: String,
    val photoId: String?,
    val outputWidth: Int,
    val outputHeight: Int,
    val slots: List<TextSlot>,
)

/** Classifies a slot by the center of its reported rectangle in output pixels. */
fun textSlotPosition(slot: TextSlot, outputWidth: Int, outputHeight: Int): TextSlotPosition? {
    if (!slot.isEditableTextSlot() || !slot.located || outputWidth <= 0 || outputHeight <= 0) return null
    if (!slot.x.isFinite() || !slot.y.isFinite() || !slot.w.isFinite() || !slot.h.isFinite()) return null
    if (slot.w <= 0.0 || slot.h <= 0.0) return null
    val centerX = ((slot.x + slot.w / 2.0) / outputWidth).coerceIn(0.0, 1.0)
    val centerY = ((slot.y + slot.h / 2.0) / outputHeight).coerceIn(0.0, 1.0)
    val column = when {
        centerX < 1.0 / 3.0 -> 0
        centerX < 2.0 / 3.0 -> 1
        else -> 2
    }
    val row = when {
        centerY < 1.0 / 3.0 -> 0
        centerY < 2.0 / 3.0 -> 1
        else -> 2
    }
    return TextSlotPosition.entries.first { it.row == row && it.column == column }
}

/**
 * Stable groups for the text inspector. Positioned rows are sorted by y/x;
 * geometry-free rows remain available at the end under `待定位`.
 */
fun groupTextSlots(slots: List<TextSlot>, outputWidth: Int, outputHeight: Int): List<TextSlotGroup> {
    val eligible = slots.filter(TextSlot::isCurrentTextSlot)
    val positioned = TextSlotPosition.entries.mapNotNull { position ->
        val members = eligible.filter { textSlotPosition(it, outputWidth, outputHeight) == position }
            .sortedWith(compareBy<TextSlot>({ it.y }, { it.x }, { it.key.orEmpty() }))
        if (members.isEmpty()) null else TextSlotGroup(position, members.mapIndexed { index, slot -> TextSlotRow(slot, index + 1) })
    }
    val unlocated = eligible.filter { textSlotPosition(it, outputWidth, outputHeight) == null }
        .sortedWith(compareBy<TextSlot>({ if (it.located) it.y else Double.POSITIVE_INFINITY }, { if (it.located) it.x else Double.POSITIVE_INFINITY }, { it.key.orEmpty() }))
    return positioned + if (unlocated.isEmpty()) emptyList() else {
        listOf(TextSlotGroup(null, unlocated.mapIndexed { index, slot -> TextSlotRow(slot, index + 1) }))
    }
}

data class TextSlotOverrides(
    val text: String = "",
    /** `false` is the renderer's explicit hidden value. */
    val visible: Boolean = true,
    val scale: Float = 1f,
)

/** Flat `params` names shared by the IR and hand renderers. */
fun textSlotOverrideKeys(key: String): Triple<String, String, String> =
    Triple("slotText:$key", "slotOff:$key", "slotScale:$key")

/** Encodes only non-default values; empty text restores the source text. */
fun encodeTextSlotOverrides(key: String, overrides: TextSlotOverrides): Map<String, Any> {
    require(isWritableTextSlotKey(key)) { "unsupported text slot key" }
    val (textKey, hiddenKey, scaleKey) = textSlotOverrideKeys(key)
    return buildMap {
        if (overrides.text.isNotEmpty()) put(textKey, overrides.text)
        if (!overrides.visible) put(hiddenKey, false)
        val scale = (overrides.scale.takeIf(Float::isFinite) ?: 1f).coerceIn(0.6f, 1.6f)
        if (scale != 1f) put(scaleKey, scale.toDouble())
    }
}

/** Decodes the same flat values produced by [encodeTextSlotOverrides]. */
fun decodeTextSlotOverrides(key: String, params: Map<String, Any?>): TextSlotOverrides {
    require(isWritableTextSlotKey(key)) { "unsupported text slot key" }
    val (textKey, hiddenKey, scaleKey) = textSlotOverrideKeys(key)
    val scale = (params[scaleKey] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0.6f, 1.6f) ?: 1f
    return TextSlotOverrides(
        text = (params[textKey] as? String).orEmpty(),
        visible = params[hiddenKey] != false,
        scale = scale,
    )
}

private const val nativeTextControlPrefix = "__native_text_slot__:"
private fun textControlId(cardId: String, key: String, field: String) =
    "$nativeTextControlPrefix$cardId:$key:$field"
private fun textControlKey(cardId: String, key: String, field: String) = "$cardId::native-text-slot:$key:$field"

fun isNativeTextSlotControl(control: InspectorControl): Boolean = control.controlId.startsWith(nativeTextControlPrefix)

/** Enabled static text controls are the fallback until the current renderer reports rows. */
fun isUsableStaticTextControl(control: InspectorControl): Boolean =
    !control.hidden &&
    control.label.isNotBlank() &&
    control.disabledReason == null &&
    !isNativeTextSlotControl(control)

fun InspectorCardModel.textFallbackControls(): List<InspectorControl> =
    sections.firstOrNull { it.section == InspectorSection.Text }
        ?.controls
        ?.filter(::isUsableStaticTextControl)
        ?.distinctBy { it.key }
        .orEmpty()


fun textSlotControls(cardId: String, slot: TextSlot): List<InspectorControl> {
    val key = slot.key?.takeIf { slot.isCurrentTextSlot() } ?: return emptyList()
    fun control(field: String, suffix: String, label: String, type: ControlType, initial: ControlValue, min: Float? = null, max: Float? = null, step: Float? = null) =
        InspectorControl(
            key = textControlKey(cardId, key, field),
            controlId = textControlId(cardId, key, field),
            param = "params.$suffix:$key",
            label = label,
            kind = "native_text_slot",
            section = InspectorSection.Text,
            type = type,
            initial = initial,
            min = min,
            max = max,
            step = step,
            options = emptyList(),
            multiline = field == "text",
            declaredDefault = null,
            currentText = if (field == "text") slot.text.ifBlank { slot.raw } else null,
            autoMatch = null,
            unit = if (field == "scale") "×" else null,
            hidden = false,
        )
    return listOf(
        control("text", "slotText", "替换文字", ControlType.Text, ControlValue.Str("")),
        // Missing/true means visible; only explicit false hides a row.
        control("visible", "slotOff", "显示文字", ControlType.Switch, ControlValue.Flag(!slot.hidden)),
        control("scale", "slotScale", "文字大小", ControlType.Slider, ControlValue.Num(1f), 0.6f, 1.6f, 0.02f),
    )
}

/** Adds current renderer-backed text controls without mutating the registry model. */
fun InspectorCardModel.withNativeTextSlots(slots: List<TextSlot>): InspectorCardModel {
    // One edit control belongs to one renderer identity. If a backend reports
    // the same node twice, keep the first row instead of creating controls that
    // write the same key but can display different values.
    val editableSlots = slots
        .asSequence()
        .filter(TextSlot::isCurrentTextSlot)
        .distinctBy { it.key }
        .toList()
    val replacedConsumers = editableSlots.flatMap { slot ->
        slot.key?.let(::textSlotOverrideKeys)?.let { listOf(it.first, it.second, it.third) }.orEmpty()
    }.toSet()
    // Registry rows spell the same consumer with the `params.` prefix while the
    // flat override keys do not, so compare the bare name: otherwise the static
    // row that writes this exact value stays and is rendered a second time.
    fun isSuperseded(control: InspectorControl): Boolean =
        (listOf(control.param) + control.companions).any { it.removePrefix("params.") in replacedConsumers }

    val baseControls = controls.filterNot(::isNativeTextSlotControl).filterNot(::isSuperseded)
    val dynamicControls = editableSlots.flatMap { textSlotControls(cardId, it) }
    val merged = baseControls + dynamicControls
    val sectionsWithoutDynamic = sections.mapNotNull { section ->
        val kept = section.controls.filterNot(::isNativeTextSlotControl).filterNot(::isSuperseded)
        if (kept.isEmpty()) null else section.copy(controls = kept)
    }
    val sectionIndex = sectionsWithoutDynamic.indexOfFirst { it.section == InspectorSection.Text }
    val mergedSections = if (sectionIndex >= 0) {
        sectionsWithoutDynamic.toMutableList().apply {
            val section = this[sectionIndex]
            this[sectionIndex] = section.copy(controls = section.controls + dynamicControls)
        }
    } else if (dynamicControls.isEmpty()) {
        sectionsWithoutDynamic
    } else {
        sectionsWithoutDynamic + InspectorSectionModel(InspectorSection.Text, dynamicControls)
    }
    return copy(
        controls = merged,
        sections = mergedSections,
        visibleIds = visibleIds + dynamicControls.map { it.controlId },
    )
}
