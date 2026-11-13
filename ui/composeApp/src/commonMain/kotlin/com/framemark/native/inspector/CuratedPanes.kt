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
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.InsetRule
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
 * One `.group`: 16 top / 12 bottom padding（`FmRhythm` 分组档，规则 2：分组之间只留白
 * 不画线）, a 12.5/600 title row (with an optional right-hand counter), then the content.
 */
@Composable
internal fun CuratedGroup(
    title: String,
    right: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FmRhythm.groupGap, bottom = FmRhythm.groupTail),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FmRhythm.row),
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
        Spacer(Modifier.height(FmRhythm.row))
        content()
    }
}

/**
 * 旧的通栏发线（栏级）。分组之间按规则 2 只留白，不再用它分隔分组；组内两段用
 * [com.framemark.native.theme.InsetRule]。
 */
@Composable
internal fun CuratedRule() {
    Box(Modifier.fillMaxWidth().height(FmRhythm.hairline).background(FmPalette.outline))
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

/**
 * Null on the phone sheet: it renders the plain control list instead.
 *
 * **必须是动态 local**（`compositionLocalOf`，不是 `staticCompositionLocalOf`）：
 * slot 表是引擎渲染完成后才到的，静态 local 不会通知读取方重组，于是「Logo 自动匹配」
 * 在打开 Logo 页时可能一直读到 null（品牌行还没到），显示成「自动匹配 · 按边框自己的品牌」。
 * WP-R 补拍时实测到过这个时序（`FM_LOGO_PANE make=null` 而文字页四行齐全）。
 */
val LocalCuratedTextSlots =
    androidx.compose.runtime.compositionLocalOf<CuratedTextSlots?> { null }

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
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.fillMaxWidth().padding(top = FmRhythm.inline),
    )
}

/** `.outsz` — label left, value right in the numeric face. */
@Composable
internal fun CuratedOutSize(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FmRhythm.rowLoose),
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
 * The 文字大小 control. The registry normalises `text.fontScale` to the consumer
 * name `fontScale` (`ControlRegistry.kt:559`), while the phone used to compare the
 * literal `"text.fontScale"` — so the slider never appeared on any hand card
 * (AU2 A-05). Desktop and phone now share this one predicate.
 */
internal fun fontScaleControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { control ->
        control.type == ControlType.Slider &&
            (control.param == "fontScale" || control.param.endsWith(".fontScale"))
    }

/** The 页脚字号 control (`footerTextScale`): engine-side simple-footer text scale. */
internal fun footerTextScaleControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { it.param.endsWith("footerTextScale") }

/** The Logo 位置 control (`params.logoPos`). */
internal fun logoPosControlOf(controls: List<InspectorControl>): InspectorControl? =
    controls.firstOrNull { it.param.endsWith("logoPos") }

/**
 * 底部留白在某个版式下是否不生效；返回一句原因，null = 生效（AU2 A-03）。
 *
 * 引擎的 `render_overlay` 一次都不读 `footerPct`（`brand_footer.rs:423-560`），
 * 所以叠加版式下这条滑杆置灰，并说清楚为什么。桌面与手机共用这一句。
 */
internal fun footerPctDisabledReason(modeValue: String): String? =
    if (modeValue == "overlay") "叠加版式把文字压在照片上，画布不加高，这一项不生效。" else null

/**
 * 底部留白的**生效下限**（百分比）。引擎取 `max(pw·pct/100, s·0.085)`，
 * 所以 `pct ≤ 8.5·s/pw` 这一段的拖动在成片上没有任何变化（AU2 A-07）。
 * 返回 null 表示没有照片尺寸、算不出来（保持声明下限）。
 */
internal fun footerPctFloor(
    photoWidthPx: Int,
    photoHeightPx: Int,
): Float? {
    if (photoWidthPx <= 0 || photoHeightPx <= 0) return null
    val shortSide = minOf(photoWidthPx, photoHeightPx).toFloat()
    return OutputSize.FOOTER_FLOOR_OF_SHORT_SIDE * shortSide * 100f / photoWidthPx
}

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
    Column(modifier = modifier.fillMaxWidth()) {
        if (theme != null) {
            CuratedGroup("底色") {
                CuratedSwatches(
                    options = theme.options,
                    selected = session.value(theme).toString(),
                    onSelect = { session.set(theme, it) },
                )
            }
        }
        if (mode != null || pct != null) {
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
                    // AU2 A-03：叠加版式的 `render_overlay` 一次都不读 `footerPct`，
                    // 所以把滑杆置灰并说明；AU2 A-07：引擎按
                    // `max(pw·pct/100, 短边·8.5%)` 取留白，低于地板的那一段拖动
                    // 在成片上没有变化，滑杆下限直接抬到地板（不再留死区）。
                    val disabledReason = footerPctDisabledReason(modeValue)
                    val photo = LocalCuratedPhotoSize.current
                    CuratedSliderRow(
                        control = pct,
                        session = session,
                        label = "底部留白",
                        hint = if (disabledReason != null) "叠加版式不生效" else "占照片宽度",
                        unit = "%",
                        decimalPlaces = 1,
                        enabled = disabledReason == null,
                        disabledReason = disabledReason,
                        effectiveMin = if (disabledReason != null) {
                            null
                        } else {
                            photo?.let { footerPctFloor(it.widthPx, it.heightPx) }
                        },
                    )
                    if (disabledReason == null) {
                        val floor = photo?.let { footerPctFloor(it.widthPx, it.heightPx) }
                        if (floor != null && floor > (pct.min ?: 0f)) {
                            CuratedSubNote("低于 ${FmUnits.formatNumber(floor, 2)}% 与地板等价：渲染按短边 8.5% 兜底，画布不再变矮。")
                        }
                    }
                }
                if (outputSize != null) {
                    // 组内两段（滑杆 ↔ 导出尺寸）用规则 3 的内缩发线，不贴边。
                    InsetRule()
                    CuratedOutSize("导出尺寸", "${outputSize.first} × ${outputSize.second} px")
                }
            }
        }
        if (rest.isNotEmpty()) {
            CuratedGroup("更多参数") {
                rest.forEach { control -> GenericControlRow(control, session) }
                // AU2 A-07：`footerAuto` 只在简约版式的 `layout()` 里被读，
                // 经典/叠加下改它没有任何效果（引擎 `brand_footer.rs:124,130`）。
                // 这张卡把它露出来时，至少在这里说清楚它的作用范围。
                if (rest.any { it.param.endsWith("footerAuto") }) {
                    CuratedSubNote("「页脚自适应」只影响简约版式的页脚高度，经典与叠加版式不读它。")
                }
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
