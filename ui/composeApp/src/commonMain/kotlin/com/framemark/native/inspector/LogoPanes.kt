package com.framemark.native.inspector

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.UserLogoLibrary

/**
 * Logo 页（演示稿 `logo.js` 的 `lgPaneLive`）：来源三选一 → 当前 Logo 卡 →
 * 「选择品牌」入口 → 品牌页 → 版本页 → 「显示」组。
 *
 * 与 WP-SL 的旧面板相比，这里只换**页面结构与文案**：品牌库数据、版本/字标
 * 键、预览图加载、`logoBrandMode` 判定全部沿用 `BrandLogoLibrary.kt` 的既有
 * 实现，没有新增数据源，也没有改「浏览不写入、只有点具体版本才写入」的规则。
 */
@Composable
internal fun CuratedLogoPane(
    model: InspectorCardModel,
    session: DesignSession,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
    /** 舞台渲染报告的 slot 表；空时退回 [LocalCuratedTextSlots]。 */
    textSlots: List<com.framemark.native.workflow.TextSlot> = emptyList(),
) {
    val brandControl = model.controls.firstOrNull { it.type == ControlType.Brand }
    val logoControls = model.sections.firstOrNull { it.section == InspectorSection.Logo }?.controls
        ?: model.controls.filter { it.section == InspectorSection.Logo }
    // 「显示 Logo」按演示稿挂在 Logo 页（引擎分区仍是文字），所以按参数名一并收进来。
    val adjustments = (logoControls + model.controls.filter { it.param.endsWith("showLogo") })
        .filterNot { it.type == ControlType.Brand }
        .distinctBy { it.key }
    when (logoBrandMode(brandControl, logoControls)) {
        LogoBrandMode.Absent -> Column(modifier = modifier.fillMaxWidth()) {
            CuratedEmptyPane(
                title = "这套边框未收录可替换 Logo",
                body = "当前模板不支持可替换字标，也没有字标参数。",
            )
        }
        LogoBrandMode.Fixed -> Column(modifier = modifier.fillMaxWidth()) {
            CuratedEmptyPane(
                title = "当前模板使用固定品牌字标",
                body = "品牌字标由模板固定，不能替换为其他品牌；下面的字标参数仍可调整。",
            )
            if (adjustments.isNotEmpty()) {
                CuratedRule()
                CuratedGroup("调整字标") {
                    adjustments.forEach { CuratedLogoAdjustment(it, session) }
                }
            }
        }
        LogoBrandMode.Branded -> BrandedCuratedLogo(
            brandControl = brandControl!!,
            adjustments = adjustments,
            session = session,
            dense = dense,
            modifier = modifier,
            textSlots = textSlots.ifEmpty { LocalCuratedTextSlots.current?.slots.orEmpty() },
        )
    }
}

/** `.seg` 三选一：自动匹配 / 品牌库 / 我的 Logo. */
private val LogoSources = listOf(
    ControlOption("auto", "自动匹配"),
    ControlOption("lib", "品牌库"),
    ControlOption("user", "我的 Logo"),
)

