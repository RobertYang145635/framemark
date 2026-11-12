package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「我的 Logo」在画不出用户 Logo 的边框上必须拦在入口，而不是写一个引擎会丢掉的值
 * （Android IR/厂商卡、桌面 IR 卡）。拦的位置是面板上的**动作**：置灰 + 按钮下面那
 * 一行原因；库本身（列出 / 导入 / 删除）的代码一行没动。
 */
class UserLogoCapabilityTest {

    /** 冻结用户可见文案：改这一句就得改这里，别让两处提示慢慢说岔。 */
    private val unsupportedNote =
        "这张边框不支持用户 Logo，请先换成支持它的边框，或使用「自动匹配」"

    @Test
    fun supportedCardEnablesMyLogoWithoutANote() {
        val decision = userLogoActionDecision(supportsUserLogo = true)
        assertTrue(decision.enabled)
        assertNull(decision.note)
    }

    @Test
    fun unsupportedCardDisablesMyLogoAndShowsTheReason() {
        val decision = userLogoActionDecision(supportsUserLogo = false)
        assertFalse(decision.enabled)
        assertEquals(unsupportedNote, decision.note)
        assertEquals(USER_LOGO_UNSUPPORTED_ON_CARD, decision.note)
    }

    @Test
    fun theReasonIsTheSameFrozenSentenceEverywhere() {
        // 动作被拦时面板上那一行就是 `USER_LOGO_UNSUPPORTED_ON_CARD`，没有第二份文案。
        assertEquals(unsupportedNote, userLogoActionDecision(supportsUserLogo = false).note)
        assertNull(userLogoActionDecision(supportsUserLogo = true).note)
    }

    @Test
    fun theAutoMatchActionIsNeverBlocked() {
        // 需要用户 Logo 能力的是「我的 Logo」这一下；自动匹配永远可用。
        listOf(true, false).forEach { supported ->
            val auto = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.Auto, phonePicker = supported)
            assertEquals(LOGO_SOURCE_AUTO, auto.write)
            assertEquals(LogoPaneBody.Panel, auto.state.body)
        }
    }

    @Test
    fun replaceAndAutoStayReachableWhenMyLogoCannotBeOpened() {
        // 边框画不出用户 Logo：更换 Logo / 自动匹配照旧，只有「我的 Logo」置灰。
        val blocked = userLogoActionDecision(supportsUserLogo = false)
        assertFalse(blocked.enabled)
        listOf(LogoPanelAction.Replace, LogoPanelAction.Auto).forEach { action ->
            val next = logoPanelAction(LogoPaneState.Landing, action, phonePicker = true)
            assertTrue(next.openPicker || next.write != null, "$action")
            assertTrue(next.state.body != LogoPaneBody.UserLibrary, "$action")
        }
    }
}
