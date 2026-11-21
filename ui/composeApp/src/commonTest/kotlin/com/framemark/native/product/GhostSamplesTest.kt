package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺值占位的示例文案（2026-11-21 逐一核对边框后定下的口径）。
 *
 * 两条规则：
 *  1. 知道角色就按角色给（品牌 / 机型 / 拍摄参数 / 日期）；
 *  2. 不知道角色时**不猜** —— 按这套边框声明的行挑一个最接近的；一行都没声明就返回空，
 *     宁可不画占位，也不能在品牌行上画一串拍摄参数。
 */
class GhostSamplesTest {

    @Test
    fun knownRolesMapToTheirOwnSample() {
        assertTrue(GhostSamples.forRole("拍摄参数").contains("ISO"))
        assertTrue(GhostSamples.forRole("日期").startsWith("2026-"))
        assertEquals("OPPO", GhostSamples.forRole("品牌"))
        assertEquals("Sony", GhostSamples.forRole("品牌", make = "Sony"))
    }

    @Test
    fun anUnknownRoleProducesNoSampleAtAll() {
        assertEquals("", GhostSamples.forRole(null), "不知道是哪一行时不能猜成拍摄参数")
        assertEquals("", GhostSamples.forRole(""))
    }

    @Test
    fun declaredRowsPickTheClosestSample() {
        assertTrue(GhostSamples.forDeclaredRows(make = false, model = false, exif = true, date = false).contains("ISO"))
        assertTrue(GhostSamples.forDeclaredRows(make = false, model = false, exif = false, date = true).startsWith("2026-"))
        assertEquals(GhostSamples.MODEL, GhostSamples.forDeclaredRows(false, true, false, false))
        assertEquals("Leica", GhostSamples.forDeclaredRows(make = true, model = false, exif = false, date = false, makeValue = "Leica"))
        assertEquals("", GhostSamples.forDeclaredRows(false, false, false, false), "没有文字行就不画占位")
    }
}
