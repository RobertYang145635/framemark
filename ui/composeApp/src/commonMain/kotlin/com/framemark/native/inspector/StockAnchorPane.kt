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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.framemark.native.product.StockWatermarkControls
import com.framemark.native.product.stockWatermarkPlan
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession

/**
 * 厂商规格卡可移动的水印位置：九宫格 +「按规格」。
 *
 * 用户口径：「这种是需要放开的，有的边框需要定死，有的不需要」。一张 IR 卡声明了
 * `stockAnchor` / `stockOffsetXPct` / `stockOffsetYPct` 就不再整页只读，但九宫格
 * **不能**退化成十项下拉框（`SelectControl` 的 `DropdownMenu`）—— 这里复用
 * `WatermarkPane` 九宫格的同一套格子语言（[AnchorCellsGrid]：3 dp 槽、chip 圆角、
 * 选中格填 `primary`），所以同一块位置在水印页和样式页长得一样，用户只需要学一次。
 *
 * 值仍然走 [DesignSession] 的普通控件通道（`session.set(control, …)` → 项目
 * `design.params["stockAnchor"]`），没有第二条状态通道。
 */

/** The nine `stockAnchor` cell values in **grid** order (left→right, top→bottom). */
internal val StockAnchorCells: List<String> = listOf(
    "leftTop", "centerTop", "rightTop",
    "leftCenter", "center", "rightCenter",
    "leftBottom", "centerBottom", "rightBottom",
)

/** The same nine cells in user copy (the brief's 左上 / 上中 / … / 右下). */
internal val StockAnchorCellLabels: List<String> = listOf(
    "左上", "上中", "右上",
    "左中", "中", "右中",
    "左下", "下中", "右下",
)

/**
 * Grid index of a `stockAnchor` value, or null when it is `vendor` (「按规格」)
 * or an unknown token — in both cases no cell is picked.
 *
 * The declared domain order is column-major (`leftTop, leftCenter, leftBottom, …`),
 * so this is an explicit lookup, never `options.indexOf`.
 */
internal fun stockAnchorIndex(value: String?): Int? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val index = StockAnchorCells.indexOf(raw)
    return index.takeIf { it >= 0 }
}

internal fun stockAnchorValueAt(index: Int): String = StockAnchorCells[index.coerceIn(0, 8)]

/**
 * The 3×3 anchor plate shared by the 水印 page ([WatermarkZoneGrid]) and a vendor
 * card's 水印位置 control ([StockAnchorControl]).
 *
 * Appearance is the 水印 page's own: a `surfaceMuted` plate with 3 dp gutters,
 * chip-radius cells (40 dp on the phone, 44 dp on the desktop rail), the picked
 * cell filled `primary`, and — only for the watermark layer — the cells the
 * movable zone covers washed `primarySoft` so the user sees the range.
 *
 * [selectedIndex] null means "no cell picked": for the watermark layer that never
 * happens, for a vendor card it is `vendor` (「按规格」).
 */
