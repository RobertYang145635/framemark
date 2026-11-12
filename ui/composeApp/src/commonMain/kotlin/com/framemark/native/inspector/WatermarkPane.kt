package com.framemark.native.inspector

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs

/** 水印旋转滑杆的编辑区间（模型允许 ±180，编辑只开放常用段）。 */
private const val WATERMARK_ROTATION_RANGE = 45f

/**
 * WP-C §4.4: the 水印 page — nine-cell grid, then only the margins that cell
 * allows, then width and opacity.
 *
 * This composable owns **no storage**: the caller passes the current
 * [WatermarkSpec] and receives anchor / width / opacity / absolute `dx`,`dy`.
 * That keeps the data model (`product/Watermark.kt`, `workflow/Watermark.kt`) and
 * the project format untouched, and lets D1/P1 wire it either to
 * `ProjectHolder.updateWatermark(...)` (desktop) or to the phone sheet.
 *
 * Legacy rule (HANDOFF part 2 §3.8): a preset whose centred direction carries an
 * offset is displayed and calculated as 0; the first user edit writes the
 * normalised value back. [WatermarkZones] does that conversion.
 *
 * **手机不再走这个整页**（2026-11-10 固定画框）：水印面板按两组（图层 / 外观）画两行
 * 参数行（2026-11-11：图层卡片 + 两条边距进「图层」，宽度 / 不透明度 / 旋转进「外观」），
 * 九宫格在**画布**上（`PhoneWatermarkAnchorLayer`，九个 ~10 dp 热点、不画图片内容）。所以
 * `part` 分段参数与它对应的 `WatermarkPanePart` 枚举、以及「两条滑杆并排」的
 * 手机密度档一起删掉了；这一页只剩桌面右栏那一种形态。
 */
@Composable
fun WatermarkPane(
    spec: WatermarkSpec?,
    photoAspect: Float,
    onAnchor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onPosition: (dx: Float, dy: Float) -> Unit,
    /**
     * 旋转（度）。null = 调用方这一轮不开放旋转，滑杆不出现（AU2 A-11）。
     * 桌面与手机都接了这个入口。
     */
    onRotation: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    assetName: String? = null,
    onUpload: (() -> Unit)? = null,
    uploadLabel: String = "上传透明 PNG 水印",
    onApplyAll: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (spec == null) {
            WatermarkEmptyBlock(onUpload = onUpload, uploadLabel = uploadLabel)
            return@Column
        }

        val zone = WatermarkZones.zoneFor(spec.anchor)
        // 桌面右栏：三组 + 两条发线，内容与顺序都没挪。
        CuratedGroup("图层") {
            WatermarkLayerBlock(
                assetName = assetName,
                onUpload = onUpload,
                onClear = onClear,
                uploadLabel = uploadLabel,
            )
        }
        CuratedRule()
        CuratedGroup(
            title = "位置",
            right = {
                if (onReset != null) {
                    CuratedLink("回到右下", onClick = onReset)
                }
            },
        ) {
            WatermarkPositionBlock(
                spec = spec,
                onAnchor = onAnchor,
                onWidth = onWidth,
                onPosition = onPosition,
            )
            WatermarkMarginsBlock(
                spec = spec,
                photoAspect = photoAspect,
                zone = zone,
                onPosition = onPosition,
            )
        }
        CuratedRule()
        CuratedGroup("外观") {
            WatermarkWidthBlock(spec = spec, zone = zone, onWidth = onWidth)
            WatermarkOpacityBlock(spec = spec, onOpacity = onOpacity)
            if (onRotation != null) {
                WatermarkRotationBlock(spec = spec, onRotation = onRotation)
            }
            CuratedSubNote("也可以直接在照片上拖动；拖动时会框出这一格能到的范围。")
            if (onApplyAll != null || onReset != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    if (onApplyAll != null) {
                        GhostAction("应用到全部", onApplyAll)
                    }
                    if (onReset != null) {
                        GhostAction("恢复默认", onReset)
                    }
                }
            }
        }
    }
}

