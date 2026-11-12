package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.CuratedPhotoSize
import com.framemark.native.inspector.CuratedTextSlots
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.LocalCuratedPhotoSize
import com.framemark.native.inspector.LocalCuratedTextSlots
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.inspector.StockAnchorBlock
import com.framemark.native.inspector.StockAnchorPart
import com.framemark.native.inspector.TextPageMode
import com.framemark.native.inspector.TextSlotEmptyPane
import com.framemark.native.inspector.WatermarkPanePart
import com.framemark.native.inspector.fontScaleControlOf
import com.framemark.native.inspector.footerModeControlOf
import com.framemark.native.inspector.footerPctControlOf
import com.framemark.native.inspector.footerPctDisabledReason
import com.framemark.native.inspector.footerPctFloor
import com.framemark.native.inspector.movableStockWatermark
import com.framemark.native.inspector.phoneStylePageBody
import com.framemark.native.inspector.rememberTextSlotSelectionState
import com.framemark.native.inspector.textPageMode
import com.framemark.native.inspector.textScaleHint
import com.framemark.native.inspector.textSlotNavEntries
import com.framemark.native.inspector.themeControlOf
import com.framemark.native.inspector.vendorSpecNote
import com.framemark.native.inspector.withoutDuplicateStockAnchors
import com.framemark.native.inspector.OutputSize
import com.framemark.native.inspector.curatedOutputPixels
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.StockWatermarkControls
import com.framemark.native.shell.PHONE_PAGE_BLOCK_GAP_DP
import com.framemark.native.shell.PhonePageKeys
import com.framemark.native.shell.showsPhoneParameterSummary
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.isUsableStaticTextControl

/**
 * The phone's parameter page for one WP-C tab ([InspectorTabInfo]).
 *
 * The showcase's phone sheet is not a control list but four hand-built panes
 * (`core.js` `paneClassicStyle` / `paneClassicText` / `lgPaneLive` / `paneWm`), so
 * this page renders the same block structure from the same data:
 *
 *  - 样式: 底色 swatches, 版式 segmented + its one-line note, 底部留白 slider and the
 *    导出尺寸 line for the adjustable card; a read-only 边框规格 block for a
 *    vendor-spec card (`paneIrStyle`); otherwise the declared controls in `.touchui`
 *    chrome (switch rows, touch sliders, segmented selects, swatches);
 *  - 文字: the renderer's slot rows ([NativeTextSlotControls] — input + 已改 /
 *    恢复原文 + 隐藏开关) plus 文字大小 when the card declares a scale;
 *  - Logo: [LogoBrandPanel] on both platforms (the same brand/version data the
 *    desktop right rail uses — no second copy here);
 *  - 水印: the watermark pane, section by section.
 *
 * **不滚（owner 2026-11）**: 这一页里**没有竖向 scroll**。`PhonePageLayout.pageGroups`
 * 把本页的控件分成若干「一组一页」，每组的高都算在 `PHONE_PAGE_GROUP_BUDGET_DP` 以内，
 * 所以四页的每个控件都在默认底板的可见范围里；组多了就横滑（把手行右端的小圆点
 * 标出第几组 / 共几组），参数区永远不出现滚动条。
 *
 *  - **公共区** ([PhoneDesignSummary]) — pinned above the blocks, identical on all four
 *    pages, never scrolls and never moves when the tab changes: the export size of
 *    the selected design. No declared control can join it: `InspectorTabs.kt`'s
 *    `inspectorPageOf` partitions a card's controls, so every control belongs to
 *    exactly one page and hoisting one would mean duplicating a page's binding.
 *  - **工具区** — the pager: one block per page. The page header (title / 已改 N /
 *    恢复默认) is the host's `PhoneSheetHead`, pinned above both zones in the sheet;
 *    in the constrained full-page branch the 公共区 is the pinned context.
 *  - [footer] — the page's primary actions, pinned *under* the pager.
 */
