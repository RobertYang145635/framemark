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
) {
    val raw = session.value(brandControl) as? String ?: ""
    var catalog by remember { mutableStateOf<List<LogoBrand>?>(null) }
    LaunchedEffect(Unit) { catalog = LogoBrandCatalog.load() }
    var page by remember(brandControl.key) {
        mutableStateOf(
            when (com.framemark.native.LaunchOptions.logoPage) {
                "brands" -> LogoPage.Brands
                "versions" -> LogoPage.Versions
                else -> LogoPage.Logo
            },
        )
    }
    var browseBrandKey by remember(brandControl.key) { mutableStateOf<String?>(null) }

    val brands = catalog.orEmpty()
    val appliedBrand = logoBrandForAssetKey(brands, raw)
    val appliedVersion = logoVersionForAssetKey(brands, raw)
    val isAuto = isAutoMatchBrandValue(raw)
    val isUser = UserLogoLibrary.isUserLogo(raw)
    val mode = when {
        isUser -> "user"
        isAuto || appliedBrand == null -> "auto"
        else -> "lib"
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
            if (brand == null) { page = LogoPage.Brands; return }
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
                assetKey = raw,
                title = when {
                    isUser -> "我的 Logo"
                    appliedVersion != null && appliedBrand != null ->
                        "${logoBrandTitle(appliedBrand)} · ${appliedVersion.name}"
                    isAuto -> "自动匹配"
                    else -> "未收录"
                },
                sub = when {
                    isUser -> "我的 Logo · 保存在全局 Logo 库"
                    appliedVersion != null && appliedBrand != null -> "边框自带标志 · 已选版本"
                    isAuto -> "自动匹配 · 按边框自己的品牌"
                    else -> "参数不在品牌库里，不会用同品牌别的图代替"
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
private fun CuratedLogoNowCard(assetKey: String, title: String, sub: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoPlate(assetKey, 64.dp, 40.dp)
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
internal fun LogoPlate(assetKey: String?, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
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

/** `.lgp-back` — 「‹ 返回」 breadcrumb. */
@Composable
private fun LogoBack(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("‹", style = CuratedNote.copy(fontSize = 15.sp), color = FmPalette.onSurfaceSecondary)
        Text(text, style = CuratedNote, color = FmPalette.onSurfaceSecondary)
    }
}

@Composable
private fun LogoCrumb(text: String) {
    Text(
        text = text,
        style = CuratedNote,
        color = FmPalette.onSurfaceFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
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
        LogoBack("返回 Logo", onBack)
        LogoCrumb(browsing ?: appliedBrand?.key ?: "未选择品牌")
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
        LogoBack("返回品牌", onBack)
        LogoCrumb("${logoBrandTitle(brand)} · ${brand.versions.size} 个版本")
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
            val eras = brand.versions.map { it.era }.distinct()
            eras.forEach { era ->
                val versions = brand.versions.filter { it.era == era }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = eraLabel(era),
                        style = CuratedNote.copy(fontWeight = FontWeight.SemiBold),
                        color = FmPalette.onSurfaceSecondary,
                        modifier = Modifier.weight(1f),
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

