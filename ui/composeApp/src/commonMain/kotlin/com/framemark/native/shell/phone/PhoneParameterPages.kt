package com.framemark.native.shell.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.LocalCuratedPhotoSize
import com.framemark.native.inspector.LocalCuratedTextSlots
import com.framemark.native.inspector.LogoBrandMode
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.inspector.OutputSize
import com.framemark.native.inspector.StockAnchorBlock
import com.framemark.native.inspector.StockAnchorPart
import com.framemark.native.inspector.StylePageBody
import com.framemark.native.inspector.TextPageMode
import com.framemark.native.inspector.TextSlotEmptyPane
import com.framemark.native.inspector.WatermarkEdge
import com.framemark.native.inspector.WatermarkEmptyBlock
import com.framemark.native.inspector.WatermarkLayerBlock
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.curatedOutputPixels
import com.framemark.native.inspector.footerModeControlOf
import com.framemark.native.inspector.footerPctControlOf
import com.framemark.native.inspector.footerPctDisabledReason
import com.framemark.native.inspector.footerPctFloor
import com.framemark.native.inspector.logoBrandMode
import com.framemark.native.inspector.movableStockWatermark
import com.framemark.native.inspector.phoneStylePageBody
import com.framemark.native.inspector.rememberTextSlotSelectionState
import com.framemark.native.inspector.textPageMode
import com.framemark.native.inspector.textSlotNavEntries
import com.framemark.native.inspector.themeControlOf
import com.framemark.native.inspector.vendorSpecNote
import com.framemark.native.library.LibraryCard
import com.framemark.native.shell.PHONE_PARAM_LABEL_LINE_DP
import com.framemark.native.shell.PHONE_PARAM_LABEL_SP
import com.framemark.native.shell.PHONE_PARAM_ROW_GAP_DP
import com.framemark.native.shell.PHONE_PARAM_VALUE_SP
import com.framemark.native.shell.PhonePageSpec
import com.framemark.native.shell.PhoneParamRow
import com.framemark.native.shell.PhoneRowKeys
import com.framemark.native.shell.PhoneRowKind
import com.framemark.native.shell.phonePanelGroups
import com.framemark.native.shell.phonePanelToolFor
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.isUsableStaticTextControl

