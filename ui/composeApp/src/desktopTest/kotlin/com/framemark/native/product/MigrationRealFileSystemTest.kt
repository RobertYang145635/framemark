package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.workflow.JvmProductFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W3-07② / W3-05 acceptance on the **real** product file system (`JvmProductFileSystem`);
 * the sibling unit tests prove the same rules with an in-memory `FakeFs`.
 *
 * The first test pins the state a restart sees when execution stopped *immediately after the
 * replace succeeded and before the transaction's final re-read/re-check*: the directory is
 * snapshotted at exactly that instant and reloaded through a fresh store. The Windows process
 * kill that produced the matching byte evidence lives in the external crash harness
 * (`MigrationCrashProbeW23.kt`, see `w3/migration-crash-w23/result.json`); this test is the
 * durable state half of that pair and asserts nothing about process termination itself.
 *
 * The other three are the real-file versions of W3-05: a backup path that cannot hold the
 * backup (occupied by a directory), a backup that already holds different bytes, and a library
 * rewritten by somebody else after the backup. Each has to stop with the source bytes intact
 * and must never mistake a bad backup for a valid one.
 */
class MigrationRealFileSystemTest {
    private val legacyText = """
        {"version":1,"presets":[
        {"id":"legacy-1","name":"中文旧预设一","updatedAt":10,
        "cardId":"simple-4","cardName":"极简 04","adjustedCount":2,
        "export":{"format":"JPG","quality":86,"longEdge":1600,"webpLossless":true,"filename":"{name}-{日期}"},
        "values":{"simple-4::inset":0.12,"simple-4::model":"X-T5"}},
        {"id":"legacy-2","name":"中文旧预设二","updatedAt":11,
        "cardId":"glass-white-1","cardName":"玻璃白 01","adjustedCount":1,
        "export":{"format":"PNG","quality":100,"longEdge":1200,"webpLossless":false,"filename":"{photo}-{index}"},
        "values":{"glass-white-1::inset":0.2}}]}
    """.trimIndent() + "\n"

    private fun converted(): List<Preset> = listOf(
        samplePreset().copy(id = "legacy-1", name = "中文旧预设一", createdAt = 10, updatedAt = 10),
        samplePreset(assetBytes = ByteArray(96) { (it * 3 + 7).toByte() })
            .copy(id = "legacy-2", name = "中文旧预设二", createdAt = 11, updatedAt = 11),
    )

    private fun bytes(p: Path): ByteArray = Files.readAllBytes(p)
    private fun write(p: Path, data: ByteArray) { Files.write(p, data) }

    /** A real install-shaped data directory on disk, plus its expected SHA-named backup path. */
    private inner class TempDir(label: String) {
        val root: Path = Files.createTempDirectory("fm-mig-real-$label-")
        val dataDir: String = Files.createDirectories(root.resolve("data")).toString()
        val libPath: String = dataDir.trimEnd('/', '\\') + "/presets.json"

        fun library(): Path = Path.of(libPath)
        fun backupFor(original: ByteArray): Path = Path.of("$libPath.legacy-${Sha256.hex(original)}.bak")
        fun seedLegacy(): ByteArray = legacyText.encodeToByteArray().also { write(library(), it) }
        fun store(fs: ProductFileSystem = JvmProductFileSystem(dataDir)) =
            PhoneProjectStore(fs, io = null, platform = null)
        fun fs(): ProductFileSystem = JvmProductFileSystem(dataDir)

        fun cleanup() {
            runCatching { Files.walk(root).use { p ->
                p.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            } }
        }
    }