@Composable
fun PhoneParameterPage(
    tab: InspectorTabInfo?,
    page: InspectorPage,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetry: () -> Unit,
    textSlots: List<TextSlot> = emptyList(),
    textOutputWidth: Int = 0,
    textOutputHeight: Int = 0,
    onTextSlotFocus: ((String?) -> Unit)? = null,
    /**
     * 水印页按组渲染：每一组只画它那一段（`WatermarkPanePart`）。null = 这一页不是水印页。
     * 外壳持有 `WatermarkSpec` 的写入回调，所以面板本身仍然「不拥有存储」。
     */
    watermarkContent: (@Composable (WatermarkPanePart) -> Unit)? = null,
    /**
     * 手机端 选择品牌 的打开请求：选择器是外壳画在整个编辑器之上的覆盖层面板，
     * 不是这一页里的一层，所以这里只把请求往上递。null = 桌面右栏的内联品牌页。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    /** Current card, for the 边框规格 block's 来源 row. */
    card: LibraryCard? = null,
    /**
     * 舞台当前照片的像素尺寸。参数页公共区的「导出尺寸」由它 + 版式参数算出
     * （与桌面 rail 同一个 [curatedOutputPixels]），不再用导出设置里的长边顶替。
     */
    photoWidthPx: Int = 0,
    photoHeightPx: Int = 0,
    /**
     * 本页的分组（`pageGroups` 的结果）：每一组一页，页内不再竖向滚动。
     * 空列表 = 单页（没有可调内容时也至少有一个空态组，见 `pageBlocks`）。
     */
    groups: List<List<String>> = emptyList(),
    /** 当前组下标（外壳持有：把手行的小圆点也读它）。 */
    groupIndex: Int = 0,
    onGroupIndexChange: (Int) -> Unit = {},
) {
    if (tab == null) {
        PanelNote(
            text = when (inspectorState) {
                InspectorLoadState.Idle, InspectorLoadState.Loading -> "正在载入可调参数…"
                is InspectorLoadState.Failed -> "暂时无法加载可调参数。"
                is InspectorLoadState.Ready -> "这套边框没有这一页的参数。"
            },
            modifier = modifier,
            onRetry = if (inspectorState is InspectorLoadState.Failed) onRetry else null,
        )
        return
    }
    // 文字页三态（WP-TX1）：有渲染器报告的文本框 → 导航 + 单卡；报告到了但一个
    // 都没有 → 空态；压根没有报告（不是实时渲染的卡）→ 保留声明出来的文字行。
    val textEntries = if (page == InspectorPage.Text) {
        remember(textSlots, textOutputWidth, textOutputHeight) {
            textSlotNavEntries(textSlots, textOutputWidth, textOutputHeight)
        }
    } else {
        emptyList()
    }
    // 选择状态按卡片 id：切边框回到第一个，切页签回来还是同一个（brief §2）。
    val textSelection = rememberTextSlotSelectionState(model?.cardId)
    val textMode = if (page == InspectorPage.Text && model != null) {
        textPageMode(
            hasReport = textOutputWidth > 0,
            entries = textEntries,
            staticTextControls = phonePageControls(tab).filter {
                it.type == ControlType.Text || it.type == ControlType.Switch
            },
        )
    } else {
        null
    }
    // 公共区的「导出尺寸」读整张卡的布局参数（它们都落在样式页）。`OutputSize` 只认
    // borderPct / footerMode / footerPct / footerAuto / footerTextScale / frame，而
    // 这些参数只有样式页能拥有（`inspectorPageOf` 是一张分区表），所以传「整卡可见行」
    // 与传「样式页那一组」算出的数字相同 —— 四页因此读到同一个尺寸。
    val designControls = remember(model) { phoneVisibleControls(model?.controls.orEmpty()) }
    // 组是政策算好的；空表（还没算出来 / 无内容）当作一个空组，页面永远有东西可画。
    val pages = remember(groups) { groups.ifEmpty { listOf(emptyList()) } }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
    // 换页签 / 换卡片回到第一组：每一页自己的第一个块永远在同一个位置。
    LaunchedEffect(page, model?.cardId) { pagerState.scrollToPage(0) }
    // 页面 → 外壳：把手行的小圆点要跟着滑。
    LaunchedEffect(pagerState.currentPage) { onGroupIndexChange(pagerState.currentPage) }
    // 外壳 → 页面：小圆点被点（或组表变化）时把 pager 挪过去。
    LaunchedEffect(groupIndex, pages.size) {
        val target = groupIndex.coerceIn(0, pages.lastIndex)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    CompositionLocalProvider(
        LocalCuratedPhotoSize provides if (photoWidthPx > 0 && photoHeightPx > 0) {
            CuratedPhotoSize(photoWidthPx, photoHeightPx)
        } else {
            null
        },
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // 先取出可用高度：`maxHeight` 是 BoxWithConstraintsScope 的成员，进到
            // Column 的作用域后就不能隐式读它了（布局 scope 的 DSL marker）。
            val pageHeightDp = maxHeight.value
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 公共区：四页同一块，永不滚走、也不随页签挪动 ----
                if (model != null && showsPhoneParameterSummary(pageHeightDp)) {
                    PhoneDesignSummary(controls = designControls, session = session)
                    PhoneZoneRule()
                }
                // ---- 工具区：一组一页的横滑页。没有竖向滚动容器 ----
                if (model == null) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        PhonePageBlockFrame {
                            PhoneEmptyBlock(page)
                        }
                    }
                } else if (pages.size <= 1) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        PhonePageBlockFrame {
                            PhonePageGroup(
                                keys = pages.first(),
                                page = page,
                                tab = tab,
                                model = model,
                                session = session,
                                card = card,
                                textSlots = textSlots,
                                textOutputWidth = textOutputWidth,
                                textOutputHeight = textOutputHeight,
                                textEntries = textEntries,
                                textSelection = textSelection,
                                textMode = textMode,
                                onTextSlotFocus = onTextSlotFocus,
                                watermarkContent = watermarkContent,
                                onOpenBrandPicker = onOpenBrandPicker,
                            )
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        pageSpacing = 0.dp,
                    ) { index ->
                        PhonePageBlockFrame {
                            PhonePageGroup(
                                keys = pages[index],
                                page = page,
                                tab = tab,
                                model = model,
                                session = session,
                                card = card,
                                textSlots = textSlots,
                                textOutputWidth = textOutputWidth,
                                textOutputHeight = textOutputHeight,
                                textEntries = textEntries,
                                textSelection = textSelection,
                                textMode = textMode,
                                onTextSlotFocus = onTextSlotFocus,
                                watermarkContent = watermarkContent,
                                onOpenBrandPicker = onOpenBrandPicker,
                            )
                        }
                    }
                }
                // ---- 主操作固定在横滑页之外：页签怎么切都点得到 ----
                footer?.invoke()
            }
        }
    }
}

