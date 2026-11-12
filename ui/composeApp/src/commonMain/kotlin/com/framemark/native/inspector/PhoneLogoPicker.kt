package com.framemark.native.inspector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.ColumnRule
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.PrimaryButton
import kotlinx.coroutines.launch

/** 面板高度占编辑器可用高度的比例（产品决定：85–90 %）。 */
internal const val PHONE_LOGO_PICKER_PANEL_FRACTION = 0.88f

/**
 * 手机端品牌字标选择器的**覆盖层**（两页，可左右滑动）。
 *
 * 它不是新页面，而是一层盖在画布上的面板：上半条带铺 `FmPalette.scrim`（舞台在
 * 遮罩下仍可见），底部再落一块 88 % 高、圆角朝上的面板。所以「挑品牌」的时候画布
 * 没有离开屏幕，选完关掉就精确回到进来之前的底板档位与工具。
 *
 * 布局只允许两个竖向滚动容器，而且都在 pager 的页面里：
 *  1. 第 1 页的品牌网格 [LazyVerticalGrid]（61 个品牌唯一该滚的地方）；
 *  2. 第 2 页的款式列表 [LazyColumn]——只有品牌版本多到一屏放不下时才真会滚；
 *     常见品牌 2–4 个版本时列表本身不滚动。
 *
 * 其余全是固定 chrome，永不滚动：面板标题行、分类行（全部 / 手机 / 相机）、
 * 第 2 页的品牌行、底部「添加 <品牌>」主按钮与脚注。左右滑动代替在矮面板里翻找。
 *
 * 取值与内联版本页走同一个写入口：[onApply] 收到的就是所选 cut 的 `assetKey`，
 * 调用方照旧 `session.set(brandControl, assetKey)`。
 *
 * 宿主：外壳（`PhoneEditorScreen`）把它画在整个编辑器之上。桌面右栏不走这里——
 * `InspectorScreen` 用内联品牌页/版本页，`onOpenBrandPicker` 为 null。
 */
@Composable
internal fun PhoneLogoPickerOverlay(
    /** 已生效的品牌控件值（`auto` / 品牌键 / 精确 cut 键），用来预选与标「已选」。 */
    appliedAssetKey: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 目录只在选择器打开时读（`LogoBrandCatalog` 自己带缓存）；第一帧 null 表示
    // 「还在载入」，与「目录是空的」是两件事。
    var catalog by remember { mutableStateOf<List<LogoBrand>?>(null) }
    LaunchedEffect(Unit) { catalog = LogoBrandCatalog.load() }
    val scrimInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelHeight = maxHeight * PHONE_LOGO_PICKER_PANEL_FRACTION
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部条带 = 遮罩下的舞台。点它就关掉选择器，和标题行的「关闭」同一结果；
            // 它只占面板以上那条带，所以点面板本身绝不会误关。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(FmPalette.scrim)
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "关闭品牌选择" },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    .clip(RoundedCornerShape(topStart = FmRadii.sheet, topEnd = FmRadii.sheet))
                    .background(FmPalette.canvas),
            ) {
                PhoneLogoPickerPanel(
                    brands = catalog.orEmpty(),
                    loading = catalog == null,
                    appliedAssetKey = appliedAssetKey,
                    onDismiss = onDismiss,
                    onApply = onApply,
                )
            }
        }
    }
}

/**
 * 面板内容：固定标题行 + 两页 pager。高度由 [PhoneLogoPickerOverlay] 决定，内容
 * 本身不假设塞满整块屏幕。
 */