    private inline fun withTemp(label: String, block: (TempDir) -> Unit) {
        val t = TempDir(label)
        try {
            block(t)
        } finally {
            t.cleanup()
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.newDirectoryStream(from).use { entries ->
            entries.forEach { e ->
                if (e.toFile().isFile) {
                    Files.copy(e, to.resolve(e.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun noTempLeftIn(dir: String) {
        Files.newDirectoryStream(Path.of(dir)).use { stream ->
            assertFalse(stream.any { it.fileName.toString().endsWith(".tmp") }, "$dir 里不得留下暂存文件")
        }
    }

    @Test
    fun a_replace_that_succeeded_and_was_never_re_read_reloads_as_the_complete_new_library() {
        withTemp("after-replace") { t ->
            val original = t.seedLegacy()
            val expected = converted()
            val live = t.fs()
            val frozen = t.root.resolve("frozen")
            // Freeze the directory the instant the real rename returns success: everything a
            // restart could see inside the unchecked window, before any later step of the
            // transaction had a chance to verify the document or roll it back.
            val fs = object : ProductFileSystem by live {
                override fun rename(from: String, to: String): Boolean {
                    val replaced = live.rename(from, to)
                    if (replaced && to == t.libPath) copyTree(Path.of(t.dataDir), frozen)
                    return replaced
                }
            }
            val store = t.store(fs)
            store.loadPresets()
            assertEquals(2, store.importLegacyPresets(expected, expectedCount = 2))
            assertTrue(bytes(frozen.resolve("presets.json")).size > 0, "窗口内的现场必须已快照")

            // A brand-new store over the frozen bytes is what the next process boots into.
            val restarted = PhoneProjectStore(JvmProductFileSystem(frozen.toString()), io = null, platform = null)
            restarted.loadPresets()
            assertEquals(expected, restarted.presets, "重启必须读到完整的新库，不能是半解码或空库")
            assertEquals(store.presets, restarted.presets)
            assertTrue(
                LegacyDocuments.isProductPresetLibrary(Files.readString(frozen.resolve("presets.json"))),
                "现场必须已经是产品文档",
            )
            assertContentEquals(
                original,
                bytes(frozen.resolve("presets.json.legacy-${Sha256.hex(original)}.bak")),
                "原始旧库字节必须在备份里逐字节可用",
            )
            noTempLeftIn(frozen.toString())
        }
    }

    @Test
    fun a_backup_path_occupied_by_a_directory_stops_the_migration_and_really_denies_the_write() {
        withTemp("backup-dir") { t ->
            val original = t.seedLegacy()
            val backup = t.backupFor(original)
            Files.createDirectories(backup)
            val fs = t.fs()
            // Prove the denial is real (not a stubbed false) before asserting the reaction.
            assertFalse(fs.writeBytes(backup.toString(), original), "目录占位必须真的拒绝生产写入")
            assertFalse(fs.writeBytesIfAbsent(backup.toString(), original), "独占创建也必须真的失败")

            val store = t.store(fs)
            store.loadPresets()
            assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))
            assertContentEquals(original, bytes(t.library()), "旧库字节必须原样保留")
            assertTrue(Files.isDirectory(backup), "备份路径仍是目录：迁移没有伪造一份备份冒充成功")
            assertEquals(0, Files.list(backup).use { it.count() }.toInt(), "拒绝迁移不得在备份目录里留下东西")
            assertNotNull(store.presetError)
            assertTrue("备份" in store.presetError!!, store.presetError!!)
            noTempLeftIn(t.dataDir)
        }
    }

    @Test
    fun a_backup_holding_different_bytes_is_refused_and_never_overwritten() {
        withTemp("backup-wrong") { t ->
            val original = t.seedLegacy()
            val backup = t.backupFor(original)
            val wrong = """{"version":1,"presets":[]}""".encodeToByteArray()
            write(backup, wrong)

            val store = t.store(t.fs())
            store.loadPresets()
            assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))
            assertContentEquals(wrong, bytes(backup), "错误备份既没被认错为有效，也没被静默改写")
            assertContentEquals(original, bytes(t.library()), "旧库字节必须原样保留")
            assertNotNull(store.presetError)
            assertTrue("备份" in store.presetError!!, store.presetError!!)
            noTempLeftIn(t.dataDir)
        }
    }

    @Test
    fun a_library_rewritten_after_the_backup_aborts_the_migration_before_any_replace() {
        withTemp("lib-changed") { t ->
            val original = t.seedLegacy()
            val live = t.fs()
            val outsider = t.fs()
            var tampered = false
            // Somebody else edits the live library right after the backup landed: same length,
            // one byte different, so only the product's own byte comparison can notice it.
            val fs = object : ProductFileSystem by live {
                override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean {
                    val wrote = live.writeBytesIfAbsent(path, bytes)
                    if (wrote && ".legacy-" in path && !tampered) {
                        val flip = original.copyOf()
                        flip[5] = (flip[5].toInt() xor 0x01).toByte()
                        tampered = outsider.writeBytes(t.libPath, flip)
                    }
                    return wrote
                }
            }
            val store = t.store(fs)
            store.loadPresets()
            val returned = store.importLegacyPresets(converted(), expectedCount = 2)
            val current = bytes(t.library())
            // The fixture must really have touched the library, otherwise this test proves nothing.
            assertTrue(tampered, "夹具必须真的在备份写好后改到过旧库")
            assertFalse(current.contentEquals(original), "第三方改动必须仍留在现场：迁移没有把库换回旧字节也没有换成新库")
            assertEquals(0, returned, "库在备份后被改动，迁移必须拒绝")
            assertFalse(LegacyDocuments.isProductPresetLibrary(current.decodeToString()), "现场不得是迁移产物")
            assertContentEquals(original, bytes(t.backupFor(original)), "备份必须仍是改动前的完整原始字节")
            assertNotNull(store.presetError)
            assertTrue("变化" in store.presetError!! && "原文件未更改" in store.presetError!!, store.presetError!!)
            noTempLeftIn(t.dataDir)
        }
    }
}
