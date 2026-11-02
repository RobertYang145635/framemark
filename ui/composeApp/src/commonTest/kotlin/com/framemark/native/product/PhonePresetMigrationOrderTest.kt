package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.shell.SAMPLE_PHOTO_ID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W3 persistence ordering acceptance for the Android preset migration.
 *
 * §5.2 fixes the order: 识别格式 → 备份原始字节和 SHA → 完整转换 → 写暂存文件 →
 * **解码/语义读回校验成功后才替换原库** → 最终再复核.
 *
 * The three tests ending `…NeverReachesTheLiveLibrary` / `…BeforeTheFirstRename…` are
 * the ordering proof. Their injected fault is deliberately invisible to every byte-level
 * check the plain save transaction makes (the write returned true, the size matches,
 * `readBytes` matches the intended bytes and the JSON root parses): the staged document
 * is a *structurally valid product preset library* that only a full decode reveals to
 * mean something else than the converted list — here one preset's embedded watermark is
 * silently lost, with the same count, the same ids and the same names. The recovery path
 * is then blocked, so a replace-before-decode implementation cannot hide behind its
 * auto-restore and the two implementations end in different states:
 *  - replace before decode → the live library holds the semantically invalid document;
 *  - decode before replace → the live library still holds the exact legacy bytes.
 */
class PhonePresetMigrationOrderTest {
    private val legacyText = """
        {"version":1,"presets":[
        {"id":"legacy-1","name":"旧预设一","updatedAt":10,
        "cardId":"simple-4","cardName":"simple-4","adjustedCount":0,
        "export":{"format":"JPG","quality":86,"longEdge":1600,"webpLossless":true,"filename":"{name}-{date}"},
        "values":{}},
        {"id":"legacy-2","name":"旧预设二","updatedAt":11,
        "cardId":"glass-white-1","cardName":"glass-white-1","adjustedCount":2,
        "export":{"format":"JPG","quality":90,"longEdge":1200,"webpLossless":false,"filename":"{name}-{date}"},
        "values":{}}]}
    """.trimIndent() + "\n"

    private fun converted(): List<Preset> = listOf(
        samplePreset().copy(id = "legacy-1", name = "旧预设一"),
        samplePreset(assetBytes = ByteArray(96) { (it * 3 + 7).toByte() })
            .copy(id = "legacy-2", name = "旧预设二", createdAt = 11, updatedAt = 11),
    )

    /** A real product library document, produced through the public store API. */
    private fun productLibrary(presets: List<Preset>): String {
        val scratch = FakeFs()
        assertTrue(SessionStore(scratch).savePresets(presets) is StoreResult.Ok)
        return scratch.files.getValue(scratch.presetsPath).decodeToString()
    }

    // ---- the ordering proof ------------------------------------------------

    @Test
    fun stagedLibraryThatDecodesToAWatermarkLosingListNeverReachesTheLiveLibrary() {
        val base = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        base.files[base.presetsPath] = legacyBytes.copyOf()
        val staged = converted()
        // Same count, same ids, same names: only a full decode shows the lost watermark.
        val lossy = productLibrary(
            listOf(staged[0].copy(design = staged[0].design.withoutOverlay()), staged[1]),
        )
        val fs = LossyDecodeViewFs(base, base.presetsPath, lossy, legacyBytes)
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(staged, expectedCount = 2))

        assertContentEquals(
            legacyBytes,
            base.files.getValue(base.presetsPath),
            "暂存文档解码后与转换列表不一致时必须在替换前拒绝：线上预设库必须仍是原始旧库字节",
        )
        assertTrue(
            fs.renamedNonOriginalToLive.isEmpty(),
            "语义未验证的文档不得 rename 到线上库：${fs.renamedNonOriginalToLive}",
        )
        assertFalse(base.files.containsKey(fs.tempPath), "被拒绝的暂存文件必须清理")
        val backup = base.files.keys.single { ".legacy-" in it }
        assertContentEquals(legacyBytes, base.files.getValue(backup), "备份必须保留原始字节")
        assertTrue(backup.contains(Sha256.hex(legacyBytes)))
        assertTrue(
            store.presetError?.startsWith("FM-E-PRESET-001") == true,
            "迁移失败必须保留禁写错误码：${store.presetError}",
        )