/**
 * 一组的框：**只有** 18 dp 左右内边距 + 4 / 4 上下内边距，没有组头、没有卡片底。
 *
 * 分页后组头（`PhoneGroup` 的 16 + 22 + 8 + 14 = 60 dp）就是「一组 150 dp 只放得下
 * 一个开关」的那 60 dp，所以组名让位给内容本身：每个控件都自带标签（滑杆标题、分段
 * 选项、色板名字），组与组的边界由把手行的小圆点和横滑反馈表达。
 */
@Composable
private fun PhonePageBlockFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        content = content,
    )
}

/** 一组的内容：按页签派给四个渲染器。 */
@Composable
private fun ColumnScope.PhonePageGroup(
    keys: List<String>,
    page: InspectorPage,
    tab: InspectorTabInfo,
    model: InspectorCardModel,
    session: DesignSession,
    card: LibraryCard?,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    textEntries: List<com.framemark.native.inspector.TextSlotNavEntry>,
    textSelection: com.framemark.native.inspector.TextSlotSelectionState,
    textMode: TextPageMode?,
    onTextSlotFocus: ((String?) -> Unit)?,
    watermarkContent: (@Composable (WatermarkPanePart) -> Unit)?,
    onOpenBrandPicker: (() -> Unit)?,
) {
    when (page) {
        InspectorPage.Style -> PhoneStyleGroup(keys = keys, model = model, session = session, card = card, tab = tab)
        InspectorPage.Text -> PhoneTextGroup(
            keys = keys,
            model = model,
            tab = tab,
            session = session,
            textSlots = textSlots,
            textOutputWidth = textOutputWidth,
            textOutputHeight = textOutputHeight,
            textEntries = textEntries,
            textSelection = textSelection,
            textMode = textMode,
            onTextSlotFocus = onTextSlotFocus,
        )
        InspectorPage.Logo -> PhoneLogoGroup(
            keys = keys,
            model = model,
            session = session,
            textSlots = textSlots,
            textOutputWidth = textOutputWidth,
            textOutputHeight = textOutputHeight,
            onOpenBrandPicker = onOpenBrandPicker,
        )
        InspectorPage.Watermark -> PhoneWatermarkGroup(keys = keys, watermarkContent = watermarkContent)
    }
}

