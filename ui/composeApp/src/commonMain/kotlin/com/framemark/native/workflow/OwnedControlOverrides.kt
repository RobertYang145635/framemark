package com.framemark.native.workflow

import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.applyConsumer
import com.framemark.native.product.FmLog

/**
 * Replaces only values owned by the current inspector model, then applies its
 * changed values. Unknown/future consumers remain intact across ordinary edits.
 */
fun PhotoDesign.applyInspectorOverrides(
    model: InspectorCardModel,
    overrides: Map<String, Any>,
): PhotoDesign {
    val consumers = model.controls.flatMap { listOf(it.param) + it.companions }
        .filter(String::isNotBlank)
        .toSet()

    var next = this
    consumers.forEach { consumer ->
        next = when (consumer) {
            "theme" -> next.copy(theme = null)
            "ratio" -> next.copy(ratio = null)
            "fontScale", "text.fontScale" -> next.copy(fontScale = null)
            "logo", "design.logo", "brandLogoFile", "params.brandLogoFile" ->
                next.copy(logo = null, params = next.params - "brandLogoFile")
            else -> when {
                consumer.startsWith("params.") -> next.copy(params = next.params - consumer.removePrefix("params."))
                consumer.startsWith("text.") -> next.copy(text = next.text - consumer.removePrefix("text."))
                consumer !in SLOT_CONSUMERS -> next.copy(
                    params = next.params - unmappedKey(consumer),
                )
                else -> next
            }
        }
    }

    model.controls.forEach { control ->
        val raw = overrides[control.key] ?: return@forEach
        val direct = when (control.param) {
            "text.fontScale" -> next.withFontScale((raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull())
            "design.logo" -> next.withLogo(raw.toString())
            else -> runCatching { next.applyConsumer(control.param, raw) }.getOrNull()
        }

        var written = direct ?: next
        control.companions.forEach { companion ->
            if (companion.startsWith("params.") || companion.startsWith("text.")) {
                written = written.applyConsumer(companion, raw)
            }
        }
        if (direct == null && control.companions.isEmpty() && control.param.isNotBlank() &&
            control.param !in SLOT_CONSUMERS
        ) {
            FmLog.info("FM_DESIGN_DROP") {
                "controlId=${control.controlId} consumer=${control.param} " +
                    "value=$raw bound=0 reason=no-engine-binding"
            }
            written = written.withParam(unmappedKey(control.param), raw)
        }
        next = written
    }
    return next.copy(baseDesign = next.isUntouched)
}

private val SLOT_CONSUMERS = setOf("slotText", "slotOff", "slotAlpha", "slotGravity")

private fun unmappedKey(consumer: String): String =
    "unmapped_" + consumer.replace('.', '_').replace(':', '_')
