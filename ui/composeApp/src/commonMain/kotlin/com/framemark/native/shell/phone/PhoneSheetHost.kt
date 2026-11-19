package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.resetInspectorPage
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.product.FmLog
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.phone.PhoneParameterPage
import com.framemark.native.shell.phone.PhoneSheetGrabber
import com.framemark.native.shell.phone.PhoneSheetHead
import com.framemark.native.shell.phone.PhoneToolBar
import com.framemark.native.shell.phone.phoneToolLabel
import com.framemark.native.shell.phone.phoneToolModified
import com.framemark.native.shell.phone.phoneToolPage
import com.framemark.native.shell.phone.phoneToolShowsDot
import com.framemark.native.shell.phone.phoneToolTab
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs

/**
 * The phone editor's parameter host: the draggable bottom sheet (grabber, title
 * row, the border landing panel [PhoneBorderPanel] or the four WP-C parameter
 * pages — including the watermark pane and the full-page fallback for a window too
 * short for a usable sheet) plus the five-tool bar that switches pages.
 *
 * The border **library** is not a page here: it is an overlay drawn by the editor
 * above the whole shell (`PhoneBorderLibraryOverlay`), so this host only forwards
 * the request ([onOpenBorderLibrary]). That keeps the rule "the border page is a
 * panel, the library is a layer" in one place: the shell owns the layer, the sheet
 * owns the panel, and neither moves the other.
 *
 * Every value stays owned by [PhoneEditorScreen]: this is a pure move of the panel
 * block that used to be inline there, with each state read passed down as a
 * parameter and each state write passed down as a callback.
 */
