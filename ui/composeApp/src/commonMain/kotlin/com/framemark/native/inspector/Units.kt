package com.framemark.native.inspector

import com.framemark.native.product.ControlLabels
import kotlin.math.round

/**
 * WP-C §4.1 (P0): the one place that turns an engine `unit` into human copy and
 * a raw value into the number the user sees.
 *
 * Sources: report §9 "单位换算（只在 displayUnit 一处）" and the WP-C row in
 * HANDOFF part 2 §4.
 *
 *  | engine unit | display | value |
 *  |---|---|---|
 *  | `pct` / `percent` / `%` | `%` | as stored |
 *  | `multiplier` | `×` | as stored |
 *  | `alpha` | `%` | ×100, **only when max ≤ 1.5** |
 *  | `count` | `个` | as stored |
 *  | `pctOfLongEdge` | `% 长边` | as stored |
 *  | `unitless` | `%` only for ratio fields (report names `logoCenterX`) | ×100 for those |
 *  | anything else | **no unit** | as stored |
 *
 * An unknown unit is never echoed: showing `0.594unitless` is the bug this file
 * exists to remove, so the fallback is "no suffix", and the raw token is
 * reported by the unit test that scans `controls.json`.
 *
 * Raw tokens that must never be displayed: `pct`, `multiplier`, `unitless`,
 * `alpha`, `count`.
 */
internal object FmUnits {

    /** Unit strings the frozen data can carry; none of them may reach the UI. */
    val RAW_UNITS: List<String> = listOf("pct", "percent", "multiplier", "unitless", "alpha", "count", "pctOfLongEdge")

    /**
     * `unitless` values that are a **ratio** and therefore read as a percentage:
     * the showcase/REPORT explicitly names `logoCenterX` ("59.4 %"), and
     * `cardCx` is its side-column twin (0..1, "侧栏水平中心").
     *
     * Everything else that arrives as `unitless` is left without a unit on
     * purpose: `brandY` / `exifY` / `exifScaleX` / `stubTitleSize` / `rowsX` /
     * `contentW` have no documented meaning in the sources, so guessing a unit
     * would be worse than showing a bare number. WP-C report §1 lists them.
     */
    val RATIO_UNITLESS_FIELDS: Set<String> = setOf("logoCenterX", "cardCx")

    /** Alpha is multiplied by 100 only while the declared maximum stays ≤ 1.5. */
    const val ALPHA_SCALE_MAX = 1.5f

    fun isAlphaPercent(unit: String?, max: Float?): Boolean =
        unit == "alpha" && (max == null || max <= ALPHA_SCALE_MAX)

    /** Value multiplier between the stored value and the displayed number. */
    fun scaleFor(unit: String?, max: Float?, param: String = ""): Float = when {
        isAlphaPercent(unit, max) -> 100f
        unit == "unitless" && isRatioField(param) -> 100f
        else -> 1f
    }

    fun isRatioField(param: String): Boolean =
        param.isNotBlank() && ControlLabels.fieldOf(param) in RATIO_UNITLESS_FIELDS

    /** Display suffix, or null when the number is shown without a unit. */
    fun suffixFor(unit: String?, max: Float?, param: String = ""): String? = when (unit) {
        null, "" -> null
        "pct", "percent", "%" -> "%"
        "multiplier" -> "×"
        "count" -> "个"
        "pctOfLongEdge" -> "% 长边"
        "alpha" -> if (isAlphaPercent(unit, max)) "%" else null
        "unitless" -> if (isRatioField(param)) "%" else null
        // Never echo an engine token. Reports an unmapped unit instead.
        else -> null
    }

    /** 3-decimal display number, identical to the inspector's historical rule. */
    fun formatNumber(value: Float): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val rounded = round(value * 1000.0) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    /**
     * D1-3: fixed-decimal variant for the watermark margins, which move on a
     * 0.1 % grid and must read like "6.0 %" after a drag. Only 1 or 2 decimals
     * are used; any other count falls back to the historical 3-decimal rule.
     */
    fun formatNumber(value: Float, decimals: Int): String {
        if (decimals != 1 && decimals != 2) return formatNumber(value)
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val factor = if (decimals == 1) 10.0 else 100.0
        val rounded = round(value * factor) / factor
        val text = rounded.toString()
        val intPart = text.substringBefore('.')
        val frac = text.substringAfter('.', "").padEnd(decimals, '0').take(decimals)
        return "$intPart.$frac"
    }

    /** Number as the user sees it (already scaled) plus its display suffix. */
    fun displayText(value: Float, unit: String?, scale: Float): String =
        formatNumber(value * scale) + (unit ?: "")

    /** Convenience for one inspector control. */
    fun displayText(control: InspectorControl, value: Float): String =
        displayText(value, control.unit, control.unitScale)

    /** Text used when the control's value must be shown without a live value. */
    fun formatValueText(control: InspectorControl, value: Float?): String =
        value?.let { displayText(control, it) } ?: "—"
}
