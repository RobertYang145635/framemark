package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「Logo 来源」分段是一个纯决策：选中哪一段决定当前 Logo 卡下面画哪一块。
 *
 * 这条合同钉住手机端那个缺陷的回归：旧实现把选中项由已生效的值反推，点
 * 「我的 Logo」只把旧值原样写回去、下面什么也不画，用户库面板（`UserLogoPanel`）
 * 因此永远不可达。现在选中「我的 Logo」⇒ 画用户库面板，选中品牌段 ⇒ 仍是品牌入口。
 */
class LogoSourceSegmentTest {

    @Test
    fun userSegmentRendersTheUserLogoLibrary() {
        assertEquals(LogoSourceBody.User, logoSourceBody("user"))
    }

    @Test
    fun brandSegmentKeepsRenderingTheBrandEntry() {
        assertEquals(LogoSourceBody.Brand, logoSourceBody("lib"))
    }

    @Test
    fun autoSegmentKeepsTheBrandEntryBecauseAutoHasNoListOfItsOwn() {
        assertEquals(LogoSourceBody.Brand, logoSourceBody("auto"))
    }

    @Test
    fun everyOfferedSegmentRendersSomethingAndOnlyUserIsTheUserLibrary() {
        val bodies = LogoSources.associate { it.value to logoSourceBody(it.value) }
        assertEquals(setOf("auto", "lib", "user"), bodies.keys)
        assertEquals(listOf("user"), bodies.filterValues { it == LogoSourceBody.User }.keys.toList())
    }

    @Test
    fun unknownOrBlankSegmentStillRendersTheBrandEntry() {
        assertEquals(LogoSourceBody.Brand, logoSourceBody(""))
        assertEquals(LogoSourceBody.Brand, logoSourceBody("user:"))
        assertEquals(LogoSourceBody.Brand, logoSourceBody("bogus"))
    }

    @Test
    fun onlyTheAutoSegmentWritesOnSelection() {
        // 品牌库 / 我的 Logo 都是浏览：不改已经生效的字标，否则点「我的 Logo」
        // 只会把旧值写回去，看着像什么都没发生。
        assertEquals("auto", logoSegmentWrite("auto"))
        assertNull(logoSegmentWrite("lib"))
        assertNull(logoSegmentWrite("user"))
    }
}
