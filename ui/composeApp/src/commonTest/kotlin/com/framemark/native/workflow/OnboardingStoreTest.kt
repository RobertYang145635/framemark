package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `settings/onboarding.txt` 的读写：与 `presets.json` / `appearance.json` 同一条路
 * （`WorkflowPlatform` 的文本读写），只是内容小到一行键值。
 *
 * 这里用一个内存版 [WorkflowPlatform]，不碰 `WorkflowEnv` 的全局状态，也不写真实
 * 数据目录 —— 否则同一个 JVM 里跑的其他测试会看到被改掉的平台。
 */
class OnboardingStoreTest {

    private class FakePlatform(override val dataDir: String) : WorkflowPlatform {
        override val exportDir: String = "$dataDir/exports"
        val files = LinkedHashMap<String, String>()

        override fun readTextFile(path: String): String? = files[path]

        override fun writeTextFile(path: String, text: String) {
            files[path] = text
        }

        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.decodeToString()
            return true
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun fileSize(path: String): Long = files[path]?.length?.toLong() ?: 0L

        override fun readBytes(path: String): ByteArray? = files[path]?.encodeToByteArray()

        override fun encodeSampleImage(
            width: Int,
            height: Int,
            format: SampleFormat,
            quality: Int,
        ): ByteArray? = null

        override fun dateStamp(): String = "20261030"

        override fun timeStamp(): String = "120000"

        override fun formatDateTime(epochMillis: Long): String = ""

        override fun nowMillis(): Long = 0L
    }

    @Test
    fun theFlagLandsInTheSameSettingsFolderAsTheOtherStateFiles() {
        val platform = FakePlatform("/app-data")
        val store = WorkflowOnboardingStore(platform)

        assertEquals("/app-data/${WorkflowOnboardingStore.FILE}", store.settingsPath)
        assertTrue(store.settingsPath.endsWith("settings/onboarding.txt"), store.settingsPath)
    }

    @Test
    fun aFreshInstallHasNoFileSoTheTutorialShows() {
        val platform = FakePlatform("/app-data")
        val store = WorkflowOnboardingStore(platform)

        assertTrue(platform.files.isEmpty(), "第一次启动不该有状态文件")
        assertEquals(false, store.getBoolean(OnboardingState.KEY_DONE, false))
        assertTrue(OnboardingState.shouldShow(store))
    }

    @Test
    fun markDoneSurvivesANewProcessOverTheSameFolder() {
        val platform = FakePlatform("/app-data")
        OnboardingState.markDone(WorkflowOnboardingStore(platform))

        // 下一次启动：新的 store 实例读同一个目录，仍然是 done（不再弹）。
        val reopened = WorkflowOnboardingStore(platform)
        assertTrue(OnboardingState.isDone(reopened))
        assertFalse(OnboardingState.shouldShow(reopened))
        assertEquals(true, reopened.getBoolean(OnboardingState.KEY_DONE, false))
        assertEquals(1, WorkflowOnboardingStore.decode(platform.files.getValue(reopened.settingsPath)).size)
    }

    @Test
    fun aCorruptFileFallsBackToNotSeen() {
        val platform = FakePlatform("/app-data")
        val path = "/app-data/${WorkflowOnboardingStore.FILE}"
        // 半个文件 / 乱码 / 别的键：都读不出 done，教程照常显示一次。
        listOf("onboarding.done=", "onboarding.done=yes", "\u0000\u0001garbage", "other=true").forEach { text ->
            platform.files[path] = text
            assertTrue(
                OnboardingState.shouldShow(WorkflowOnboardingStore(platform)),
                "corrupt='$text' 应当当作没看过",
            )
        }
    }

    @Test
    fun theEncoderWritesOneLinePerKey() {
        val text = WorkflowOnboardingStore.encode(
            linkedMapOf(
                OnboardingState.KEY_DONE to true,
                "another.flag" to false,
            ),
        )

        assertTrue(text.contains("onboarding.done=true"), text)
        assertTrue(text.contains("another.flag=false"), text)
        assertEquals(
            mapOf(OnboardingState.KEY_DONE to true, "another.flag" to false),
            WorkflowOnboardingStore.decode(text),
        )
        // 注释行不会被读成键。
        assertTrue(WorkflowOnboardingStore.decode("# onboarding.done=true\n").isEmpty())
    }
}