@Composable
private fun BrandedCuratedLogo(
    brandControl: InspectorControl,
    adjustments: List<InspectorControl>,
    session: DesignSession,
    dense: Boolean,
    modifier: Modifier,
    textSlots: List<com.framemark.native.workflow.TextSlot>,
) {
    val raw = session.value(brandControl) as? String ?: ""
    var catalog by remember { mutableStateOf<List<LogoBrand>?>(null) }
    LaunchedEffect(Unit) { catalog = LogoBrandCatalog.load() }
    var page by remember(brandControl.key) {
        mutableStateOf(
            when {
                com.framemark.native.LaunchOptions.logoPage == "brands" -> LogoPage.Brands
                // WP-V4: a pick implies the version page, so the capture hook needs
                // only `FM_LOGO_PICK` to reach 14/16.
                com.framemark.native.LaunchOptions.logoPage == "versions" -> LogoPage.Versions
                !com.framemark.native.LaunchOptions.logoPickVersion.isNullOrBlank() -> LogoPage.Versions
                else -> LogoPage.Logo
            },
        )
    }
    var browseBrandKey by remember(brandControl.key) {
        mutableStateOf(com.framemark.native.LaunchOptions.logoBrowseBrand?.takeIf { it.isNotBlank() })
    }

    val brands = catalog.orEmpty()
    // 取值域是「auto / 品牌键 / 精确 cut 键」：先按 cut 键找版本，找不到再按品牌键
    // 落回品牌条目（`laika` 这类值就是「这本边框用徕卡品牌」）。
    val appliedBrandByCut = logoBrandForAssetKey(brands, raw)
    val appliedBrandByKey = if (appliedBrandByCut == null) logoBrandForKeyValue(brands, raw) else null
    val appliedBrand = appliedBrandByCut ?: appliedBrandByKey
    val appliedVersion = logoVersionForAssetKey(brands, raw)
        ?: appliedBrandByKey?.let { brand ->
            brand.versions.firstOrNull { it.id == brand.canonicalVersion } ?: brand.versions.firstOrNull()
        }
    val isAuto = isAutoMatchBrandValue(raw)
    val isUser = UserLogoLibrary.isUserLogo(raw)
    // 引擎渲染报告里「品牌」那一行的内容 = 照片 EXIF 的 Make。只有真·自动匹配时
    // 才有这一行：一旦指定了某个品牌字标，引擎画的是那枚字标，品牌文字行就没了。
    val photoBrand = LogoPhotoBrand.resolve(textSlots, brands)
    val photoMake = LogoPhotoBrand.makeText(textSlots)
    // 现场诊断（与仓库里其它 FM_ 钩子同风格）：自动匹配/品牌键的分支很难从截图区分，
    // 打一行就够定位「未收录」到底是没读到品牌、还是值不在库里。
    println(
        "FM_LOGO_PANE raw=${raw.take(48)} isAuto=$isAuto isUser=$isUser brands=${brands.size} " +
            "cutKey=${logoBrandForAssetKey(brands, raw)?.key} brandKey=${logoBrandForKeyValue(brands, raw)?.key} " +
            "make=$photoMake photoBrand=${photoBrand?.brand?.key} " +
            "slots=${textSlots.size} " +
            "brandRows=${textSlots.filter { (it.key ?: it.id).contains("make") || (it.key ?: it.id).contains("brand") }
                .joinToString("|") { (it.key ?: it.id) + "=" + it.text.take(16) }}",
    )
    // 自动匹配 = 按照片品牌（演示稿 `lgAutoBrand()`）：品牌行 slot → 品牌库条目。
    // slot 来自 `LocalCuratedTextSlots`（桌面 rail 与手机底板都提供），所以两端口径一致。
    // 值既不是 auto 也不在库里（老工程 / 手改文档）时，引擎同样落到自动解析，
    // 所以界面也按自动匹配显示，不谎报「未收录」。
    val effectivelyAuto = isAuto || appliedBrand == null
    val mode = when {
        isUser -> "user"
        effectivelyAuto -> "auto"
        else -> "lib"
    }

    // WP-V4 evidence hook: click one named version once the catalog is available.
    // The write is the same `session.set(brandControl, assetKey)` a real click does,
    // so undo/modified-count/stage all behave exactly as in the demo.
    var autoPicked by remember(brandControl.key) { mutableStateOf(false) }
    LaunchedEffect(brands, page) {
        val pick = com.framemark.native.LaunchOptions.logoPickVersion
        if (autoPicked || pick.isNullOrBlank() || brands.isEmpty() || page != LogoPage.Versions) {
            return@LaunchedEffect
        }
        val brand = brands.firstOrNull { it.key == browseBrandKey } ?: appliedBrand ?: return@LaunchedEffect
        val version = brand.versions.firstOrNull { it.name.contains(pick, ignoreCase = true) }
            ?: return@LaunchedEffect
        val cut = version.cuts.firstOrNull() ?: return@LaunchedEffect
        autoPicked = true
        browseBrandKey = brand.key
        session.set(brandControl, cut.assetKey)
        if (com.framemark.native.LaunchOptions.logoAfterBack) page = LogoPage.Logo
    }

    when (page) {
        LogoPage.Brands -> {
            CuratedLogoBrandPage(
                brands = brands,
                appliedBrand = appliedBrand,
                browsing = browseBrandKey,
                onBack = { page = LogoPage.Logo },
                onPickBrand = { key ->
                    browseBrandKey = key
                    page = LogoPage.Versions
                },
            )
            return
        }
        LogoPage.Versions -> {
            val brand = brands.firstOrNull { it.key == browseBrandKey } ?: appliedBrand
            // 目录是异步加载的：首帧 brands 为空时不能把页面判成「品牌不存在」，
            // 否则 `FM_LOGO_PAGE=versions` 会被自己弹回品牌页。
            if (brand == null) {
                if (brands.isNotEmpty()) page = LogoPage.Brands
                return
            }
            CuratedLogoVersionPage(
                brand = brand,
                appliedRaw = raw,
                onBack = { page = LogoPage.Brands },
                onPick = { assetKey ->
                    session.set(brandControl, assetKey)
                    page = LogoPage.Logo
                },
            )
            return
        }
        LogoPage.Logo -> Unit
    }

    Column(modifier = modifier.fillMaxWidth()) {
        CuratedGroup(
            title = "Logo 来源",
            right = { CuratedCount(when (mode) { "lib" -> "品牌库"; "user" -> "用户"; else -> "自动" }) },
        ) {
            CuratedSegmented(
                options = LogoSources,
                selected = mode,
                touch = dense,
                onSelect = { next ->
                    when (next) {
                        "auto" -> session.set(brandControl, "auto")
                        "lib" -> page = LogoPage.Brands
                        else -> if (raw.isNotBlank()) session.set(brandControl, raw)
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            CuratedLogoNowCard(
                assetKey = when {
                    isUser -> raw
                    appliedVersion != null && appliedBrand != null -> raw
                    // 自动匹配画的是引擎按照片品牌选中的那枚字标（`_black` app-logo）。
                    effectivelyAuto -> photoBrand?.assetKey
                    else -> raw
                },
                plateFallback = when {
                    // 没有照片品牌可画时，「自动匹配」这四个字比「未收录」准确：
                    // 未收录说的是品牌库里没有，而这里常常是这一帧没报告品牌行。
                    effectivelyAuto && photoBrand == null -> "自动"
                    isUser -> "—"
                    else -> null
                },
                title = when {
                    isUser -> "我的 Logo"
                    appliedVersion != null && appliedBrand != null ->
                        "${logoBrandTitle(appliedBrand)} · ${appliedVersion.name}"
                    // 演示稿 `lgPaneLive`：有照片品牌时显示品牌名。
                    effectivelyAuto -> photoBrand?.brand?.label ?: "自动匹配"
                    else -> "未收录"
                },
                sub = when {
                    isUser -> "我的 Logo · 保存在全局 Logo 库"
                    appliedVersion != null && appliedBrand != null -> "边框自带标志 · 已选版本"
                    effectivelyAuto && photoBrand != null -> "自动匹配 · 按照片品牌 ${photoBrand.brand.label}"
                    // 指定了品牌字标时引擎不画品牌文字行，这一帧就读不到照片品牌。
                    effectivelyAuto -> "自动匹配 · 按边框自己的品牌"
                    // 演示稿：照片品牌“X”未收录，可从品牌库选择（没有 make 时写“未知”）。
                    else -> "照片品牌“${photoMake ?: "未知"}”未收录，可从品牌库选择"
                },
            )
            Spacer(Modifier.height(10.dp))
            if (mode == "user") {
                CuratedSubNote("我的 Logo 在手机端导入后会同步到全局 Logo 库；桌面端这里只做选择。")
            } else {
                CuratedLogoEntry(
                    subtitle = "${brands.size} 个品牌 · ${brands.sumOf { it.versions.size }} 个版本，单列按年代",
                    onClick = { page = LogoPage.Brands },
                )
            }
        }
        if (adjustments.isNotEmpty()) {
            CuratedRule()
            CuratedGroup("显示") {
                orderedLogoAdjustments(adjustments).forEach { CuratedLogoAdjustment(it, session) }
            }
        }
    }
}

/** `lgPaneLive` 显示组 order: 显示 Logo → 位置 → Logo 大小 → 水平位置. */
private fun orderedLogoAdjustments(controls: List<InspectorControl>): List<InspectorControl> {
    val order = listOf("showLogo", "logoPos", "logoScale", "logoCenterX")
    fun rank(control: InspectorControl): Int =
        order.indexOfFirst { control.param.endsWith(it) }.let { if (it < 0) order.size else it }
    return controls.sortedBy(::rank)
}

private enum class LogoPage { Logo, Brands, Versions }

/** `lgp-now` — 64 × 40 plate + title + subtitle. */
@Composable
private fun CuratedLogoNowCard(
    assetKey: String?,
    title: String,
    sub: String,
    plateFallback: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoNowPlate(assetKey = assetKey, fallback = plateFallback)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 「当前 Logo」卡的字标位：入参是**持久化的 cut 键**（`panasionic_black`、
 * `ver:laika:…`），而 [LogoPlate] 认的是打包的 webp 名，中间差一步
 * [LogoBrandCatalog.previewForAssetKey]。这一步以前缺失，于是选中的版本在卡片上
 * 显示「未收录」（WP-Q 的 `desktop-afterback` 就是这样），自动匹配也画不出图。
 */
@Composable
private fun LogoNowPlate(assetKey: String?, fallback: String?) {
    val key = assetKey?.takeIf { it.isNotBlank() && !isAutoMatchBrandValue(it) }
    var resolved by remember(key) { mutableStateOf(false) }
    var preview by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        preview = if (key == null) null else LogoBrandCatalog.previewForAssetKey(key)
        resolved = true
    }
    val placeholder = when {
        !resolved -> "—"
        else -> fallback ?: if (key != null) "未收录" else "自动"
    }
    LogoPlate(preview, 64.dp, 40.dp, fallback = if (preview != null) null else placeholder)
}

/** `lgp-entry` — 「选择品牌」 + subtitle + 「进入 ›」. */
@Composable
private fun CuratedLogoEntry(subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "选择品牌" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "选择品牌",
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
            )
            Text(subtitle, style = CuratedNote, color = FmPalette.onSurfaceMuted, maxLines = 2)
        }
        Text("进入 ›", style = CuratedNote, color = FmPalette.primaryStrong)
    }
}