/**
 * 空态：说明 + primary「添加图片水印」。
 *
 * [dense] 只给手机面板用（`PhoneParameterPage` 的「图层」组空态）；桌面右栏不传。
 */
@Composable
internal fun WatermarkEmptyBlock(
    dense: Boolean = false,
    onUpload: (() -> Unit)?,
    uploadLabel: String,
) {
    // `core.js:778 paneWm` 空态：标题 + 一句说明 + primary「添加图片水印」。
    CuratedEmptyPane(
        title = "还没有图片水印",
        body = "签名、印章等透明 PNG，可放在九宫格任一位置再微调。",
        dense = dense,
    )
    if (onUpload != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.primary)
                .clickable(role = Role.Button, onClick = onUpload)
                .semantics { contentDescription = uploadLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text("＋ 添加图片水印", style = FmType.labelMedium, color = FmPalette.onPrimary)
        }
    }
}

/**
 * 图层卡片：48 × 36 的 PNG 板 + 名称 / 一句说明 + 两个图标动作。整行 44 dp。
 *
 * [dense]（手机）下两个图标动作也是 44 × 44 的触控格、卡片上下不再留 8 dp，
 * 于是这一块正好是一行 [FmDenseRowHeight]，而动作的可点面从 26 长到了 44。
 */
@Composable
internal fun WatermarkLayerBlock(
    assetName: String?,
    onUpload: (() -> Unit)?,
    onClear: (() -> Unit)?,
    uploadLabel: String,
    dense: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            // 2026-11 密度过：上下 8 → 0，整块就是 36 + 图标格里最高的那个（44）= 44 dp。
            .padding(horizontal = 8.dp, vertical = if (dense) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("PNG", style = CuratedSliderHint, color = FmPalette.onSurfaceMuted)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = assetName ?: "图片水印",
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "透明 PNG · 可拖动位置",
                style = CuratedSliderHint,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        if (onUpload != null) {
            WatermarkIconAction("↻", uploadLabel, FmPalette.onSurfaceMuted, onUpload, dense = dense)
        }
        if (onClear != null) {
            WatermarkIconAction("🗑", "移除水印", FmPalette.error, onClear, dense = dense)
        }
    }
}

/** 位置：九宫格（每格独立：换格从模型默认边距开始）。桌面右栏的整页形态。 */
@Composable
private fun WatermarkPositionBlock(
    spec: WatermarkSpec,
    onAnchor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onPosition: (dx: Float, dy: Float) -> Unit,
) {
    WatermarkZoneGrid(
        anchor = spec.anchor,
        onAnchor = { next ->
            if (next != spec.anchor) {
                // Per-cell independence: a new cell starts from the model's
                // default margin (offsets reset), and a width that no longer
                // fits the new column is pulled back (report §9.1).
                onAnchor(next)
                onPosition(0f, 0f)
                val cap = WatermarkZones.widthCapFor(next)
                if (spec.width > cap) onWidth(cap)
            }
        },
    )
}

