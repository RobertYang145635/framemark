package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「我的 Logo」在画不出用户 Logo 的边框上必须拦住选中，而不是写一个引擎会丢掉的值
 * （Android IR/厂商卡、桌面 IR 卡）。用户库是全局的，所以列出 / 导入 / 删除照旧；
 * 这里只钉「选中」这一个写入点，以及面板头部那一行原因。
 */
class UserLogoCapabilityTest {

    /** 冻结用户可见文案：改这一句就得改这里，别让两处提示慢慢说岔。 */
    private val unsupportedNote =
        "这张边框不支持用户 Logo，请先换成支持它的边框，或使用「自动匹配」"

    @Test
    fun supportedCardAllowsTheTapWithoutANote() {
        // 能画用户 Logo 的边框：任何来源段、有没有已生效用户值，都和以前一模一样。
        listOf("user", "lib", "auto").forEach { segment ->
            listOf(false, true).forEach { applied ->
                val decision = userLogoSelectionDecision(
                    segment = segment,
                    supportsUserLogo = true,
                    hasAppliedUserValue = applied,
                )
                assertTrue(decision.allowed, "segment=$segment applied=$applied")
                assertNull(decision.note, "segment=$segment applied=$applied")
            }
        }
    }

    @Test
    fun unsupportedCardBlocksTheUserSegmentAndShowsTheReason() {
        val decision = userLogoSelectionDecision(
            segment = "user",
            supportsUserLogo = false,
            hasAppliedUserValue = false,
        )
        assertFalse(decision.allowed)
        assertEquals(unsupportedNote, decision.note)
        assertEquals(USER_LOGO_UNSUPPORTED_ON_CARD, decision.note)
    }

    @Test
    fun unsupportedCardWithAnAppliedUserValueKeepsTheReasonShown() {
        // 老工程 / 刚从支持它的边框切过来：`user:…` 还在值里，但画不出来。
        // 停在「我的 Logo」段继续拦；切到别的段也要把那行原因留在头部。
        assertFalse(
            userLogoSelectionDecision("user", supportsUserLogo = false, hasAppliedUserValue = true).allowed,
        )
        listOf("user", "lib", "auto").forEach { segment ->
            val decision = userLogoSelectionDecision(
                segment = segment,
                supportsUserLogo = false,
                hasAppliedUserValue = true,
            )
            assertEquals(unsupportedNote, decision.note, "segment=$segment")
        }
    }

    @Test
    fun otherSegmentsWithoutAUserValueAreUnaffected() {
        listOf("auto", "lib").forEach { segment ->
            val decision = userLogoSelectionDecision(
                segment = segment,
                supportsUserLogo = false,
                hasAppliedUserValue = false,
            )
            assertTrue(decision.allowed, "segment=$segment")
            assertNull(decision.note, "segment=$segment")
        }
    }

    @Test
    fun everyOfferedSegmentIsCoveredByTheDecision() {
        // 段键来自 `LogoSources`；未知键按品牌段处理，不能画出一个没人裁决过的段。
        val decisions = LogoSources.associate { option ->
            option.value to userLogoSelectionDecision(
                segment = option.value,
                supportsUserLogo = false,
                hasAppliedUserValue = false,
            )
        }
        assertEquals(setOf("auto", "lib", "user"), decisions.keys)
        assertEquals(listOf("user"), decisions.filterValues { !it.allowed }.keys.toList())
        assertEquals(listOf("user"), decisions.filterValues { it.note != null }.keys.toList())
        assertTrue(userLogoSelectionDecision("bogus", false, false).allowed)
        assertNull(userLogoSelectionDecision("bogus", false, false).note)
    }
}
