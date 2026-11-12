package com.framemark.native.inspector

/**
 * 边框画不出用户 Logo 时面板里的那一行原因。
 *
 * 文案只此一份：拦下「我的 Logo」动作的地方与提示都读它，避免两处慢慢说成两种话。
 */
internal const val USER_LOGO_UNSUPPORTED_ON_CARD =
    "这张边框不支持用户 Logo，请先换成支持它的边框，或使用「自动匹配」"

/**
 * Logo 面板上「我的 Logo」这个动作能不能点。
 *
 * [enabled] 只裁**进入用户库**这一下：本机库是全局的，但边框画不出用户 Logo 时引擎
 * 会静默丢掉写进去的值，所以直接拦在入口，并把原因写在按钮下面，而不是让用户挑完
 * 一次什么都没发生。
 */
internal data class UserLogoActionDecision(
    /** true = 这一下进用户库（每行写回 Brand 控件）；false = 拦下，只显示 [note]。 */
    val enabled: Boolean,
    /** 按钮下面那一行原因；null = 这张边框能画用户 Logo，不提示。 */
    val note: String?,
)

/**
 * 纯决策：给定当前边框能否画用户 Logo，决定「我的 Logo」动作能不能点、以及面板上
 * 要不要说明原因。
 *
 * 能力来自平台渲染器装进 `UserLogoSupport` 的那一个探针（桌面
 * `DesktopNativeRender.supportsUserLogo`、Android
 * `AndroidNativeRender.supportsUserLogo`），这里不按卡片名单另立判断。
 *
 * - 支持：动作可点，不提示，行为与以前完全一致。
 * - 不支持：动作置灰并显示 [USER_LOGO_UNSUPPORTED_ON_CARD]；已生效值是 `user:…` 时
 *   同一行原因也留在面板上，不假装它还在渲染（用户可能刚从别的边框切过来，或者打开
 *   的是老工程）。
 */
internal fun userLogoActionDecision(supportsUserLogo: Boolean): UserLogoActionDecision =
    if (supportsUserLogo) {
        UserLogoActionDecision(enabled = true, note = null)
    } else {
        UserLogoActionDecision(enabled = false, note = USER_LOGO_UNSUPPORTED_ON_CARD)
    }