/** 边距：居中格写「固定」说明，其余格给两条 `距…` 滑杆；旧预设的居中说明也在这里。 */
@Composable
private fun WatermarkMarginsBlock(
    spec: WatermarkSpec,
    photoAspect: Float,
    zone: WatermarkZone,
    onPosition: (dx: Float, dy: Float) -> Unit,
) {
    val legacyOffset = legacyCentredOffset(spec)
    if (legacyOffset != null) {
        Text(
            text = "旧预设的居中方向带有偏移；这里按 0 显示，改动后才写入新值。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
    }
    if (zone.pinned) {
        Text(
            text = "居中格子的水平和垂直都固定，换一个格子才能移动。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
    } else {
        MarginSlider(
            spec = spec,
            photoAspect = photoAspect,
            edge = zone.horizontalEdge,
            onPosition = onPosition,
        )
        MarginSlider(
            spec = spec,
            photoAspect = photoAspect,
            edge = zone.verticalEdge,
            onPosition = onPosition,
        )
    }
}

/** 宽度：按格上限收窄；上限就是滑杆的上限（值框里的 % 已经在说同一件事）。 */
@Composable
private fun WatermarkWidthBlock(
    spec: WatermarkSpec,
    zone: WatermarkZone,
    onWidth: (Float) -> Unit,
) {
    // Width: capped by the column (47 % sides / 60 % middle).
    val cap = zone.widthCap
    val widthPercent = spec.width.coerceIn(0.05f, cap) * 100f
    FmDesktopSliderRow(
        label = "宽度",
        value = widthPercent,
        min = 5f,
        max = cap * 100f,
        step = 0.5f,
        default = WatermarkZones.DEFAULT_WIDTH * 100f,
        unit = "%",
        onValueChange = { onWidth((it / 100f).coerceIn(0.05f, cap)) },
    )
    Text(
        text = "此格宽度上限 ${(cap * 100).toInt()}%",
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceMuted,
    )
}

/** 不透明度。 */
@Composable
private fun WatermarkOpacityBlock(
    spec: WatermarkSpec,
    onOpacity: (Float) -> Unit,
) {
    val opacityPercent = spec.opacity.coerceIn(0.1f, 1f) * 100f
    FmDesktopSliderRow(
        label = "不透明度",
        value = opacityPercent,
        min = 10f,
        max = 100f,
        step = 1f,
        default = WatermarkZones.DEFAULT_OPACITY * 100f,
        unit = "%",
        onValueChange = { onOpacity((it / 100f).coerceIn(0.1f, 1f)) },
    )
}

/** 旋转 −45..45°（模型允许 ±180，编辑收窄到常用段，避免误拖成倒置）。 */
@Composable
private fun WatermarkRotationBlock(
    spec: WatermarkSpec,
    onRotation: (Float) -> Unit,
) {
    val angle = spec.rotation.coerceIn(-WATERMARK_ROTATION_RANGE, WATERMARK_ROTATION_RANGE)
    val label = "旋转"
    val onChange: (Float) -> Unit = { raw ->
        onRotation(raw.coerceIn(-WATERMARK_ROTATION_RANGE, WATERMARK_ROTATION_RANGE))
    }
    FmDesktopSliderRow(
        label = label,
        value = angle,
        min = -WATERMARK_ROTATION_RANGE,
        max = WATERMARK_ROTATION_RANGE,
        step = 1f,
        default = 0f,
        unit = "°",
        onValueChange = onChange,
        decimalPlaces = 0,
    )
}

/** `.ibtn.sm` in the layer card — a 26 dp square glyph action (44 dp on the phone). */
@Composable
private fun WatermarkIconAction(
    glyph: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    dense: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(if (dense) FmDenseRowHeight else 26.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = CuratedSliderHint.copy(fontSize = 14.sp), color = tint)
    }
}

@Composable
private fun GhostAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary)
    }
}

/**
 * One margin slider for the desktop rail; an edge with no editable axis draws nothing
 * (the 「居中格固定」 sentence above already says it).
 *
 * 手机不再用这一条：面板里的 距左 / 距上 是两行参数行（标签 + 数值 + 整宽轨道），
 * 由 `PhoneSliderParamRow` 画，居中的格子是同一行的置灰态。
 */
