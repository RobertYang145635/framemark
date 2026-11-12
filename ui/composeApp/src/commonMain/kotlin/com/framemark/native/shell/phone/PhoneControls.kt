package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.FmColorSwatch
import com.framemark.native.inspector.swatchColor
import com.framemark.native.shell.PHONE_PARAM_ROW_MIN_TOUCH_DP
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch controls of the phone parameter pages, copied from the showcase's `.touchui`
 * rules (`styles.css`).
 *
 * **2026-11-10 固定画框**：参数行现在是两行的 `PhoneSliderParamRow`（在
 * `PhoneParamRows.kt`），所以这一版删掉了旧的密度档行：
 *
 *  - `PhoneSliderRow` + `PhoneCompactSliderRow`（标签 / 轨道 / 数值挤在一行 44 dp 的
 *    形态，以及它的两行旧形态）—— 换成整宽轨道的两行行；
 *  - `PhoneSwitchRow`（旧的 52 / 44 dp 开关行）—— 换成 `PhoneSwitchParamRow`
 *    （标签 + 开/关 + 开关轨道）；
 *  - `PhoneGroup`（60 dp 的组头）、`PhoneEmptyNote`、`PhonePaneColumn` —— 三个已经
 *    没有调用者的旧底盘。
 *
 * 留下的是仍在用的四块：段落选择器（[PhoneSegmented]）、色板（[PhoneSwatchRow]）、
 * 键值行（[PhoneKeyValueRow]）、只读规格（[PhoneSpecRows]）和一句说明（[PhoneSubNote]）。
 */

/** `.touchui .sub-note { font-size: 12.5px; color: --ink-4; line-height: 1.5 }`. */
@Composable
fun PhoneSubNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = androidx.compose.material3.LocalTextStyle.current.copy(
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        ),
        color = FmPalette.onSurfaceMuted,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * `.seg.touch`: a `--seg` plate (radius 10) with **44 dp** buttons — the touch floor
 * itself, so the plate has no vertical padding (3+36+3 was 42: below the floor).
 *
 * 2026-11 密度过: 42 → 44 dp. A densified page lays these rows out two-per-row and
 * next to a label, so the row height *is* the touch height; it may not go below
 * [PHONE_PARAM_ROW_MIN_TOUCH_DP] (44). The plate keeps its 3 dp side padding so the track still
 * reads as a track, and each option fills the full 44 dp height.
 */
@Composable
fun PhoneSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surfaceMuted)
            .padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, text) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(PHONE_PARAM_ROW_MIN_TOUCH_DP.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(if (active) FmPalette.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        if (active) {
                            Modifier.border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.chip))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics {
                        contentDescription = "$label：$text"
                        stateDescription = if (active) "已选" else "未选"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Number of decimals a step needs, as the showcase's `decOf(step)`. */
internal fun phoneDecimalsOf(step: Float?): Int {
    if (step == null || step <= 0f) return 0
    val rounded = (step * 1000f).roundToInt() / 1000f
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) 0 else (text.length - dot - 1)
}

/** `11` → `"11"`, `11.1` → `"11.1"`, `0.05` → `"0.05"` (showcase `fmt`). */
internal fun phoneFormatValue(value: Float, decimals: Int): String {
    if (decimals <= 0) return value.roundToInt().toString()
    val factor = when (decimals) {
        1 -> 10
        2 -> 100
        else -> 1000
    }
    val scaled = (value * factor).roundToInt()
    // 量到的 `曝光度 …… -0.13`：小数部分的符号必须留在**整数部分**，否则 -0.13 会
    // 印成 `0.13`（旧实现用整数除法取整，-13 / 100 截断成 0，符号就丢了）。
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    return sign + (magnitude / factor).toString() + "." +
        (magnitude % factor).toString().padStart(decimals, '0')
}

/**
 * `.swatches`: six columns of 38 dp circles with 12.5 px labels (`.touchui`).
 *
 * 2026-11 密度过：格子的上下 4 dp 内边距去掉（38 + 5 + 16 = **59 dp**，整格仍是
 * 59 × ~50 的点击面，远高于 44 dp 地板），一个主题的六个色片永远保持**一行六列**
 * —— 不再折成一块带边距的色板。选项多于六个时才换行（行间 6 dp）。
 */
@Composable
fun PhoneSwatchRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (value, label) ->
                    val active = value == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .clickable(role = Role.RadioButton) { onSelect(value) }
                            .semantics {
                                contentDescription = "底色：$label"
                                stateDescription = if (active) "已选" else "未选"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FmColorSwatch(
                            color = swatchColor(value),
                            selected = active,
                            label = label,
                            onClick = { onSelect(value) },
                            size = 38.dp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = label,
                            style = androidx.compose.material3.LocalTextStyle.current.copy(
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}


/**
 * `.outsz`: a dashed-top row of 12 px supporting copy with its number on the right.
 *
 * [topPadding] is the 9 dp `.outsz` rule gap when the row sits under a dashed rule
 * inside a group; a pinned band (`PhoneDesignSummary`) pads the band itself and
 * passes `0.dp`.
 */
@Composable
fun PhoneKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 9.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .semantics(mergeDescendants = true) { contentDescription = "$label：$value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = FmPalette.onSurface,
            maxLines = 1,
        )
    }
}

/** `.kv` block: two columns of 12.5 px label/value pairs (边框规格). */
@Composable
fun PhoneSpecRows(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = value,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}