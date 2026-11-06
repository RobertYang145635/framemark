package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.workflow.DesignSession

/**
 * WP-V3 curated panes — the 演示稿 page model (`core.js:851 buildPane`) instead of
 * the engine-parameter form.
 *
 * 样式页 = 底色色板 + 版式段控 + 底部留白 + 导出尺寸（可调边框）；厂商规格卡只给
 * 只读「边框规格」块，不铺引擎原始参数。文字页 / 水印页在各自文件里。
 *
 * 所有取值都从 [InspectorControl] 的公开字段（`param` / `kind` / `options` /
 * `min` / `max` / `step` / `declaredDefault`）读，不新造数据模型、不写引擎参数名
 * 以外的约定。
 */

/** `--ink` group title: 12.5 / 600 (styles.css `.ghead h4`). */
internal val CuratedGroupTitle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    fontWeight = FontWeight.SemiBold,
)

/** `.sub-note`: 11.5, `ink-4`, line-height 1.5. */
internal val CuratedNote = TextStyle(
    fontSize = 11.5.sp,
    lineHeight = 17.sp,
    fontWeight = FontWeight.Normal,
)

/** `.ghead .count` / `.outsz`: 11–12 px numeric copy. */
internal val CuratedNumeric = TextStyle(
    fontSize = 12.sp,
    lineHeight = 17.sp,
    fontWeight = FontWeight.Medium,
).withNumericDigits()

/**
 * One `.group`: 14 top / 12 bottom padding, a 12.5/600 title row (with an
 * optional right-hand counter), then the content. The caller draws the hairline
 * between groups.
 */
@Composable
internal fun CuratedGroup(
    title: String,
    right: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = CuratedGroupTitle,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            right?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Hairline between two groups (`border-bottom: 1px var(--line)`). */
@Composable
internal fun CuratedRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
}

/** Pixel size of the photo the stage is showing (`导出尺寸` needs it). */
data class CuratedPhotoSize(val widthPx: Int, val heightPx: Int)

/**
 * 文字页的行数据：舞台最近一次渲染报告的文本 slot，加上那张成片的像素画布。
 *
 * 尺寸只给每行的位置示意图用（`core.js:360 diagram`）：slot 的 x/y/w/h 以成片像素
 * 表示，除以画布宽高就得到归一化矩形。桌面 rail 提供，手机底板不提供，所以手机
 * 保持原来的控件列表。
 */
data class CuratedTextSlots(
    val slots: List<com.framemark.native.workflow.TextSlot>,
    val widthPx: Int,
    val heightPx: Int,
)

/** Null on the phone sheet: it renders the plain control list instead. */
val LocalCuratedTextSlots =
    androidx.compose.runtime.staticCompositionLocalOf<CuratedTextSlots?> { null }

/**
 * Supplied by [com.framemark.native.shell.desktop.DesktopInspectorRail] from the
 * shell's active photo. Null on the phone sheet, which keeps `导出尺寸` hidden
 * there instead of guessing a size.
 */
val LocalCuratedPhotoSize = androidx.compose.runtime.staticCompositionLocalOf<CuratedPhotoSize?> { null }