@Composable
internal fun AnchorCellsGrid(
    selectedIndex: Int?,
    dense: Boolean,
    onPick: (Int) -> Unit,
    glyphOf: (Int) -> String,
    descriptionOf: (Int) -> String,
    modifier: Modifier = Modifier,
    /** Zone wash: index → true when this cell is inside the movable range. */
    inZone: ((Int) -> Boolean)? = null,
) {
    Column(
        modifier = modifier
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
                    val isSelected = index == selectedIndex
                    val washed = !isSelected && inZone?.invoke(index) == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (dense) 40.dp else 44.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(
                                when {
                                    isSelected -> FmPalette.primary
                                    washed -> FmPalette.primarySoft
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (isSelected) 0.dp else 1.dp,
                                color = if (isSelected) FmPalette.primary else FmPalette.outline,
                                shape = RoundedCornerShape(FmRadii.chip),
                            )
                            .clickable(role = Role.Button) { onPick(index) }
                            .semantics {
                                contentDescription = descriptionOf(index)
                                selected = isSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = glyphOf(index),
                            style = FmType.labelMedium,
                            color = if (isSelected) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One vendor card's 水印位置: label + current choice, the 3×3 grid, then a wide
 * 「按规格」 chip that writes [StockWatermarkControls.VENDOR_ANCHOR].
 *
 * [dense] follows the host chassis (phone touch scale vs desktop rail), the way
 * `ControlItem` does; the grid itself keeps the watermark page's cells.
 */
/**
 * The two blocks a vendor 水印位置 control splits into on the phone sheet.
 *
 * The whole control (label + 3×3 grid + 按规格 chip + 说明) is 218 dp at the old
 * sizing — taller than the 150 dp the tightest carried window leaves a page
 * ([com.framemark.native.shell.PHONE_PAGE_GROUP_BUDGET_DP]). Splitting it keeps every
 * touch target: the grid page is the grid alone (132 dp), the second page carries the
 * label, the chip and one guidance line (**66 dp**: the label / chip share one
 * 44 dp row, the sentence 22). Nothing is shrunk, and the phone sheet never needs a
 * vertical scroll — on the roomier windows the 210 dp pair fits **one** page again.
 */
enum class StockAnchorPart {
    /** The 3×3 cells only (40 dp each, unchanged). */
    Grid,

    /** Label + 当前值 + 按规格 chip on one 44 dp row, then one line of guidance. */
    Footer,
}

/**
 * One block of the vendor 水印位置 control (see [StockAnchorPart]).
 *
 * The same private pieces back [StockAnchorControl], so the phone's split page and
 * the desktop / legacy single block cannot drift apart.
 */
@Composable
internal fun StockAnchorBlock(
    control: InspectorControl,
    session: DesignSession,
    dense: Boolean,
    part: StockAnchorPart,
    showReset: Boolean = false,
) {
    val raw = session.value(control).toString()
    val index = stockAnchorIndex(raw)
    when (part) {
        StockAnchorPart.Grid -> AnchorCellsGrid(
            selectedIndex = index,
            dense = dense,
            onPick = { picked -> session.set(control, stockAnchorValueAt(picked)) },
            glyphOf = { StockAnchorCellLabels[it] },
            descriptionOf = { "水印位置：${StockAnchorCellLabels[it]}" },
        )
        StockAnchorPart.Footer -> Column(modifier = Modifier.fillMaxWidth()) {
            if (dense) {
                // 2026-11 密度过：标签 / 当前值 / 按规格 挤进一行 44 dp，说明只留一句。
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = FmDenseRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = control.label.ifBlank { "水印位置" },
                        style = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = index?.let { StockAnchorCellLabels[it] } ?: "按规格",
                        style = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    AnchorVendorChip(index = index, dense = true, inline = true) {
                        session.set(control, StockWatermarkControls.VENDOR_ANCHOR)
                    }
                }
                Text(
                    text = "点任意一格移动水印；下面的微调仍然生效。",
                    style = LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                AnchorHeaderRow(control = control, index = index, dense = dense)
                Spacer(Modifier.height(6.dp))
                AnchorVendorChip(index = index, dense = dense) {
                    session.set(control, StockWatermarkControls.VENDOR_ANCHOR)
                }
                AnchorNote(dense = dense)
            }
            if (showReset && session.isModified(control)) {
                AnchorReset(control = control, session = session, dense = dense, paddingTop = 6.dp)
            }
        }
    }
}

/** Label on the left, the current cell (or 按规格) on the right. */
@Composable
private fun AnchorHeaderRow(control: InspectorControl, index: Int?, dense: Boolean) {
    val bodyStyle = if (dense) {
        LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp)
    } else {
        FmType.bodyMedium
    }
    val valueStyle = if (dense) {
        LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 18.sp)
    } else {
        FmType.labelSmall
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = control.label.ifBlank { "水印位置" },
            style = bodyStyle,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = index?.let { StockAnchorCellLabels[it] } ?: "按规格",
            style = valueStyle,
            color = FmPalette.onSurfaceSecondary,
        )
    }
}

/**
 * The wide 「按规格」 chip that writes [StockWatermarkControls.VENDOR_ANCHOR].
 *
 * [inline] 是手机密度档的一行形态（2026-11）：宽度包住文字、高度 44 dp，和标签一起
 * 排在同一行里；桌面与整块形态仍然是通栏那条。
 */
@Composable
private fun AnchorVendorChip(
    index: Int?,
    dense: Boolean,
    inline: Boolean = false,
    onPickVendor: () -> Unit,
) {
    val valueStyle = if (dense) {
        LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 18.sp)
    } else {
        FmType.labelSmall
    }
    Box(
        modifier = Modifier
            .then(
                if (inline) {
                    Modifier.height(FmDenseRowHeight)
                } else {
                    Modifier.fillMaxWidth().heightIn(min = if (dense) 40.dp else 32.dp)
                },
            )
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(if (index == null) FmPalette.primary else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (index == null) FmPalette.primary else FmPalette.outline,
                shape = RoundedCornerShape(FmRadii.chip),
            )
            .clickable(role = Role.RadioButton, onClick = onPickVendor)
            .semantics {
                contentDescription = "水印位置：按规格"
                selected = index == null
            }
            .padding(horizontal = if (inline) 14.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "按规格",
            style = valueStyle,
            color = if (index == null) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
        )
    }
}