/** Logo artwork plate — stays a light surface in both appearances. */
@Composable
internal fun LogoPlate(
    assetKey: String?,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    /** 无图时的占位文字；null 时沿用「自动 / 未收录 / —」的原判定。 */
    fallback: String? = null,
) {
    // 自动匹配 没有具体字标资源（由引擎按照片品牌解析），不画「未收录」。
    val isAutoKey = assetKey.isNullOrBlank() || isAutoMatchBrandValue(assetKey)
    val preview = rememberLogoPreview(if (isAutoKey) null else assetKey)
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.78f).height(height * 0.55f),
            )
        } else {
            Text(
                text = when {
                    fallback != null -> fallback
                    isAutoKey -> "自动"
                    preview.missing -> "未收录"
                    else -> "—"
                },
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                color = FmPalette.onSurfaceFaint,
            )
        }
    }
}

/** Brand title with the Chinese name (`lgBrandTitle`). */
internal fun logoBrandTitle(brand: LogoBrand): String =
    if (brand.cn.isBlank()) brand.label else "${brand.label} · ${brand.cn}"

/** One 显示 row: switches, segments and sliders of the Logo section. */
@Composable
internal fun CuratedLogoAdjustment(control: InspectorControl, session: DesignSession) {
    when (control.type) {
        ControlType.Switch -> CuratedSwitchRow(control, session)
        ControlType.Slider -> CuratedSliderRow(
            control = control,
            session = session,
            label = logoAdjustLabel(control),
            hint = logoAdjustHint(control, session),
            unit = control.unit ?: "×",
            decimalPlaces = 1,
        )
        ControlType.Select -> {
            val value = session.value(control).toString()
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    text = logoAdjustLabel(control),
                    style = CuratedNote,
                    color = FmPalette.onSurfaceMuted,
                )
                Spacer(Modifier.height(6.dp))
                CuratedSegmented(
                    options = orderedLogoOptions(control.options),
                    selected = value,
                    onSelect = { session.set(control, it) },
                )
            }
        }
        else -> GenericControlRow(control, session)
    }
}