/**
 * 公共区: the export size of the selected design — pinned, identical on all four
 * pages, and the one row that used to be the *bottom* of the longest page
 * (样式 › 版式 › `.outsz`), i.e. the row you had to scroll to.
 *
 * Same number as before: photo pixels + this card's layout params through
 * [curatedOutputPixels], then the export long-edge tier ([OutputSize.applyLongEdge],
 * the same call the desktop rail and the export dialog make). `null` (the photo's
 * pixel size is not wired in yet) draws nothing rather than a guessed value, so the
 * band has zero height and cannot push the tool zone around.
 */
@Composable
private fun PhoneDesignSummary(controls: List<InspectorControl>, session: DesignSession) {
    val pixels = OutputSize.applyLongEdge(
        pixels = curatedOutputPixels(controls, session),
        mode = session.export.sizeMode,
        longEdge = session.export.longEdge,
    ) ?: return
    PhoneKeyValueRow(
        label = "导出尺寸",
        value = "$pixels px",
        modifier = Modifier.padding(horizontal = PageInset, vertical = FmSpacing.sm),
        topPadding = 0.dp,
    )
}

/** The `.touchui` page inset (`PhoneGroup`'s `16px 18px 14px`), reused by pinned bands. */
private val PageInset = 18.dp

/** Hairline between the pinned 公共区 and the tool zone. */
@Composable
private fun PhoneZoneRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FmPalette.outline),
    )
}

/** 间距：同一页里两个块之间（与政策里的 `PHONE_PAGE_BLOCK_GAP_DP` 同一个数）。 */
private val BlockGap = PHONE_PAGE_BLOCK_GAP_DP.dp

// ---------------------------------------------------------------------------
// 样式
// ---------------------------------------------------------------------------

/**
 * One 样式 group (`paneClassicStyle` structure, split into pageable blocks).
 *
 * 区块顺序与政策一致：只读 边框规格（厂商卡）→ 底色 → 版式（含底部留白）→ 其余声明
 * 控件（同一 section 的可以同页，见 `pageBlocks`）。每个块的内容和改前的 pane 完全
 * 一样，只是不再套 60 dp 的组头。
 */