@Composable
internal fun ColumnScope.PhoneSheetHost(
    fullParameterPage: Boolean,
    sheetHeight: Dp,
    /**
     * 底板顶部两行的实际高度（把手 + 标题行）。边框页读 128 dp 卡片阶梯、参数页读
     * 固定画框的 chrome（16 + 28），两者都由 `phoneSheetChrome` 算好后传进来。
     */
    grabberHeight: Dp,
    headHeight: Dp,
    snapHeights: List<Dp>,
    sheetLevel: Int,
    onSheetLevelChange: (Int) -> Unit,
    sheetDragPx: Float?,
    onSheetDragPx: (Float?) -> Unit,
    density: Density,
    tool: EditorTool,
    tabModel: InspectorTabModel,
    card: LibraryCard?,
    libraryIndex: LibraryIndex?,
    onRetryLibrary: () -> Unit,
    onOpenPresets: () -> Unit,
    /**
     * Logo 页的 选择品牌：选择器是外壳画在整个编辑器之上的覆盖层（舞台仍在），
     * 底板只负责把请求递上去。null 时 Logo 页走内联品牌页（桌面右栏）。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    onToolChange: (EditorTool) -> Unit,
    /**
     * 边框工具的 选择一个边框 / 换一个边框：库覆盖层由外壳画在整个编辑器之上
     * （和品牌选择器同一层），底板只负责把请求递上去。
     */
    onOpenBorderLibrary: () -> Unit = {},
    /**
     * 「把当前设计用到全部 N 张」：边框页面板当前边框那一行右侧的入口（UX 审查
     * P1-3）。和导出表单里那一枚是**同一个动作**；null / 只有一张照片时不画。
     */
    onApplyToAll: (() -> Unit)? = null,
    /** 工程里的照片总数，只用来写那枚入口的「全部 N 张」。 */
    applyToAllCount: Int = 0,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetryInspector: () -> Unit,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onFocusedTextSlotChange: (String?) -> Unit,
    watermark: WatermarkSpec?,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
    onPickWatermark: () -> Unit,
    photoWidthPx: Int,
    photoHeightPx: Int,
    previewGeo: PreviewGeo?,
    tools: List<EditorTool>,
    toolBarH: Dp,
) {
                Box(
                    modifier = if (fullParameterPage) {
                        Modifier.fillMaxWidth().weight(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(sheetHeight)
                            // 底板浮在舞台上：先把整块矩形刷成舞台色（= 设置里的画布背景），
                            // **再** clip 面板。于是上圆角切出来的是舞台色，和面板正上方那条
                            // 舞台带接得上，圆角就是唯一的分界。
                            // 以前这里直接留空，缺口露的是页面底色 `FmPalette.canvas` —— 它在
                            // 每套配色里都只比 `surface` 深一档，于是圆角外沿读起来就是「圆角 +
                            // 直角」两块叠在一起的小耳朵（用户反馈两轮的就是它）。
                            // 注意顺序：background 必须在 clip 之前，否则它会被一起裁掉、缺口
                            // 又露回底色；而「把父容器刷成固定颜色」那种只在单一主题下成立的
                            // 做法不行，这里读的是舞台色本身，浅灰 / 中灰 / 深灰三种画布都成立。
                            .background(phoneStageColor())
                            .clip(RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius))
                            .background(FmPalette.surface)
                            .border(
                                1.dp,
                                FmPalette.outline,
                                RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius),
                            )
                    },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val toolPage = phoneToolPage(tool)
                        val pageModified = phoneToolModified(tool, tabModel)
                        if (!fullParameterPage) {
                            PhoneSheetGrabber(
                                height = grabberHeight,
                                onToggle = {
                                    // Grabber tap cycles through the three stops: 收起 →
                                    // 半开 → 展开 → 收起.
                                    onSheetDragPx(null)
                                    onSheetLevelChange((sheetLevel + 1) % snapHeights.size)
                                },
                                onDragStart = {
                                    onSheetDragPx(with(density) { sheetHeight.toPx() })
                                },
                                onDrag = { dy ->
                                    val lo = with(density) { snapHeights.first().toPx() }
                                    val hi = with(density) { snapHeights.last().toPx() }
                                    onSheetDragPx(((sheetDragPx ?: lo) - dy).coerceIn(lo, hi))
                                },
                                onDragEnd = {
                                    val current = sheetDragPx
                                    if (current != null) {
                                        val px = snapHeights.map { with(density) { it.toPx() } }
                                        onSheetLevelChange(px.indices.minByOrNull { abs(px[it] - current) } ?: 0)
                                        onSheetDragPx(null)
                                    }
                                },
                            )
                            PhoneSheetHead(
                                title = phoneToolLabel(tool),
                                // 边框页不再重复当前名称（配色方案 §4.1）：名称只在
                                // 面板的当前项一行出现；其它工具页保留卡片名作副题。
                                subtitle = if (tool == EditorTool.Border) {
                                    null
                                } else {
                                    card?.name.orEmpty()
                                },
                                modified = pageModified,
                                expanded = sheetLevel == snapHeights.lastIndex,
                                height = headHeight,
                                onToggleExpanded = {
                                    onSheetDragPx(null)
                                    onSheetLevelChange((sheetLevel + 1) % snapHeights.size)
                                },
                                onReset = if (toolPage == null) {
                                    null
                                } else if (toolPage == InspectorPage.Watermark) {
                                    {
                                        watermark?.let { spec ->
                                            onWatermarkChange(
                                                spec.copy(
                                                    anchor = WatermarkZones.DEFAULT_ANCHOR,
                                                    width = WatermarkZones.DEFAULT_WIDTH,
                                                    opacity = WatermarkZones.DEFAULT_OPACITY,
                                                    dx = 0f,
                                                    dy = 0f,
                                                ),
                                            )
                                            FmLog.info("FM_WM_RESET")
                                        }
                                    }
                                } else {
                                    {
                                        val changed = resetInspectorPage(tabModel, toolPage, session)
                                        FmLog.info("FM_PAGE_RESET") { "page=${toolPage.name} changed=$changed" }
                                    }
                                },
                                // 编辑中直接套用预设 (hand-off §3 decision 5): the phone keeps
                                // the entry on the border sheet's title.
                                onOpenPresets = if (tool == EditorTool.Border) onOpenPresets else null,
                                // Demo `.psh .adj`: 调整样式 › jumps from the border sheet to
                                // the 样式 page of the applied card.
                                onAdjustStyle = if (tool == EditorTool.Border && card != null) {
                                    { onToolChange(EditorTool.Params) }
                                } else {
                                    null
                                },
                            )
                        }
                        // 换页过渡（motion spec §3.1 #2）：整页左右推，不是交叉淡出。
                        // 两页同向平移一个整宽，所以中途是"左右相邻"而不是叠在一起；
                        // 出页与入页共用同一条 220 ms 的 standard 曲线，这才让两页始终
                        // 首尾相接。容器（底板高度、页头、工具条）一概不动。
                        PhoneSheetPagePager(
                            tool = tool,
                            tools = tools,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) { targetTool ->
                            // 没有边框时工具条只剩「边框」一格（`phoneToolsFor(hasBorder =
                            // false)`），所以这里画的一定是这块面板 —— 包括「刚删掉边框、
                            // 工具条正在收口」那一帧还在飞的过渡页（那时 targetTool 仍是
                            // 样式 / 文字 / 水印，而 card 已经没了）。以前那一帧会落到
                            // 「先选择边框 + 去选边框」：同一个动作的第二个入口，现在没有了。
                            if (card == null || targetTool == EditorTool.Border) {
                                // 边框页的落地态就是这块面板：当前边框那一行。库里点一张卡
                                // 就应用；唯一的入口在画布上（`StagePlaceholder`），面板里
                                // 没有第二枚按钮。
                                PhoneBorderPanel(
                                    index = libraryIndex,
                                    card = card,
                                    onRetry = onRetryLibrary,
                                    onOpenLibrary = onOpenBorderLibrary,
                                    // 当前边框那一行右侧的「用到全部 N 张」：与导出表单
                                    // 里那一枚同一个动作（UX 审查 P1-3）。
                                    onApplyToAll = onApplyToAll,
                                    applyToAllCount = applyToAllCount,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // 水印页只画参数行（图层 + 外观两组）；九宫格在画布上（StageArea 画的
                                // `PhoneWatermarkAnchorLayer`），这里一个字段都不存。
                                val photoAspect = previewGeo?.photoRect
                                    ?.takeIf { it.h > 0 }
                                    ?.let { it.w.toFloat() / it.h }
                                    ?: watermark?.photoAspect
                                    ?: 1.5f
                                PhoneParameterPage(
                                    tab = phoneToolTab(targetTool, tabModel),
                                    page = phoneToolPage(targetTool) ?: InspectorPage.Style,
                                    model = model,
                                    session = session,
                                    inspectorState = inspectorState,
                                    onRetry = onRetryInspector,
                                    textSlots = textSlots,
                                    textOutputWidth = textOutputWidth,
                                    textOutputHeight = textOutputHeight,
                                    onTextSlotFocus = { key ->
                                        FmLog.info("FM_TEXT_FOCUS") { "key=$key tool=$targetTool" }
                                        onFocusedTextSlotChange(key)
                                    },
                                    watermark = watermark,
                                    photoAspect = photoAspect,
                                    onWatermarkWidth = { width ->
                                        watermark?.let { onWatermarkChange(it.copy(width = width)) }
                                    },
                                    onWatermarkOpacity = { opacity ->
                                        watermark?.let { onWatermarkChange(it.copy(opacity = opacity)) }
                                    },
                                    onWatermarkPosition = { dx, dy ->
                                        watermark?.let { onWatermarkChange(it.copy(dx = dx, dy = dy)) }
                                    },
                                    // AU2 A-11：手机与桌面共用同一个入口。
                                    onWatermarkRotation = { degrees ->
                                        watermark?.let { onWatermarkChange(it.copy(rotation = degrees)) }
                                    },
                                    onPickWatermark = onPickWatermark,
                                    onClearWatermark = { onWatermarkChange(null) },
                                    onOpenBrandPicker = onOpenBrandPicker,
                                    modifier = Modifier.fillMaxSize(),
                                    card = card,
                                    photoWidthPx = photoWidthPx,
                                    photoHeightPx = photoHeightPx,
                                )
                            }
                        }
                    }
                }
                if (!fullParameterPage) {
                    PhoneToolBar(
                        height = toolBarH,
                        tool = tool,
                        availableTools = tools,
                        modifiedOf = { entry -> phoneToolShowsDot(entry, tabModel) },
                        onSelect = { onToolChange(it) },
                        // 验收人「保存预设和使用预设我没看到」：入口常驻在工具条尾部，
                        // 不再只藏在 边框 页头的长按菜单里。打开的是同一个预设面板。
                        onOpenPresets = onOpenPresets,
                    )
                }
}

/** 1-based index of the selected strip photo; 1 when the strip has no selection yet. */
internal fun photoIndexOf(tray: PhotoTray): Int {
    val index = tray.items.indexOfFirst { it.id == tray.selectedId }
    return if (index >= 0) index + 1 else 1
}

/** Full parameter page title: the page is the current tool rendered full height. */
internal fun panelTitle(tool: EditorTool): String = phoneToolLabel(tool)

// ---------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------

@Composable
internal fun ParameterPageHeader(
    height: Dp,
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline)
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "参数 · $title",
            style = FmType.titleSmall,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onClose)
                .padding(horizontal = FmSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = closeLabel,
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
            )
        }
    }
}

/**
 * Old mobile `.inspector-panel { border-radius: 22px 22px 0 0 }`. 22 dp is the
 * stage token, not the sheet role, so the sheet now uses [FmRadii.sheet] — the
 * same radius as every other sheet surface (`FmSheetSurface`, dialogs).
 */
internal val SheetRadius = FmRadii.sheet