private fun logoAdjustLabel(control: InspectorControl): String = when {
    control.param.endsWith("logoPos") -> "位置"
    control.param.endsWith("logoScale") -> "Logo 大小"
    control.param.endsWith("logoCenterX") -> "水平位置"
    control.param.endsWith("showLogo") -> "显示 Logo"
    else -> control.label
}

private fun logoAdjustHint(control: InspectorControl, session: DesignSession): String? = when {
    control.param.endsWith("logoCenterX") -> "占底栏宽度"
    else -> null
}

/** 居左 / 居中 / 靠右 order (`logo.js` `lgPaneLive`). */
private fun orderedLogoOptions(options: List<ControlOption>): List<ControlOption> {
    val order = listOf("left", "center", "right")
    val known = order.mapNotNull { key -> options.firstOrNull { it.value == key } }
    return if (known.isEmpty()) options else known + options.filterNot { o -> order.any { it == o.value } }
}

// ---------------------------------------------------------------------------
// 品牌页 / 版本页（`logo.js` lgBrandPage / lgVersionPage），内联在右栏里
// ---------------------------------------------------------------------------

/**
 * `.lgp-top` — 返回 chip 与「当前在哪」**同一行**，面包屑右对齐
 * （`logo.css:8` `.lgp-top` + `.lgp-crumb{margin-left:auto}`）。
 * WP-Q D3：两行堆叠比演示稿多一行，折叠成一行。
 */