/** Plain sub-note paragraph. */
@Composable
internal fun CuratedSubNote(text: String) {
    Text(
        text = text,
        style = CuratedNote,
        color = FmPalette.onSurfaceFaint,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** `.outsz` — label left, value right in the numeric face. */
@Composable
internal fun CuratedOutSize(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CuratedNote,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = CuratedNumeric,
            color = FmPalette.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * `.swatches` — `grid repeat(6, 1fr)` gap 6; each cell is a 28 dp disc with an
 * 11 px caption. Selected = accent ring (2 dp, offset 2) + the theme's own
 * foreground bar inside the disc; the caption turns `accent-strong` 600.
 */
@Composable
internal fun CuratedSwatches(
    options: List<ControlOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { option ->
                    CuratedSwatch(
                        option = option,
                        selected = option.value == selected,
                        onClick = { onSelect(option.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CuratedSwatch(
    option: ControlOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = swatchColor(option.value)
    val mark = swatchForeground(option.value)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = option.display
                stateDescription = if (selected) "已选" else "未选"
            }
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, FmPalette.primary, CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .border(1.dp, FmPalette.outlineStrong, CircleShape),
            ) {
                // `i::after` — a 12 × 2 bar in the theme's own ink, 7 dp above the bottom.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp)
                        .width(12.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(mark),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = option.display,
            style = TextStyle(
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) FmPalette.onSurface else FmPalette.onSurfaceMuted,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** `--plate` pill: 11 / 18, radius 4 — used by the head and the tab counters. */
@Composable
internal fun CuratedPlatePill(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 11.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        color = FmPalette.onSurfaceMuted,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FmPalette.surfaceMuted)
            .padding(horizontal = 6.dp),
    )
}

/** `.link.muted` — the small text action (「回到右下」). */
@Composable
internal fun CuratedLink(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = text,
        style = CuratedNote,
        color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .widthIn(min = 0.dp)
            .padding(horizontal = 2.dp),
    )
}

// ---------------------------------------------------------------- 样式页

/** Showcase `MODE_OPTS` order: 经典 / 简约 / 叠加. */
private val FooterModeOrder = listOf("classic", "simple", "overlay")

private val FooterModeLabel = mapOf("classic" to "经典", "simple" to "简约", "overlay" to "叠加")

/** Showcase `MODE_NOTE` — one sentence per footer layout, shown under the segment. */
private val FooterModeNote = mapOf(
    "classic" to "左侧品牌与机型，右侧参数与日期，中间放 Logo。",
    "simple" to "单行居中：Logo · 品牌机型 · 参数 · 日期。",
    "overlay" to "文字压在照片底部，画布不加高。",
)

/** The 底色 control: the registry's `theme` row (`themeSwatches`). */
internal fun themeControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { it.param == "theme" || it.kind == "themeSwatches" }

/** The 版式 control: `params.footerMode` (`gravitySegment`). */
internal fun footerModeControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { it.param.endsWith("footerMode") }

/** The 底部留白 control: `params.footerPct`. */
internal fun footerPctControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { it.param.endsWith("footerPct") }

/**
 * 样式页 (showcase `paneClassicStyle`): 底色色板 → 版式段控 + 说明 + 底部留白 →
 * 导出尺寸。可调边框才走这里；厂商规格卡走 [CuratedSpecPane]。
 */
@Composable
internal fun CuratedStylePane(
    controls: List<InspectorControl>,
    session: DesignSession,
    modifier: Modifier = Modifier,
    /** 导出像素尺寸；null 时（照片尺寸未接线）不画这一行。 */
    outputSize: Pair<Int, Int>? = null,
) {
    val theme = themeControlOf(controls)
    val mode = footerModeControlOf(controls)
    val pct = footerPctControlOf(controls)
    val handled = listOfNotNull(theme, mode, pct).toSet()
    val rest = controls.filterNot { it in handled }
    var first = true
    Column(modifier = modifier.fillMaxWidth()) {
        if (theme != null) {
            CuratedGroup("底色") {
                CuratedSwatches(
                    options = theme.options,
                    selected = session.value(theme).toString(),
                    onSelect = { session.set(theme, it) },
                )
            }
            first = false
        }
        if (mode != null || pct != null) {
            if (!first) CuratedRule()
            val modeValue = mode?.let { session.value(it).toString() }.orEmpty()
            CuratedGroup("版式") {
                if (mode != null && mode.options.isNotEmpty()) {
                    CuratedSegmented(
                        options = orderedModeOptions(mode.options),
                        selected = modeValue,
                        onSelect = { session.set(mode, it) },
                    )
                    CuratedSubNote(FooterModeNote[modeValue] ?: "")
                }
                if (pct != null) {
                    CuratedSliderRow(
                        control = pct,
                        session = session,
                        label = "底部留白",
                        hint = if (modeValue == "overlay") "文字区高度" else "占照片宽度",
                        unit = "%",
                        decimalPlaces = 1,
                    )
                }
                if (outputSize != null) {
                    CuratedOutSize("导出尺寸", "${outputSize.first} × ${outputSize.second} px")
                }
            }
            first = false
        }
        if (rest.isNotEmpty()) {
            if (!first) CuratedRule()
            CuratedGroup("更多参数") {
                rest.forEach { control -> GenericControlRow(control, session) }
            }
        }
    }
}

/** 经典 / 简约 / 叠加 first, then anything the registry adds. */
private fun orderedModeOptions(options: List<ControlOption>): List<ControlOption> {
    val known = FooterModeOrder.mapNotNull { key -> options.firstOrNull { it.value == key } }
    return known + options.filterNot { option -> FooterModeOrder.any { it == option.value } }
}

/** Fallback row for a control the curated panes do not own yet. */
@Composable
internal fun GenericControlRow(control: InspectorControl, session: DesignSession) {
    ControlItem(control = control, session = session, dense = false)
}
