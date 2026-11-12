package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.framemark.native.shell.PHONE_PARAM_ROW_MIN_TOUCH_DP
import com.framemark.native.shell.watermarkAnchorAt
import com.framemark.native.theme.FmPalette
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 水印九宫格**在画布上**（2026-11-10；2026-11-11 改成「只有热点」）。
 *
 * 九个 ≥ 44 dp 的触控格铺在照片矩形上，每格中心只有一个 ~10 dp 的可见热点：
 * 当前格是实心点，其余是细环。**这一层不画任何图片内容**：没有缩略图、没有九宫格
 * 边框、没有铺满一格的底色 —— 照片本身一个像素都不被盖住（只有那九个小点）。
 *
 * 为什么只留热点：上一版把每一格画成一个带 1 dp 白边、铺满格子的圆角方块，照片被
 * 切成 3 × 3 的三块九拼，看起来像「水印被复制了九次」（截图见
 * `evidence/verify-rc14/`）。位置选择只需要「点哪里」，不需要把照片再画一遍。
 *
 * 点一格写回的就是旧面板九宫格写回的那个 `anchor`（0..8，左上 = 0，行优先），走同
 * 一条 `WatermarkSpec` 写入路径（[com.framemark.native.shell.watermarkSpecForAnchor]），
 * 映射与写入都没有改。
 *
 * 可见性由政策决定：[com.framemark.native.shell.showsWatermarkAnchorLayer] —— 只有
 * 水印工具、并且真的有一层水印时才画；其余工具上它既不画也不吃手势。
 */

/** 九宫格的触控格文案（无障碍读出来的「左上 / 上中 / …」，行优先）。 */
internal val WatermarkAnchorLabels = listOf(
    "左上", "上中", "右上",
    "左中", "居中", "右中",
    "左下", "下中", "右下",
)

/** 一格可见热点的直径（dp）：10 dp ≈ 手指下的一个小点，不是一块图像。 */
const val WATERMARK_ANCHOR_DOT_DP = 10f

/** 未选中格的细环线宽（dp）。环的外径仍然是 [WATERMARK_ANCHOR_DOT_DP]。 */
const val WATERMARK_ANCHOR_RING_DP = 1.5f

/**
 * 一格的可见形态：选中 = 实心点（填 `primary`），未选中 = 细环。
 *
 * 这是这一层唯一的「画什么」的决定，且只有颜色 / 直径 / 环宽 —— 没有图像字段，
 * 所以「九宫格渲染成 3 × 3 张水印图」在类型上就写不出来。
 */
data class AnchorHotspotVisual(
    val filled: Boolean,
    val diameterDp: Float,
    val ringWidthDp: Float,
) {
    /** 可见面积的上界（实心 = 圆面积；细环按外圆算）——测试用它断言「只是一个点」。 */
    val visibleAreaDp2: Float
        get() = PI.toFloat() * (diameterDp / 2f) * (diameterDp / 2f)
}

/** 选中 / 未选中的可见形态。两态同径，只有实心与细环的区别。 */
fun anchorHotspotVisual(active: Boolean): AnchorHotspotVisual = AnchorHotspotVisual(
    filled = active,
    diameterDp = WATERMARK_ANCHOR_DOT_DP,
    ringWidthDp = if (active) 0f else WATERMARK_ANCHOR_RING_DP,
)

/** 九宫格在舞台像素坐标里的框（左上角 + 尺寸）。 */
internal data class AnchorLayerBox(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
) {
    val cellWidthPx: Float get() = widthPx / 3f
    val cellHeightPx: Float get() = heightPx / 3f
}

/**
 * 水印矩形的舞台像素框：照片在舞台上按 contain 铺（`stage`），引擎再在照片里划出
 * `photoRect`。**没有 `photoRect` 时返回 null** —— 绝不退回整块画布，否则格子会盖到
 * 边框 / 页脚上，点下去就等于在照片外面选位置。
 *
 * 格子边长不足 [minCellPx]（很小的舞台）时，整层按 3 × 44 dp 居中放在照片上：
 * 触控面不缩，九宫格仍然只覆盖照片。
 */
