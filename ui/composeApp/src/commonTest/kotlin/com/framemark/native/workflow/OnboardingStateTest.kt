package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 新手教程的首次判定与「看过了」标记（纯逻辑，没有界面、没有平台目录）。
 *
 * 三个必须成立的约定：
 *  1. 全新安装（文件里没有 `onboarding.done`）→ 显示；
 *  2. 跳过**或**完成之后 → 同一位被写成 true，之后不再显示；
 *  3. 再次打开教程不会把这个状态改回去，也不会多写一次盘。
 */
class OnboardingStateTest {

    @Test
    fun firstLaunchShowsTheTutorial() {
        val store = MemoryOnboardingStore()

        assertTrue(OnboardingState.shouldShow(store), "全新安装应当自动弹教程")
        assertFalse(OnboardingState.isDone(store))
        assertEquals(0, store.writes, "只读判定不该写盘")
    }

    @Test
    fun skipMarksDoneAndItNeverShowsAgain() {
        val store = MemoryOnboardingStore()

        // 跳过按钮走的正是这一句（那片遮罩的 onSkip）。
        OnboardingState.markDone(store)

        assertTrue(OnboardingState.isDone(store))
        assertFalse(OnboardingState.shouldShow(store), "跳过之后不该每次启动都弹")
        assertEquals(mapOf(OnboardingState.KEY_DONE to true), store.snapshot)
        assertEquals(1, store.writes)
    }

    @Test
    fun finishingTheLastPageAlsoMarksDone() {
        val store = MemoryOnboardingStore()

        // 完成按钮（最后一页的「开始使用」）与跳过是同一个入口。
        OnboardingState.markDone(store)

        assertFalse(OnboardingState.shouldShow(store))
        assertEquals(1, store.writes)
    }

    @Test
    fun reopeningTheTutorialLeavesDoneAlone() {
        val store = MemoryOnboardingStore()
        OnboardingState.markDone(store)

        // 设置 / 齿轮里「新手教程」只是把覆盖层打开：它读状态，不写状态。
        assertFalse(OnboardingState.shouldShow(store), "手动打开照旧是已看过")
        assertTrue(OnboardingState.isDone(store))

        // 走完/关掉之后会再标记一次，但已经是 done，所以不产生第二次写入。
        OnboardingState.markDone(store)
        assertEquals(1, store.writes, "重复标记不该再写一次")
        assertEquals(mapOf(OnboardingState.KEY_DONE to true), store.snapshot)
        assertFalse(OnboardingState.shouldShow(store))
    }

    @Test
    fun aStoreThatAlreadySaysDoneStaysClosed() {
        // 老用户升级上来：文件里已经有 onboarding.done=true。
        val store = MemoryOnboardingStore(mapOf(OnboardingState.KEY_DONE to true))

        assertFalse(OnboardingState.shouldShow(store))
        OnboardingState.markDone(store)
        assertEquals(0, store.writes, "已看过的安装不该被标记写盘")
    }
}
