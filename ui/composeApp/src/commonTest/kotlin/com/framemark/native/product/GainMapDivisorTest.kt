package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 增益图合成倍率（2026-11-21）：200 MP 手机照片按固定 2 倍合成会得到 54 MP 的增益图，
 * 移动端失败后 HDR 被静默降级成 SDR——用户以为成片是 HDR，相册却不显示。
 *
 * 规则：默认 2 倍；画布长边很大时继续放大，保证增益图长边不超过 2048（低频信号，
 * 解码器会插值）。
 */
class GainMapDivisorTest {

    @Test
    fun ordinaryCanvasesKeepTheTwoTimesDivisor() {
        assertEquals(2, UltraHdrExport.gainMapDivisor(4032, 3024))
        assertEquals(2, UltraHdrExport.gainMapDivisor(2048, 2048))
    }

    @Test
    fun bigCanvasesRaiseTheDivisorSoTheGainMapStaysSmall() {
        val divisor = UltraHdrExport.gainMapDivisor(12288, 17748)
        assertTrue(divisor >= 9, "17748 / 9 = 1972，长边要降到 2048 以内，实际 divisor=$divisor")
        assertTrue(17748 / divisor <= UltraHdrExport.MAX_GAIN_MAP_EDGE)
        assertEquals(2, UltraHdrExport.gainMapDivisor(0, 0), "非法尺寸退回默认值")
    }
}
