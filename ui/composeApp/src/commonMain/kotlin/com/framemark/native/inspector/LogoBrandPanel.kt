package com.framemark.native.inspector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.fmElevated
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.UserLogo
import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoImportStatus
import com.framemark.native.workflow.UserLogoLibrary
import com.framemark.native.workflow.UserLogoRepository
import com.framemark.native.workflow.UserLogoSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The 品牌 Logo page of the shared inspector (V06, both platforms).
 *
 * One column, fixed order:
 *  1. 调整字标: this card's non-Brand Logo controls (位置 / 大小 / 显示 Logo);
 *  2. 更换 Logo (the only entry into the brand library — an overlay on the phone),
 *     自动匹配 (writes `auto` in place) and 我的 Logo (the machine-wide user library);
 *  3. a one-line `当前：品牌 · 款式` caption.
 *
 * Only tapping one specific cut writes `session.set(brandControl, cut.assetKey)`.
 * Browsing a brand never changes the applied value: the brand page only sets the
 * local `browseBrandKey` filter and returns.
 *
 * This composable is deliberately a plain `Column` — it is hosted inside the
 * phone sheet's single scroll region and the desktop inspector scroll, so a
 * nested vertical lazy list here would be unbounded. 手机端的品牌选择器是一层盖在
 * 画布上的覆盖层面板（由外壳绘制，舞台仍可见）；桌面右栏仍是内联品牌页。
 */
@Composable
fun LogoBrandPanel(
    model: InspectorCardModel,
    session: DesignSession,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    /**
     * 舞台最近一次渲染报告的文本 slot。显式传进来（而不是只靠 CompositionLocal）：
     * slot 是异步到的，显式参数能保证槽表更新后自动匹配重新解析。
     */
    textSlots: List<com.framemark.native.workflow.TextSlot> = emptyList(),
    /**
     * 手机端传「打开品牌选择器」的回调：覆盖层面的容器必须是整个编辑器（底板只画
     * 自己的高度，盖不住舞台），所以这里只发请求，由外壳绘制。传 null 时保持桌面
     * 右栏的内联品牌页 / 版本页；两条路径写入同一个 Brand 控件值。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    /**
     * 手机底板分页：只画这些控件键的行 / 只画说明 / 只画三个动作（见 `CuratedLogoPane`）。
     * 桌面右栏不传，保持整页。
     */
    onlyKeys: Set<String>? = null,
    showNotice: Boolean = true,
    showActions: Boolean = true,
    grouped: Boolean = true,
) {
    // WP-V3: the page body moved to `LogoPanes.kt` so the desktop rail and the
    // phone sheet render the showcase structure (`logo.js` lgPaneLive / brand /
    // version pages). Signature and behaviour inputs are unchanged; the legacy
    // body below stays compiled (still reachable by tests via `logoBrandMode`)
    // but is no longer the rendered page.
    CuratedLogoPane(
        model = model,
        session = session,
        dense = dense,
        modifier = modifier,
        textSlots = textSlots,
        onOpenBrandPicker = onOpenBrandPicker,
        onlyKeys = onlyKeys,
        showNotice = showNotice,
        showActions = showActions,
        grouped = grouped,
    )
}