@Composable
private fun ColumnScope.PhoneStyleGroup(
    keys: List<String>,
    model: InspectorCardModel,
    session: DesignSession,
    card: LibraryCard?,
    tab: InspectorTabInfo,
) {
    val controls = phonePageControls(tab)
    val theme = themeControlOf(controls)
    val mode = footerModeControlOf(controls)
    val footerPct = footerPctControlOf(controls)
    // 只有「什么都没声明」的厂商规格卡才是只读的；同一个判据在桌面样式页也读
    // （`phoneStylePageBody` / `stockWatermarkPlan`，定义在 inspector 包）。
    val specOnly = phoneStylePageBody(isIr = model.isIr, controls = controls) ==
        com.framemark.native.inspector.StylePageBody.SpecOnly
    val movable = movableStockWatermark(controls)
    val anchor = controls.firstOrNull { StockWatermarkControls.isAnchorParam(it.param) }
    keys.forEachIndexed { index, key ->
        if (index > 0) Spacer(Modifier.height(BlockGap))
        when {
            key == PhonePageKeys.SPEC -> PhoneSpecBlock(
                card = card,
                slotCount = if (specOnly) {
                    // 只读块里的「文字 N 行」沿用 WP-C 的口径：渲染器报出来的文本框数。
                    model.controls.count { isNativeTextSlotControl(it) }
                } else {
                    // 声明了可移动水印的厂商规格卡：数声明出来的文字行（IR 卡没有 slot）。
                    model.controls.count { !it.hidden && it.type == ControlType.Text }
                },
                textPositionFixed = if (specOnly) true else !movable,
            )
            theme != null && key == theme.key -> {
                PhoneSwatchRow(
                    options = theme.options.map { it.value to it.display },
                    selected = session.value(theme)?.toString() ?: "",
                    onSelect = { value -> session.set(theme, value) },
                )
            }
            mode != null && key == mode.key -> {
                val modeValue = session.value(mode)?.toString() ?: ""
                PhoneSegmented(
                    options = phoneModeOptions(mode.options.map { it.value to phoneModeLabel(it.display) }),
                    selected = modeValue,
                    onSelect = { value -> session.set(mode, value) },
                    label = "版式",
                )
                PhoneSubNote(phoneModeNote(modeValue))
            }
            footerPct != null && key == footerPct.key -> {
                val modeValue = mode?.let { session.value(it)?.toString() } ?: ""
                // AU2 A-03/A-07：叠加版式不读底部留白；经典/简约下低于短边 8.5%
                // 地板的那一段拖动没有变化，滑杆下限抬到地板。
                val disabledReason = if (mode != null) footerPctDisabledReason(modeValue) else null
                val photo = LocalCuratedPhotoSize.current
                PhoneControlSlider(
                    control = footerPct,
                    session = session,
                    hint = if (disabledReason != null) "叠加版式不生效" else "占照片宽度",
                    enabled = disabledReason == null,
                    disabledReason = disabledReason,
                    effectiveMin = if (disabledReason != null) {
                        null
                    } else {
                        photo?.let { footerPctFloor(it.widthPx, it.heightPx) }
                    },
                )
            }
            key == PhonePageKeys.ANCHOR_GRID && anchor != null ->
                StockAnchorBlock(control = anchor, session = session, dense = true, part = StockAnchorPart.Grid)
            key == PhonePageKeys.ANCHOR_FOOTER && anchor != null ->
                StockAnchorBlock(control = anchor, session = session, dense = true, part = StockAnchorPart.Footer)
            else -> controls.firstOrNull { it.key == key }?.let { PhoneControlRow(control = it, session = session) }
        }
    }
}

/**
 * Showcase `paneIrStyle`: the fixed geometry of a vendor-spec border.
 *
 * [textPositionFixed] is the user decision in copy form: a card whose watermark can
 * move must not claim 文字位置 is fixed (same sentence as the desktop rail, see
 * [vendorSpecNote]).
 */
