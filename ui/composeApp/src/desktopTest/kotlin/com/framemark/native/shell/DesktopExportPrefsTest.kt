package com.framemark.native.shell

import com.framemark.native.workflow.DesktopWorkflowPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「记住上一次的导出目录与质量」（UX-RESEARCH A-06）。
 *
 * 这一层不在 WP-D2 新写的文件里：桌面平台把这两项写在 `<数据目录>\local-export.properties`
 * （原子替换），导出对话框读它、`更改…` 写它，批量任务也用它当输出目录。这里证明往返与
 * 落盘确实成立，免得界面「显示记住了」而文件没写。
 */
class DesktopExportPrefsTest {

    private fun tempDataDir(): File = Files.createTempDirectory("fm-export-prefs").toFile()

    @Test
    fun the_saved_directory_and_quality_come_back_on_the_next_launch() {
        val data = tempDataDir()
        try {
            val first = DesktopWorkflowPlatform(dataDir = data.absolutePath)
            assertEquals(100, first.preferredJpegQuality(), "默认质量 100")
            assertTrue(first.exportDir.isNotBlank())

            // 与「更改…」同一条写路径：平台自己的本地偏好文件。
            val chosen = File(data, "picked-output").apply { mkdirs() }.absolutePath
            File(data, "local-export.properties").writeText(
                "directory=${chosen.replace("\\", "\\\\")}\njpegQuality=77\n",
                Charsets.ISO_8859_1,
            )

            val second = DesktopWorkflowPlatform(dataDir = data.absolutePath)
            assertEquals(chosen, second.exportDir, "上次选的目录下次还在")
            assertEquals(77, second.preferredJpegQuality(), "质量也一起记住")

            val updated = second.setPreferredJpegQuality(65)
            assertEquals(null, updated, "60–100 是合法范围")
            val third = DesktopWorkflowPlatform(dataDir = data.absolutePath)
            assertEquals(65, third.preferredJpegQuality(), "写回后新实例读得到")
            assertEquals(65, second.preferredJpegQuality(), "同实例也立刻生效")

            assertTrue(second.setPreferredJpegQuality(101) != null, "越界拒绝")
            assertEquals(65, third.preferredJpegQuality(), "拒绝的写入不能落盘")
        } finally {
            data.deleteRecursively()
        }
    }

    @Test
    fun a_broken_preference_file_falls_back_to_the_defaults() {
        val data = tempDataDir()
        try {
            File(data, "local-export.properties").writeText("jpegQuality=不支持\n", Charsets.ISO_8859_1)
            val platform = DesktopWorkflowPlatform(dataDir = data.absolutePath)
            assertEquals(100, platform.preferredJpegQuality(), "读不出来就退回 100")
            assertTrue(platform.exportDir.isNotBlank())
        } finally {
            data.deleteRecursively()
        }
    }
}