@Composable
private fun BrandedLogoPanel(
    brandControl: InspectorControl,
    session: DesignSession,
    adjustments: List<InspectorControl>,
    modifier: Modifier,
    dense: Boolean,
    userSupported: Boolean,
) {
    val raw = session.value(brandControl) as? String ?: ""
    val isAuto = isAutoMatchBrandValue(raw)
    val userRepo = UserLogoEnv.repository
    var userTab by remember(brandControl.key) {
        mutableStateOf(userSupported && UserLogoLibrary.isUserLogo(raw))
    }
    var catalog by remember { mutableStateOf<List<LogoBrand>?>(null) }
    LaunchedEffect(Unit) { catalog = LogoBrandCatalog.load() }
    var browseBrandKey by remember(brandControl.key) { mutableStateOf<String?>(null) }
    var brandPageOpen by remember(brandControl.key) { mutableStateOf(false) }

    val loaded = catalog
    val appliedBrand = loaded?.let { logoBrandForAssetKey(it, raw) }
    val appliedVersion = loaded?.let { logoVersionForAssetKey(it, raw) }
    // Browsing wins while the user compares brands; otherwise show the brand of
    // the applied cut so the list is useful straight after a selection.
    val browsingBrand = loaded?.let { brands ->
        brands.firstOrNull { it.key == browseBrandKey } ?: appliedBrand
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("品牌 Logo", style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(4.dp))
        // Three layers, stated once: 品牌 → 版本 → 款式.
        LogoBreadcrumb()
        Spacer(Modifier.height(if (dense) FmSpacing.sm else FmSpacing.md))
        if (userSupported) {
            LogoLibraryTabs(
                userSelected = userTab,
                dense = dense,
                onBrand = { userTab = false },
                onUser = { userTab = true },
            )
            Spacer(Modifier.height(FmSpacing.sm))
        }
        if (!userSupported && UserLogoLibrary.isUserLogo(raw)) {
            Text(
                text = "当前边框不支持用户 Logo；请改选品牌库字标。",
                style = FmType.labelSmall,
                color = FmPalette.error,
                modifier = Modifier.padding(bottom = FmSpacing.xs),
            )
        }
        if (userTab) {
            UserLogoPanel(
                control = brandControl,
                session = session,
                repo = userRepo,
                dense = dense,
            )
            if (adjustments.isNotEmpty()) {
                LogoAdjustments(controls = adjustments, session = session, dense = dense)
            }
        } else {
        BrandEntryRow(
            brand = browsingBrand,
            loading = loaded == null,
            catalogEmpty = loaded?.isEmpty() == true,
            appliedLabel = when {
                appliedBrand != null && appliedVersion != null -> "${appliedBrand.label} · ${appliedVersion.name}"
                isAuto -> "自动匹配（按照片品牌）"
                else -> null
            },
            dense = dense,
            onOpenBrandPage = { brandPageOpen = true },
        )
        LogoDivider()
        AutoMatchRow(
            isAuto = isAuto,
            dense = dense,
            onEnable = { session.set(brandControl, "") },
        )
        if (isAuto) {
            Text(
                text = "选择具体版本可覆盖自动匹配。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = FmSpacing.xxs, bottom = FmSpacing.xs),
            )
        }
        LogoDivider()

        when {
            loaded == null -> LogoHint("正在载入品牌库…", dense)
            loaded.isEmpty() -> LogoHint("暂时无法载入品牌库；自动匹配仍然可用。", dense)
            browsingBrand == null -> LogoHint(
                "在“选择品牌”里挑一个品牌，这里会列出它的具体字标版本。",
                dense,
            )
            else -> {
                Text(
                    text = "字标版本 · ${browsingBrand.label} · ${browsingBrand.cn}",
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceSecondary,
                    modifier = Modifier.padding(top = FmSpacing.sm, bottom = FmSpacing.xxs),
                )
                logoCutRows(browsingBrand).forEach { row ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LogoCutRowItem(
                            brandLabel = browsingBrand.label,
                            row = row,
                            selected = row.cut.assetKey == raw,
                            dense = dense,
                            onPick = { session.set(brandControl, row.cut.assetKey) },
                        )
                        Spacer(Modifier.height(FmSpacing.xs))
                    }
                }
            }
        }

        if (adjustments.isNotEmpty()) {
            LogoAdjustments(controls = adjustments, session = session, dense = dense)
        }
        }
    }

    if (brandPageOpen) {
        LogoBrandPage(
            brands = loaded.orEmpty(),
            loading = loaded == null,
            currentBrandKey = browsingBrand?.key,
            appliedAssetKey = raw,
            onBack = { brandPageOpen = false },
            onPick = { brand ->
                // Browse only: the applied cut keeps rendering until the user
                // taps one exact cut back on the main page.
                browseBrandKey = brand.key
                brandPageOpen = false
            },
        )
    }
}

