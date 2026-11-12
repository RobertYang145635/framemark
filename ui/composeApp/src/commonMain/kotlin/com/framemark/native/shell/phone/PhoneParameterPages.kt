package com.framemark.native.shell.phone

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.framemark.native.inspector.InspectorPageContent
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.LocalCuratedPhotoSize
import com.framemark.native.inspector.LocalCuratedTextSlots
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.inspector.StockAnchorControl
import com.framemark.native.inspector.StylePageBody
import com.framemark.native.inspector.TextPageMode
import com.framemark.native.inspector.TextSlotEmptyPane
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
 *  - 水印: [watermarkPane], owned by the editor because the layer lives there.
 *
 * **面**（「不要让用户为了调参数上下来回滚」）: the page is two zones with at most one
 * scrollable region, and that region never contains a primary action.
 *
 *  - **公共区** ([PhoneDesignSummary]) — pinned above the tools, identical on all four
 *    pages, never scrolls and never moves when the tab changes: the export size of
 *    the selected design. No declared control can join it: `InspectorTabs.kt`'s
 *    `inspectorPageOf` partitions a card's controls, so every control belongs to
 *    exactly one page and hoisting one would mean duplicating a page's binding.
 *  - **工具区** — the only part that changes with [page] and the only scroller. The
 *    page header (title / 已改 N / 恢复默认) is the host's `PhoneSheetHead`, already
 *    pinned above both zones in the sheet; in the constrained full-page branch the
 *    公共区 is the pinned context.
 *  - [footer] — the page's primary actions, pinned *under* the scroller.
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
    watermarkPane: (@Composable () -> Unit)? = null,
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
            // 滚动位移按页记（[phoneToolScrollFor]）：改前四页共用一条位移，新页签
            // 一打开可能停在半路；现在切回一个页签回到自己那一行。
            val toolOffsets = remember(model?.cardId) { mutableMapOf<InspectorPage, ScrollState>() }
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 公共区：四页同一块，永不滚走、也不随页签挪动 ----
                if (model != null && showsPhoneParameterSummary(pageHeightDp)) {
                    PhoneDesignSummary(controls = designControls, session = session)
                    PhoneZoneRule()
                }
                // ---- 工具区：本页唯一随页签变的容器，也是本页唯一的滚动区 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(phoneToolScrollFor(toolOffsets, page)),
                ) {
                    when {
                        page == InspectorPage.Watermark ->
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                watermarkPane?.invoke()
                            }
                        page == InspectorPage.Logo && model != null ->
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                // 自动匹配要读「照片品牌」：与桌面 rail 同一个 CompositionLocal，
                                // 数据就是舞台渲染报告里的那张 slot 表（不再另造一份）。
                                CompositionLocalProvider(
                                    LocalCuratedTextSlots provides if (textSlots.isEmpty()) {
                                        null
                                    } else {
                                        CuratedTextSlots(textSlots, textOutputWidth, textOutputHeight)
                                    },
                                ) {
                                    LogoBrandPanel(
                                        model = model,
                                        session = session,
                                        dense = true,
                                        textSlots = textSlots,
                                        // 手机端的品牌选择是盖在画布上的覆盖层面板（外壳绘制，
                                        // 舞台仍在）；这里只发打开请求，不在矮底板里铺 61 行。
                                        onOpenBrandPicker = onOpenBrandPicker,
                                    )
                                }
                            }
                        page == InspectorPage.Style && model != null -> PhoneStylePane(
                            model = model,
                            session = session,
                            card = card,
                            tab = tab,
                        )
                        textMode == TextPageMode.Selector && model != null -> Column {
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                NativeTextSlotControls(
                                    model = model,
                                    slots = textSlots,
                                    outputWidth = textOutputWidth,
                                    outputHeight = textOutputHeight,
                                    session = session,
                                    onTextSlotFocus = onTextSlotFocus,
                                    dense = true,
                                    selection = textSelection,
                                )
                            }
                            PhoneTextScaleGroup(
                                controls = tab.controls,
                                session = session,
                                boxCount = textEntries.count { !it.readonly },
                            )
                        }
                        textMode == TextPageMode.Empty ->
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                TextSlotEmptyPane(dense = true)
                            }
                        else -> Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                            InspectorPageContent(
                                tab = tab.copy(controls = phonePageControls(tab)),
                                session = session,
                                dense = true,
                            )
                        }
                    }
                }
                // ---- 主操作固定在滚动区之外：页签怎么切都点得到 ----
                footer?.invoke()
            }
        }
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

/**
 * The tool zone's scroll offset for [page]: one per page, kept while the same card is
 * selected.
 *
 * The page used to own a single `rememberScrollState()` shared by all four tabs, so
 * opening a different tool inherited the previous tab's offset — a fresh page could
 * open halfway down a list the user had never scrolled. Keying the offset by page
 * makes 样式 → 文字 → 样式 return to the row being adjusted instead of the top.
 * A different card starts with an empty cache ([PhoneParameterPage] remembers it by
 * `model.cardId`), because the rows are different rows.
 */
internal fun phoneToolScrollFor(
    offsets: MutableMap<InspectorPage, ScrollState>,
    page: InspectorPage,
): ScrollState = offsets.getOrPut(page) { ScrollState(0) }