@Composable
private fun PhoneSpecBlock(card: LibraryCard?, slotCount: Int, textPositionFixed: Boolean) {
    PhoneSpecRows(
        rows = buildList {
            add("文字" to "$slotCount 行")
            val source = listOfNotNull(
                card?.brand?.takeIf { it.isNotBlank() },
                card?.family?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (source.isNotBlank()) add("来源" to source)
        },
    )
    PhoneSubNote(vendorSpecNote(watermarkMovable = !textPositionFixed))
}

// ---------------------------------------------------------------------------
// 文字
// ---------------------------------------------------------------------------

/**
 * 文字页一组：要么是渲染器报告的选择器（一整块），要么是一条声明控件（`StaticRows`）。
 * 文字大小是单独的一组（滑杆一块），横滑一下就到，不用在页里找。
 */
@Composable
private fun ColumnScope.PhoneTextGroup(
    keys: List<String>,
    model: InspectorCardModel,
    tab: InspectorTabInfo,
    session: DesignSession,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    textEntries: List<com.framemark.native.inspector.TextSlotNavEntry>,
    textSelection: com.framemark.native.inspector.TextSlotSelectionState,
    textMode: TextPageMode?,
    onTextSlotFocus: ((String?) -> Unit)?,
) {
    if (PhonePageKeys.TEXT_SLOTS in keys) {
        when (textMode) {
            TextPageMode.Selector -> NativeTextSlotControls(
                model = model,
                slots = textSlots,
                outputWidth = textOutputWidth,
                outputHeight = textOutputHeight,
                session = session,
                onTextSlotFocus = onTextSlotFocus,
                dense = true,
                selection = textSelection,
            )
            TextPageMode.Empty -> TextSlotEmptyPane(dense = true)
            else -> Unit
        }
        return
    }
    val controls = phonePageControls(tab)
    val scale = fontScaleControlOf(controls)
    keys.forEachIndexed { index, key ->
        if (index > 0) Spacer(Modifier.height(BlockGap))
        when {
            scale != null && key == scale.key -> PhoneControlSlider(
                control = scale,
                session = session,
                hint = textScaleHint(textEntries.count { !it.readonly }),
            )
            else -> controls.firstOrNull { it.key == key }?.let { PhoneControlRow(control = it, session = session) }
        }
    }
}

// ---------------------------------------------------------------------------
// Logo
// ---------------------------------------------------------------------------

/**
 * Logo 页一组：面板只画 `onlyKeys` 里的行（`grouped = false`，组头由分页器接替）。
 * 品牌库 / 版本页 / 我的 Logo 仍然只能由动作点进去，仍是盖在画布上的覆盖层。
 */
@Composable
private fun ColumnScope.PhoneLogoGroup(
    keys: List<String>,
    model: InspectorCardModel,
    session: DesignSession,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onOpenBrandPicker: (() -> Unit)?,
) {
    // 自动匹配要读「照片品牌」：与桌面 rail 同一个 CompositionLocal，
    // 数据就是舞台渲染报告里的那张 slot 表（不再另造一份）。
    CompositionLocalProvider(
        LocalCuratedTextSlots provides if (textSlots.isEmpty()) {
            null
        } else {
            CuratedTextSlots(textSlots, textOutputWidth, textOutputHeight)
        },
    ) {
        val brandKey = model.controls.firstOrNull { it.type == ControlType.Brand }?.key
        LogoBrandPanel(
            model = model,
            session = session,
            dense = true,
            textSlots = textSlots,
            // 手机端的品牌选择是盖在画布上的覆盖层面板（外壳绘制，舞台仍在）；
            // 这里只发打开请求，不在矮底板里铺 61 行。
            onOpenBrandPicker = onOpenBrandPicker,
            onlyKeys = keys.toSet(),
            showNotice = PhonePageKeys.LOGO_NOTICE in keys,
            showActions = brandKey != null && brandKey in keys,
            grouped = false,
        )
    }
}

// ---------------------------------------------------------------------------
// 水印
// ---------------------------------------------------------------------------

/**
 * 水印页一组：一段一块（图层 / 位置 / 边距 / 宽度 / 不透明度 / 旋转）。
 * 水印数据仍由外壳持有，这里一个字段都不存。
 */
@Composable
private fun ColumnScope.PhoneWatermarkGroup(
    keys: List<String>,
    watermarkContent: (@Composable (WatermarkPanePart) -> Unit)?,
) {
    keys.forEach { key ->
        val part = when (key) {
            PhonePageKeys.WATERMARK_EMPTY -> WatermarkPanePart.Empty
            PhonePageKeys.WATERMARK_LAYER -> WatermarkPanePart.Layer
            PhonePageKeys.WATERMARK_POSITION -> WatermarkPanePart.Position
            PhonePageKeys.WATERMARK_MARGINS -> WatermarkPanePart.Margins
            PhonePageKeys.WATERMARK_WIDTH -> WatermarkPanePart.Width
            PhonePageKeys.WATERMARK_OPACITY -> WatermarkPanePart.Opacity
            PhonePageKeys.WATERMARK_ROTATION -> WatermarkPanePart.Rotation
            else -> null
        }
        if (part != null) watermarkContent?.invoke(part)
    }
}

/** 这一页没有可画的内容（没有卡片模型时的兜底）。 */
@Composable
private fun PhoneEmptyBlock(page: InspectorPage) {
    Text(
        text = when (page) {
            InspectorPage.Style -> "这套边框没有可调的样式参数。"
            InspectorPage.Text -> "这套边框没有可调的文字参数。"
            InspectorPage.Logo -> "这套边框没有可调的 Logo 参数。"
            InspectorPage.Watermark -> ""
        },
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(vertical = FmSpacing.md),
    )
}

/** One declared control in the showcase's touch chrome. */
@Composable
private fun PhoneControlRow(control: InspectorControl, session: DesignSession) {
    when (control.type) {
        ControlType.Switch -> PhoneSwitchRow(
            label = phoneControlLabel(control),
            checked = session.value(control) as? Boolean ?: false,
            onCheckedChange = { session.set(control, it) },
        )
        ControlType.Slider -> PhoneControlSlider(control = control, session = session)
        ControlType.Color -> PhoneSwatchRow(
            options = control.options.map { it.value to it.display },
            selected = session.value(control)?.toString() ?: "",
            onSelect = { value -> session.set(control, value) },
        )
        ControlType.Select -> when {
            // 「这种是需要放开的」: 厂商水印位置用九宫格，不用十项下拉/色片。
            StockWatermarkControls.isAnchorParam(control.param) ->
                StockAnchorBlock(control = control, session = session, dense = true, part = StockAnchorPart.Grid)
            control.options.size <= 4 -> PhoneSegmented(
                options = control.options.map { it.value to phoneModeLabel(it.display) },
                selected = session.value(control)?.toString() ?: "",
                onSelect = { value -> session.set(control, value) },
                label = phoneControlLabel(control),
            )
            else -> PhoneSwatchRow(
                options = control.options.map { it.value to it.display },
                selected = session.value(control)?.toString() ?: "",
                onSelect = { value -> session.set(control, value) },
            )
        }
        ControlType.Text -> PhoneTextValueRow(control = control, session = session)
        // 品牌字标 is owned by the Logo page on both platforms; showing it here too
        // would duplicate that data (brief: reuse, never re-implement).
        ControlType.Brand -> Unit
    }
}

/** A slider row bound to one declared control (display scale applied). */
@Composable
private fun PhoneControlSlider(
    control: InspectorControl,
    session: DesignSession,
    hint: String? = null,
    /** AU2 A-03/A-04：当前状态下不生效时置灰并在下面写一句原因。 */
    enabled: Boolean = true,
    disabledReason: String? = null,
    /** 生效下限（例如底部留白的短边 8.5% 地板，AU2 A-07）。 */
    effectiveMin: Float? = null,
) {
    val raw = (session.value(control) as? Number)?.toFloat()
        ?: (control.initial as? ControlValue.Num)?.value
        ?: 0f
    val declaredMin = control.min ?: 0f
    val max = control.max ?: 100f
    val min = effectiveMin?.takeIf { it > declaredMin }?.coerceAtMost(max) ?: declaredMin
    val scale = control.unitScale.takeIf { it > 0f } ?: 1f
    val modified = session.isModified(control)
    PhoneSliderRow(
        label = phoneControlLabel(control),
        value = raw.coerceIn(min, max),
        valueRange = min..max,
        defaultValue = phoneDeclaredDefault(control),
        step = control.step,
        unit = control.unit,
        modified = modified,
        onValueChange = { next -> session.set(control, next) },
        onReset = if (modified && enabled) {
            { session.resetControl(control) }
        } else {
            null
        },
        hint = hint,
        scale = scale,
        decimals = phoneDecimalsOf(control.step?.let { it * scale }),
        enabled = enabled,
        disabledReason = disabledReason,
    )
}

/** A declared free-text row: input + 已改 / 恢复原文 (showcase `.trow`). */
@Composable
private fun PhoneTextValueRow(control: InspectorControl, session: DesignSession) {
    val current = session.value(control)?.toString().orEmpty()
    val original = (control.initial as? ControlValue.Str)?.value.orEmpty()
    val modified = session.isModified(control)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = phoneControlLabel(control) + if (modified) "　已改" else "",
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = if (modified) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = current,
            onValueChange = { text -> session.set(control, text) },
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = FmPalette.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(FmPalette.primary),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        if (modified) {
            Text(
                text = "恢复原文",
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                color = FmPalette.primaryStrong,
                modifier = Modifier.clickable { session.set(control, original) }.padding(vertical = 4.dp),
            )
        }
    }
}