@Composable
private fun LogoLibraryTabs(
    userSelected: Boolean,
    dense: Boolean,
    onBrand: () -> Unit,
    onUser: () -> Unit,
) {
    // V2 shared segmented control (WP-C §4.2): same look as 样式 / 文字 / Logo / 水印.
    FmSegmented(
        options = listOf("品牌库", "我的 Logo"),
        selected = if (userSelected) "我的 Logo" else "品牌库",
        onSelect = { next -> if (next == "我的 Logo") onUser() else onBrand() },
        touch = dense,
    )
}

/** V2 breadcrumb for the three layers the panel keeps. */
@Composable
private fun LogoBreadcrumb() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("1 品牌", "2 版本", "3 款式").forEachIndexed { index, step ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceFaint,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                text = step,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

/**
 * 用户 Logo 库面板：导入入口 + 本机全局库列表 + 选中 / 删除。
 *
 * 桌面右栏与手机底板共用这一份（`LogoPanes.kt` 的 [LogoPaneBody.UserLibrary] 正文），
 * 不是第二个选择器：两者都读 [UserLogoEnv.repository]，选中都写
 * `session.set(control, entry.designValue)`，所以导入与选中走的是同一条会话路径。
 *
 * [selectable] 只裁「选中」：库本身是全局的，列出 / 导入 / 删除与当前边框无关。
 * 入口那一下由面板拦（`userLogoActionDecision`：画不出用户 Logo 的边框上「我的 Logo」
 * 动作置灰并给出原因），所以正常路径下面板不会以 `selectable = false` 打开。
 */
@Composable
internal fun UserLogoPanel(
    control: InspectorControl,
    session: DesignSession,
    repo: UserLogoRepository?,
    dense: Boolean,
    selectable: Boolean = true,
) {
    if (repo == null) {
        LogoHint("用户 Logo 库未安装。", dense)
        return
    }
    val revision = UserLogoLibrary.revision
    val raw = session.value(control) as? String ?: ""
    val selectedId = UserLogoLibrary.idOf(raw)
    var entries by remember(repo) { mutableStateOf<List<UserLogo>?>(null) }
    var loadError by remember(repo) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo, revision) {
        entries = runCatching { repo.list() }
            // Raw `it.message` was shown verbatim; the library is user copy only.
            .onFailure { loadError = "用户 Logo 库读取失败，请重试。" }
            .getOrElse { emptyList() }
    }

    Text(
        text = "用户 Logo 只从本机用户库选择；缺失时显示“未收录”，不会替换成品牌库图片。",
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(bottom = FmSpacing.xs),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.primarySoft)
            .clickable(enabled = UserLogoEnv.picker != null) {
                UserLogoImportStatus.clear()
                UserLogoEnv.picker?.invoke()
            }
            .semantics { contentDescription = "导入用户 Logo" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (UserLogoEnv.picker == null) "当前平台不支持导入" else "导入 PNG / WebP Logo",
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
        )
    }
    UserLogoImportStatus.error?.let {
        Text(
            text = it,
            style = FmType.labelSmall,
            color = FmPalette.error,
            modifier = Modifier.padding(top = FmSpacing.xxs),
        )
    }
    loadError?.let {
        Text(
            text = it,
            style = FmType.labelSmall,
            color = FmPalette.error,
            modifier = Modifier.padding(top = FmSpacing.xxs),
        )
    }
    LogoDivider()
    val list = entries
    when {
        list == null -> LogoHint("正在读取用户 Logo…", dense)
        list.isEmpty() -> {
            LogoHint("还没有用户 Logo。导入一张透明 PNG/WebP 后会出现在这里。", dense)
            if (selectedId != null) {
                Text(
                    text = "所选用户 Logo 未收录；不会替换成品牌库图片。",
                    style = FmType.labelSmall,
                    color = FmPalette.error,
                    modifier = Modifier.padding(top = FmSpacing.xs),
                )
            }
        }
        else -> {
            list.forEach { entry ->
                UserLogoRow(
                    entry = entry,
                    selected = entry.id == selectedId,
                    repo = repo,
                    dense = dense,
                    selectable = selectable,
                    onPick = { session.set(control, entry.designValue) },
                    onDelete = {
                        scope.launch {
                            runCatching { repo.delete(entry.id) }
                                .onSuccess {
                                    // Clear the selection only after the library
                                    // mutation committed; a failed delete must keep
                                    // the current design usable.
                                    val current = UserLogoLibrary.idOf(session.value(control) as? String)
                                    if (current == entry.id) session.set(control, "")
                                }
                                .onFailure {
                                    UserLogoImportStatus.fail("删除失败，请重试。")
                                }
                        }
                    },
                )
            }
            if (selectedId != null && list.none { it.id == selectedId }) {
                Text(
                    text = "所选用户 Logo 未收录；不会替换成品牌库图片。",
                    style = FmType.labelSmall,
                    color = FmPalette.error,
                    modifier = Modifier.padding(top = FmSpacing.xs),
                )
            }
        }
    }
}