/**
 * The phone's parameter panel for one WP-C tab — **fixed-frame** version (2026-11-10).
 *
 * 面板内部从上到下只有三块，与量到的 Lightroom 一致：
 *
 *  1. **组 tab 行**（[PhoneGroupTabRow]）—— 钉在顶部，横滑，不跟参数滚：样式
 *     `[外观｜位置｜留白｜规格]`（空组不出现）、文字 `[文字]`、Logo `[显示｜调整]`、
 *     水印 `[图层｜外观]`。每个组 2..3 行（`PhonePanelLayout.kt` 的分组规则），所以
 *     固定画框下面不会空出一大片。组不是横滑分页：点一个 tab 就换下面的行，面板高度不变。
 *  2. **参数区** —— tab 行下面、面板底边上面，**只有它在行放不下时竖向滚动**。
 *     一行是两行的两行组：标签 15 sp 在左、数值 15 sp 在右，下面一条整宽轨道
 *     （[PhoneSliderParamRow]）。没有「两条滑杆并排」这种形态。
 *  3. 可选的 [footer]（主操作），钉在面板底部。
 *
 * 这里**没有** pager、没有圆点、没有「公共区」横条：分页机器与按内容算高的预算
 * 一起删掉了（见 `PhonePanelLayout.kt` 顶部）。水印的九宫格也不在这里 —— 它在画布上
 * （`PhoneWatermarkAnchorLayer`），面板只留 图层 / 距左 / 距上 / 宽度 / 不透明度 / 旋转。
 *
 * 品牌选择器与边框库仍然是**盖在编辑器上的覆盖层**（外壳画的），这一页只把请求往上递。
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
    /** 水印当前值：图层 / 边距 / 外观三组读它；null = 没有水印层（空态 + 上传）。 */
    watermark: WatermarkSpec? = null,
    photoAspect: Float = 1.5f,
    onWatermarkWidth: (Float) -> Unit = {},
    onWatermarkOpacity: (Float) -> Unit = {},
    onWatermarkPosition: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onWatermarkRotation: (Float) -> Unit = {},
    onPickWatermark: () -> Unit = {},
    onClearWatermark: () -> Unit = {},
    /**
     * 手机端 选择品牌 的打开请求：选择器是外壳画在整个编辑器之上的覆盖层面板，
     * 不是这一页里的一层，所以这里只把请求往上递。null = 桌面右栏的内联品牌页。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    /** Current card, for the 边框规格 block's 来源 row. */
    card: LibraryCard? = null,
    /** 舞台当前照片的像素尺寸（导出尺寸行读它；未知时这一行只说「等照片」）。 */
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
    val spec = remember(tab, model?.cardId, model?.isIr, textOutputWidth, watermark != null, photoWidthPx, photoHeightPx) {
        PhonePageSpec(
            tool = phonePanelToolFor(page),
            controls = phonePageControls(tab),
            isIr = model?.isIr == true,
            hasTextReport = textOutputWidth > 0,
            hasWatermarkLayer = watermark != null,
            hasExportSize = photoWidthPx > 0 && photoHeightPx > 0,
        )
    }
    val groups = remember(spec) { phonePanelGroups(spec) }
    // 换页签 / 换卡片回到第一组：每一页第一个 tab 永远在同一个位置。
    var selectedGroupId by rememberSaveable(page, model?.cardId) { mutableStateOf<String?>(null) }
    val active = groups.firstOrNull { it.id == selectedGroupId } ?: groups.firstOrNull()

    // 「底部留白」那一条仍然读照片的长短边（AU2 A-07 的地板），口径与改前一致。
    CompositionLocalProvider(
        LocalCuratedPhotoSize provides if (photoWidthPx > 0 && photoHeightPx > 0) {
            CuratedPhotoSize(photoWidthPx, photoHeightPx)
        } else {
            null
        },
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            // ---- 钉在顶部的组 tab 行（不跟参数滚，放不下就横滑） ----
            PhoneGroupTabRow(
                groups = groups,
                selectedId = active?.id,
                onSelect = { selectedGroupId = it },
            )
            // ---- 参数区：只有这块滚 ----
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (model == null) {
                    EmptyPageNote(page)
                } else {
                    PhoneParameterRows(
                        rows = active?.rows.orEmpty(),
                        page = page,
                        tab = tab,
                        model = model,
                        session = session,
                        textSlots = textSlots,
                        textOutputWidth = textOutputWidth,
                        textOutputHeight = textOutputHeight,
                        onTextSlotFocus = onTextSlotFocus,
                        watermark = watermark,
                        photoAspect = photoAspect,
                        onWatermarkWidth = onWatermarkWidth,
                        onWatermarkOpacity = onWatermarkOpacity,
                        onWatermarkPosition = onWatermarkPosition,
                        onWatermarkRotation = onWatermarkRotation,
                        onPickWatermark = onPickWatermark,
                        onClearWatermark = onClearWatermark,
                        onOpenBrandPicker = onOpenBrandPicker,
                        card = card,
                    )
                }
            }
            footer?.invoke()
        }
    }
}

/**
 * 参数区：**唯一**的竖向滚动容器。
 *
 * 行距 [PHONE_PARAM_ROW_GAP_DP] = 12 dp，与 `PhoneParamRow.heightDp` 里含的那 12 是
 * 同一个数（行高 = 内容 + 行距），所以「滚动前能看几行」的纯函数与实际渲染不会分叉。
 */
