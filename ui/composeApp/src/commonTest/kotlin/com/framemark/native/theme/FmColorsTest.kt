package com.framemark.native.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 默认配色（暖灰墨绿）的快照契约，外加「派生角色只看向自己的快照」这条铁律。
 *
 * 四套配色 × 明暗的完整对比度矩阵在 `PaletteContrastTest`；这里只钉默认快照
 * （`FmPalettes.of(Forest, …)`）和派生规则本身。
 */
class FmColorsTest {

    private val light = FmPalettes.of(PaletteId.Forest, dark = false)
    private val dark = FmPalettes.of(PaletteId.Forest, dark = true)

    @Test
    fun lightIsTheDefaultForestPalette() {
        // 配色方案 §6.1 浅色列。
        assertEquals(Color(0xFFF2F1ED), light.canvas)
        assertEquals(Color(0xFFFCFBF8), light.surface)
        assertEquals(Color(0xFFE7E8E2), light.surfaceMuted)
        assertEquals(Color(0xFF242823), light.onSurface)
        assertEquals(Color(0xFF31634F), light.primary)
        assertEquals(Color(0xFFFFFFFF), light.onPrimary)
        assertEquals(Color(0xFF31634F), light.primaryStrong)
        assertEquals(Color(0xFFD9DCD4), light.outline)
        assertEquals(Color(0xFFE2EBE4), light.primarySoft)
    }

    @Test
    fun darkIsTheDefaultForestPalette() {
        // 配色方案 §6.1 深色列。
        assertEquals(Color(0xFF181C19), dark.canvas)
        assertEquals(Color(0xFF222923), dark.surface)
        assertEquals(Color(0xFF2C352E), dark.surfaceMuted)
        assertEquals(Color(0xFFE8EDE7), dark.onSurface)
        assertEquals(Color(0xFF93B9A2), dark.primary)
        assertEquals(Color(0xFF182A20), dark.onPrimary)
        assertEquals(Color(0xFFA9CBB6), dark.primaryStrong)
        assertEquals(Color(0xFF3A453C), dark.outline)
        assertEquals(Color(0xFF2E4537), dark.primarySoft)
    }

    @Test
    fun theDefaultPaletteIsForestWithADocumentedWireId() {
        assertEquals(PaletteId.Forest, PaletteId.Default)
        assertEquals(listOf("forest", "graphite", "indigo", "clay", "classic", "celadon", "amber"), PaletteId.entries.map { it.wire })
        assertEquals("暖灰墨绿", PaletteId.Default.label)
    }

