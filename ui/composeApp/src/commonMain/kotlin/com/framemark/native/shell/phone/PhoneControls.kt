package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.FmColorSwatch
import com.framemark.native.inspector.FmSlider
import com.framemark.native.inspector.swatchColor
import com.framemark.native.shell.PHONE_PAGE_ROW_DP
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmRhythm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch controls of the phone parameter pages, copied from the showcase's `.touchui`
 * rules (`styles.css`): `.group`, `.ghead`, `.sub-note`, `.fs.touch`, `.seg.touch`,
 * `.sw.touch`, `.swatches`, `.outsz`.
 *
 * They live here rather than in `inspector/` on purpose: the desktop inspector's
 * `FmControls`/`FmSlider` are another package's files, and these are the phone's own
 * chassis around the shared primitives ([FmSlider], [FmColorSwatch]).
 */

/** `.touchui .group { padding: 16px 18px 14px }` + `border-bottom: 1px solid --line`. */
@Composable
fun PhoneGroup(
    title: String,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = FmPalette.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (right != null) right()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

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
 * [PHONE_PAGE_ROW_DP] (44). The plate keeps its 3 dp side padding so the track still
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
                    .height(PHONE_PAGE_ROW_DP.dp)
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
    val text = if (decimals <= 0) {
        value.roundToInt().toString()
    } else {
        val factor = when (decimals) {
            1 -> 10f
            2 -> 100f
            else -> 1000f
        }
        val scaled = (value * factor).roundToInt()
        val whole = scaled / factor.toInt()
        val rest = abs(scaled % factor.toInt()).toString().padStart(decimals, '0')
        "$whole.$rest"
    }
    return text
}

/**
 * `.fs.touch`: the 15 px label (with the 6 px 已改 dot) and the 32 dp value plate on
 * one line, the 40 dp slider track under both. The ↺ reset only appears once the
 * value is off its default — the showcase hides it with `opacity: 0` otherwise.
 *
 * **[compact]（2026-11 密度过）**：标签、轨道与数值挤进**一行 44 dp**（
 * [PHONE_PAGE_ROW_DP]）。标签占 88 dp 一列（有 hint 时在标签下面排 10.5 sp 的一行，
 * 两行合计 30 < 44），轨道吃剩下的宽度，数值靠右。整行就是触控目标，44 dp 是地板，
 * 不因为「一行更挤」而缩到 44 以下；两条这样的行可以并排，
 * 高度仍然只有一行 —— 这正是 Logo / 水印的「两条滑杆一行」。
 */
