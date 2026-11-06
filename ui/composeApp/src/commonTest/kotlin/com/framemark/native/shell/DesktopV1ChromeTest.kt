package com.framemark.native.shell

import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmNumeric
import com.framemark.native.theme.FmType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * WP-V1 桌面外壳的几何与字号（PARITY-SPEC §1 / §2A / §2C / §2D）。
 *
 * 这些数值是并排比对时逐项核对过的：胶片条 96、顶栏内边距 16/14、舞台留白
 * 56/64/84。写死在这里是为了让"演示稿对齐"退化成一次编译期回归——有人把
 * 120 改回去、或把舞台留白改成 16，测试会先说话。
 */
class DesktopV1ChromeTest {

    @Test
    fun filmstrip_is_96_high_with_56_frames() {
        assertEquals(96f, FmDesktop.filmstripHeight.value)
        assertEquals(56f, FmDesktop.filmstripFrameHeight.value)
        assertEquals(56f, FmDesktop.filmstripAddSize.value)
    }

    @Test
    fun top_bar_padding_matches_the_appbar_rule() {
        // styles.css 98 行：`padding: 0 14px 0 16px`.
        assertEquals(16f, FmDesktop.topBarPadStart.value)
        assertEquals(14f, FmDesktop.topBarPadEnd.value)
        assertEquals(52f, FmDesktop.topBarHeight.value)
        assertEquals(26f, FmDesktop.topBarMarkSize.value)
        assertEquals(20f, FmDesktop.topBarSeparatorHeight.value)
        // 中间 segmented 固定 220（styles.css 784 行 `.appbar .mid .seg`）。
        assertEquals(220f, FmDesktop.topBarSegWidth.value)
    }

    @Test
    fun stage_padding_matches_the_stage_area_rule() {
        // styles.css 354 行：`padding: var(--pad, 56px 64px 84px)`.
        assertEquals(56f, FmDesktop.stagePadTop.value)
        assertEquals(64f, FmDesktop.stagePadSide.value)
        assertEquals(84f, FmDesktop.stagePadBottom.value)
        // 工具条：bottom 18、圆角 12、按钮 30、缩放读数 46。
        assertEquals(18f, FmDesktop.stageToolbarBottom.value)
        assertEquals(12f, FmDesktop.stageToolbarRadius.value)
        assertEquals(30f, FmDesktop.stageToolbarHeight.value)
        assertEquals(46f, FmDesktop.stageZoomLabelWidth.value)
    }

    @Test
    fun demo_type_scale_is_appended_without_touching_the_phone_scale() {
        // 手机端沿用的旧尺度不动。
        assertEquals(22f, FmType.titleLarge.fontSize.value)
        assertEquals(17f, FmType.titleMedium.fontSize.value)
        assertEquals(15f, FmType.titleSmall.fontSize.value)
        assertEquals(13f, FmType.bodyMedium.fontSize.value)
        assertEquals(12f, FmType.labelMedium.fontSize.value)
        // 演示稿桌面尺度：16/700、15/700、14、12.5、12、11、10.5。
        assertEquals(16f, FmType.desktopHeading.fontSize.value)
        assertEquals(15f, FmType.desktopWordmark.fontSize.value)
        assertEquals(14f, FmType.desktopTab.fontSize.value)
        assertEquals(12.5f, FmType.desktopChip.fontSize.value)
        assertEquals(12f, FmType.desktopCaption.fontSize.value)
        assertEquals(11f, FmType.desktopFootnote.fontSize.value)
        assertEquals(10.5f, FmType.desktopFrameNumber.fontSize.value)
    }

    @Test
    fun numeric_styles_are_tabular_and_12_11_10_5() {
        assertEquals(12f, FmNumeric.value.fontSize.value)
        assertEquals(11f, FmNumeric.caption.fontSize.value)
        assertEquals(10.5f, FmNumeric.frameNumber.fontSize.value)
        // `--num` 的 tabular-nums：数字必须等宽，缩放百分比才不会左右跳。
        assertEquals("tnum", FmNumeric.value.fontFeatureSettings)
        assertEquals("tnum", FmNumeric.frameNumber.fontFeatureSettings)
        assertNotNull(FmNumeric.value.fontFamily)
    }
}
