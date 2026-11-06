package com.framemark.native.shell

import com.framemark.native.library.RecentFrames
import com.framemark.native.shell.desktop.DesktopRecentFramesStore
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面“最近使用”文件：`<数据目录>\settings\recent-frames.json`。
 * 覆盖写入后再读出一致、临时文件不残留、文件损坏 / 缺失 / 版本不认识时清空。
 */
class DesktopRecentFramesStoreTest {

    private val dir: File = File(
        System.getProperty("java.io.tmpdir"),
        "fm-d1-recent-" + UUID.randomUUID().toString().take(8),
    ).apply { mkdirs() }

    private val file = File(dir, RecentFrames.FILE_NAME)
    private val store = DesktopRecentFramesStore(file)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun save_then_load_round_trips_in_recent_order() {
        store.save(listOf("simple-1", "wm-classic", "glass-card-4"))
        assertEquals(listOf("simple-1", "wm-classic", "glass-card-4"), store.load())
        assertTrue(file.isFile, "文件写到 ${RecentFrames.FILE_NAME}")
        assertFalse(File(dir, RecentFrames.FILE_NAME + DesktopRecentFramesStore.TEMP_SUFFIX).exists(),
            "原子写完成后不留 .tmp")
    }

    @Test
    fun save_normalises_to_the_shared_codec() {
        store.save(listOf("a", "a", " ", "b"))
        assertEquals(listOf("a", "b"), store.load(), "编码统一走 RecentFrames.encode")
    }

    @Test
    fun missing_file_reads_empty() {
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun corrupt_file_reads_empty_without_throwing() {
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json", Charsets.UTF_8)
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun unknown_version_reads_empty() {
        file.parentFile?.mkdirs()
        file.writeText("""{"version":99,"cards":["a"]}""", Charsets.UTF_8)
        assertEquals(emptyList(), store.load())
    }
}