@Composable
private fun LogoTop(back: String, crumb: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surfaceMuted)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = back }
                .padding(start = 7.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("‹", style = CuratedNote.copy(fontSize = 13.sp), color = FmPalette.onSurfaceMuted)
            Text(
                text = back,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                color = FmPalette.onSurfaceSecondary,
                maxLines = 1,
            )
        }
        Text(
            text = crumb,
            style = CuratedNote.copy(fontSize = 11.sp),
            color = FmPalette.onSurfaceFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Brand page: search · group chips · one row per brand. */
@Composable
private fun CuratedLogoBrandPage(
    brands: List<LogoBrand>,
    appliedBrand: LogoBrand?,
    browsing: String?,
    onBack: () -> Unit,
    onPickBrand: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("all") }
    val q = query.trim().lowercase()
    val rows = brands.filter { brand ->
        (group == "all" || brand.group == group) &&
            (q.isEmpty() || (brand.label + " " + brand.cn + " " + brand.key).lowercase().contains(q) ||
                brand.versions.any { it.name.lowercase().contains(q) })
    }
    Column(Modifier.fillMaxWidth()) {
        // 演示稿：`kb ? '当前浏览：' + lgBrandTitle : '未选择品牌'`，右对齐在返回 chip 同一行。
        val browsingBrand = brands.firstOrNull { it.key == browsing } ?: appliedBrand
        LogoTop(
            back = "返回 Logo",
            crumb = browsingBrand?.let { "当前浏览：${logoBrandTitle(it)}" } ?: "未选择品牌",
            onBack = onBack,
        )
        CuratedGroup(
            title = "选择品牌",
            right = { CuratedCount("${rows.size} / ${brands.size}") },
        ) {
            LogoSearchField(query, onQueryChange = { query = it })
            Spacer(Modifier.height(8.dp))
            CuratedSegmented(
                options = listOf(
                    ControlOption("all", "全部"),
                    ControlOption("camera", "相机 ${brands.count { it.group == "camera" }}"),
                    ControlOption("phone", "手机 ${brands.count { it.group == "phone" }}"),
                ),
                selected = group,
                onSelect = { group = it },
            )
            Spacer(Modifier.height(10.dp))
            if (rows.isEmpty()) {
                CuratedSubNote("没有匹配的品牌。换个关键词，或切回“全部”。")
            } else {
                rows.forEachIndexed { index, brand ->
                    if (index > 0) CuratedRule()
                    LogoBrandRowItem(
                        brand = brand,
                        browsing = brand.key == browsing,
                        applied = appliedBrand?.key == brand.key,
                        onPick = { onPickBrand(brand.key) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            CuratedSubNote(
                "浏览只决定版本页列出哪个品牌；返回不会改变已经用上的 Logo。一共 " +
                    "${brands.size} 个品牌 · ${brands.sumOf { it.versions.size }} 个版本。",
            )
        }
    }
}

/** `.search` — h34, radius 8, `muted-bg`, magnifier + placeholder. */
@Composable
private fun LogoSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", style = CuratedNote.copy(fontSize = 14.sp), color = FmPalette.onSurfaceMuted)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索品牌，如 Leica / 哈苏 / 柯达",
                    style = CuratedNote,
                    color = FmPalette.onSurfaceFaint,
                    maxLines = 1,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = FmPalette.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FmPalette.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索品牌" },
            )
        }
    }
}

