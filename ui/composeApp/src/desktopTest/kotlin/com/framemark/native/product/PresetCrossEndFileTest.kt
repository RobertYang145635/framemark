package com.framemark.native.product

import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.PresetTransfer
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B2-B / B: the preset file is the cross-end contract, and it can be checked without
 * a window. The controller writes a real file, we read it back through the same
 * import path the other end uses, and the three import choices are pinned:
 * 覆盖只动所选项、另存拿新 id、取消（不调用导入）原条目与字节不变。
 */
class PresetCrossEndFileTest {
    private fun asset(name: String): String = DesktopTestImageFixtures.path(name)

    private class TempPlatform(override val dataDir: String) : WorkflowPlatform {
        override val exportDir: String = "$dataDir/out"
        override fun readTextFile(path: String): String? = File(path).takeIf { it.isFile }?.readText()
        override fun writeTextFile(path: String, text: String) { File(path).apply { parentFile?.mkdirs() }.writeText(text) }
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            return true
        }
        override fun exists(path: String): Boolean = File(path).exists()
        override fun fileSize(path: String): Long = File(path).length()
        override fun readBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261103"
        override fun timeStamp(): String = "013000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 1_700_000_000_000
    }

    private fun controller(dir: Path): DesktopProjectController = DesktopProjectController(
        dataDir = dir.toString(),
        store = SessionStore(JvmProductFileSystem(dir.toString())),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { 1_700_000_000_000 },
    )

    private suspend fun <T> withPlatform(dir: Path, block: suspend () -> T): T {
        val old = WorkflowEnv.platform
        WorkflowEnv.platform = TempPlatform(dir.toString())
        return try { block() } finally { WorkflowEnv.platform = old }
    }

    @Test
    fun the_exported_file_carries_the_design_but_not_this_machines_quality() = runBlocking {
        val dir = Files.createTempDirectory("fm-b2b-preset-file-").toFile()
        try {
            withPlatform(dir.toPath()) {
                val controller = controller(dir.toPath())
                controller.load()
                controller.importPhotos(listOf(asset("vertical.jpg")))
                controller.selectPhoto(controller.photos.first().id)
                controller.applyCard("simple-1", "simple-1")

                val saved = assertNotNull(controller.savePreset("跨端预设"), "保存预设失败：${controller.presetError}")
                val target = File(dir, "跨端预设.json")
                assertTrue(controller.exportPresetFile(saved.id, target.path), "导出预设文件失败")
                val text = target.readText()

                // 本机项不得混进文件；设计与可共享导出设置必须在。
                assertTrue("\"quality\"" !in text, "预设文件不得带本机 JPEG 质量")
                assertTrue(saved.design.cardId in text, "预设文件应包含设计")
                assertTrue("\"schemaVersion\"" in text)

                // 另一端的读取路径：readPresetFile → 与导出前的对象一致（除 quality 归 100）。
                val reread = assertNotNull(controller.readPresetFile(target.path), "读回预设失败")
                assertEquals(saved.id, reread.id)
                assertEquals(saved.design.cardId, reread.design.cardId)
                assertEquals(saved.exportSettings.format, reread.exportSettings.format)
                assertEquals(saved.exportSettings.sizeMode, reread.exportSettings.sizeMode)
                assertEquals(100, reread.exportSettings.quality, "跨端读回的质量应为默认 100")
                controller.dispose()
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun overwrite_save_as_and_cancel_are_distinguishable_at_the_store_level() = runBlocking {
        val dir = Files.createTempDirectory("fm-b2b-preset-modes-").toFile()
        try {
            withPlatform(dir.toPath()) {
                val controller = controller(dir.toPath())
                controller.load()
                controller.importPhotos(listOf(asset("vertical.jpg")))
                controller.selectPhoto(controller.photos.first().id)
                controller.applyCard("simple-1", "simple-1")

                val original = assertNotNull(controller.savePreset("原始"), "保存失败")
                val file = File(dir, "原始.json")
                assertTrue(controller.exportPresetFile(original.id, file.path))
                val bytesBefore = file.readBytes()
                val countBefore = controller.presets.size

                // 取消 = 不调用导入：库与文件都不动。
                assertEquals(countBefore, controller.presets.size)
                assertTrue(bytesBefore.contentEquals(file.readBytes()))

                // 同 id 冲突且未选择覆盖：拒绝，原条目保持不变。
                val incoming = assertNotNull(controller.readPresetFile(file.path))
                assertEquals(false, controller.importPreset(incoming, replaceExisting = false), "同 id 未覆盖时应拒绝")
                assertEquals(countBefore, controller.presets.size)
                assertEquals("原始", controller.presets.first { it.id == original.id }.name)

                // 另存：新 id 入库，原条目还在。
                val copy = PresetTransfer.copyWithNewId(incoming, controller.presets.map { it.id }.toSet())
                assertTrue(controller.importPreset(copy, replaceExisting = false), "另存失败")
                assertEquals(countBefore + 1, controller.presets.size)
                assertEquals("原始", controller.presets.first { it.id == original.id }.name)

                // 覆盖：只动所选 id，条数不变，名称被更新。
                val renamed = incoming.copy(name = "覆盖后的名字")
                assertTrue(controller.importPreset(renamed, replaceExisting = true), "覆盖失败")
                assertEquals(countBefore + 1, controller.presets.size)
                assertEquals("覆盖后的名字", controller.presets.first { it.id == original.id }.name)
                assertNull(controller.presetError)
                controller.dispose()
            }
        } finally { dir.deleteRecursively() }
    }
}
