package com.framemark.native.shell

import com.framemark.native.theme.FmDesktop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V2 几何（UI 报告 §6）：左栏 296、右栏 336、顶栏 52、发丝线分隔（无 12 dp 间隙）、
 * 左栏收起为 52 px 窄条。三栏宽度之和必须正好等于内容宽度。
 */
class DesktopV2GeometryTest {

    @Test
    fun v2_tokens_match_the_design_report() {
        assertEquals(296f, FmDesktop.libraryWidth.value)
        assertEquals(336f, FmDesktop.inspectorWidth.value)
        assertEquals(52f, FmDesktop.topBarHeight.value)
        assertEquals(52f, desktopCollapsedRailWidth(), "左栏收起成 52 px 窄条")
        assertEquals(0f, desktopColumnGapWidth(), "V2 用发丝线分隔，不再留 12 dp 间隙")
    }

    @Test
    fun wide_1280_content_pays_for_both_v2_rails_and_the_stage() {
        val columns = computeDesktopColumns(
            availableWidthDp = 1280f,
            requestedLeft = FmDesktop.libraryWidth.value,
            requestedRight = FmDesktop.inspectorWidth.value,
            leftCollapsed = false,
            rightCollapsed = false,
        )
        assertFalse(columns.compact)
        assertEquals(296f, columns.left)
        assertEquals(336f, columns.right)
        assertEquals(1280f - 296f - 336f, columns.stage)
        assertTrue(abs(1280f - (columns.left + columns.right + columns.stage)) < 0.01f)
    }

    @Test
    fun collapsed_left_rail_is_52_px_and_the_stage_takes_the_rest() {
        val columns = computeDesktopColumns(
            availableWidthDp = 1280f,
            requestedLeft = 296f,
            requestedRight = 336f,
            leftCollapsed = true,
            rightCollapsed = false,
        )
        assertFalse(columns.showLeft)
        assertEquals(52f, columns.left)
        assertTrue(columns.showRight)
        assertEquals(1280f - 52f - columns.right, columns.stage)
    }
}