/** Showcase `RELABEL`: the inspector's shorter label for a known parameter. */
internal fun phoneControlLabel(control: InspectorControl): String = when (control.label) {
    "显示标志" -> "显示 Logo"
    "标志位置" -> "Logo 位置"
    "字标缩放" -> "Logo 大小"
    "字标水平中心" -> "Logo 水平位置"
    "品牌标志" -> "Logo"
    "底色主题" -> "底色"
    "显示日期行" -> "显示日期"
    "整卡字号缩放" -> "整体文字大小"
    "页脚版式" -> "版式"
    "页脚自适应" -> "页脚自适应"
    else -> control.label
}

/** Showcase `MODE_OPTS`: 经典 / 简约 / 叠加. */
internal fun phoneModeLabel(display: String): String = when (display.lowercase()) {
    "classic" -> "经典"
    "simple" -> "简约"
    "overlay" -> "叠加"
    else -> display
}

/**
 * The 版式 choices in the showcase's order (`MODE_OPTS` = classic, simple, overlay),
 * whatever order the metadata declares. Options outside that set keep their own
 * order and are appended, so a card with a fourth mode still shows it.
 */
internal fun phoneModeOptions(options: List<Pair<String, String>>): List<Pair<String, String>> {
    val order = listOf("classic", "simple", "overlay")
    val known = order.mapNotNull { key -> options.firstOrNull { it.first == key } }
    val rest = options.filterNot { it.first in order }
    return known + rest
}

