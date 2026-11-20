package com.framemark.native.product.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 启动自动检查的两条边界（2026-11-20）：**只对真的更新的版本弹**，以及**点过「跳过
 * 这个版本」的那个版本不再弹**（下一个版本照常弹）。这两条决定了「每次打开都弹窗」
 * 会不会变成骚扰，所以单独钉住。
 */
class UpdateLaunchPolicyTest {

    @Test
    fun promptsOnlyForANewerVersion() {
        assertTrue(shouldPrompt("0.9.0-rc44", null, "0.9.0-rc45"))
        assertFalse(shouldPrompt("0.9.0-rc45", null, "0.9.0-rc45"), "同版本不弹")
        assertFalse(shouldPrompt("0.9.0-rc45", null, "0.9.0-rc44"), "老版本不弹")
        assertFalse(shouldPrompt("dev", null, "0.9.0-rc45"), "读不出版本时按「没有新版」处理")
    }

    @Test
    fun aSkippedVersionStopsPromptingUntilTheNextOne() {
        assertFalse(shouldPrompt("0.9.0-rc44", "0.9.0-rc45", "0.9.0-rc45"), "跳过过的版本不再打扰")
        assertTrue(shouldPrompt("0.9.0-rc44", "0.9.0-rc45", "0.9.0-rc46"), "下一个版本照常提示")
    }

    @Test
    fun blankSkipIsTreatedAsNotSkipped() {
        assertTrue(shouldPrompt("0.9.0-rc44", "", "0.9.0-rc45"))
        assertTrue(shouldPrompt("0.9.0-rc44", "   ", "0.9.0-rc45"))
    }

    @Test
    fun skipFileLineEndingsDoNotBreakTheComparison() {
        // 跳过文件里存的是一行版本名（可能带空白/换行），比较要按 trim 后的值。
        assertFalse(shouldPrompt("0.9.0-rc44", " 0.9.0-rc45\n", "0.9.0-rc45"))
    }
}
