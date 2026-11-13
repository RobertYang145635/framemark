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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.GhostButton
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoLibrary
import com.framemark.native.workflow.UserLogoSupport
import com.framemark.native.product.FmLog

/**
 * Logo 页（演示稿 `logo.js` 的 `lgPaneLive`，按 UI-v2 复测的反馈重排）。
 *
 * 面板正文 = **调整字标**（位置 / 大小 / 显示 Logo）+ **三个动作**（更换 Logo /
 * 自动匹配 / 我的 Logo）+ 一行 `当前：品牌 · 款式`。落地永远是这一块：进来先看见
 * 「现在用的是哪个字标」，并且当场就能调；品牌库是一层盖在画布上的覆盖层（手机）
 * 或内联品牌页（桌面），只能由「更换 Logo」点进去。
 *
 * 数据与写入点一个没换：品牌库数据、版本/字标键、预览图加载、`logoBrandMode` 判定
 * 沿用 `BrandLogoLibrary.kt`；仍是「浏览不写入、只有点具体字标才写入」。
 */
@Composable
internal fun CuratedLogoPane(
    model: InspectorCardModel,
    session: DesignSession,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
    /** 舞台渲染报告的 slot 表；空时退回 [LocalCuratedTextSlots]。 */
    textSlots: List<com.framemark.native.workflow.TextSlot> = emptyList(),
    /**
     * 手机端传一个「打开」回调：品牌入口要开的是**盖在画布上的覆盖层面板**，而它
     * 必须由外壳画在整个编辑器之上（底板只画自己的高度，画不下覆盖层）。传 null
     * 时保持桌面右栏的内联品牌页 / 版本页；两条路径写的是同一个 Brand 控件值。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    /**
     * 手机底板分页用：只画这些控件键的行（null = 全部）。
     *
     * 每组一页时，一组只放「调整字标」里的两行；品牌动作是另一组。这一个参数让
     * 同一份面板能被切成两页，而品牌 / 版本 / 我的 Logo 的写入路径一条都没换。
     */
    onlyKeys: Set<String>? = null,
    /** 只画「没有可替换字标」的说明块（手机把它当成一页）。 */
    showNotice: Boolean = true,
    /** 只画三个动作 + 一行说明（手机把它当成一页）。 */
    showActions: Boolean = true,
    /**
     * true = 桌面右栏的组结构（`CuratedGroup` + 组头）；false = 手机的裸块
     * （组头由分页器的小圆点接替，60 dp 的组头让给内容）。
     */
    grouped: Boolean = true,
) {
    val brandControl = model.controls.firstOrNull { it.type == ControlType.Brand }
    val logoControls = model.sections.firstOrNull { it.section == InspectorSection.Logo }?.controls
        ?: model.controls.filter { it.section == InspectorSection.Logo }
    // 「显示 Logo」按演示稿挂在 Logo 页（引擎分区仍是文字），所以按参数名一并收进来。
    val adjustments = (logoControls + model.controls.filter { it.param.endsWith("showLogo") })
        .filterNot { it.type == ControlType.Brand }
        .distinctBy { it.key }
        .filter { onlyKeys == null || it.key in onlyKeys }
    // 这张边框能不能真的画用户 Logo：只认平台渲染器装进来的那一个探针
    // （`UserLogoSupport` ← `DesktopNativeRender` / `AndroidNativeRender` 的
    // `supportsUserLogo`），不按卡片名单另写一份判断。
    val supportsUserLogo = UserLogoSupport.supports(model.cardId)
    when (logoBrandMode(brandControl, logoControls)) {
        LogoBrandMode.Absent -> if (showNotice) {
            Column(modifier = modifier.fillMaxWidth()) {
                CuratedEmptyPane(
                    title = "这套边框未收录可替换 Logo",
                    body = "当前模板不支持可替换字标，也没有字标参数。",
                    dense = dense,
                )
            }
        }
        LogoBrandMode.Fixed -> if (showNotice || adjustments.isNotEmpty()) {
            Column(modifier = modifier.fillMaxWidth()) {
                if (showNotice) {
                    CuratedEmptyPane(
                        title = "当前模板使用固定品牌字标",
                        body = "品牌字标由模板固定，不能替换为其他品牌；下面的字标参数仍可调整。",
                        dense = dense,
                    )
                }
                if (adjustments.isNotEmpty()) {
                    if (grouped) {
                        CuratedRule()
                        CuratedGroup("调整字标") {
                            adjustments.forEach { CuratedLogoAdjustment(it, session, adjustments) }
                        }
                    } else {
                        adjustments.forEach { CuratedLogoAdjustment(it, session, adjustments) }
                    }
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
            onOpenBrandPicker = onOpenBrandPicker,
            supportsUserLogo = supportsUserLogo,
            showActions = showActions,
            grouped = grouped,
        )
    }
}

// ---------------------------------------------------------------------------
// Logo 页的视图状态与决策（纯函数：没有 Compose、没有资源，commonTest 直接覆盖）
// ---------------------------------------------------------------------------

/** 写入 Brand 控件的「自动匹配」值。取值域是 `auto` / 品牌键 / 精确 cut 键。 */
internal const val LOGO_SOURCE_AUTO = "auto"

/**
 * Logo 页此刻画哪一块正文。
 *
 * 落地永远是 [Panel]：进来就是「调位置 / 大小 + 三个动作」，画布仍在，手上立刻有活。
 * 品牌库 / 版本库 / 我的 Logo 都只能由**明确的点击**进入，而且各自都带返回入口 ——
 * 选完 / 取消都回得到同一块面板，不会停在列表上，也不会什么都不画。
 */
internal enum class LogoPaneBody {
    /**
     * 落地态：调整字标（位置 / 大小 / 显示 Logo）+ 更换 Logo / 自动匹配 / 我的 Logo
     * + 一行 `当前：…` 说明。这一块是唯一的落地与返回落点。
     */
    Panel,

    /** 品牌库列表（桌面右栏的内联品牌页；手机上只有盖在画布上的覆盖层）。 */
    LibraryList,

    /** 我的 Logo：本机全局用户库，导入与选中都写回同一个 Brand 控件。 */
    UserLibrary,
}

/**
 * 面板的视图状态：只记「现在看哪一块」，**不存已生效值的副本**。
 *
 * 说明行里的品牌 / 版本都由 [logoSummary] 现算，所以选中、取消、目录重新载入之后都
 * 不会留下一份过期的镜像。旧实现用 `segment` 记视图选择、又用 `page` 记列表页，
 * 两个字段可以互相矛盾：选完品牌还停在列表页，或者回到一个谁也不认的段。
 */
internal data class LogoPaneState(
    val body: LogoPaneBody = LogoPaneBody.Panel,
    /** 内联版本页正在看哪个品牌；null = 用已生效值的品牌。 */
    val browseBrandKey: String? = null,
    /** [LogoPaneBody.LibraryList] 的子页：true = 版本页，false = 品牌页。 */
    val versionPage: Boolean = false,
) {
    companion object {
        /** 打开 Logo 工具的第一帧：调整 + 动作。任何已生效值都不会让它落在列表上。 */
        val Landing = LogoPaneState()
    }
}

/** 一次点击的完整结果：新状态 + 要写的值（null = 不改已生效字标）+ 是否开覆盖层。 */
internal data class LogoPaneAction(
    val state: LogoPaneState,
    val write: String? = null,
    val openPicker: Boolean = false,
)

/** 面板上的三个动作；品牌 / 版本列表只从「更换 Logo」开始。 */
internal enum class LogoPanelAction {
    /** 更换 Logo：手机端开盖在画布上的覆盖层，桌面端进内联品牌页。 */
    Replace,

    /** 自动匹配：就地写 `auto`，停在面板上（不导航、不开覆盖层）。 */
    Auto,

    /** 我的 Logo：本机用户库（画不出用户 Logo 的边框由 `userLogoActionDecision` 拦下）。 */
    User,
}

/**
 * 点一个动作之后会发生什么。
 *
 * - [LogoPanelAction.Replace]：手机端只发「开覆盖层」的请求，正文不动 —— 覆盖层选完 /
 *   关掉都回到同一块面板；桌面端进内联品牌页（品牌页再进版本页）。
 * - [LogoPanelAction.Auto]：写 `auto` 并停在面板上，说明行立刻变成「自动匹配」。
 * - [LogoPanelAction.User]：进本机用户库正文（桌面上也是内联），每行只写 Brand 控件。
 */
internal fun logoPanelAction(
    state: LogoPaneState,
    action: LogoPanelAction,
    phonePicker: Boolean,
): LogoPaneAction = when (action) {
    LogoPanelAction.Auto -> LogoPaneAction(
        state = state.copy(body = LogoPaneBody.Panel, versionPage = false),
        write = LOGO_SOURCE_AUTO,
    )
    LogoPanelAction.Replace -> LogoPaneAction(
        state = state.copy(
            body = if (phonePicker) LogoPaneBody.Panel else LogoPaneBody.LibraryList,
            versionPage = false,
        ),
        openPicker = phonePicker,
    )
    LogoPanelAction.User -> LogoPaneAction(
        state = state.copy(body = LogoPaneBody.UserLibrary, versionPage = false),
    )
}

/** 挑中一枚具体字标：写它，正文回到面板（覆盖层与内联版本页共用这一条）。 */
internal fun logoPickCut(state: LogoPaneState, assetKey: String): LogoPaneAction =
    LogoPaneAction(state = state.copy(body = LogoPaneBody.Panel, versionPage = false), write = assetKey)

/**
 * 取消 / 返回面板：一个字都不写，正文回到面板。
 *
 * 和 [logoPickCut] 一起钉死「选完 / 取消都落在同一块面板」：覆盖层是选中了还是被关掉，
 * 回来的都是同一个正文，不会停在列表页，也不会停在谁也不认识的段。
 */
internal fun logoPaneBack(state: LogoPaneState): LogoPaneState =
    state.copy(body = LogoPaneBody.Panel, versionPage = false)

/** 覆盖层的返回值 → 面板要做什么；null / 空白键 = 取消，一个字都不写。 */
internal fun logoPickerResult(state: LogoPaneState, pickedAssetKey: String?): LogoPaneAction =
    pickedAssetKey?.takeIf { it.isNotBlank() }?.let { logoPickCut(state, it) }
        ?: LogoPaneAction(logoPaneBack(state))

/** 内联版本页里点「返回品牌」：列表内部后退，不回到面板。 */
internal fun logoVersionBack(state: LogoPaneState): LogoPaneState = state.copy(versionPage = false)

/** 内联品牌页里点一个品牌：只决定版本页列哪个品牌，不改已生效值。 */
internal fun logoBrowseBrand(state: LogoPaneState, brandKey: String): LogoPaneState =
    state.copy(browseBrandKey = brandKey, versionPage = true)

/** 面板上「当前用的是哪一种字标」。 */
internal enum class LogoSummaryKind { Applied, Auto, User }

/**
 * 「当前字标」摘要：面板上那一条 `当前：…` 说明，不是一页。
 *
 * 三级判定与引擎同口径：`user:…` → 我的 Logo；能在品牌库里落地（精确 cut 键，或品牌键
 * `laika`）→ 品牌 · 版本；其余（`auto` / 空 / 未收录 / 目录还没载入）→ 自动匹配。引擎对
 * 手改出来、库里没有的值同样落到自动解析，所以界面也这么说，不谎报「未收录」。
 */
internal data class LogoSummary(
    val kind: LogoSummaryKind,
    /** `品牌 · 版本` / 自动匹配 / 我的 Logo。 */
    val title: String,
    /** 自动匹配时照片品牌的名字（引擎按它选字标）；没有 → null。 */
    val photoBrandLabel: String? = null,
    /** 已生效值落在的品牌；自动匹配与用户 Logo 时为 null。 */
    val brand: LogoBrand? = null,
)

/**
 * 现算当前字标摘要。[photoBrandLabel] 是舞台渲染报告里的照片品牌
 * （`LogoPhotoBrand`）：自动匹配画的就是它的字标，所以说明行照实说「按照片品牌 X」。
 */
internal fun logoSummary(
    raw: String,
    brands: List<LogoBrand>,
    photoBrandLabel: String? = null,
): LogoSummary {
    if (UserLogoLibrary.isUserLogo(raw)) {
        return LogoSummary(kind = LogoSummaryKind.User, title = "我的 Logo")
    }
    val brand = logoBrandForAssetKey(brands, raw) ?: logoBrandForKeyValue(brands, raw)
    if (brand != null) {
        // 取值域是「精确 cut 键 / 品牌键」：品牌键没有自己的版本，落回最新版本。
        val version = logoVersionForAssetKey(brands, raw)
            ?: brand.versions.firstOrNull { it.id == brand.canonicalVersion }
            ?: brand.versions.firstOrNull()
        return LogoSummary(
            kind = LogoSummaryKind.Applied,
            title = if (version != null) "${logoBrandTitle(brand)} · ${version.name}" else logoBrandTitle(brand),
            brand = brand,
        )
    }
    return LogoSummary(
        kind = LogoSummaryKind.Auto,
        title = "自动匹配",
        photoBrandLabel = photoBrandLabel,
    )
}

/**
 * 动作下面那一行说明：现在生效的是哪一个字标。**一行**，不是一页。
 * 自动匹配连「它正按什么解析」一起说清楚（照片品牌 / 边框自己的品牌）。
 */
internal fun logoCaption(summary: LogoSummary): String = when (summary.kind) {
    LogoSummaryKind.Applied -> "当前：${summary.title}"
    LogoSummaryKind.User -> "当前：我的 Logo · 保存在全局 Logo 库"
    LogoSummaryKind.Auto -> summary.photoBrandLabel?.let { "当前：自动匹配 · 按照片品牌 $it" }
        ?: "当前：自动匹配 · 按边框自己的品牌"
}

/** 组标题右边那个小计数：现在用的是哪一条来源。 */
internal fun logoSourceLabel(summary: LogoSummary): String = when (summary.kind) {
    LogoSummaryKind.Applied -> "品牌库"
    LogoSummaryKind.User -> "用户"
    LogoSummaryKind.Auto -> "自动"
}

/**
 * 取证钩子（只读 [com.framemark.native.LaunchOptions]，UI 永不写）：桌面截图脚本把
 * 面板直接摆到品牌页 / 版本页截图。null = 正常落地（面板）—— 产品的落地态永远不是列表。
 */
private fun logoEvidencePane(): LogoPaneState? {
    val browse = com.framemark.native.LaunchOptions.logoBrowseBrand?.takeIf { it.isNotBlank() }
    val versions = com.framemark.native.LaunchOptions.logoPage == "versions" ||
        !com.framemark.native.LaunchOptions.logoPickVersion.isNullOrBlank()
    return when {
        com.framemark.native.LaunchOptions.logoPage == "brands" ->
            LogoPaneState(body = LogoPaneBody.LibraryList, browseBrandKey = browse)
        versions -> LogoPaneState(body = LogoPaneBody.LibraryList, browseBrandKey = browse, versionPage = true)
        else -> null
    }
}

/**
 * 品牌字标面板（手机底板与桌面右栏同一份）。
 *
 * 正文只有一块，顺序固定：
 *  1. **调整字标**（位置 / 大小 / 显示 Logo）—— 用户是盯着画布调这些的，所以放在最上；
 *  2. **三个动作**：更换 Logo（开覆盖层）/ 自动匹配（就地写 `auto`）/ 我的 Logo（本机库）；
 *  3. 一行 `当前：品牌 · 款式`（自动匹配时说清按什么解析）。
 *
 * 品牌库、版本页、我的 Logo 都只能由动作点进去，各带返回入口，所以落地与返回永远是
 * 这一块 —— 没有分段控件，也没有任何一页列表会自己冒出来。
 */
@Composable
private fun BrandedCuratedLogo(
    brandControl: InspectorControl,
    adjustments: List<InspectorControl>,
    session: DesignSession,
    dense: Boolean,
    modifier: Modifier,
    textSlots: List<com.framemark.native.workflow.TextSlot>,
    onOpenBrandPicker: (() -> Unit)?,
    /** 当前边框能否画用户 Logo（`UserLogoSupport`）；见 [userLogoActionDecision]。 */
    supportsUserLogo: Boolean,
    /** 手机分页：这一页画不画「三个动作」那一块。 */
    showActions: Boolean = true,
    /** false = 手机的裸块（无 `CuratedGroup` 组头）。 */
    grouped: Boolean = true,
) {
    val raw = session.value(brandControl) as? String ?: ""
    var catalog by remember { mutableStateOf<List<LogoBrand>?>(null) }
    LaunchedEffect(Unit) { catalog = LogoBrandCatalog.load() }
    val brands = catalog.orEmpty()
    // 自动匹配 = 按照片品牌（演示稿 `lgAutoBrand()`）：品牌行 slot → 品牌库条目。
    // slot 来自 `LocalCuratedTextSlots`（桌面 rail 与手机底板都提供），所以两端口径一致。
    val photoBrand = LogoPhotoBrand.resolve(textSlots, brands)
    val photoMake = LogoPhotoBrand.makeText(textSlots)
    val summary = logoSummary(raw, brands, photoBrand?.brand?.label)
    // 画不出用户 Logo 的边框只拦「我的 Logo」这一下：引擎画不出来，进去选就是一次
    // 静默无效。原因（`USER_LOGO_UNSUPPORTED_ON_CARD`）就显示在按钮下面。
    val userAction = userLogoActionDecision(supportsUserLogo)
    // 手机端：更换 Logo 开的是外壳绘制的覆盖层面板（舞台仍在），这里只回调打开；
    // 桌面端 onOpenBrandPicker 为 null，走内联品牌页 / 版本页。
    val phonePicker = onOpenBrandPicker

    // 视图状态：null = 还没点过 ⇒ 落地态（面板）。取证钩子只改初始页，见 logoEvidencePane。
    var pane by remember(brandControl.key) { mutableStateOf(logoEvidencePane()) }
    val state = pane ?: LogoPaneState.Landing

    // 现场诊断（与仓库里其它 FM_ 钩子同风格）：自动匹配 / 品牌键的分支很难从截图区分，
    // 打一行就够定位「未收录」到底是没读到品牌、还是值不在库里。
    FmLog.info("FM_LOGO_PANE") {
        "raw=${raw.take(48)} kind=${summary.kind} body=${state.body} brands=${brands.size} " +
            "cutKey=${logoBrandForAssetKey(brands, raw)?.key} brandKey=${logoBrandForKeyValue(brands, raw)?.key} " +
            "make=$photoMake photoBrand=${photoBrand?.brand?.key} " +
            "slots=${textSlots.size} " +
            "brandRows=${textSlots.filter { (it.key ?: it.id).contains("make") || (it.key ?: it.id).contains("brand") }
                .joinToString("|") { (it.key ?: it.id) + "=" + it.text.take(16) }}"
    }

    fun run(action: LogoPaneAction) {
        pane = action.state
        action.write?.let { session.set(brandControl, it) }
        if (action.openPicker) phonePicker?.invoke()
    }

    // WP-V4 evidence hook: click one named version once the catalog is available.
    // The write is the same `session.set(brandControl, assetKey)` a real click does,
    // so undo/modified-count/stage all behave exactly as in the demo.
    var autoPicked by remember(brandControl.key) { mutableStateOf(false) }
    LaunchedEffect(brands, state.versionPage, state.browseBrandKey) {
        val pick = com.framemark.native.LaunchOptions.logoPickVersion
        if (autoPicked || pick.isNullOrBlank() || brands.isEmpty() || !state.versionPage) {
            return@LaunchedEffect
        }
        val brand = brands.firstOrNull { it.key == state.browseBrandKey } ?: summary.brand ?: return@LaunchedEffect
        val version = brand.versions.firstOrNull { it.name.contains(pick, ignoreCase = true) }
            ?: return@LaunchedEffect
        val cut = version.cuts.firstOrNull() ?: return@LaunchedEffect
        autoPicked = true
        run(
            if (com.framemark.native.LaunchOptions.logoAfterBack) {
                // 取证钩子（`logoAfterBack`）：像用户点完一样回到面板（`desktop-afterback`）。
                logoPickerResult(state, cut.assetKey)
            } else {
                // 不然就停在版本页，让 `desktop-selected` 拍到「已选」。
                LogoPaneAction(state = state, write = cut.assetKey)
            },
        )
    }

    when (state.body) {
        LogoPaneBody.LibraryList -> {
            // 目录是异步加载的：首帧 brands 为空时不能把页面判成「品牌不存在」，
            // 所以品牌还解析不出来就先画品牌页，而不是画一块空白。
            val versionBrand = brands.firstOrNull { it.key == state.browseBrandKey } ?: summary.brand
            val listBody: @Composable () -> Unit = {
                if (state.versionPage && versionBrand != null) {
                    CuratedLogoVersionPage(
                        brand = versionBrand,
                        appliedRaw = raw,
                        onBack = { pane = logoVersionBack(state) },
                        onPick = { assetKey -> run(logoPickCut(state, assetKey)) },
                    )
                } else {
                    CuratedLogoBrandPage(
                        brands = brands,
                        appliedBrand = summary.brand,
                        browsing = state.browseBrandKey,
                        onBack = { pane = logoPaneBack(state) },
                        onPickBrand = { key -> pane = logoBrowseBrand(state, key) },
                    )
                }
            }
            // 手机底板的高是算好的（一页一组），品牌 / 版本列表是**内容列表**，
            // 规则允许它自带一个竖向滚动区；桌面右栏本来就在滚动列表里，不能再套。
            if (dense) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { listBody() }
            } else {
                listBody()
            }
            return
        }
        LogoPaneBody.UserLibrary -> {
            val userBody: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 返回入口就在面板原来的位置：点「我的 Logo」不会把用户带到另一个工具页。
                    LogoTop(
                        back = "返回 Logo",
                        crumb = "当前：${summary.title}",
                        onBack = { pane = logoPaneBack(state) },
                    )
                    CuratedGroup(title = "我的 Logo", right = { CuratedCount("本机库") }) {
                        UserLogoPanel(
                            control = brandControl,
                            session = session,
                            repo = UserLogoEnv.repository,
                            dense = dense,
                            selectable = userAction.enabled,
                        )
                    }
                }
            }
            // 我的 Logo 同样是内容列表：手机端一个滚动区，桌面端跟着右栏滚。
            if (dense) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { userBody() }
            } else {
                userBody()
            }
            return
        }
        LogoPaneBody.Panel -> Unit
    }

    val actions: @Composable () -> Unit = {
        if (dense) {
            // 手机密度档（2026-11）：三个动作并排一行（每个 44 dp），下面仍然只有
            // 一行说明。「我的 Logo」画不出用户字标时，理由就用这一行说明，不再另起一行。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                PrimaryButton(
                    text = "更换 Logo",
                    onClick = { run(logoPanelAction(state, LogoPanelAction.Replace, phonePicker != null)) },
                    modifier = Modifier.weight(1f),
                    minHeight = DenseButtonHeight,
                )
                GhostButton(
                    text = "自动匹配",
                    onClick = { run(logoPanelAction(state, LogoPanelAction.Auto, phonePicker != null)) },
                    modifier = Modifier.weight(1f),
                    minHeight = DenseButtonHeight,
                )
                GhostButton(
                    text = "我的 Logo",
                    onClick = { run(logoPanelAction(state, LogoPanelAction.User, phonePicker != null)) },
                    enabled = userAction.enabled,
                    modifier = Modifier.weight(1f),
                    minHeight = DenseButtonHeight,
                )
            }
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                text = userAction.note ?: logoCaption(summary),
                style = CuratedNote,
                color = if (userAction.note != null) FmPalette.error else FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            PrimaryButton(
                text = "更换 Logo",
                onClick = { run(logoPanelAction(state, LogoPanelAction.Replace, phonePicker != null)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(FmSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                GhostButton(
                    text = "自动匹配",
                    onClick = { run(logoPanelAction(state, LogoPanelAction.Auto, phonePicker != null)) },
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = "我的 Logo",
                    onClick = { run(logoPanelAction(state, LogoPanelAction.User, phonePicker != null)) },
                    enabled = userAction.enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                text = logoCaption(summary),
                style = CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 画不出用户 Logo 时唯一那一行原因：动作置灰，理由就贴在按钮下面。
            userAction.note?.let { note ->
                Text(
                    text = note,
                    style = FmType.labelSmall,
                    color = FmPalette.error,
                    modifier = Modifier.padding(top = FmSpacing.xs),
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. 调整字标：进来第一件事就是能在画布还看得见的时候调位置 / 大小 / 显示。
        if (adjustments.isNotEmpty()) {
            val rows: @Composable () -> Unit = {
                if (dense) {
                    // 相邻两条滑杆一行（`logoAdjustmentRows` 与分页政策同一份行表）。
                    logoAdjustmentRows(adjustments).forEach { row ->
                        if (row.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(FmSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEach { control ->
                                    Box(Modifier.weight(1f)) {
                                        CuratedLogoAdjustment(control, session, adjustments, dense = true)
                                    }
                                }
                            }
                        } else {
                            CuratedLogoAdjustment(row.first(), session, adjustments, dense = true)
                        }
                    }
                } else {
                    orderedLogoAdjustments(adjustments).forEach {
                        CuratedLogoAdjustment(it, session, adjustments)
                    }
                }
            }
            if (grouped) {
                CuratedGroup("调整字标") { rows() }
            } else {
                rows()
            }
        }
        // 2. 三个动作 + 一行说明。品牌 / 版本列表只能从「更换 Logo」进去。
        if (showActions) {
            if (grouped) {
                CuratedGroup(title = "字标", right = { CuratedCount(logoSourceLabel(summary)) }) {
                    actions()
                }
            } else {
                actions()
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
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

/** Brand title with the Chinese name (`lgBrandTitle`). */
internal fun logoBrandTitle(brand: LogoBrand): String =
    if (brand.cn.isBlank()) brand.label else "${brand.label} · ${brand.cn}"

/**
 * 手机一页里「调整字标」的行：先按 [orderedLogoAdjustments] 排（显示 → 位置 → 大小 →
 * 水平位置），再把**相邻两条滑杆并成一行**（各占一半，行高仍是一条 44 dp 的密度档
 * 滑杆），其余控件一行一个。
 *
 * 这是政策（`PhonePageLayout.pageBlocks`）与渲染（[BrandedCuratedLogo]）共用的同一个
 * 纯函数：分页器按它算行高，面板按它画行，所以「两条滑杆一行」不会在两边各写一套。
 */
internal fun logoAdjustmentRows(controls: List<InspectorControl>): List<List<InspectorControl>> {    val rows = ArrayList<MutableList<InspectorControl>>()
    var openSliderRow: MutableList<InspectorControl>? = null
    orderedLogoAdjustments(controls).forEach { control ->
        if (control.type == ControlType.Slider) {
            val open = openSliderRow
            if (open != null && open.size == 1) {
                open += control
                openSliderRow = null
            } else {
                val row = mutableListOf(control)
                rows += row
                openSliderRow = row
            }
        } else {
            openSliderRow = null
            rows += mutableListOf(control)
        }
    }
    return rows
}

/**
 * 两个动作按钮并排时，[PrimaryButton] / [GhostButton] 在手机密度档收 48 → 44：
 * 仍然是同一个按钮、同一个写入路径，只是高度落到触控地板（不再多占 4 dp）。
 */
private val DenseButtonHeight = 44.dp

/**
 * 「调整字标」里的一行（One 显示 row：开关 / 分段 / 滑杆）。桌面（`dense = false`）保持
 * 原来的整块形态；手机密度档（2026-11）把标签与控件压成一行 44 dp：
 *  - 开关 → [CuratedSwitchRow] 的 44 dp 行；
 *  - 滑杆 → [CuratedSliderRow] 的 compact 行（两条并排时各占一半）；
 *  - 位置分段 → 标签在左、[CuratedSegmented] 的 40 dp 触控按钮在右（整行 44）。
 *
 * 居左时那句「左侧文字会消失」的说明在手机不再单占一行：画布就在底板上面，选完
 * 立刻看得见；桌面右栏保留整句。
 */
@Composable
internal fun CuratedLogoAdjustment(
    control: InspectorControl,
    session: DesignSession,
    /** 同一张卡的其余 Logo 控件：位置会决定「水平位置」是否生效、以及居左的后果。 */
    siblings: List<InspectorControl> = emptyList(),
    dense: Boolean = false,
) {
    val headerPos = siblings.firstOrNull { it.param.endsWith("logoPos") }
        ?: (control.takeIf { it.param.endsWith("logoPos") })
    val logoPosition = headerPos?.let { session.value(it)?.toString() }
    when (control.type) {
        ControlType.Switch -> CuratedSwitchRow(control, session, dense = dense)
        ControlType.Slider -> {
            // AU2 A-04：`logoCenterX` 只在「位置 = 居中」分支被引擎读；居左/居右
            // 时拖动没有任何效果，所以置灰并说明，而不是让用户白拖。
            val inactiveReason = if (control.param.endsWith("logoCenterX")) {
                logoCenterXDisabledReason(logoPosition)
            } else {
                null
            }
            CuratedSliderRow(
                control = control,
                session = session,
                label = logoAdjustLabel(control),
                hint = logoAdjustHint(control, session),
                unit = control.unit ?: "×",
                decimalPlaces = 1,
                enabled = inactiveReason == null,
                disabledReason = inactiveReason,
                compact = dense,
                labelWidth = if (dense) 56.dp else 88.dp,
            )
        }
        ControlType.Select -> {
            val value = session.value(control).toString()
            if (dense) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = FmDenseRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = logoAdjustLabel(control),
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 72.dp),
                    )
                    Spacer(Modifier.width(FmRhythm.inlineLoose))
                    CuratedSegmented(
                        options = orderedLogoOptions(control.options),
                        selected = value,
                        onSelect = { session.set(control, it) },
                        modifier = Modifier.weight(1f),
                        touch = true,
                    )
                }
            } else {
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
                    if (control.param.endsWith("logoPos") && value == "left") {
                        // AU2 A-02：引擎 `brand_footer.rs:776-778` 里居左会直接不画左侧
                        // 品牌/机型两行（不是挪位）。引擎侧本轮不改，至少在选择处就说实话。
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = logoLeftHidesTextNote(siblings, session),
                            style = CuratedNote,
                            color = FmPalette.onSurfaceMuted,
                        )
                    }
                }
            }
        }
        else -> GenericControlRow(control, session)
    }
}

/**
 * 「水平位置」在某个 logo 位置下是否不生效；返回一句原因，null = 生效（AU2 A-04）。
 *
 * 引擎只在 `logo_pos == "center"` 分支读 `logoCenterX`（`brand_footer.rs:860`），
 * 居左/居右时拖动没有任何效果，所以置灰并说明。桌面与手机共用这一句。
 */
internal fun logoCenterXDisabledReason(logoPosition: String?): String? =
    if (logoPosition != null && logoPosition != "center") {
        "只有「位置 = 居中」时水平位置才生效。"
    } else {
        null
    }

/**
 * 「位置 = 居左」的后果说明（AU2 A-02）。只有左侧文字还显示时才值得提；
 * 两行都关掉以后居左不再有破坏性，就不用再吓用户。
 */
internal fun logoLeftHidesTextNote(siblings: List<InspectorControl>, session: DesignSession): String {
    fun shown(name: String): Boolean {
        val control = siblings.firstOrNull { it.param.endsWith(name) } ?: return true
        return session.value(control) as? Boolean ?: true
    }
    val rows = buildList {
        if (shown("showMake")) add("品牌")
        if (shown("showModel")) add("机型")
    }
    return if (rows.isEmpty()) {
        "居左时左侧不放 Logo。"
    } else {
        "居左时左侧${rows.joinToString("、")}两行将不显示（按版式排，不是挪位）。"
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
            color = FmPalette.onSurfaceMuted,
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

/** Version page: one row per exact cut, grouped by era (a black/white pair is two rows). */
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
                    // 一个版本里的**每一枚** cut 都要能点：黑标 / 白标是两枚独立字标，
                    // `cuts.firstOrNull()` 只让第一枚可达（白色那枚永远点不到）。
                    version.cuts.forEach { cut ->
                        LogoVersionRow(
                            version = version,
                            cut = cut,
                            selected = cut.assetKey == appliedRaw,
                            missing = rememberLogoPreview(cut.preview).missing,
                            onPick = { onPick(cut.assetKey) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            CuratedSubNote("点具体字标（黑标 / 白标）才会更换 Logo；浏览品牌、返回，都不改变已经用上的字标。")
        }
    }
}

/** 一行 = 一枚精确 cut：同一版本的黑色 / 白色是两行，各有自己的色板和名字。 */
@Composable
private fun LogoVersionRow(
    version: LogoVersion,
    cut: LogoCut,
    selected: Boolean,
    missing: Boolean,
    onPick: () -> Unit,
) {
    val tone = PhoneLogoPickerState.toneLabel(cut)
    val variants = version.cuts.size > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(enabled = !missing, role = Role.Button, onClick = onPick)
            .semantics {
                contentDescription = if (variants) "字标：${version.name} $tone" else "字标版本：${version.name}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoPlate(cut.preview, 76.dp, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (variants) "${version.name} · $tone" else version.name,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = if (missing) FmPalette.onSurfaceMuted else FmPalette.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = versionSubtitle(version.era, version.years),
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