    @Test
    fun stageIsSharedByEveryPaletteAndAppearance() {
        // 配色方案 §7.2：舞台不参与配色切换，四套配色、明暗两种外观都是同三档。
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            for (colors in listOf(lightColors, darkColors)) {
                assertEquals(Color(0xFF2B2C2F), colors.stage, "palette=$id stage")
                assertEquals(Color(0xFF2B2C2F), colors.stageDark, "palette=$id stageDark")
                assertEquals(Color(0xFF5A5C61), colors.stageMid, "palette=$id stageMid")
                assertEquals(Color(0xFFD9DADD), colors.stageLight, "palette=$id stageLight")
                assertEquals(Color(0xFF3A3F47), colors.stageLine, "palette=$id stageLine")
                assertEquals(Color(0xFFD4DAE3), colors.stageInk, "palette=$id stageInk")
                assertEquals(Color(0xFF969AA4), colors.stageMuted, "palette=$id stageMuted")
            }
        }
    }

    @Test
    fun stageInkStaysReadableOnTheStage() {
        // 品牌标 / 舞台文字用 stageInk；它必须比 3:1 高得多（品牌标窗口是实心块）。
        for ((id, light, dark) in FmPalettes.all()) {
            for (colors in listOf(light, dark)) {
                val ratio = contrastRatio(colors.stageInk, colors.stage)
                assertTrue(ratio >= 4.5, "palette=$id stageInk on stage is $ratio:1")
            }
        }
    }

    @Test
    fun stageColorMapsEveryTone() {
        assertEquals(light.stageDark, light.stageColor(StageTone.Dark))
        assertEquals(light.stageMid, light.stageColor(StageTone.Mid))
        assertEquals(light.stageLight, light.stageColor(StageTone.Light))
    }

    @Test
    fun feedbackAndShadowsKeepTheirMeaningAcrossPalettes() {
        // 错误 / 警告 / 成功四套配色共用（配色方案 §7.1）：墨绿不能把「选中」读成
        // 「已保存」，所以状态色不跟着品牌色走。
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            assertEquals(Color(0xFFD2453B), lightColors.error, "palette=$id light error")
            assertEquals(Color(0xFFC27A0E), lightColors.warning, "palette=$id light warning")
            assertEquals(Color(0xFF1E9E5A), lightColors.success, "palette=$id light success")
            assertEquals(Color(0xFFF06A5F), darkColors.error, "palette=$id dark error")
            assertEquals(Color(0xFFE0A23A), darkColors.warning, "palette=$id dark warning")
            assertEquals(Color(0xFF3CC07A), darkColors.success, "palette=$id dark success")
            assertTrue(
                darkColors.shadowNavSpot.alpha > lightColors.shadowNavSpot.alpha,
                "palette=$id: dark shadows must be deeper",
            )
        }
    }

    @Test
    fun explicitSoftSelectionBackgroundWins() {
        // 配色表给了柔和选中底色，就不再拿主色透明叠加去猜。
        assertEquals(light.primarySoft, light.softBase)
        assertEquals(dark.primarySoft, dark.softBase)
        assertEquals(0.10f, light.primarySoftAlpha, "light primary opacity stays the documented 10 %")
        assertEquals(0.18f, dark.primarySoftAlpha, "dark primary opacity stays the documented 18 %")
    }

    @Test
    fun derivedColorsUseTheirOwnSnapshot() {
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            for (colors in listOf(lightColors, darkColors)) {
                assertEquals(
                    colors.primary.copy(alpha = colors.ringAlpha),
                    colors.ring,
                    "palette=$id ring must be its own primary at its own alpha",
                )
                assertEquals(colors.outline, colors.indicator)
                assertEquals(colors.surface.copy(alpha = colors.navSurface.alpha), colors.navSurface)
            }
        }
    }

    @Test
    fun inkLadderKeepsItsOrderInEveryPalette() {
        // 主文字最深、次要文字次之、faint 最浅（相对面板）。深浅两种模式方向相反，
        // 所以比较「谁离面板更远」，不比较亮度大小。
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            for (colors in listOf(lightColors, darkColors)) {
                val surface = colors.surface
                val ink = contrastRatio(colors.onSurface, surface)
                val secondary = contrastRatio(colors.onSurfaceSecondary, surface)
                val muted = contrastRatio(colors.onSurfaceMuted, surface)
                val faint = contrastRatio(colors.onSurfaceFaint, surface)
                assertTrue(ink > secondary, "palette=$id ink=$ink secondary=$secondary")
                assertTrue(secondary > muted, "palette=$id secondary=$secondary muted=$muted")
                assertTrue(muted > faint, "palette=$id muted=$muted faint=$faint")
            }
        }
    }

    @Test
    fun navSurfaceIsTheSurfaceAtPointNineEightFive() {
        // Compose packs an sRGB Color into 0xAARRGGBB, so 0.985f quantises to
        // 251/255 = 0.98431373.
        val quantised = 251f / 255f
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            for (colors in listOf(lightColors, darkColors)) {
                assertEquals(quantised, colors.navSurface.alpha, "palette=$id")
                assertEquals(colors.surface.red, colors.navSurface.red, "palette=$id")
                assertEquals(colors.surface.green, colors.navSurface.green, "palette=$id")
                assertEquals(colors.surface.blue, colors.navSurface.blue, "palette=$id")
            }
        }
    }

    @Test
    fun hoverAndPressComeFromThePaletteInk() {
        for ((id, lightColors, darkColors) in FmPalettes.all()) {
            for (colors in listOf(lightColors, darkColors)) {
                assertEquals(colors.onSurface.red, colors.hover.red, "palette=$id")
                assertEquals(colors.onSurface.green, colors.hover.green, "palette=$id")
                assertEquals(colors.onSurface.blue, colors.hover.blue, "palette=$id")
                assertTrue(colors.press.alpha > colors.hover.alpha, "palette=$id press must be deeper")
            }
        }
    }
}
