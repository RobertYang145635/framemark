package com.framemark.native.engine

import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorControlHints
import com.framemark.native.inspector.InspectorSection
import org.json.JSONObject
import java.io.File

/**
 * Controls the frozen audit rows do not carry for the Android-wired IR cards:
 * the theme choices really declared by the style JSON, and the photo aspect the
 * JNI accepts (`--ratio`). Only cards wired to the native renderer get them.
 */
class AndroidInspectorHints(
    private val wiredStyles: () -> Map<String, EngineData.Style>,
) : InspectorControlHints {

    private val cache = HashMap<String, List<InspectorControl>>()

    @Synchronized
    override fun extraControls(cardId: String): List<InspectorControl> =
        cache.getOrPut(cardId) { build(cardId) }

    private fun build(cardId: String): List<InspectorControl> {
        val style = wiredStyles()[cardId] ?: return emptyList()
        val doc = runCatching { JSONObject(File(style.style).readText()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<InspectorControl>()
        themeControl(cardId, doc)?.let { out += it }
        out += ratioControl(cardId)
        return out
    }

    private fun themeControl(cardId: String, doc: JSONObject): InspectorControl? {
        val themes = doc.optJSONArray("themes") ?: return null
        val options = (0 until themes.length()).mapNotNull { index ->
            val id = themes.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            THEME_LABELS[id]?.let { ControlOption(id, it) }
        }
        // Unknown theme ids stay hidden: the UI never shows raw source identifiers.
        if (options.size < 2 || options.size != themes.length()) return null
        val fallback = options.first().value
        val declared = doc.optString("defaultTheme").takeIf { id -> options.any { it.value == id } } ?: fallback
        return InspectorControl(
            key = "$cardId::hint.theme",
            controlId = "hint.theme",
            param = "theme",
            label = "主题",
            kind = "themeSwatches",
            section = InspectorSection.Theme,
            type = ControlType.Select,
            initial = ControlValue.Str(declared),
            min = null,
            max = null,
            step = null,
            options = options,
            multiline = false,
            declaredDefault = null,
            currentText = null,
            autoMatch = null,
            unit = null,
            hidden = false,
        )
    }

    private fun ratioControl(cardId: String): InspectorControl = InspectorControl(
        key = "$cardId::hint.ratio",
        controlId = "hint.ratio",
        param = "params.ratio",
        label = "画面比例",
        kind = "modeSegment",
        section = InspectorSection.Layout,
        type = ControlType.Select,
        initial = ControlValue.Str("original"),
        min = null,
        max = null,
        step = null,
        options = RATIOS,
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private companion object {
        val THEME_LABELS = mapOf(
            "white" to "浅色",
            "black" to "深色",
            "glass" to "玻璃",
            "light_blur" to "浅色模糊",
            "dark_blur" to "深色模糊",
            "transparent_white" to "透明白",
            "transparent_black" to "透明黑",
        )

        val RATIOS = listOf(
            ControlOption("original", "原始"),
            ControlOption("1:1", "1:1"),
            ControlOption("4:3", "4:3"),
            ControlOption("3:4", "3:4"),
            ControlOption("16:9", "16:9"),
        )
    }
}