        // 禁写：失败后任何真实写入口都不能用内存（空）库替换原库。
        assertFalse(store.importPreset(samplePreset().copy(id = "after-failure")))
        assertEquals(0, store.importLegacyPresets(staged, expectedCount = 2))
        assertContentEquals(legacyBytes, base.files.getValue(base.presetsPath))
    }

    @Test
    fun stagedLibraryIsDecodedBeforeTheFirstRenameOntoTheLiveLibrary() {
        val base = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        base.files[base.presetsPath] = legacyBytes.copyOf()
        val staged = converted()
        val lossy = productLibrary(
            listOf(staged[0], staged[1].copy(design = staged[1].design.withoutOverlay())),
        )
        val fs = LossyDecodeViewFs(base, base.presetsPath, lossy, legacyBytes)
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(staged, expectedCount = 2))

        // Evidence only: the exact file-operation order the migration took.
        println("FM_MIGRATION_OPS ${fs.ops}")
        val decodedStaged = fs.ops.indexOfFirst { it == "readText ${fs.tempPath}" }
        val firstReplace = fs.ops.indexOfFirst { it == "rename ${fs.tempPath} -> ${base.presetsPath}" }
        assertTrue(
            decodedStaged >= 0 && (firstReplace < 0 || decodedStaged < firstReplace),
            "替换线上库之前必须先完整解码暂存文档（readText 就是编解码器的读取通道）；实际操作序列：${fs.ops}",
        )
        assertTrue(
            fs.renamedNonOriginalToLive.isEmpty(),
            "语义未验证的文档不得替换线上库：${fs.renamedNonOriginalToLive}",
        )
        assertContentEquals(legacyBytes, base.files.getValue(base.presetsPath))
    }

    @Test
    fun stagedLibraryThatFailsToDecodeAtAllNeverReachesTheLiveLibrary() {
        val base = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        base.files[base.presetsPath] = legacyBytes.copyOf()
        val fs = LossyDecodeViewFs(base, base.presetsPath, "{", legacyBytes)
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(legacyBytes, base.files.getValue(base.presetsPath), "暂存解码失败时原库字节必须不变")
        assertTrue(
            fs.renamedNonOriginalToLive.isEmpty(),
            "解码失败的暂存文档不得替换线上库：${fs.renamedNonOriginalToLive}",
        )
        assertTrue(store.presetError?.startsWith("FM-E-PRESET-001") == true, store.presetError.orEmpty())
    }

    // ---- W3-04：重复 ID -------------------------------------------------------

    @Test
    fun duplicateIdsInALegacyOrProductLibraryAreRefusedWithoutTouchingTheDocument() {
        // (a) The old hand-written envelope may carry two rows with the same id; the
        //     retained ID gate has to refuse before anything is staged or renamed.
        val dupLegacy = """
            {"version":1,"presets":[
            {"id":"legacy-1","name":"旧预设一","updatedAt":10,
            "cardId":"simple-4","cardName":"simple-4","adjustedCount":0,
            "export":{"format":"JPG","quality":86,"longEdge":1600,"webpLossless":true,"filename":"{name}-{date}"},
            "values":{}},
            {"id":"legacy-1","name":"旧预设二","updatedAt":11,
            "cardId":"glass-white-1","cardName":"glass-white-1","adjustedCount":0,
            "export":{"format":"JPG","quality":90,"longEdge":1200,"webpLossless":false,"filename":"{name}-{date}"},
            "values":{}}]}
        """.trimIndent() + "\n"
        val fs = FakeFs()
        val legacyBytes = dupLegacy.encodeToByteArray()
        fs.files[fs.presetsPath] = legacyBytes.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()
        val duplicated = listOf(
            samplePreset().copy(id = "legacy-1", name = "旧预设一"),
            samplePreset().copy(id = "legacy-1", name = "旧预设二", createdAt = 11, updatedAt = 11),
        )

        assertEquals(0, store.importLegacyPresets(duplicated, expectedCount = 2))

        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath), "重复 ID 迁移失败不得改写旧库")
        assertTrue(fs.renames.isEmpty(), "重复 ID 迁移不得替换线上库：${fs.renames}")
        assertTrue(
            store.presetError?.contains("ID 冲突") == true,
            "重复 ID 必须给出明确拒绝：${store.presetError}",
        )
        assertFalse(store.importPreset(samplePreset().copy(id = "ordinary-after-duplicate")))
        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath))

        // (b) A product library with duplicate ids is unreadable as a whole, so it can
        //     never be "repaired" by saving the empty in-memory list over it.
        val fs2 = FakeFs()
        val dupProduct = productLibrary(duplicated).encodeToByteArray()
        fs2.files[fs2.presetsPath] = dupProduct.copyOf()
        val store2 = PhoneProjectStore(fs2, io = null, platform = null)
        store2.loadPresets()

        assertTrue(store2.presets.isEmpty(), "重复 ID 文档不得部分加载")
        assertNotNull(store2.presetError)
        assertFalse(store2.importPreset(samplePreset()))
        assertEquals(0, store2.importLegacyPresets(converted(), expectedCount = 2))
        assertContentEquals(dupProduct, fs2.files.getValue(fs2.presetsPath), "不可读的产品库必须逐字节保留")
        assertTrue(fs2.files.keys.none { ".legacy-" in it }, "产品库不是旧格式，不能生成迁移备份")
        assertTrue(fs2.renames.none { it.second == fs2.presetsPath }, "重复 ID 文档不得被替换：${fs2.renames}")
    }

    // ---- W3-05：备份与迁移前变化 ---------------------------------------------

    @Test
    fun aPreExistingBackupWithDifferentBytesBlocksTheMigrationBeforeStaging() {
        val fs = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        fs.files[fs.presetsPath] = legacyBytes.copyOf()
        val backupPath = "${fs.presetsPath}.legacy-${Sha256.hex(legacyBytes)}.bak"
        // An earlier, wrong backup must never be accepted as "the" original bytes.
        fs.files[backupPath] = "被截断的旧备份".encodeToByteArray()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath), "错误备份下不得替换原库")
        assertContentEquals(
            "被截断的旧备份".encodeToByteArray(),
            fs.files.getValue(backupPath),
            "已存在的备份文件不得被静默改写",
        )
        assertFalse(fs.files.containsKey("${fs.presetsPath}.tmp"), "备份校验失败时不能进入暂存阶段")
        assertTrue(fs.renames.isEmpty(), "备份校验失败时不能有任何替换动作：${fs.renames}")
        assertTrue(store.presetError?.startsWith("FM-E-PRESET-001") == true, store.presetError.orEmpty())
    }

    @Test
    fun aFailedBackupWriteStopsBeforeAnyStagingOrRename() {
        val fs = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        fs.files[fs.presetsPath] = legacyBytes.copyOf()
        fs.failWritePath = "${fs.presetsPath}.legacy-${Sha256.hex(legacyBytes)}.bak"
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath))
        assertFalse(fs.files.containsKey("${fs.presetsPath}.tmp"), "备份失败时不能写暂存文件")
        assertTrue(fs.renames.isEmpty(), "备份失败时不能有任何替换动作：${fs.renames}")
        assertNotNull(store.presetError)
        assertTrue(store.presetError!!.startsWith("FM-E-PRESET-001"), store.presetError.orEmpty())
    }

    @Test
    fun theLibraryChangingAfterTheBackupAbortsBeforeStaging() {
        val base = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        base.files[base.presetsPath] = legacyBytes.copyOf()
        val fs = ChangedAfterBackupFs(base, base.presetsPath, Sha256.hex(legacyBytes))
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(legacyBytes, base.files.getValue(base.presetsPath), "迁移期间库被改动时必须放弃替换")
        assertFalse(base.files.containsKey("${base.presetsPath}.tmp"), "复核到变化后不能继续暂存/替换")
        assertTrue(fs.renamedToLive.isEmpty(), "迁移期间库被改动时不得替换线上库：${fs.renamedToLive}")
        assertTrue(store.presetError?.startsWith("FM-E-PRESET-001") == true, store.presetError.orEmpty())
    }

    // ---- W3-06：暂存截断 -------------------------------------------------------

    @Test
    fun aTruncatedStagedLibraryKeepsTheLegacyBytesAndBlocksEveryLaterWriteEntry() {
        val fs = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        fs.files[fs.presetsPath] = legacyBytes.copyOf()
        fs.partialWriteBytes = 32
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath), "截断的暂存文档不能替换原库")
        assertFalse(fs.files.containsKey("${fs.presetsPath}.tmp"), "截断暂存必须清理")
        val backup = fs.files.keys.single { ".legacy-" in it }
        assertContentEquals(legacyBytes, fs.files.getValue(backup))
        assertTrue(fs.renames.isEmpty(), "截断暂存不得替换线上库：${fs.renames}")
        assertNotNull(store.presetError)
        assertFalse(store.importPreset(samplePreset().copy(id = "after-truncation")))
        assertContentEquals(legacyBytes, fs.files.getValue(fs.presetsPath))
    }

    // ---- W3-08：真实写入口与不误锁 ---------------------------------------------

    @Test
    fun anUnreadableLibrarySurvivesQuickSaveAutosaveAndImportAttempts() {
        val fs = FakeFs()
        val original = """{"document":"framemark.presets","schemaVersion":9,"presets":[]}""".encodeToByteArray()
        fs.files[fs.presetsPath] = original.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()
        store.adoptSample()
        store.setDesign(SAMPLE_PHOTO_ID, PhotoDesign.base("simple-4"))
        // Land the debounced autosave synchronously so the rest of the case is deterministic.
        assertTrue(store.saveNow())

        // 快存 / 自动保存 / 预设写入口 / 旧库迁移入口，一个都不许把空库盖上去。
        assertNull(store.savePreset("快存预设"))
        assertTrue(store.saveNow())
        assertTrue(store.flushBlocking())
        store.loadPresets()
        assertFalse(store.importPreset(samplePreset()))
        assertFalse(store.renamePreset("legacy-1", "改名"))
        assertFalse(store.deletePreset("legacy-1"))
        assertEquals(0, store.importLegacyPresets(converted(), expectedCount = 2))

        assertContentEquals(original, fs.files.getValue(fs.presetsPath), "不可读预设库必须逐字节保留")
        assertTrue(fs.files.keys.none { ".legacy-" in it }, "非旧格式不能生成迁移备份")
        assertFalse(fs.files.containsKey("${fs.presetsPath}.tmp"))
        assertTrue(fs.renames.none { it.second == fs.presetsPath }, "禁写状态下不能替换预设库：${fs.renames}")
        assertTrue(store.presets.isEmpty())
        assertNotNull(store.presetError)
        assertTrue(store.presetError!!.startsWith("FM-E-PRESET-001"), store.presetError.orEmpty())
        // The project document is a separate authority and keeps saving.
        assertTrue(fs.files.containsKey(fs.projectPath))
    }

    @Test
    fun aReadableLibraryKeepsItsSaveCapabilityAfterATransientStagingFailure() {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()
        store.adoptSample()
        store.setDesign(SAMPLE_PHOTO_ID, PhotoDesign.base("simple-4"))
        assertTrue(store.saveNow())

        assertNotNull(store.savePreset("第一套"))
        assertEquals(1, store.presets.size)

        fs.failWritePath = "${fs.presetsPath}.tmp"
        assertNull(store.savePreset("写入失败"))
        assertEquals(1, store.presets.size)
        assertNotNull(store.presetError)

        // A transient IO failure is not a corrupt library: the next save still works.
        fs.failWritePath = null
        assertNotNull(store.savePreset("第二套"))
        assertEquals(2, store.presets.size)
        assertEquals(2, SessionStore(fs).loadPresets().ok().size)
    }

    // ---- W3a 迁移顺序的“物理可实现”变体（任务 B）--------------------------------

    /**
     * The physically-realizable counterpart of the decoder-visible/byte-invisible fault
     * injected by the three ordering tests above. There the staged document is *always*
     * the intended bytes to every byte check and only the codec's read channel sees a
     * different meaning. Here the tamper is a real mid-transaction rewrite: the staged
     * temp is opened and verified with the correct bytes, and a concurrent writer then
     * changes the on-disk temp **after** `saveAt`'s byte comparison but **before** the
     * atomic rename — a scenario any second process touching the temp folder can produce.
     *
     * It is modelled as a read-count-ordered double over [FakeFs]: the first `readBytes`
     * of the temp (the byte comparison) faithfully returns the intended bytes, and only
     * after returning does the backing temp become the tampered content that any later
     * reader — the staged decode in the fixed ordering, or the rename source in the
     * pre-fix ordering — actually sees. The `.restore.tmp` auto-restore is blocked so the
     * pre-fix implementation cannot hide behind its post-rename mitigation:
     *  - pre-fix `savePresets`/`saveAt` ordering renames the tampered temp straight onto
     *    the live library (byte compare already passed on the pre-tamper read);
     *  - the current `migrateLegacyPresetLibrary` decodes the staged temp before renaming,
     *    sees the tampered list, refuses, and leaves the exact original legacy bytes live
     *    with the temp cleaned.
     */
    @Test
    fun aConcurrentTempRewriteAfterTheByteCompareNeverReachesTheLiveLibrary() {
        val base = FakeFs()
        val legacyBytes = legacyText.encodeToByteArray()
        base.files[base.presetsPath] = legacyBytes.copyOf()
        val staged = converted()
        // A structurally valid product library that decodes to the same count / ids / names
        // but silently loses the second watermark: an ordered rewrite, not corruption.
        val tampered = productLibrary(
            listOf(staged[0], staged[1].copy(design = staged[1].design.withoutOverlay())),
        ).encodeToByteArray()
        val fs = MidTransactionTempRewriteFs(base, base.presetsPath, tampered, legacyBytes)
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(staged, expectedCount = 2))

        assertContentEquals(
            legacyBytes,
            base.files.getValue(base.presetsPath),
            "暂存字节比对之后被并发改写的 temp 不得替换线上库：线上库必须仍是原始旧库字节",
        )
        assertTrue(
            fs.renamedNonOriginalToLive.isEmpty(),
            "被并发改写的暂存文件不得 rename 到线上库：${fs.renamedNonOriginalToLive}",
        )
        // The tampered temp was promoted only as far as the decode check, which then removed it.
        assertFalse(base.files.containsKey(fs.tempPath), "被拒绝的暂存文件必须清理")
        val backup = base.files.keys.single { ".legacy-" in it }
        assertContentEquals(legacyBytes, base.files.getValue(backup), "备份必须保留原始字节")
        assertTrue(backup.contains(Sha256.hex(legacyBytes)))
        assertTrue(
            store.presetError?.startsWith("FM-E-PRESET-001") == true,
            "并发改写后的解码不符必须保留禁写错误码：${store.presetError}",
        )

        // The temp really was tampered mid-transaction: the byte comparison ran on the
        // pre-tamper read and the staged decode ran on the post-tamper content.
        assertTrue(
            fs.ops.count { it.startsWith("readBytes ${fs.tempPath}") } >= 1 &&
                fs.ops.any { it == "readText ${fs.tempPath}" } &&
                fs.tamperApplied,
            "必须确有“字节比对在前、暂存解码在后”的中途改写：${fs.ops}",
        )
    }

    // ---- doubles -------------------------------------------------------------

    /**
     * Fakes a staged document that every *byte* check accepts but that the codec reads
     * back differently: [readBytes] stays faithful (so the size check, the byte-equality
     * check and the JSON-root parse of the plain save transaction all pass), while the
     * codec's own channel — [readText] — answers [lossyText] for exactly those bytes,
     * wherever they live (the temp before a rename, the live path after one).
     *
     * Recovery is blocked: nothing may write or rename a `.restore.tmp`, so a
     * replace-before-decode implementation cannot hide behind its auto-restore.
     */
    private class LossyDecodeViewFs(
        private val base: FakeFs,
        private val livePath: String,
        private val lossyText: String,
        private val legacyBytes: ByteArray,
    ) : ProductFileSystem by base {
        val ops = mutableListOf<String>()
        val renamedNonOriginalToLive = mutableListOf<String>()
        val tempPath get() = "$livePath.tmp"
        private var stagedBytes: ByteArray? = null

        override fun readText(path: String): String? {
            ops += "readText $path"
            val raw = base.readBytes(path) ?: return null
            val staged = stagedBytes
            if (staged != null && raw.contentEquals(staged)) return lossyText
            return raw.decodeToString()
        }

        override fun readBytes(path: String): ByteArray? {
            ops += "readBytes $path"
            return base.readBytes(path)
        }

        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            ops += "writeBytes $path ${bytes.size}"
            if (path.endsWith(".restore.tmp")) return false
            if (path == tempPath) stagedBytes = bytes.copyOf()
            return base.writeBytes(path, bytes)
        }

        override fun rename(from: String, to: String): Boolean {
            ops += "rename $from -> $to"
            if (from.endsWith(".restore.tmp")) return false
            if (to == livePath) {
                val moving = base.readBytes(from)
                if (moving == null || !moving.contentEquals(legacyBytes)) {
                    renamedNonOriginalToLive += "$from -> $to (${moving?.size ?: -1} B)"
                }
            }
            return base.rename(from, to)
        }
    }

    /**
     * A read-count-ordered double for a *real* mid-transaction temp rewrite. The staged
     * temp is written and read back for the byte comparison with the genuine bytes; only
     * after that comparison read returns does the backing temp become the tampered content
     * every later reader sees (the staged decode in the fixed ordering, or the rename
     * source in the pre-fix `saveAt` ordering). `.restore.tmp` writes/rename are refused so
     * the pre-fix implementation cannot conceal a promoted tamper behind its auto-restore.
     */
    private class MidTransactionTempRewriteFs(
        private val base: FakeFs,
        private val livePath: String,
        private val tamperedBytes: ByteArray,
        private val legacyBytes: ByteArray,
    ) : ProductFileSystem by base {
        val ops = mutableListOf<String>()
        val renamedNonOriginalToLive = mutableListOf<String>()
        val tempPath get() = "$livePath.tmp"
        var tamperApplied = false
            private set
        private var genuineStaged: ByteArray? = null
        private var armed = false

        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            ops += "writeBytes $path ${bytes.size}"
            // The auto-restore mitigation must not succeed either.
            if (path.endsWith(".restore.tmp")) return false
            if (path == tempPath) genuineStaged = bytes.copyOf()
            return base.writeBytes(path, bytes)
        }

        override fun readBytes(path: String): ByteArray? {
            ops += "readBytes $path"
            if (path == tempPath) {
                val genuine = genuineStaged
                if (!armed && genuine != null) {
                    // This read is the byte comparison: hand back the genuine staged bytes,
                    // then rewrite the on-disk temp so every subsequent read sees the tamper.
                    armed = true
                    base.files[tempPath] = tamperedBytes.copyOf()
                    tamperApplied = true
                    return genuine.copyOf()
                }
            }
            return base.readBytes(path)
        }

        override fun readText(path: String): String? {
            ops += "readText $path"
            return base.readText(path)
        }

        override fun rename(from: String, to: String): Boolean {
            ops += "rename $from -> $to"
            if (from.endsWith(".restore.tmp")) return false
            if (to == livePath) {
                val moving = base.readBytes(from)
                if (moving == null || !moving.contentEquals(legacyBytes)) {
                    renamedNonOriginalToLive += "$from -> $to (${moving?.size ?: -1} B)"
                }
            }
            return base.rename(from, to)
        }
    }

    /** Reports a *different* library body once the SHA backup exists (a concurrent writer). */
    private class ChangedAfterBackupFs(
        private val base: FakeFs,
        private val livePath: String,
        sha: String,
    ) : ProductFileSystem by base {
        val renamedToLive = mutableListOf<String>()
        private val backupPath = "$livePath.legacy-$sha.bak"
        private var reported = false

        override fun readBytes(path: String): ByteArray? {
            if (path == livePath && !reported && base.exists(backupPath)) {
                reported = true
                // Same legacy envelope, one row short: the library changed under us.
                return """{"version":1,"presets":[{"id":"legacy-1","name":"旧预设一","updatedAt":10,"cardId":"simple-4"}]}"""
                    .encodeToByteArray()
            }
            return base.readBytes(path)
        }

        override fun rename(from: String, to: String): Boolean {
            if (to == livePath) renamedToLive += from
            return base.rename(from, to)
        }
    }
}