@Composable
private fun PhoneLogoPickerPanel(
    brands: List<LogoBrand>,
    /** 目录还在异步加载（首帧 brands 为空不是「没有品牌」）。 */
    loading: Boolean,
    /** 已生效的品牌控件值（`auto` / 品牌键 / 精确 cut 键），用来预选与标「已选」。 */
    appliedAssetKey: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var model by remember { mutableStateOf(PhoneLogoPickerState.initial()) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = PhoneLogoPickerState.pageForStage(model.stage),
    ) { PhoneLogoPickerState.PAGE_COUNT }
    // 页码是阶段的唯一事实来源：往左滑回品牌页时阶段跟着回来。
    LaunchedEffect(pagerState.currentPage) {
        model = PhoneLogoPickerState.onPage(model, pagerState.currentPage)
    }
    val categories = remember(brands) { PhoneLogoPickerState.categories(brands) }
    val gridBrands = remember(brands, model.categoryKey, model.query) {
        PhoneLogoPickerState.filteredBrands(brands, model.categoryKey, model.query)
    }
    val recentKeys = PhoneLogoRecents.keys
    val recentBrands = remember(brands, recentKeys) {
        PhoneLogoPickerState.recentBrands(brands, recentKeys)
    }
    val pickedBrand = brands.firstOrNull { it.key == model.brandKey }
    val appliedBrand = logoBrandForAssetKey(brands, appliedAssetKey)
        ?: logoBrandForKeyValue(brands, appliedAssetKey)

    fun goTo(stage: PhoneLogoPickerStage) {
        scope.launch {
            pagerState.animateScrollToPage(PhoneLogoPickerState.pageForStage(stage))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PickerHeader(onClose = onDismiss)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = PhoneLogoPickerState.canDrag(model),
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (PhoneLogoPickerState.stageForPage(page)) {
                PhoneLogoPickerStage.Brands -> PickerBrandsPage(
                    brands = gridBrands,
                    categories = categories,
                    categoryKey = model.categoryKey,
                    query = model.query,
                    recents = if (model.query.isBlank()) recentBrands else emptyList(),
                    appliedBrandKey = appliedBrand?.key,
                    loading = loading,
                    catalogEmpty = brands.isEmpty(),
                    onCategory = { key -> model = PhoneLogoPickerState.pickCategory(model, key) },
                    onQuery = { text -> model = PhoneLogoPickerState.pickQuery(model, text) },
                    onBrand = { brand ->
                        model = PhoneLogoPickerState.pickBrand(model, brand, appliedAssetKey)
                        goTo(PhoneLogoPickerStage.Styles)
                    },
                )

                PhoneLogoPickerStage.Styles -> {
                    if (pickedBrand == null) {
                        PickerEmptyNote("先在品牌页挑一个品牌。")
                    } else {
                        PickerStylesPage(
                            brand = pickedBrand,
                            selectedCutKey = model.cutKey,
                            onBack = {
                                model = PhoneLogoPickerState.backToBrands(model)
                                goTo(PhoneLogoPickerStage.Brands)
                            },
                            onCut = { cutKey -> model = PhoneLogoPickerState.pickCut(model, cutKey) },
                            onApply = {
                                PhoneLogoPickerState.selectionValue(model, pickedBrand)?.let { value ->
                                    // 只有真正写到设计上的品牌才算「最近使用」。
                                    PhoneLogoRecents.record(pickedBrand.key)
                                    onApply(value)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}


/** 固定标题行：品牌预设 + 用途说明 + 关闭。 */
@Composable
private fun PickerHeader(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(FmPalette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("品牌预设", style = FmType.titleMedium, color = FmPalette.onSurface)
                Spacer(Modifier.height(FmSpacing.xxs))
                Text(
                    text = "仅用于标注拍摄器材，商标归各品牌所有",
                    style = CuratedNote,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = "关闭",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "关闭品牌选择" }
                    .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
            )
        }
        ColumnRule()
    }
}

/** 第 1 页：搜索 + 分类 + 最近使用（固定 chrome）+ 品牌网格（唯一可竖向滚动的区域）。 */
@Composable
private fun PickerBrandsPage(
    brands: List<LogoBrand>,
    categories: List<PhoneLogoCategory>,
    categoryKey: String,
    query: String,
    recents: List<LogoBrand>,
    appliedBrandKey: String?,
    loading: Boolean,
    catalogEmpty: Boolean,
    onCategory: (String) -> Unit,
    onQuery: (String) -> Unit,
    onBrand: (LogoBrand) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PickerSearchField(query = query, onQueryChange = onQuery)
        PickerCategoryRow(
            categories = categories,
            categoryKey = categoryKey,
            onCategory = onCategory,
        )
        if (recents.isNotEmpty()) {
            PickerRecentRow(recents = recents, onBrand = onBrand)
        }
        when {
            loading -> PickerEmptyNote("正在载入品牌库…")
            catalogEmpty -> PickerEmptyNote("品牌库暂时不可用，先返回再试。")
            brands.isEmpty() -> PickerEmptyNote(
                if (query.isBlank()) {
                    "这个分类下没有品牌，切回「全部」看看。"
                } else {
                    "没有匹配的品牌，换个关键词。"
                },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = FmSpacing.lg,
                    end = FmSpacing.lg,
                    top = FmSpacing.xs,
                    bottom = FmSpacing.xxl,
                ),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                items(brands, key = { it.key }) { brand ->
                    PickerBrandCell(
                        brand = brand,
                        applied = brand.key == appliedBrandKey,
                        onClick = { onBrand(brand) },
                    )
                }
            }
        }
    }
}

/** 搜索框：固定 chrome，逐字过滤品牌英文名 / 中文名。 */
@Composable
private fun PickerSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FmSpacing.lg, end = FmSpacing.lg, top = FmSpacing.md)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", style = CuratedNote.copy(fontSize = 15.sp), color = FmPalette.onSurfaceMuted)
        Spacer(Modifier.width(FmSpacing.sm))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索品牌，如 Leica / 哈苏 / 柯达",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceFaint,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
                cursorBrush = SolidColor(FmPalette.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索品牌" },
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(FmSpacing.xs))
            Text(
                text = "清除",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = "清除搜索" }
                    .padding(horizontal = FmSpacing.xs, vertical = FmSpacing.xs),
            )
        }
    }
}