/**
 * 样式 page of the showcase (`paneClassicStyle`): the theme swatches, the footer
 * layout segmented control with its one-line explanation and the footer-height touch
 * slider. The pane's last row, 导出尺寸 (`.outsz`), moved up into the page's pinned
 * 公共区 ([PhoneDesignSummary]): it is a property of the design, not of this page, and
 * it was the row furthest down the longest scroll.
 *
 * `paneIrStyle` (vendor-spec cards) used to be **unconditionally** the read-only
 * 边框规格 block. The user decision 「这种是需要放开的，有的边框需要定死，有的不需要」
 * splits it: a vendor card that declares `stockAnchor` / the two offsets gets those
 * declared controls (水印位置 as a 3×3 grid, then the 水平/垂直微调 sliders) under the
 * same 边框规格 note; a card that declares nothing adjustable keeps exactly the old
 * read-only block. Any other card falls back to its declared controls, rendered with
 * the same `.touchui` chrome rather than a form of drop-downs.
 */
@Composable
private fun PhoneStylePane(
    model: InspectorCardModel,
    session: DesignSession,
    card: LibraryCard?,
    tab: InspectorTabInfo,
) {
    val controls = phonePageControls(tab)
    val theme = themeControlOf(controls)
    // AU2 A-17：与桌面同一个 tail-match 判定，不再比对字面量 `params.footerMode`。
    val mode = footerModeControlOf(controls)
    val footerPct = footerPctControlOf(controls)
    // 只有「什么都没声明」的厂商规格卡才是只读的；同一个判据在桌面样式页也读
    // （`phoneStylePageBody` / `stockWatermarkPlan`，定义在 inspector 包）。
    val body = phoneStylePageBody(isIr = model.isIr, controls = controls)
    val movable = movableStockWatermark(controls)
    if (body == StylePageBody.SpecOnly) {
        // 只读块里的「文字 N 行」沿用 WP-C 的口径：渲染器报出来的文本框数。
        PhoneSpecPane(
            card = card,
            slotCount = model.controls.count { isNativeTextSlotControl(it) },
            textPositionFixed = true,
        )
        return
    }
    val handled = setOfNotNull(theme?.key, mode?.key, footerPct?.key)
    // 声明可以重复同一条 anchorGrid 行，但 `params.stockAnchor` 只有一个字段：
    // 九宫格只画一次。
    val rest = withoutDuplicateStockAnchors(controls.filterNot { it.key in handled })
    Column(modifier = Modifier.fillMaxWidth()) {
        // 声明了可移动水印的厂商规格卡：规格说明仍然在上，但文案不再说位置是固定的；
        // 下面就是九宫格 + 微调，和手写卡一样走声明出来的控件。这一段的「文字 N 行」
        // 数的是声明出来的文字行（IR 卡没有渲染器 slot）。
        if (model.isIr) {
            PhoneSpecPane(
                card = card,
                slotCount = model.controls.count { !it.hidden && it.type == ControlType.Text },
                textPositionFixed = !movable,
            )
        }
        if (theme != null) {
            PhoneGroup(title = "底色") {
                PhoneSwatchRow(
                    options = theme.options.map { it.value to it.display },
                    selected = session.value(theme)?.toString() ?: "",
                    onSelect = { value -> session.set(theme, value) },
                )
            }
        }
        if (mode != null) {
            val modeValue = session.value(mode)?.toString() ?: ""
            PhoneGroup(title = "版式") {
                PhoneSegmented(
                    options = phoneModeOptions(mode.options.map { it.value to phoneModeLabel(it.display) }),
                    selected = modeValue,
                    onSelect = { value -> session.set(mode, value) },
                    label = "版式",
                )
                PhoneSubNote(phoneModeNote(modeValue))
                if (footerPct != null) {
                    // AU2 A-03/A-07：叠加版式不读底部留白；经典/简约下低于短边 8.5%
                    // 地板的那一段拖动没有变化，滑杆下限抬到地板。
                    val disabledReason = footerPctDisabledReason(modeValue)
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
            }
        } else if (footerPct != null) {
            PhoneGroup(title = "版式") {
                PhoneControlSlider(control = footerPct, session = session, hint = "占照片宽度")
            }
        }
        if (rest.isNotEmpty()) {
            rest.groupBy { it.section }.forEach { (section, rows) ->
                PhoneGroup(title = section.title) {
                    rows.forEachIndexed { index, control ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        PhoneControlRow(control = control, session = session)
                    }
                }
            }
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
private fun PhoneSpecPane(card: LibraryCard?, slotCount: Int, textPositionFixed: Boolean) {
    PhoneGroup(title = "边框规格") {
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
}

/** The 文字 page's 文字大小 group (showcase `grp('文字大小', fmSlider(...))`). */
@Composable
private fun PhoneTextScaleGroup(
    controls: List<InspectorControl>,
    session: DesignSession,
    /** 这一页有几个文本框：四行沿用演示稿口径，其他数量说人话。 */
    boxCount: Int = 0,
) {
    // AU2 A-05: 这里原来比对字面量 `"text.fontScale"`，而注册表归一后 consumer 是
    // `fontScale` —— 62 张手写卡的「文字大小」在手机上从来没出现过。改用与桌面
    // 同一个判定，避免第三处再分叉。
    val scale = fontScaleControlOf(controls) ?: return
    PhoneGroup(title = "文字大小") {
        PhoneControlSlider(
            control = scale,
            session = session,
            hint = textScaleHint(boxCount),
        )
    }
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
                StockAnchorControl(control = control, session = session, dense = true)
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