@Composable
private fun MarginSlider(
    spec: WatermarkSpec,
    photoAspect: Float,
    edge: WatermarkEdge?,
    onPosition: (Float, Float) -> Unit,
) {
    if (edge == null) return
    val maxPct = WatermarkZones.maxMarginPct(
        anchor = spec.anchor,
        width = spec.width,
        photoAspect = photoAspect,
        assetAspect = spec.aspect,
        edge = edge,
    )
    val current = WatermarkZones.marginPct(spec, photoAspect, edge).coerceIn(0f, maxPct)
    val label = when (edge) {
        WatermarkEdge.Left -> "距左边"
        WatermarkEdge.Right -> "距右边"
        WatermarkEdge.Top -> "距上边"
        WatermarkEdge.Bottom -> "距下边"
    }
    if (maxPct <= 0.05f) {
        Text(
            text = "$label：此格已没有可调空间（水印已占满这一格）。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
        return
    }
    // Default margin in % of width, both axes per report §9.1.
    val aspect = photoAspect.takeIf { it.isFinite() && it > 0.01f } ?: 1.5f
    val defaultPct = if (edge == WatermarkEdge.Top || edge == WatermarkEdge.Bottom) {
        // The model default is a normalised height offset; distances are width based.
        (WatermarkZones.DEFAULT_MARGIN / aspect * 100f).coerceIn(0f, maxPct)
    } else {
        (WatermarkZones.DEFAULT_MARGIN * 100f).coerceIn(0f, maxPct)
    }
    FmDesktopSliderRow(
        label = label,
        value = current,
        min = 0f,
        max = maxPct,
        step = 0.1f,
        default = defaultPct,
        unit = "%",
        decimalPlaces = 1,
        onValueChange = { next ->
            val (dx, dy) = WatermarkZones.positionFor(spec, photoAspect, edge, next)
            onPosition(dx, dy)
        },
    )
}

/**
 * The nine-cell picker of the desktop 水印 page. The selected cell is filled
 * `primary`; every cell of the selected cell's **zone** gets the soft wash, so the
 * user sees the area the watermark may move in (report §9.1 "范围看得见").
 *
 * The plate/cell chrome itself lives in [AnchorCellsGrid] because a vendor card's
 * 水印位置 control (`StockAnchorControl`) draws the same grid with word labels
 * instead of arrows — one look, two callers.
 *
 * 手机上这个格子**不在面板里**：它在画布上（`PhoneWatermarkAnchorLayer`，九个 ~10 dp
 * 热点、不画图片内容），面板只留 图层组（图层 / 距左 / 距上）与外观组（宽度 /
 * 不透明度 / 旋转）六条两行参数行。
 */
@Composable
private fun WatermarkZoneGrid(
    anchor: Int,
    onAnchor: (Int) -> Unit,
) {
    val zone = WatermarkZones.zoneFor(anchor)
    AnchorCellsGrid(
        selectedIndex = anchor,
        dense = false,
        onPick = onAnchor,
        glyphOf = { WatermarkZoneGlyphs[it] },
        descriptionOf = { "水印位置：${WatermarkZoneGlyphs[it]}" },
        inZone = { index ->
            // A cell is washed when it overlaps the selected cell's zone (the zone
            // is half a photo wide, so it covers two columns).
            val col = index % 3
            val row = index / 3
            val cellX = col / 3f to (col + 1) / 3f
            val cellY = row / 3f to (row + 1) / 3f
            cellX.first < zone.xMax && cellX.second > zone.xMin &&
                cellY.first < zone.yMax && cellY.second > zone.yMin
        },
    )
}

/** Arrow per cell, row-major (top-left → bottom-right). */
private val WatermarkZoneGlyphs = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")

/**
 * Non-null when a legacy preset carries an offset on a centred direction; the
 * pane then shows the "treated as 0" note (once visible, it never rewrites the
 * file by itself).
 */
internal fun legacyCentredOffset(spec: WatermarkSpec): Pair<Float, Float>? {
    val centredX = WatermarkZones.columnOf(spec.anchor) == 1
    val centredY = WatermarkZones.rowOf(spec.anchor) == 1
    val x = if (centredX && abs(spec.dx) > 1e-4f) spec.dx else 0f
    val y = if (centredY && abs(spec.dy) > 1e-4f) spec.dy else 0f
    return if (x == 0f && y == 0f) null else x to y
}

/** Chip used by the page header ("边距 0.1%"). Kept for P1's compact sheet. */
@Composable
internal fun WatermarkCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .fmElevated(FmElevation.card, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.md),
    ) {
        content()
    }
}
