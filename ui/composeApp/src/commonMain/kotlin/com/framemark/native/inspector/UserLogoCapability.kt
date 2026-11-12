package com.framemark.native.inspector

/**
 * 边框画不出用户 Logo 时面板里的那一行原因。
 *
 * 文案只此一份：拦下点击的地方与「已生效值是 `user:…`」的提示都读它，避免两处
 * 慢慢说成两种话。
 */
internal const val USER_LOGO_UNSUPPORTED_ON_CARD =
    "这张边框不支持用户 Logo，请先换成支持它的边框，或使用「自动匹配」"

/**
 * 「我的 Logo」里点一行会发生什么。
 *
 * [allowed] 只裁**选中**这一下写入：用户库是全局的，列出 / 导入 / 删除与当前边框
 * 无关，所以面板本身永远不被禁用或隐藏（见 [UserLogoPanel] 的 `selectable`）。
 */
internal data class UserLogoSelectionDecision(
    /** true = 这一下写回 Brand 控件（引擎画得出来）；false = 拦下，只显示 [note]。 */
    val allowed: Boolean,
    /** 面板头部要显示的一行原因；null = 这张边框能画用户 Logo，不提示。 */
    val note: String?,
)

/**
 * 纯决策：给定来源段、当前边框能否画用户 Logo、已生效值是不是用户 Logo，决定
 * 「我的 Logo」的行点击能否写入、以及面板头部要不要说明原因。
 *
 * 能力来自平台渲染器装进 `UserLogoSupport` 的那一个探针（桌面
 * `DesktopNativeRender.supportsUserLogo`、Android
 * `AndroidNativeRender.supportsUserLogo`），这里不按卡片名单另立判断。
 *
 * - 支持：不拦、不提示，行为与以前完全一致。
 * - 不支持：「我的 Logo」段的每一行都被拦下——引擎画不出来，写进去只会变成一次
 *   静默的无效操作；同时显示 [USER_LOGO_UNSUPPORTED_ON_CARD]。
 * - 已生效值是 `user:…` 而边框不支持：不管停在哪一段都显示同一行原因，不假装
 *   它还在渲染（用户可能刚从别的边框切过来，或者打开的是老工程）。
 * - 其它来源段且没有已生效用户值：不受影响。
 */
internal fun userLogoSelectionDecision(
    segment: String,
    supportsUserLogo: Boolean,
    hasAppliedUserValue: Boolean,
): UserLogoSelectionDecision {
    if (supportsUserLogo) return UserLogoSelectionDecision(allowed = true, note = null)
    val note = if (segment == "user" || hasAppliedUserValue) USER_LOGO_UNSUPPORTED_ON_CARD else null
    // 只有「我的 Logo」段会把用户库的行画出来；其它段没有行可点，保持允许。
    return UserLogoSelectionDecision(allowed = segment != "user", note = note)
}