/** The 说明 under the chip (phone 12.5 sp, desktop `CuratedNote`). */
@Composable
private fun AnchorNote(dense: Boolean) {
    Text(
        text = "按厂商规格的位置；点任意一格让水印整体移到那一格，下面的微调仍然生效。",
        style = if (dense) {
            LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
        } else {
            CuratedNote
        },
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** 「恢复默认」 link under the control (dense hosts that do not draw their own). */
@Composable
private fun AnchorReset(
    control: InspectorControl,
    session: DesignSession,
    dense: Boolean,
    paddingTop: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .padding(top = paddingTop)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button) { session.resetControl(control) }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "恢复默认",
            style = if (dense) {
                LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp)
            } else {
                CuratedNote
            },
            color = FmPalette.primaryStrong,
        )
    }
}

/**
 * One vendor card's 水印位置 in one block: label + current choice, the 3×3 grid, then
 * a wide 「按规格」 chip that writes [StockWatermarkControls.VENDOR_ANCHOR].
 *
 * The phone sheet does not call this: its page budget is 150 dp and this block is
 * 254 dp, so it uses the two [StockAnchorPart]s on two pages instead (same pieces,
 * same writes).
 *
 * [dense] follows the host chassis (phone touch scale vs desktop rail), the way
 * `ControlItem` does; the grid itself keeps the watermark page's cells.
 */
@Composable
internal fun StockAnchorControl(
    control: InspectorControl,
    session: DesignSession,
    dense: Boolean,
    modifier: Modifier = Modifier,
    /** 恢复默认 link; hosts that already draw one (`ControlItem`) pass false. */
    showReset: Boolean = true,
) {
    val raw = session.value(control).toString()
    val index = stockAnchorIndex(raw)
    Column(modifier = modifier.fillMaxWidth()) {
        AnchorHeaderRow(control = control, index = index, dense = dense)
        Spacer(Modifier.height(if (dense) 8.dp else 6.dp))
        AnchorCellsGrid(
            selectedIndex = index,
            dense = dense,
            onPick = { picked -> session.set(control, stockAnchorValueAt(picked)) },
            glyphOf = { StockAnchorCellLabels[it] },
            descriptionOf = { "水印位置：${StockAnchorCellLabels[it]}" },
        )
        Spacer(Modifier.height(6.dp))
        AnchorVendorChip(index = index, dense = dense) {
            session.set(control, StockWatermarkControls.VENDOR_ANCHOR)
        }
        AnchorNote(dense = dense)
        if (showReset && session.isModified(control)) {
            AnchorReset(control = control, session = session, dense = dense, paddingTop = 6.dp)
        }
    }
}

/**
 * Declared rows of one page with only the first 水印位置 control kept.
 *
 * `params.stockAnchor` is one design field, so a card that repeats the
 * `anchorGrid` row must still show one grid — the render loops iterate the raw
 * declared list, so the duplicate is dropped here instead of being drawn twice.
 */
internal fun withoutDuplicateStockAnchors(controls: List<InspectorControl>): List<InspectorControl> {
    var kept = false
    val out = ArrayList<InspectorControl>(controls.size)
    controls.forEach { control ->
        if (!StockWatermarkControls.isAnchorParam(control.param)) {
            out += control
        } else if (!kept) {
            kept = true
            out += control
        }
    }
    return out
}

/** What a 样式 page body draws (the read-only gate, one name for both hosts). */
internal enum class StylePageBody { Controls, SpecOnly }

/** True when [controls] declare any movable vendor-watermark control. */
internal fun movableStockWatermark(controls: List<InspectorControl>): Boolean =
    stockWatermarkPlan(controls) { it.param }.adjustable

/**
 * Phone 样式页 gate. The read-only 边框规格 block is drawn only when the card
 * declares nothing movable; hand (audited) cards keep WP-C's rule **verbatim**
 * (底色 / 版式 or any slider means there is something to adjust), so their
 * behaviour is unchanged.
 */
internal fun phoneStylePageBody(isIr: Boolean, controls: List<InspectorControl>): StylePageBody {
    if (movableStockWatermark(controls)) return StylePageBody.Controls
    val nothingTunable = themeControlOf(controls) == null &&
        footerModeControlOf(controls) == null &&
        controls.none { it.type == ControlType.Slider }
    return if (isIr || nothingTunable) StylePageBody.SpecOnly else StylePageBody.Controls
}

/** Desktop 样式页: the showcase's curated panel only for a fully adjustable border. */
internal fun desktopStylePageIsCurated(controls: List<InspectorControl>): Boolean =
    themeControlOf(controls) != null &&
        footerModeControlOf(controls) != null &&
        footerPctControlOf(controls) != null

/**
 * 边框规格 的一句话说明。A card whose watermark can move must not claim its
 * position is fixed — that was the wrong half of the old copy.
 */
internal fun vendorSpecNote(watermarkMovable: Boolean): String = if (watermarkMovable) {
    "厂商规格边框的尺寸、留白按厂商规格固定；水印位置可以改，也可以改文字内容和加图片水印。"
} else {
    "厂商规格边框的尺寸、留白和文字位置都按规格固定，可以改文字内容和加图片水印。"
}