@Composable
private fun PhoneParameterRows(
    rows: List<PhoneParamRow>,
    page: InspectorPage,
    tab: InspectorTabInfo,
    model: InspectorCardModel,
    session: DesignSession,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onTextSlotFocus: ((String?) -> Unit)?,
    watermark: WatermarkSpec?,
    photoAspect: Float,
    onWatermarkWidth: (Float) -> Unit,
    onWatermarkOpacity: (Float) -> Unit,
    onWatermarkPosition: (dx: Float, dy: Float) -> Unit,
    onWatermarkRotation: (Float) -> Unit,
    onPickWatermark: () -> Unit,
    onClearWatermark: () -> Unit,
    onOpenBrandPicker: (() -> Unit)?,
    card: LibraryCard?,
) {
    if (rows.isEmpty()) {
        QuietNote("这一组没有可调参数。")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(PHONE_PARAM_ROW_GAP_DP.dp),
    ) {
        rows.forEach { row ->
            PhoneParamRowView(
                row = row,
                page = page,
                tab = tab,
                model = model,
                session = session,
                textSlots = textSlots,
                textOutputWidth = textOutputWidth,
                textOutputHeight = textOutputHeight,
                onTextSlotFocus = onTextSlotFocus,
                watermark = watermark,
                photoAspect = photoAspect,
                onWatermarkWidth = onWatermarkWidth,
                onWatermarkOpacity = onWatermarkOpacity,
                onWatermarkPosition = onWatermarkPosition,
                onWatermarkRotation = onWatermarkRotation,
                onPickWatermark = onPickWatermark,
                onClearWatermark = onClearWatermark,
                onOpenBrandPicker = onOpenBrandPicker,
                card = card,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 一行 → 一个形态
// ---------------------------------------------------------------------------

@Composable
private fun PhoneParamRowView(
    row: PhoneParamRow,
    page: InspectorPage,
    tab: InspectorTabInfo,
    model: InspectorCardModel,
    session: DesignSession,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onTextSlotFocus: ((String?) -> Unit)?,
    watermark: WatermarkSpec?,
    photoAspect: Float,
    onWatermarkWidth: (Float) -> Unit,
    onWatermarkOpacity: (Float) -> Unit,
    onWatermarkPosition: (dx: Float, dy: Float) -> Unit,
    onWatermarkRotation: (Float) -> Unit,
    onPickWatermark: () -> Unit,
    onClearWatermark: () -> Unit,
    onOpenBrandPicker: (() -> Unit)?,
    card: LibraryCard?,
) {
    when (row.kind) {
        PhoneRowKind.Slider -> if (page == InspectorPage.Watermark || row.control == null) {
            WatermarkSliderRow(
                row = row,
                watermark = watermark,
                photoAspect = photoAspect,
                onWidth = onWatermarkWidth,
                onOpacity = onWatermarkOpacity,
                onPosition = onWatermarkPosition,
                onRotation = onWatermarkRotation,
            )
        } else {
            DeclaredSliderRow(control = row.control, label = row.label, session = session, tab = tab)
        }
        PhoneRowKind.Switch -> row.control?.let { control ->
            PhoneSwitchParamRow(
                label = row.label,
                checked = session.value(control) as? Boolean ?: false,
                onCheckedChange = { session.set(control, it) },
            )
        }
        PhoneRowKind.Segmented -> row.control?.let { control ->
            val selected = session.value(control)?.toString().orEmpty()
            PhoneSegmentedParamRow(
                label = row.label,
                options = control.options,
                selected = selected,
                onSelect = { session.set(control, it) },
            )
            // 版式那一句说明跟着选中项变（量到的 Lightroom 版式行也有这一句）。
            if (control.param.contains("footerMode", ignoreCase = true)) {
                PhoneSubNote(com.framemark.native.shell.phoneParamModeNote(selected))
            }
        }
        PhoneRowKind.Swatches -> row.control?.let { control ->
            val selected = session.value(control)?.toString().orEmpty()
            LabelValueLine(
                label = row.label,
                value = control.options.firstOrNull { it.value == selected }?.display ?: selected,
            )
            PhoneSwatchRow(
                options = control.options.map { it.value to it.display },
                selected = selected,
                onSelect = { session.set(control, it) },
            )
        }
        PhoneRowKind.Text -> row.control?.let { control ->
            TextParamRow(control = control, session = session, label = row.label)
        }
        PhoneRowKind.TextSlots -> TextSlotsRow(
            model = model,
            tab = tab,
            session = session,
            textSlots = textSlots,
            textOutputWidth = textOutputWidth,
            textOutputHeight = textOutputHeight,
            onTextSlotFocus = onTextSlotFocus,
        )
        PhoneRowKind.AnchorGrid -> row.control?.let { control ->
            StockAnchorBlock(control = control, session = session, dense = true, part = StockAnchorPart.Grid)
        }
        PhoneRowKind.AnchorFooter -> row.control?.let { control ->
            StockAnchorBlock(control = control, session = session, dense = true, part = StockAnchorPart.Footer)
        }
        PhoneRowKind.Spec -> SpecBlock(model = model, tab = tab, card = card)
        PhoneRowKind.LogoActions -> LogoActionsRow(
            model = model,
            session = session,
            brandKey = row.key,
            textSlots = textSlots,
            onOpenBrandPicker = onOpenBrandPicker,
        )
        PhoneRowKind.LogoNotice -> QuietNote(logoNoticeText(model))
        PhoneRowKind.WatermarkLayer -> WatermarkLayerBlock(
            assetName = null,
            onUpload = onPickWatermark,
            onClear = onClearWatermark,
            uploadLabel = "更换水印图片",
            dense = true,
        )
        PhoneRowKind.WatermarkEmpty -> WatermarkEmptyBlock(
            dense = true,
            onUpload = onPickWatermark,
            uploadLabel = "上传透明 PNG 水印",
        )
        PhoneRowKind.Note -> QuietNote("这一组没有可调参数。")
        PhoneRowKind.ExportSize -> ExportSizeRow(model = model, session = session)
    }
}

/** 标签行：左标签 + 右数值（色板这类没有轨道的行也用同一行高）。 */
@Composable
private fun LabelValueLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PHONE_PARAM_LABEL_LINE_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LocalTextStyle.current.copy(fontSize = PHONE_PARAM_LABEL_SP.sp, lineHeight = 20.sp),
            color = FmPalette.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LocalTextStyle.current.copy(
                fontSize = PHONE_PARAM_VALUE_SP.sp,
                lineHeight = 20.sp,
            ),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuietNote(text: String) {
    Text(
        text = text,
        style = LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 一条声明的数值滑杆。除了一点外都是直接映射：[footerPct] 在「叠加」版式下不生效，
 * 低于短边 8.5 % 地板的那一段拖不动 —— 判据仍是 AU2 A-03/A-07 的那两个函数。
 */
@Composable
private fun DeclaredSliderRow(
    control: InspectorControl,
    label: String,
    session: DesignSession,
    tab: InspectorTabInfo,
) {
    val controls = phonePageControls(tab)
    val isFooterPct = footerPctControlOf(controls)?.key == control.key
    if (!isFooterPct) {
        PhoneDeclaredSliderRow(control = control, session = session, label = label)
        return
    }
    val mode = footerModeControlOf(controls)
    val modeValue = mode?.let { session.value(it)?.toString() } ?: ""
    val disabledReason = if (mode != null) footerPctDisabledReason(modeValue) else null
    val photo = LocalCuratedPhotoSize.current
    PhoneDeclaredSliderRow(
        control = control,
        session = session,
        label = label,
        enabled = disabledReason == null,
        disabledReason = disabledReason,
        effectiveMin = if (disabledReason != null) {
            null
        } else {
            photo?.let { footerPctFloor(it.widthPx, it.heightPx) }
        },
    )
}

// ---------------------------------------------------------------------------
// 文字
// ---------------------------------------------------------------------------

@Composable
private fun TextSlotsRow(
    model: InspectorCardModel,
    tab: InspectorTabInfo,
    session: DesignSession,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onTextSlotFocus: ((String?) -> Unit)?,
) {
    // 文字页三态（WP-TX1）：有渲染器报告的文本框 → 选择器；报告到了但一个都没有 →
    // 空态；压根没有报告 → 保留声明出来的文字行（由组里的声明行画）。
    val entries = remember(textSlots, textOutputWidth, textOutputHeight) {
        textSlotNavEntries(textSlots, textOutputWidth, textOutputHeight)
    }
    val selection = rememberTextSlotSelectionState(model.cardId)
    val mode = textPageMode(
        hasReport = textOutputWidth > 0,
        entries = entries,
        staticTextControls = phonePageControls(tab).filter {
            it.type == ControlType.Text || it.type == ControlType.Switch
        },
    )
    when (mode) {
        TextPageMode.Selector -> NativeTextSlotControls(
            model = model,
            slots = textSlots,
            outputWidth = textOutputWidth,
            outputHeight = textOutputHeight,
            session = session,
            onTextSlotFocus = onTextSlotFocus,
            dense = true,
            selection = selection,
        )
        TextPageMode.Empty -> TextSlotEmptyPane(dense = true)
        else -> QuietNote("这套边框没有可读到的文本框。")
    }
}

/** 声明出来的自由文本行：标签行 + 输入框 + 已改 / 恢复原文。 */
@Composable
private fun TextParamRow(control: InspectorControl, session: DesignSession, label: String) {
    val current = session.value(control)?.toString().orEmpty()
    val original = (control.initial as? ControlValue.Str)?.value.orEmpty()
    val modified = session.isModified(control)
    LabelValueLine(label = label, value = if (modified) "已改" else "")
    BasicTextField(
        value = current,
        onValueChange = { text -> session.set(control, text) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 22.sp, color = FmPalette.onSurface),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
    if (modified) {
        Text(
            text = "恢复原文",
            style = LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = FmPalette.primaryStrong,
            modifier = Modifier.clickable { session.set(control, original) }.padding(vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 样式：只读规格 / 导出尺寸
// ---------------------------------------------------------------------------

@Composable
private fun SpecBlock(model: InspectorCardModel, tab: InspectorTabInfo, card: LibraryCard?) {
    val controls = phonePageControls(tab)
    val specOnly = phoneStylePageBody(isIr = model.isIr, controls = controls) == StylePageBody.SpecOnly
    val movable = movableStockWatermark(controls)
    PhoneSpecRows(
        rows = buildList {
            add(
                "文字" to if (specOnly) {
                    // 只读块里的「文字 N 行」沿用 WP-C 的口径：渲染器报出来的文本框数。
                    "${model.controls.count { isNativeTextSlotControl(it) }} 行"
                } else {
                    // 声明了可移动水印的厂商规格卡：数声明出来的文字行（IR 卡没有 slot）。
                    "${model.controls.count { !it.hidden && it.type == ControlType.Text }} 行"
                },
            )
            val source = listOfNotNull(
                card?.brand?.takeIf { it.isNotBlank() },
                card?.family?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (source.isNotBlank()) add("来源" to source)
        },
    )
    PhoneSubNote(vendorSpecNote(watermarkMovable = if (specOnly) false else movable))
}

@Composable
private fun ExportSizeRow(model: InspectorCardModel, session: DesignSession) {
    val pixels = OutputSize.applyLongEdge(
        pixels = curatedOutputPixels(phoneVisibleControls(model.controls), session),
        mode = session.export.sizeMode,
        longEdge = session.export.longEdge,
    )
    if (pixels == null) {
        QuietNote("导出尺寸：照片就绪后显示。")
        return
    }
    PhoneKeyValueRow(label = "导出尺寸", value = "$pixels px", topPadding = 0.dp)
}

// ---------------------------------------------------------------------------
// Logo
// ---------------------------------------------------------------------------

@Composable
private fun LogoActionsRow(
    model: InspectorCardModel,
    session: DesignSession,
    brandKey: String,
    textSlots: List<TextSlot>,
    onOpenBrandPicker: (() -> Unit)?,
) {
    // 自动匹配要读「照片品牌」：与桌面 rail 同一个 CompositionLocal，
    // 数据就是舞台渲染报告里的那张 slot 表（不再另造一份）。
    CompositionLocalProvider(
        LocalCuratedTextSlots provides if (textSlots.isEmpty()) {
            null
        } else {
            CuratedTextSlots(textSlots, 0, 0)
        },
    ) {
        LogoBrandPanel(
            model = model,
            session = session,
            dense = true,
            textSlots = textSlots,
            onOpenBrandPicker = onOpenBrandPicker,
            onlyKeys = setOf(brandKey),
            showNotice = false,
            showActions = true,
            grouped = false,
        )
    }
}

/** Logo 页没有可替换字标时的那句说明（与 `CuratedLogoPane` 同一个判据）。 */
private fun logoNoticeText(model: InspectorCardModel): String {
    val brand = model.controls.firstOrNull { it.type == ControlType.Brand }
    val logoControls = model.sections.firstOrNull { it.section == InspectorSection.Logo }?.controls
        ?: model.controls.filter { it.section == InspectorSection.Logo }
    return when (logoBrandMode(brand, logoControls)) {
        LogoBrandMode.Absent -> "这套边框未收录可替换 Logo：当前模板不支持可替换字标，也没有字标参数。"
        LogoBrandMode.Fixed -> "当前模板使用固定品牌字标；品牌由模板固定，字标参数仍可调整。"
        LogoBrandMode.Branded -> "这套边框可以更换 Logo。"
    }
}

// ---------------------------------------------------------------------------
// 水印：图层以外五个两行行（九宫格在画布上）
// ---------------------------------------------------------------------------

/** 水印旋转滑杆的编辑区间（模型允许 ±180，编辑只开放常用段）。 */
private const val WATERMARK_ROTATION_RANGE = 45f

@Composable
private fun WatermarkSliderRow(
    row: PhoneParamRow,
    watermark: WatermarkSpec?,
    photoAspect: Float,
    onWidth: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onPosition: (dx: Float, dy: Float) -> Unit,
    onRotation: (Float) -> Unit,
) {
    val spec = watermark
    if (spec == null) {
        QuietNote("还没有图片水印。先在「图层」里添加一张透明 PNG。")
        return
    }
    val zone = WatermarkZones.zoneFor(spec.anchor)
    when (row.key) {
        PhoneRowKeys.WATERMARK_MARGIN_X -> MarginRow(
            spec = spec,
            photoAspect = photoAspect,
            edge = zone.horizontalEdge,
            label = if (zone.horizontalEdge == WatermarkEdge.Right) "距右" else "距左",
            onPosition = onPosition,
        )
        PhoneRowKeys.WATERMARK_MARGIN_Y -> MarginRow(
            spec = spec,
            photoAspect = photoAspect,
            edge = zone.verticalEdge,
            label = if (zone.verticalEdge == WatermarkEdge.Bottom) "距下" else "距上",
            onPosition = onPosition,
        )
        PhoneRowKeys.WATERMARK_WIDTH -> {
            val cap = zone.widthCap
            PhoneSliderParamRow(
                label = "宽度",
                value = spec.width.coerceIn(0.05f, cap) * 100f,
                range = 5f..(cap * 100f),
                defaultValue = WatermarkZones.DEFAULT_WIDTH * 100f,
                step = 0.5f,
                unit = "%",
                decimals = 1,
                onValueChange = { next -> onWidth((next / 100f).coerceIn(0.05f, cap)) },
            )
        }
        PhoneRowKeys.WATERMARK_OPACITY -> PhoneSliderParamRow(
            label = "不透明度",
            value = spec.opacity.coerceIn(0.1f, 1f) * 100f,
            range = 10f..100f,
            defaultValue = WatermarkZones.DEFAULT_OPACITY * 100f,
            step = 1f,
            unit = "%",
            decimals = 0,
            onValueChange = { next -> onOpacity((next / 100f).coerceIn(0.1f, 1f)) },
        )
        PhoneRowKeys.WATERMARK_ROTATION -> PhoneSliderParamRow(
            label = "旋转",
            value = spec.rotation.coerceIn(-WATERMARK_ROTATION_RANGE, WATERMARK_ROTATION_RANGE),
            range = -WATERMARK_ROTATION_RANGE..WATERMARK_ROTATION_RANGE,
            defaultValue = 0f,
            step = 1f,
            unit = "°",
            decimals = 0,
            onValueChange = { next ->
                onRotation(next.coerceIn(-WATERMARK_ROTATION_RANGE, WATERMARK_ROTATION_RANGE))
            },
        )
        else -> QuietNote("这一项不可调。")
    }
}

/**
 * 一条边距行。格子居中 / 没有可调空间时不是「藏起来」，而是同一行的置灰态：
 * 标签还在、数值写「固定」，所以组的行数与水印位置无关（面板高度也就不随它变）。
 */
@Composable
private fun MarginRow(
    spec: WatermarkSpec,
    photoAspect: Float,
    edge: WatermarkEdge?,
    label: String,
    onPosition: (dx: Float, dy: Float) -> Unit,
) {
    if (edge == null) {
        FixedSliderRow(label = label, valueText = "固定", reason = "居中方向固定，换一个格子才能移动。")
        return
    }
    val maxPct = WatermarkZones.maxMarginPct(
        anchor = spec.anchor,
        width = spec.width,
        photoAspect = photoAspect,
        assetAspect = spec.aspect,
        edge = edge,
    )
    if (maxPct <= 0.05f) {
        FixedSliderRow(label = label, valueText = "无空间", reason = "这一格已经没有可调空间（水印已占满）。")
        return
    }
    val aspect = photoAspect.takeIf { it.isFinite() && it > 0.01f } ?: 1.5f
    val defaultPct = if (edge == WatermarkEdge.Top || edge == WatermarkEdge.Bottom) {
        (WatermarkZones.DEFAULT_MARGIN / aspect * 100f).coerceIn(0f, maxPct)
    } else {
        (WatermarkZones.DEFAULT_MARGIN * 100f).coerceIn(0f, maxPct)
    }
    PhoneSliderParamRow(
        label = label,
        value = WatermarkZones.marginPct(spec, photoAspect, edge).coerceIn(0f, maxPct),
        range = 0f..maxPct,
        defaultValue = defaultPct,
        step = 0.1f,
        unit = "%",
        decimals = 1,
        onValueChange = { next ->
            val (dx, dy) = WatermarkZones.positionFor(spec, photoAspect, edge, next)
            onPosition(dx, dy)
        },
    )
}

/** 不可调的数值行：同一行高、同一标签位，数值写「固定 / 无空间」，轨道置灰。 */
@Composable
private fun FixedSliderRow(label: String, valueText: String, reason: String) {
    PhoneSliderParamRow(
        label = label,
        value = 0f,
        range = 0f..1f,
        defaultValue = 0f,
        step = null,
        unit = null,
        decimals = 0,
        onValueChange = {},
        enabled = false,
        disabledReason = reason,
        valueText = valueText,
    )
}

// ---------------------------------------------------------------------------
// 兜底
// ---------------------------------------------------------------------------

@Composable
private fun EmptyPageNote(page: InspectorPage) {
    Text(
        text = when (page) {
            InspectorPage.Style -> "这套边框没有可调的样式参数。"
            InspectorPage.Text -> "这套边框没有可调的文字参数。"
            InspectorPage.Logo -> "这套边框没有可调的 Logo 参数。"
            InspectorPage.Watermark -> "还没有图片水印。"
        },
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = FmSpacing.md),
    )
}

@Composable
private fun PanelNote(text: String, modifier: Modifier, onRetry: (() -> Unit)?) {
    Column(
        modifier = modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text("重新加载参数", style = FmType.labelMedium, color = FmPalette.primaryStrong)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 控件表（保持既有口径）
// ---------------------------------------------------------------------------

/**
 * Rows that can be drawn at all: no hidden rows, no blank labels, no internal
 * `params.slots` row, and no per-slot generated controls (those are drawn by
 * [NativeTextSlotControls] under their own row).
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

/**
 * 一条参数行的**数值文本**（标签行右端那 15 sp 的内容）。
 *
 * 测试用它断言「每一行都有标签、有数值、有轨道」里的「有数值」：数值格式化是纯函数，
 * 所以这条断言不需要跑 Compose。
 */
internal fun phoneParamValueLabel(control: InspectorControl, session: DesignSession): String =
    when (control.type) {
        ControlType.Slider -> {
            val raw = (session.value(control) as? Number)?.toFloat()
                ?: (control.initial as? ControlValue.Num)?.value
                ?: 0f
            val scale = control.scale()
            phoneParamNumberText(
                raw * scale,
                phoneDecimalsOf(control.step?.let { it * scale }),
                control.unit,
            )
        }
        ControlType.Switch -> if (session.value(control) as? Boolean ?: false) "开" else "关"
        ControlType.Select, ControlType.Color -> {
            val raw = session.value(control)?.toString().orEmpty()
            control.options.firstOrNull { it.value == raw }?.display ?: raw
        }
        ControlType.Text -> session.value(control)?.toString().orEmpty()
        ControlType.Brand -> ""
    }