@Composable
private fun UserLogoRow(
    entry: UserLogo,
    selected: Boolean,
    repo: UserLogoRepository,
    dense: Boolean,
    /** false = 当前边框画不出用户 Logo：行仍可看 / 可删，只是选中不写入。 */
    selectable: Boolean,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(entry.id, UserLogoLibrary.revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.id, UserLogoLibrary.revision) {
        val asset = runCatching { repo.load(entry.id) }.getOrNull()
        bitmap = withContext(Dispatchers.Default) {
            runCatching { asset?.bytes?.decodeToImageBitmap() }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 56.dp else 48.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surfaceMuted)
            .clickable(enabled = selectable, onClick = onPick)
            .semantics { contentDescription = "用户 Logo：${entry.name}" }
            .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.surface),
            contentAlignment = Alignment.Center,
        ) {
            val art = bitmap
            if (art != null) {
                Image(bitmap = art, contentDescription = entry.name, modifier = Modifier.fillMaxWidth(0.8f).height(24.dp))
            } else {
                Text("未收录", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = entry.name,
            style = FmType.bodyMedium,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                selected -> "已选"
                !selectable -> "不可选"
                else -> "选择"
            },
            style = FmType.labelSmall,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.width(FmSpacing.xs))
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .clickable(onClick = onDelete)
                .semantics { contentDescription = "删除用户 Logo：${entry.name}" }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("删除", style = FmType.labelSmall, color = FmPalette.error)
        }
    }
}