@Composable
fun PhoneSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float?,
    step: Float?,
    unit: String?,
    modified: Boolean,
    onValueChange: (Float) -> Unit,
    onReset: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    scale: Float = 1f,
    decimals: Int = phoneDecimalsOf(step?.let { it * scale }),
    sliderValue: Float = value,
    sliderRange: ClosedFloatingPointRange<Float> = valueRange,
    onSliderValueChange: (Float) -> Unit = onValueChange,
    /** AU2 A-03/A-04：当前状态下不生效时置灰，并在下面写一句原因。 */
    enabled: Boolean = true,
    disabledReason: String? = null,
    /** 一行 44 dp 的密度档（见上面的说明）；false = 原来两行的触控形态。 */
    compact: Boolean = false,
    /** compact 档里标签列宽：整行 88 dp，两条并排的格子传 56 dp。 */
    labelWidth: Dp = 88.dp,
) {
    if (compact) {
        PhoneCompactSliderRow(
            label = label,
            hint = hint,
            value = sliderValue,
            range = sliderRange,
            decimals = decimals,
            scale = scale,
            unit = unit,
            modified = modified,
            enabled = enabled,
            disabledReason = disabledReason,
            defaultValue = defaultValue,
            step = step,
            onValueChange = onSliderValueChange,
            onReset = onReset,
            labelWidth = labelWidth,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (modified) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                )
            }
            // Label (+hint) is **one** weighted group and the value plate is unweighted, the
            // way `CuratedSliderRow` does it. The old shape — label `weight(1f, fill=false)`
            // plus a separate `Spacer(weight(1f))` — left the label's unused half allocation
            // *after* the plate, so the plate stopped ~74 dp short of the row end instead of
            // being right-aligned. With one weighted group the plate is flush with the row
            // end, and the group shrinks (label ellipsizes) before the plate can ever clip
            // its unit.
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!hint.isNullOrBlank()) {
                    // AU4-16：单位/说明贴着标签（`底部留白 · 占照片宽度`），值框靠右。
                    Spacer(Modifier.width(FmRhythm.inlineLoose))
                    Text(
                        text = "· $hint",
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onReset != null && modified) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .semantics {
                            contentDescription = "恢复默认"
                            role = Role.Button
                        }
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneResetGlyph(tint = FmPalette.onSurfaceMuted)
                }
            }
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = phoneFormatValue(value * scale, decimals),
                    style = androidx.compose.material3.LocalTextStyle.current.copy(
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        FmSlider(
            value = sliderValue,
            onValueChange = onSliderValueChange,
            valueRange = sliderRange,
            modified = modified,
            defaultValue = defaultValue,
            step = step,
            onReset = onReset,
            touch = true,
            enabled = enabled,
            valueText = { phoneFormatValue(it * scale, decimals) + (unit ?: "") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!enabled) {
            Text(
                text = disabledReason ?: "暂不可调",
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** `.sw.touch`: a 46 × 26 track with a 22 dp knob; the row is the 44 dp touch floor. */
@Composable
fun PhoneSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 2026-11 密度过: 52 → 44. The label (15 sp) and the 26 dp track both fit the
            // 44 dp WCAG floor, so the row keeps every pixel of its touch target and
            // gives the 8 dp it saves back to the page.
            .heightIn(min = PHONE_PAGE_ROW_DP.dp)
            .clickable { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = FmPalette.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(if (checked) FmPalette.primary else FmPalette.outlineStrong),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .offset(x = if (checked) 20.dp else 0.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(FmPalette.surface),
            )
        }
    }
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

// ---------------------------------------------------------------------------
// 密度档：一行 44 dp 的滑杆 / 两条并排
// ---------------------------------------------------------------------------

/**
 * 一条「标签 + 轨道 + 数值」挤在**一行 44 dp**（[PHONE_PAGE_ROW_DP]）里的滑杆。
 *
 * 标签占 [labelWidth] 一列（hint 用 10.5 sp 排在标签下面，两行 30 dp 仍在 44 里），
 * 轨道吃掉剩余宽度，数值靠右；置灰时那句「为什么拖不动」也进标签列，不再另占一行。
 * 复位仍然有两条路：拖动气泡 / 双击轨道（`FmSlider` 的 `onReset`）——这一行省掉的
 * 是原来标签行的高度，不是可点面积。
 */
@Composable
private fun PhoneCompactSliderRow(
    label: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int,
    scale: Float,
    unit: String?,
    modified: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    defaultValue: Float?,
    step: Float?,
    onValueChange: (Float) -> Unit,
    onReset: (() -> Unit)?,
    labelWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = PHONE_PAGE_ROW_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.widthIn(max = labelWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (modified) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                )
            }
            Column {
                Text(
                    text = label,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!enabled && !disabledReason.isNullOrBlank()) {
                    Text(
                        text = disabledReason,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!hint.isNullOrBlank()) {
                    Text(
                        text = hint,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(FmRhythm.inlineLoose))
        FmSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modified = modified,
            defaultValue = defaultValue,
            step = step,
            onReset = onReset,
            touch = true,
            enabled = enabled,
            valueText = { phoneFormatValue(it * scale, decimals) + (unit ?: "") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(FmRhythm.inlineLoose))
        Text(
            text = phoneFormatValue(
                if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else range.start,
                decimals,
            ) + (unit ?: ""),
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = FmPalette.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 34.dp),
        )
    }
}

/**
 * 两条 [PhoneCompactSliderRow] 并排占**一行 44 dp**（Logo 的大小 / 水平位置，
 * 水印的两条边距、不透明度 / 旋转）。每个格子自己仍然是一条 44 dp 的行，
 * 所以并排省的是另一行的高度，不是触控高度 —— 政策里的
 * `PHONE_PAGE_SLIDER_PAIR_ROW_DP` 就是这个 44。
 *
 * 面板各自用 `Row { Box(weight(1f)) { ... } }` 画（Logo / 水印在 inspector 包里，
 * 不能反过来依赖这一层），这里只保留这个高度语义的说明，不再提供空的壳函数。
 */


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

/** A quiet full-width note used when a page has nothing to adjust. */
@Composable
fun PhoneEmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = FmPalette.onSurfaceMuted,
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

/** Convenience: the standard page body column (`--pad` 0, sections draw their own). */
@Composable
fun PhonePaneColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) { content() }
}