internal fun watermarkAnchorLayerBox(
    geo: PreviewGeo,
    stage: IntSize,
    minCellPx: Float,
): AnchorLayerBox? {
    if (geo.w <= 0 || geo.h <= 0 || stage.width <= 0 || stage.height <= 0) return null
    val photo = geo.photoRect ?: return null
    if (photo.w <= 0 || photo.h <= 0) return null
    val scale = minOf(stage.width.toFloat() / geo.w, stage.height.toFloat() / geo.h)
    if (!scale.isFinite() || scale <= 0f) return null
    val offsetX = (stage.width - geo.w * scale) / 2f
    val offsetY = (stage.height - geo.h * scale) / 2f
    val photoLeft = offsetX + photo.x * scale
    val photoTop = offsetY + photo.y * scale
    val photoWidth = photo.w * scale
    val photoHeight = photo.h * scale
    if (photoWidth <= 1f || photoHeight <= 1f) return null
    val minCell = max(minCellPx, 1f)
    val cellW = max(photoWidth / 3f, minCell)
    val cellH = max(photoHeight / 3f, minCell)
    val width = cellW * 3f
    val height = cellH * 3f
    // 居中在照片上。照片比 3 × 44 dp 还小（很宽的横照片）时格子保住 44 dp，整层会
    // 上下 / 左右探出照片一点：触控面不缩，但层的原点被夹回舞台里，不会画到舞台外。
    val maxLeft = (stage.width - width).coerceAtLeast(0f)
    val maxTop = (stage.height - height).coerceAtLeast(0f)
    val left = (photoLeft + (photoWidth - width) / 2f).coerceIn(0f, maxLeft)
    val top = (photoTop + (photoHeight - height) / 2f).coerceIn(0f, maxTop)
    return AnchorLayerBox(leftPx = left, topPx = top, widthPx = width, heightPx = height)
}

/**
 * 画在照片上的九宫格**热点层**。每一格都是独立触控目标（≥ 44 dp），格子里只有一个
 * ~10 dp 的圆点（当前格实心，其余细环）；照片本身不被覆盖。
 *
 * 参数表里没有图片：这一层拿到的只有几何（[geo] / [stage]）、密度和当前的 anchor。
 * 点一格调 [onPick]，参数是政策层写进 `WatermarkSpec.anchor` 的那个 index。
 */
@Composable
internal fun BoxScope.PhoneWatermarkAnchorLayer(
    geo: PreviewGeo,
    stage: IntSize,
    density: Density,
    anchor: Int,
    onPick: (Int) -> Unit,
) {
    val minCellPx = with(density) { PHONE_PARAM_ROW_MIN_TOUCH_DP.dp.toPx() }
    val box = watermarkAnchorLayerBox(geo, stage, minCellPx) ?: return
    val current = anchor.coerceIn(0, 8)
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(box.leftPx.roundToInt(), box.topPx.roundToInt()) }
            .size(with(density) { box.widthPx.toDp() }, with(density) { box.heightPx.toDp() }),
    ) {
        for (row in 0 until 3) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    AnchorHotspot(
                        index = index,
                        active = index == current,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

/**
 * 一格：整个 44 dp 格子都是触控面，可见的只有正中那个 ~10 dp 的圆点。
 *
 * 这里**不画**格子边框 / 格子底色 / 缩略图：那些都会把照片切成一格一格的
 * 「九张图」（缺陷 1）。点与环都用 `CircleShape` 画，尺寸来自 [anchorHotspotVisual]。
 */
@Composable
private fun AnchorHotspot(
    index: Int,
    active: Boolean,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    val visual = anchorHotspotVisual(active)
    Box(
        modifier = modifier
            .clickable(role = Role.Button) { onPick(index) }
            .semantics {
                contentDescription = "水印位置：${WatermarkAnchorLabels[index]}"
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visual.diameterDp.dp)
                .then(
                    if (visual.filled) {
                        Modifier.background(FmPalette.primary, CircleShape)
                    } else {
                        Modifier
                            .background(Color.Black.copy(alpha = 0.18f), CircleShape)
                            .border(visual.ringWidthDp.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    },
                ),
        )
    }
}

/** 点在照片里的相对位置 → 九宫格 index；一次点击只走这一条路径
 * （`watermarkAnchorAt`，纯函数，测试直接覆盖）。点照片外不写。
 */
internal fun anchorIndexForTap(
    box: AnchorLayerBox,
    tapX: Float,
    tapY: Float,
): Int? {
    if (box.widthPx <= 0f || box.heightPx <= 0f) return null
    val fx = (tapX - box.leftPx) / box.widthPx
    val fy = (tapY - box.topPx) / box.heightPx
    return watermarkAnchorAt(fx, fy)
}
