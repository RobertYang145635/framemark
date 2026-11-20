package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 舞台那一个提示位画哪一句。三条共用一个位置，所以优先级是行为的一部分：
 * 删除只能救 4 秒（破坏性），排第一；「已撤销 · 重做」比「已换边框 · 撤销」更即时，
 * 排第二；换边框可以从顶栏再来一次，排最后。
 */
class PhoneStageNoticeTest {

    @Test
    fun removalWinsOverTheOtherTwo() {
        assertEquals(
            PhoneStageNotice.Removed("1000084839.jpg"),
            stageNotice("1000084839.jpg", undoNotice = true, borderNotice = true),
        )
    }

    @Test
    fun undoWinsOverTheBorderSwitch() {
        assertEquals(
            PhoneStageNotice.RedoAvailable,
            stageNotice(null, undoNotice = true, borderNotice = true),
        )
    }

    @Test
    fun aBorderSwitchAloneStillGetsItsWayBack() {
        assertEquals(
            PhoneStageNotice.BorderSwitched,
            stageNotice(null, undoNotice = false, borderNotice = true),
        )
    }

    @Test
    fun nothingToSayDrawsNothing() {
        assertNull(stageNotice(null, undoNotice = false, borderNotice = false))
    }

    @Test
    fun aBlankLabelIsNotANotice() {
        // 平台可能给出没有文件名的照片：空白标签不能画成「已移除  · 撤销」。
        assertNull(stageNotice("   ", undoNotice = false, borderNotice = false))
    }

    @Test
    fun theLabelIsTrimmedAndKepsTheFilename() {
        assertEquals("已移除 a.jpg", (stageNotice(" a.jpg ", false, false) as PhoneStageNotice.Removed).text)
    }
}