/** Showcase `MODE_NOTE`: the one line under the 版式 segmented control. */
internal fun phoneModeNote(value: String): String = when (value) {
    "classic" -> "左侧品牌与机型，右侧参数与日期，中间放 Logo。"
    "simple" -> "单行居中：Logo · 品牌机型 · 参数 · 日期。"
    "overlay" -> "文字压在照片底部，画布不加高。"
    else -> ""
}

/** The declared default of a numeric control, used for the slider's tick and reset. */
internal fun phoneDeclaredDefault(control: InspectorControl): Float? =
    (control.initial as? ControlValue.Num)?.value

/**
 * Rows that can be drawn at all: no hidden rows, no blank labels, no internal
 * `params.slots` row, and no per-slot generated controls (those are drawn by
 * [NativeTextSlotControls] under their own row).
 *
 * This is the page-independent half of [phonePageControls]: the pinned 公共区 reads a
 * card's layout params through it ([PhoneDesignSummary]), because a page never owns
 * those exclusively — `OutputSize` only looks at the layout params themselves.
 */
internal fun phoneVisibleControls(controls: List<InspectorControl>): List<InspectorControl> =
    controls
        .filterNot { it.hidden }
        .filter { it.label.isNotBlank() }
        .filterNot { it.param == "params.slots" }
        .filterNot { isNativeTextSlotControl(it) }
        .distinctBy { it.key }

/**
 * Controls the phone page actually shows: [phoneVisibleControls] plus the page rule —
 * static text controls are only kept when the card has no reliable renderer rows
 * (same rule as WP-C).
 */
internal fun phonePageControls(tab: InspectorTabInfo): List<InspectorControl> =
    phoneVisibleControls(tab.controls)
        .filter { tab.page != InspectorPage.Text || isUsableStaticTextControl(it) }

@Composable
private fun PanelNote(text: String, modifier: Modifier, onRetry: (() -> Unit)?) {
    Column(
        modifier = modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
        if (onRetry != null) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("重新加载参数", style = FmType.labelMedium, color = FmPalette.primaryStrong)
            }
        }
    }
}
