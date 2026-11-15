package com.framemark.native.theme

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 四套配色 × 明暗的对比度契约（配色方案 §6.5 的落地版）。
 *
 * 设计文档只给了 10 个基础色和「基础文字对比度」表；`Palette.kt` 在这 10 个色上
 * 又派生了 ink-2 / ink-4 / 描边加强 / 轨道 / 滑杆空闲段。派生规则不能把某个小字
 * 掉到 AA 以下，也不能让 `faint` 悄悄升到正文可用的亮度 —— 这个测试逐套逐模式钉住
 * 两条边界：
 *  - 正文墨水（主 / 次 / 次要）≥ 4.5:1，主文字 ≥ 7:1；
 *  - `faint` 仍低于 4.5:1（只给禁用态和占位文字），但至少 2.5:1，否则「禁用」
 *    两个字都看不清；
 *  - 链接 / 选中文字在主面板和柔和选中底色上都 ≥ 4.5:1；
 *  - 深色外观的滑杆空闲段相对面板 ≥ 3:1。
 */
class PaletteContrastTest {

    private val all = FmPalettes.all()

    @Test
    fun everyPaletteShipsBothModes() {
        assertEquals(PaletteId.entries.size, all.size)
        assertEquals(PaletteId.entries.map { it }, all.map { it.first })
        all.forEach { (id, light, dark) ->
            assertTrue(light.canvas != dark.canvas, "palette=$id: light/dark must differ")
            assertTrue(
                contrastRatio(light.canvas, dark.canvas) > 1.2,
                "palette=$id: light and dark canvases must be visibly different",
            )
        }
    }