/** One brand row: plate · title · counts · 已选 / browsing check. */
@Composable
private fun LogoBrandRowItem(
    brand: LogoBrand,
    browsing: Boolean,
    applied: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button, onClick = onPick)
            .semantics { contentDescription = "浏览品牌：${logoBrandTitle(brand)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoPlate(brand.preview, 56.dp, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = logoBrandTitle(brand),
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = logoBrandCountLabel(brand) +
                    if (brand.merged.isEmpty()) "" else " · 含 " + brand.merged.joinToString(" / "),
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (applied) {
            CuratedTag("已选", selected = true)
        } else if (browsing) {
            Text("✓", style = CuratedNote, color = FmPalette.primaryStrong)
        }
    }
}

/** `.lgp-tag` — 10.5 / 600 on a soft plate. */
@Composable
internal fun CuratedTag(text: String, selected: Boolean) {
    Text(
        text = text,
        style = TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surfaceMuted)
            .padding(horizontal = 6.dp),
    )
}

/** Version page: one row per exact version, grouped by era. */
@Composable
private fun CuratedLogoVersionPage(
    brand: LogoBrand,
    appliedRaw: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LogoTop(
            back = "返回品牌",
            crumb = "${logoBrandTitle(brand)} · ${brand.versions.size} 个版本",
            onBack = onBack,
        )
        CuratedGroup(
            title = logoBrandTitle(brand),
            right = { CuratedCount("单列 · 按年代") },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoPlate(brand.preview, 56.dp, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = logoBrandTitle(brand),
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                        color = FmPalette.onSurface,
                    )
                    Text(
                        text = logoBrandCountLabel(brand),
                        style = CuratedNote,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            // `logo.js` lgVersionPage 的年代顺序：现行 → 经典 → 历史 → 既有。
            val eraOrder = listOf("current", "classic", "historical", "legacy")
            val eras = brand.versions.map { it.era }.distinct()
                .sortedBy { era -> eraOrder.indexOf(era).takeIf { it >= 0 } ?: eraOrder.size }
            eras.forEach { era ->
                val versions = brand.versions.filter { it.era == era }
                // 演示稿 `.lgp-era`：年代 + 紧跟的计数（`gap:6`），不是右对齐的徽标。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = eraLabel(era),
                        style = CuratedNote.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                        color = FmPalette.onSurfaceSecondary,
                    )
                    CuratedCount(versions.size.toString())
                }
                versions.forEach { version ->
                    val cut = version.cuts.firstOrNull()
                    val selected = cut != null && cut.assetKey == appliedRaw
                    LogoVersionRow(
                        version = version,
                        selected = selected,
                        missing = rememberLogoPreview(cut?.preview).missing,
                        onPick = { cut?.let { onPick(it.assetKey) } },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            CuratedSubNote("点具体版本才会更换 Logo；浏览品牌、返回，都不改变已经用上的版本。")
        }
    }
}

@Composable
private fun LogoVersionRow(
    version: LogoVersion,
    selected: Boolean,
    missing: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(enabled = !missing, role = Role.Button, onClick = onPick)
            .semantics { contentDescription = "字标版本：${version.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoPlate(version.preview, 76.dp, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = version.name,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = if (missing) FmPalette.onSurfaceMuted else FmPalette.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${eraLabel(version.era)} · ${version.years}",
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        CuratedTag(
            text = when {
                selected -> "已选"
                missing -> "未收录"
                else -> surfaceBadge(version.surface)
            },
            selected = selected,
        )
    }
}