/** 最近使用：最近点过「添加」的品牌，等宽 chip，固定 chrome。 */
@Composable
private fun PickerRecentRow(recents: List<LogoBrand>, onBrand: (LogoBrand) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FmSpacing.lg, end = FmSpacing.lg, top = FmSpacing.md),
    ) {
        Text("最近使用", style = CuratedGroupTitle, color = FmPalette.onSurfaceSecondary)
        Spacer(Modifier.height(FmSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            recents.forEach { brand ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surface)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
                        .clickable(role = Role.Button) { onBrand(brand) }
                        .semantics { contentDescription = "最近使用：${logoBrandTitle(brand)}" }
                        .padding(horizontal = FmSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = brand.label,
                        style = FmType.labelMedium,
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 分类行：全部 / 手机 / 相机，计数来自目录；三项等宽，永不滚动。 */
@Composable
private fun PickerCategoryRow(
    categories: List<PhoneLogoCategory>,
    categoryKey: String,
    onCategory: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        categories.forEach { category ->
            val active = category.key == categoryKey
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (active) FmPalette.primary else FmPalette.surface)
                    .border(
                        width = 1.dp,
                        color = if (active) FmPalette.primary else FmPalette.outline,
                        shape = RoundedCornerShape(FmRadii.pill),
                    )
                    .clickable(role = Role.Tab) { if (!active) onCategory(category.key) }
                    .semantics {
                        selected = active
                        contentDescription = "${category.label} ${category.count}"
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = category.label,
                    style = FmType.labelMedium,
                    color = if (active) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(FmSpacing.xs))
                Text(
                    text = category.count.toString(),
                    style = FmType.labelSmall,
                    color = if (active) FmPalette.onPrimary else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 品牌格：标志位 + 名称（有中文名的品牌两行都显示）。 */
@Composable
private fun PickerBrandCell(brand: LogoBrand, applied: Boolean, onClick: () -> Unit) {
    val preview = rememberLogoPreview(brand.preview)
    val shape = RoundedCornerShape(FmRadii.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (applied) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (applied) FmPalette.primary else FmPalette.outline,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "品牌：${logoBrandTitle(brand)}" }
            .padding(FmSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.8f).height(24.dp),
                )
            } else {
                Text(brand.label.take(2), style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            text = brand.label,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (brand.cn.isNotBlank()) {
            Text(
                text = brand.cn,
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 第 2 页：品牌行（固定）+ 全部字标款式（按版本分组，只有放不下时才滚）+
 * 底部固定的「添加」主按钮与脚注。
 */
@Composable
private fun PickerStylesPage(
    brand: LogoBrand,
    selectedCutKey: String?,
    onBack: () -> Unit,
    onCut: (String) -> Unit,
    onApply: () -> Unit,
) {
    val rows = remember(brand) { PhoneLogoPickerState.styleRows(brand) }
    // 一个版本一行；黑/白两枚 cut 并排出现，不再折叠成第一枚。
    val versions = remember(rows) { rows.groupBy { it.version.id }.values.toList() }
    val selected = rows.firstOrNull { it.cut.assetKey == selectedCutKey }
    val selectedPreview = rememberLogoPreview(selected?.cut?.preview)
    val applyEnabled = selected != null && !selectedPreview.missing

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FmPalette.surface)
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoPlate(brand.preview, 56.dp, 36.dp)
            Spacer(Modifier.width(FmSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = logoBrandTitle(brand),
                    style = FmType.titleSmall,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${brand.versions.size} 个版本 · ${rows.size} 个字标",
                    style = CuratedNote,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = "‹ 品牌",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "返回品牌列表" }
                    .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
            )
        }
        ColumnRule()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = FmSpacing.lg,
                end = FmSpacing.lg,
                top = FmSpacing.md,
                bottom = FmSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(FmSpacing.lg),
        ) {
            items(versions, key = { it.first().version.id }) { samples ->
                PickerVersionBlock(
                    version = samples.first().version,
                    samples = samples,
                    selectedCutKey = selectedCutKey,
                    onCut = onCut,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().background(FmPalette.surface)) {
            ColumnRule()
            Column(modifier = Modifier.padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md)) {
                Text(
                    text = when {
                        selected == null -> "还没有选字标款式"
                        selectedPreview.missing -> "所选字标未收录，换一个款式"
                        else -> "将添加：${brand.label} · ${selected.version.name} · " +
                            PhoneLogoPickerState.toneLabel(selected.cut)
                    },
                    style = CuratedGroupTitle,
                    color = if (selected != null && selectedPreview.missing) {
                        FmPalette.error
                    } else if (selected == null) {
                        FmPalette.onSurfaceMuted
                    } else {
                        FmPalette.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(FmSpacing.sm))
                PrimaryButton(
                    text = "添加 ${brand.label}",
                    onClick = onApply,
                    enabled = applyEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = "黑标只用于浅色底，白标只用于深色底。",
                    style = CuratedNote,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 一个版本：名称/年代一行，下面并排它的每一枚 cut。 */
@Composable
private fun PickerVersionBlock(
    version: LogoVersion,
    samples: List<LogoCutRow>,
    selectedCutKey: String?,
    onCut: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = version.name,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = "${eraLabel(version.era)} · ${version.years}",
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            samples.forEach { row ->
                PickerCutSwatch(
                    row = row,
                    selected = row.cut.assetKey == selectedCutKey,
                    onPick = { onCut(row.cut.assetKey) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一枚 cut 的色板：预览 + 黑标/白标/彩标 + 已选。 */
@Composable
private fun PickerCutSwatch(
    row: LogoCutRow,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = rememberLogoPreview(row.cut.preview)
    val missing = preview.missing
    val shape = RoundedCornerShape(FmRadii.tile)
    Column(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (selected) FmPalette.primary else FmPalette.outline,
                shape = shape,
            )
            .clickable(enabled = !missing, role = Role.Button, onClick = onPick)
            .semantics {
                this.selected = selected
                contentDescription = "字标：${row.version.name} " + PhoneLogoPickerState.toneLabel(row.cut)
            }
            .padding(FmSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.8f).height(22.dp),
                )
            } else {
                Text(
                    text = if (missing) "未收录" else "…",
                    style = FmType.labelSmall,
                    color = if (missing) FmPalette.error else FmPalette.onSurfaceMuted,
                )
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = PhoneLogoPickerState.toneLabel(row.cut),
                style = FmType.labelMedium,
                color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("已选", style = FmType.labelSmall, color = FmPalette.primaryStrong, maxLines = 1)
            }
        }
    }
}

/** 空态/加载态：一段说明，不是滚动容器。 */
@Composable
private fun PickerEmptyNote(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(FmSpacing.xxl)) {
        Text(text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
    }
}