    @Test
    fun bodyInkPassesAaOnEverySurface() {
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val surfaces = mapOf(
                    "canvas" to colors.canvas,
                    "surface" to colors.surface,
                    "surfaceMuted" to colors.surfaceMuted,
                    "primarySoft" to colors.primarySoft,
                )
                val inks = mapOf(
                    "onSurface" to colors.onSurface,
                    "onSurfaceSecondary" to colors.onSurfaceSecondary,
                    "onSurfaceMuted" to colors.onSurfaceMuted,
                )
                for ((inkName, ink) in inks) {
                    for ((surfaceName, background) in surfaces) {
                        val ratio = contrastRatio(ink, background)
                        assertTrue(
                            ratio >= 4.5,
                            "$id/$mode $inkName on $surfaceName is $ratio:1 (need >= 4.5:1)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun primaryInkIsAaaWhereItCarriesBodyText() {
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val ratio = contrastRatio(colors.onSurface, colors.surface)
                assertTrue(ratio >= 7.0, "$id/$mode primary ink on surface is $ratio:1 (need >= 7:1)")
            }
        }
    }

    @Test
    fun faintInkStaysDisabledAndPlaceholderOnly() {
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val ratio = contrastRatio(colors.onSurfaceFaint, colors.surface)
                assertTrue(
                    ratio < 4.5,
                    "$id/$mode faint ink on surface is $ratio:1 — if this now clears AA, " +
                        "re-read the Tokens.kt usage rule before using it for body text",
                )
                assertTrue(
                    ratio >= 2.5,
                    "$id/$mode faint ink on surface is only $ratio:1 — disabled text must still read",
                )
            }
        }
    }

    @Test
    fun accentFillAndItsLabelPassAa() {
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val onFill = contrastRatio(colors.onPrimary, colors.primary)
                assertTrue(onFill >= 4.5, "$id/$mode onPrimary on primary is $onFill:1")
                val linkOnSurface = contrastRatio(colors.primaryStrong, colors.surface)
                assertTrue(linkOnSurface >= 4.5, "$id/$mode link on surface is $linkOnSurface:1")
                val linkOnSoft = contrastRatio(colors.primaryStrong, colors.primarySoft)
                assertTrue(linkOnSoft >= 4.5, "$id/$mode link on soft is $linkOnSoft:1")
            }
        }
    }

    @Test
    fun darkSliderIdleClearsThreeToOne() {
        for ((id, _, dark) in all) {
            val ratio = contrastRatio(dark.sliderIdle, dark.surface)
            assertTrue(ratio >= 3.0, "$id dark sliderIdle on surface is $ratio:1 (need >= 3:1)")
        }
    }

    @Test
    fun hairlinesStayVisibleWithoutBecomingInk() {
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val line = contrastRatio(colors.outline, colors.surface)
                assertTrue(line > 1.1, "$id/$mode outline vs surface is only $line:1")
                assertTrue(line < 3.0, "$id/$mode outline vs surface is $line:1 — a hairline, not a border")
                val strong = contrastRatio(colors.outlineStrong, colors.surface)
                assertTrue(
                    strong > line,
                    "$id/$mode outlineStrong ($strong:1) must be stronger than outline ($line:1)",
                )
            }
        }
    }

    @Test
    fun documentedBaseColoursAreUsedVerbatim() {
        // 配色方案 §6.1–§6.4 的 4 × 2 × 10 个基础色。派生角色可以算，基础色不许漂。
        val expected = mapOf(
            PaletteId.Forest to mapOf(
                "light" to listOf(0xFFF2F1ED, 0xFFFCFBF8, 0xFFE7E8E2, 0xFF242823, 0xFF646B63,
                    0xFF31634F, 0xFFFFFFFF, 0xFF31634F, 0xFFD9DCD4, 0xFFE2EBE4),
                "dark" to listOf(0xFF181C19, 0xFF222923, 0xFF2C352E, 0xFFE8EDE7, 0xFFB1BBB1,
                    0xFF93B9A2, 0xFF182A20, 0xFFA9CBB6, 0xFF3A453C, 0xFF2E4537),
            ),
            PaletteId.Graphite to mapOf(
                "light" to listOf(0xFFF4F4F2, 0xFFFFFFFF, 0xFFEAEAE6, 0xFF242424, 0xFF666660,
                    0xFF8A5A13, 0xFFFFFFFF, 0xFF80520D, 0xFFDCDCD6, 0xFFF2E8D4),
                "dark" to listOf(0xFF171717, 0xFF222222, 0xFF2E2E2E, 0xFFEFEFEC, 0xFFB7B7AF,
                    0xFFD9B46D, 0xFF252016, 0xFFE8C787, 0xFF3C3C38, 0xFF423726),
            ),
            PaletteId.Indigo to mapOf(
                "light" to listOf(0xFFF2F3F7, 0xFFFFFFFF, 0xFFE9EBF2, 0xFF24293A, 0xFF626A80,
                    0xFF505B8A, 0xFFFFFFFF, 0xFF505B8A, 0xFFD9DDE8, 0xFFE6E9F4),
                "dark" to listOf(0xFF191B24, 0xFF242735, 0xFF2E3343, 0xFFECEEF7, 0xFFB4BCD3,
                    0xFFA3AEDF, 0xFF20253E, 0xFFBBC7F5, 0xFF3C4256, 0xFF333C59),
            ),
            PaletteId.Clay to mapOf(
                "light" to listOf(0xFFF4F0E9, 0xFFFFFCF6, 0xFFEAE1D6, 0xFF352D27, 0xFF77685B,
                    0xFF9B4F38, 0xFFFFFFFF, 0xFF8F442F, 0xFFDED2C6, 0xFFF3E2D9),
                "dark" to listOf(0xFF211C19, 0xFF2C2521, 0xFF3A3029, 0xFFF3E9DF, 0xFFC5B0A2,
                    0xFFE3A18B, 0xFF34221C, 0xFFF0B6A1, 0xFF4A3B32, 0xFF4B342B),
            ),
            // 经典蓝：首发蓝的主色深一档到 #2E71E0，白字按钮才过 4.5:1（见 Palette.kt）。
            PaletteId.Classic to mapOf(
                "light" to listOf(0xFFF0F2F5, 0xFFFFFFFF, 0xFFF6F8FB, 0xFF14181F, 0xFF5C6673,
                    0xFF2E71E0, 0xFFFFFFFF, 0xFF1D5FC6, 0xFFE3E7EC, 0xFFEAF1FC),
                "dark" to listOf(0xFF121316, 0xFF1D1E22, 0xFF26282D, 0xFFEDEFF3, 0xFF9AA1AC,
                    0xFF2E71E0, 0xFFFFFFFF, 0xFF7FB0FF, 0xFF2E3036, 0xFF202D44),
            ),
        )
        for ((id, light, dark) in all) {
            for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
                val want = expected.getValue(id).getValue(mode)
                val got = listOf(
                    colors.canvas, colors.surface, colors.surfaceMuted,
                    colors.onSurface, colors.onSurfaceMuted,
                    colors.primary, colors.onPrimary, colors.primaryStrong,
                    colors.outline, colors.primarySoft,
                )
                // ink 与 muted 允许按 AA 微调，其余 8 个必须逐字一致。
                listOf(0, 1, 2, 3, 5, 6, 7, 8, 9).forEach { index ->
                    assertEquals(
                        want[index].toInt(),
                        got[index].toArgb(),
                        "$id/$mode role #$index must use the documented colour verbatim",
                    )
                }
            }
        }
    }
}
