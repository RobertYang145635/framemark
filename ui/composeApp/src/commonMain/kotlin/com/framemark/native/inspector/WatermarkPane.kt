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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs

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
 */
@Composable
fun WatermarkPane(
    spec: WatermarkSpec?,
    photoAspect: Float,
    onAnchor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onPosition: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
    assetName: String? = null,
    onUpload: (() -> Unit)? = null,
    uploadLabel: String = "上传透明 PNG 水印",
    onApplyAll: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    dense: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        if (spec == null) {
            if (onUpload != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(FmRadii.input))
                        .background(FmPalette.surfaceMuted)
                        .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.input))
                        .clickable(role = Role.Button, onClick = onUpload)
                        .semantics { contentDescription = uploadLabel }
                        .padding(FmSpacing.lg),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text("＋ $uploadLabel", style = FmType.bodyMedium, color = FmPalette.onSurfaceSecondary)
                        Text(
                            "加一张透明 PNG，再在照片上拖到想要的位置。",
                            style = FmType.labelSmall,
                            color = FmPalette.onSurfaceMuted,
                        )
                    }
                }
            } else {
                Text("先添加一张照片，再上传水印。", style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
            }
            return@Column
        }

        assetName?.let {
            Text(
                text = it,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Layer switch and upload live at the top: "图层（显示、移除）".
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onUpload != null) {
                Text(
                    text = "更换水印",
                    style = FmType.labelMedium,
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button, onClick = onUpload)
                        .padding(horizontal = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (onClear != null) {
                Text(
                    text = "移除",
                    style = FmType.labelMedium,
                    color = FmPalette.error,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button, onClick = onClear)
                        .padding(horizontal = 6.dp),
                )
            }
        }

        Text("位置", style = FmType.labelMedium, color = FmPalette.onSurface)
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
            dense = dense,
        )

        val zone = WatermarkZones.zoneFor(spec.anchor)
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
                dense = dense,
            )
            MarginSlider(
                spec = spec,
                photoAspect = photoAspect,
                edge = zone.verticalEdge,
                onPosition = onPosition,
                dense = dense,
            )
        }

        // Width: capped by the column (47 % sides / 60 % middle).
        val cap = zone.widthCap
        val widthPercent = spec.width.coerceIn(0.05f, cap) * 100f
        if (dense) {
            FmTouchSliderRow(
                label = "宽度",
                value = widthPercent,
                min = 5f,
                max = cap * 100f,
                step = 0.5f,
                default = WatermarkZones.DEFAULT_WIDTH * 100f,
                unit = "%",
                onValueChange = { onWidth((it / 100f).coerceIn(0.05f, cap)) },
            )
        } else {
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
        }
        Text(
            text = "此格宽度上限 ${(cap * 100).toInt()}%",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )

        val opacityPercent = spec.opacity.coerceIn(0.1f, 1f) * 100f
        if (dense) {
            FmTouchSliderRow(
                label = "不透明度",
                value = opacityPercent,
                min = 10f,
                max = 100f,
                step = 1f,
                default = WatermarkZones.DEFAULT_OPACITY * 100f,
                unit = "%",
                onValueChange = { onOpacity((it / 100f).coerceIn(0.1f, 1f)) },
            )
        } else {
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

        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            if (onApplyAll != null) {
                GhostAction("应用到全部", onApplyAll)
            }
            if (onReset != null) {
                GhostAction("恢复默认", onReset)
            }
        }
        Text(
            text = "也可以直接在照片上拖动；拖动时会框出这一格能到的范围。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
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

/** One margin slider; hidden when the cell has no editable axis on this edge. */
@Composable
private fun MarginSlider(
    spec: WatermarkSpec,
    photoAspect: Float,
    edge: WatermarkEdge?,
    onPosition: (Float, Float) -> Unit,
    dense: Boolean,
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
    val onChange: (Float) -> Unit = { next ->
        val (dx, dy) = WatermarkZones.positionFor(spec, photoAspect, edge, next)
        onPosition(dx, dy)
    }
    if (dense) {
        FmTouchSliderRow(
            label = label,
            value = current,
            min = 0f,
            max = maxPct,
            // 0.1 % precision (report §9.1).
            step = 0.1f,
            default = defaultPct,
            unit = "%",
            decimalPlaces = 1,
            onValueChange = onChange,
        )
    } else {
        FmDesktopSliderRow(
            label = label,
            value = current,
            min = 0f,
            max = maxPct,
            step = 0.1f,
            default = defaultPct,
            unit = "%",
            decimalPlaces = 1,
            onValueChange = onChange,
        )
    }
}

/**
 * The nine-cell picker. The selected cell is filled `primary`; every cell of the
 * selected cell's **zone** gets the soft wash, so the user sees the area the
 * watermark may move in (report §9.1 "范围看得见").
 */
@Composable
private fun WatermarkZoneGrid(
    anchor: Int,
    onAnchor: (Int) -> Unit,
    dense: Boolean,
) {
    val zone = WatermarkZones.zoneFor(anchor)
    val glyphs = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in 0 until 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val isSelected = index == anchor
                    // A cell is washed when it overlaps the selected cell's zone
                    // (the zone is half a photo wide, so it covers two columns).
                    val cellX = col / 3f to (col + 1) / 3f
                    val cellY = row / 3f to (row + 1) / 3f
                    val inZone = cellX.first < zone.xMax && cellX.second > zone.xMin &&
                        cellY.first < zone.yMax && cellY.second > zone.yMin
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (dense) 40.dp else 44.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(
                                when {
                                    isSelected -> FmPalette.primary
                                    inZone -> FmPalette.primarySoft
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (isSelected) 0.dp else 1.dp,
                                color = if (isSelected) FmPalette.primary else FmPalette.outline,
                                shape = RoundedCornerShape(FmRadii.chip),
                            )
                            .clickable(role = Role.Button) { onAnchor(index) }
                            .semantics {
                                contentDescription = "水印位置：${glyphs[index]}"
                                selected = index == anchor
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = glyphs[index],
                            style = FmType.labelMedium,
                            color = if (isSelected) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                        )
                    }
                }
            }
        }
    }
}

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