/** 1. 当前浏览品牌 + 选择品牌 ›. Opening the page never changes the applied cut. */
@Composable
private fun BrandEntryRow(    brand: LogoBrand?,
    loading: Boolean,
    catalogEmpty: Boolean,
    appliedLabel: String?,
    dense: Boolean,
    onOpenBrandPage: () -> Unit,
) {
    val preview = rememberLogoPreview(brand?.preview)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 48.dp else 0.dp)
            .fmElevated(FmElevation.card, RoundedCornerShape(FmRadii.input))
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
            .clickable(onClick = onOpenBrandPage)
            .semantics {
                contentDescription = "选择品牌；当前浏览品牌：" +
                    (brand?.let { "${it.label} ${it.cn}" } ?: "未选择")
            }
            .padding(horizontal = FmSpacing.md, vertical = if (dense) 6.dp else FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.surface),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = preview.bitmap
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = brand?.label,
                    modifier = Modifier.fillMaxWidth(0.8f).height(20.dp),
                )
            } else {
                Text(
                    text = brand?.label?.take(2) ?: "—",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text("当前浏览品牌", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            Text(
                text = when {
                    brand != null -> "${brand.label} · ${brand.cn}"
                    loading -> "正在载入品牌库…"
                    catalogEmpty -> "品牌库暂不可用"
                    else -> "自动匹配照片品牌"
                },
                style = FmType.bodyMedium,
                color = if (brand != null) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (appliedLabel != null) {
                Text(
                    text = "已应用：$appliedLabel",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = "选择品牌 ›",
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 2. 自动匹配照片品牌: one row, 已启用 / 启用. */
@Composable
private fun AutoMatchRow(isAuto: Boolean, dense: Boolean, onEnable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 48.dp else 0.dp)
            .clickable(enabled = !isAuto, onClick = onEnable)
            .padding(vertical = if (dense) 6.dp else FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("自动匹配照片品牌", style = FmType.bodyMedium, color = FmPalette.onSurface)
            Text(
                text = "按照片里的品牌自动选择字标。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = if (isAuto) "已启用" else "启用",
            style = FmType.labelMedium,
            color = if (isAuto) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(if (isAuto) FmPalette.primarySoft else FmPalette.surfaceMuted)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 3. One row per exact catalog cut; unavailable rows stay visible but disabled. */
@Composable
private fun LogoCutRowItem(
    brandLabel: String,
    row: LogoCutRow,
    selected: Boolean,
    dense: Boolean,
    onPick: () -> Unit,
) {
    val preview = rememberLogoPreview(row.cut.preview)
    val missing = preview.missing
    val enabled = preview.available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 48.dp else 0.dp)
            .fmElevated(FmElevation.card, RoundedCornerShape(FmRadii.input))
            .clip(RoundedCornerShape(FmRadii.input))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (selected) FmPalette.primary else FmPalette.outline,
                shape = RoundedCornerShape(FmRadii.input),
            )
            .clickable(enabled = enabled, onClick = onPick)
            .semantics {
                contentDescription = buildString {
                    append("字标：")
                    append(brandLabel)
                    append(" ")
                    append(row.version.name)
                    if (row.variantCount > 1) append(" 变体${row.variantIndex}/${row.variantCount}")
                    if (selected) append("，已选") else if (missing) append("，未收录")
                }
            }
            .padding(horizontal = FmSpacing.sm, vertical = if (dense) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 40.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(if (selected) FmPalette.primarySoft else FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = preview.bitmap
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.78f).height(24.dp),
                )
            } else if (missing) {
                Text("未收录", style = FmType.labelSmall, color = FmPalette.error)
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.version.name,
                    style = FmType.bodyMedium,
                    color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(FmSpacing.xs))
                Text(
                    text = when {
                        selected -> "已选"
                        missing -> "未收录"
                        else -> surfaceBadge(row.version.surface)
                    },
                    style = FmType.labelSmall,
                    color = when {
                        selected -> FmPalette.primaryStrong
                        missing -> FmPalette.error
                        else -> FmPalette.onSurfaceMuted
                    },
                )
            }
            Text(
                text = versionSubtitle(row.version.era, row.version.years),
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            if (row.variantCount > 1) {
                Text(
                    text = "变体 ${row.variantIndex}/${row.variantCount}",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

/** 4. 调整字标: this card's non-Brand Logo controls, same rows as every other tab. */
@Composable
private fun LogoAdjustments(
    controls: List<InspectorControl>,
    session: DesignSession,
    dense: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "调整字标",
            style = FmType.titleSmall,
            color = FmPalette.onSurface,
            modifier = Modifier.padding(top = if (dense) FmSpacing.sm else FmSpacing.md),
        )
        controls.forEachIndexed { index, control ->
            if (index > 0) LogoDivider()
            ControlItem(control = control, session = session, dense = dense)
        }
    }
}

/** Brand page: single column, browse only, always has a way back. */
@Composable
private fun LogoBrandPage(
    brands: List<LogoBrand>,
    loading: Boolean,
    currentBrandKey: String?,
    appliedAssetKey: String,
    onBack: () -> Unit,
    onPick: (LogoBrand) -> Unit,
) {
    val currentBrand = brands.firstOrNull { it.key == currentBrandKey }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FmSpacing.md)
                .background(FmPalette.surface, RoundedCornerShape(FmRadii.card))
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                .padding(FmSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = "返回 Logo 页面" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹ 返回 Logo",
                        style = FmType.labelMedium,
                        color = FmPalette.onSurfaceSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = currentBrand?.let { "当前浏览：${it.label}" } ?: "未选择品牌",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(FmSpacing.sm))
            Text("选择品牌", style = FmType.titleMedium, color = FmPalette.onSurface)
            Text(
                text = "浏览只决定主页面列出的版本；返回不会改变已经应用的品牌字标。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.sm))
            when {
                loading -> Text(
                    text = "正在载入品牌库…",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(vertical = FmSpacing.md),
                )
                brands.isEmpty() -> Text(
                    text = "暂时无法载入品牌库。",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(vertical = FmSpacing.md),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(brands, key = { it.key }) { brand ->
                        BrandPageRow(
                            brand = brand,
                            browsing = brand.key == currentBrandKey,
                            applied = brand.versions.any { version ->
                                version.cuts.any { it.assetKey == appliedAssetKey }
                            },
                            onPick = { onPick(brand) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandPageRow(
    brand: LogoBrand,
    browsing: Boolean,
    applied: Boolean,
    onPick: () -> Unit,
) {
    val preview = rememberLogoPreview(brand.preview)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(FmRadii.card))
            .background(if (browsing) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (browsing) FmPalette.primary else FmPalette.outline,
                shape = RoundedCornerShape(FmRadii.card),
            )
            .clickable(onClick = onPick)
            .semantics {
                contentDescription = "浏览品牌：${brand.label} ${brand.cn}" +
                    (if (browsing) "，当前浏览" else "")
            }
            .padding(horizontal = FmSpacing.sm, vertical = FmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = preview.bitmap
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.82f).height(22.dp),
                )
            } else {
                Text(
                    text = brand.label.take(2),
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${brand.label} · ${brand.cn}",
                style = FmType.bodyMedium,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val mergedLabel = if (brand.merged.isNotEmpty()) {
                " · 含 ${brand.merged.joinToString(" / ")}"
            } else {
                ""
            }
            Text(
                text = logoBrandCountLabel(brand) + mergedLabel,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (applied) {
            Text("已选", style = FmType.labelSmall, color = FmPalette.primaryStrong)
            Spacer(Modifier.width(FmSpacing.xs))
        }
        if (browsing) {
            Text("✓", style = FmType.labelMedium, color = FmPalette.primaryStrong)
        }
    }
}

@Composable
private fun FixedLogoNote(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = FmType.bodyMedium, color = FmPalette.onSurface)
        Text(
            text = body,
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(top = FmSpacing.xxs),
        )
    }
}

@Composable
private fun LogoHint(text: String, dense: Boolean) {
    Text(
        text = text,
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(vertical = if (dense) FmSpacing.xs else FmSpacing.sm),
    )
}

@Composable
private fun LogoDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FmPalette.outline),
    )
}

// ---------------------------------------------------------------------------
// Pure state helpers (unit-tested in commonTest; no Compose, no resources)
// ---------------------------------------------------------------------------

/** What the Logo tab can actually do for one card. */
internal enum class LogoBrandMode {
    /** No Logo section and no Brand control: nothing may be offered. */
    Absent,

    /** The template draws a fixed brand mark: explain it, keep the other Logo rows. */
    Fixed,

    /** A brand picker exists: brand page + exact cuts + auto match. */
    Branded,
}

/**
 * Classifies a card's Logo capability. [logoControls] is the card's Logo section
 * (empty when the card has none), [brandControl] is the single `ControlType.Brand`
 * row if the card declares one.
 */
internal fun logoBrandMode(
    brandControl: InspectorControl?,
    logoControls: List<InspectorControl>,
): LogoBrandMode = when {
    brandControl != null -> LogoBrandMode.Branded
    logoControls.any { it.type != ControlType.Brand } -> LogoBrandMode.Fixed
    else -> LogoBrandMode.Absent
}

/** The value the renderer treats as 自动匹配. The picker writes `""`. */
internal fun isAutoMatchBrandValue(raw: String): Boolean = raw.isBlank() || raw == "auto"

/** The brand that owns one exact catalog [assetKey], or null when the key is unknown. */
internal fun logoBrandForAssetKey(brands: List<LogoBrand>, assetKey: String): LogoBrand? {
    if (assetKey.isBlank()) return null
    return brands.firstOrNull { brand ->
        brand.versions.any { version -> version.cuts.any { it.assetKey == assetKey } }
    }
}

/**
 * 品牌键（不是某一枚 cut）落回品牌条目：`PhotoDesign.logo` 的取值域是
 * `auto` / 品牌键（`laika`）/ 精确 cut 键三者的并集，所以只认 cut 键会让
 * **已经生效的品牌**显示成「未收录」（WP-Q D1 的桌面/手机同源症状之一）。
 *
 * 规则照演示稿 `core.js` 的 `brandKey()`：去掉 `_black` / `_white` / `_long`
 * 后缀，再按 `BRAND_ALIAS` 把英文名折回目录键。
 */
internal fun logoBrandForKeyValue(brands: List<LogoBrand>, raw: String): LogoBrand? {
    val trimmed = raw.trim().lowercase()
    if (trimmed.isEmpty()) return null
    val stripped = trimmed.replace(LOGO_TONE_SUFFIX, "")
    val key = BrandKeyAlias[stripped] ?: stripped
    return brands.firstOrNull { it.key.lowercase() == key }
}

/** `core.js` `BRAND_ALIAS`（英文名 → 目录键），另加目录折叠里的 LUMIX。 */
private val BrandKeyAlias = mapOf(
    "leica" to "laika",
    "panasonic" to "panasionic",
    "lumix" to "panasionic",
    "hasselblad" to "hasu",
    "fujifilm" to "fushi",
    "olympus" to "aolinbasi",
    "pentax" to "binde",
)

private val LOGO_TONE_SUFFIX = Regex("_(black|white|long|loing)$")

/** The exact version of one applied [assetKey]; never a same-brand substitute. */
internal fun logoVersionForAssetKey(brands: List<LogoBrand>, assetKey: String): LogoVersion? {
    if (assetKey.isBlank()) return null
    brands.forEach { brand ->
        brand.versions.forEach { version ->
            if (version.cuts.any { it.assetKey == assetKey }) return version
        }
    }
    return null
}

/** Total exact cuts a brand offers (one row per cut is rendered). */
internal fun logoCutCount(brand: LogoBrand): Int = brand.versions.sumOf { it.cuts.size }

/** Subtitle of a brand page row: version count and exact cut count. */
internal fun logoBrandCountLabel(brand: LogoBrand): String =
    "${brand.versions.size} 个版本 · ${logoCutCount(brand)} 个字标"

/** One rendered row: an exact cut plus the version that carries it. */
internal data class LogoCutRow(
    val version: LogoVersion,
    val cut: LogoCut,
    val variantIndex: Int,
    val variantCount: Int,
)

/**
 * Flattens a brand into one row per exact cut, in catalog order. A version with
 * several cuts (black/white pairs) yields several rows; no cut is skipped,
 * merged or replaced.
 */
internal fun logoCutRows(brand: LogoBrand): List<LogoCutRow> = buildList {
    brand.versions.forEach { version ->
        version.cuts.forEachIndexed { index, cut ->
            add(
                LogoCutRow(
                    version = version,
                    cut = cut,
                    variantIndex = index + 1,
                    variantCount = version.cuts.size,
                ),
            )
        }
    }
}
