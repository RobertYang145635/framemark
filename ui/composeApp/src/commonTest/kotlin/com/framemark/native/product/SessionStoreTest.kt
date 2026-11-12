package com.framemark.native.product

import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowPlatform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {
    @Test
    fun project_round_trip_is_byte_stable() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val project = sampleProject()

        assertTrue(store.saveProject(project) is StoreResult.Ok)
        val first = fs.files.getValue(fs.projectPath).copyOf()

        val loaded = store.loadProject().ok()
        assertEquals(project, loaded)

        assertTrue(store.saveProject(loaded) is StoreResult.Ok)
        assertContentEquals(first, fs.files.getValue(fs.projectPath), "同一模型重复保存产生了不同字节")
        assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "写入后仍残留临时文件")
        assertEquals(2, fs.renames.size)
        assertTrue(
            fs.renames.all { it.first == "${fs.projectPath}.tmp" && it.second == fs.projectPath },
            "写入没有走 temp→rename：${fs.renames}",
        )
    }

    @Test
    fun missing_documents_are_reported_as_missing() {
        val store = SessionStore(FakeFs())
        assertEquals(StoreResult.Missing, store.loadProject())
        assertEquals(StoreResult.Missing, store.loadPresets())
        assertEquals(StoreResult.Missing, store.loadBatch())
    }

    @Test
    fun a_document_over_the_read_limit_fails_with_a_structured_error() {
        val fs = FakeFs()
        // A small bound exercises the real refusal path without allocating 50 MiB.
        val store = SessionStore(fs, maxDocumentBytes = 64)
        fs.files[fs.projectPath] = ByteArray(128) { ' '.code.toByte() }
        fs.files["/imports/huge.preset.json"] = ByteArray(128) { ' '.code.toByte() }

        val project = store.loadProject()
        assertTrue(project is StoreResult.Failed, "超限文档必须被拒绝：$project")
        val projectError = project.errorOrNull()
        assertNotNull(projectError)
        assertEquals(StoreCodes.IO_READ, projectError.code)
        assertTrue(projectError.message.contains("上限"), projectError.message)

        val raw = store.documentText(DocumentKind.Project)
        assertTrue(raw is StoreResult.Failed, "documentText 也必须受同一上限约束：$raw")
        assertEquals(StoreCodes.IO_READ, raw.errorOrNull()?.code)

        val imported = store.importPreset("/imports/huge.preset.json")
        assertTrue(imported is StoreResult.Failed, "importPreset 也必须受同一上限约束：$imported")
        assertEquals(StoreCodes.IO_READ, imported.errorOrNull()?.code)
    }

    @Test
    fun a_normal_document_still_loads_under_the_default_limit() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        assertEquals(sampleProject(), store.loadProject().ok())
    }

    @Test
    fun an_over_limit_document_is_never_published() {
        val fs = FakeFs()
        assertTrue(SessionStore(fs).saveProject(sampleProject()) is StoreResult.Ok)
        val before = fs.files.getValue(fs.projectPath).copyOf()

        val error = SessionStore(fs, maxDocumentBytes = 64)
            .saveProject(sampleProject().copy(updatedAt = 999))
            .errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("上限"), error.message)
        assertContentEquals(before, fs.files.getValue(fs.projectPath), "超限写入破坏了旧文档")
        assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "超限写入不应留下临时文件")
    }

    @Test
    fun saving_verifies_the_staged_file_by_digest_without_reading_it_back() {
        val base = FakeFs()
        val fs = CountingReadFs(base)

        assertTrue(SessionStore(fs).saveProject(sampleProject()) is StoreResult.Ok)

        assertEquals(0, fs.tempReads, "保存不得再次整份读取暂存文件：${fs.tempReads}")
        assertEquals(1, fs.tempDigests, "暂存文件必须用一次流式摘要校验")
        assertContentEquals(base.files.getValue(base.projectPath), fs.lastTempDigestInput)
    }

    /** Counts a full read of `<path>.tmp` against a streaming digest of the same file. */
    private class CountingReadFs(private val base: FakeFs) : ProductFileSystem by base {
        var tempReads = 0
            private set
        var tempDigests = 0
            private set
        var lastTempDigestInput: ByteArray = ByteArray(0)
            private set

        override fun readBytes(path: String): ByteArray? {
            if (path.endsWith(".tmp")) tempReads++
            return base.readBytes(path)
        }

        override fun sha256Of(path: String): String? {
            if (path.endsWith(".tmp")) {
                tempDigests++
                lastTempDigestInput = base.files[path]?.copyOf() ?: ByteArray(0)
            }
            return base.sha256Of(path)
        }
    }

    @Test
    fun corrupt_json_reports_a_structured_error_with_the_cause() {
        val fs = FakeFs()
        fs.files[fs.projectPath] = "{not json".encodeToByteArray()

        val error = SessionStore(fs).loadProject().errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.INTERNAL, error.code)
        assertTrue(error.message.contains("损坏"), error.message)
        assertNotNull(error.cause)
    }

    @Test
    fun an_unreadable_document_reports_an_io_error_instead_of_missing() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)

        fs.unreadablePath = fs.projectPath
        val result = store.loadProject()
        assertTrue(result is StoreResult.Failed)
        val error = result.errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_READ, error.code)
    }

    @Test
    fun a_failed_write_keeps_the_previous_document() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.failWritePath = "${fs.projectPath}.tmp"
        val error = store.saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.retryable)
        assertContentEquals(before, fs.files.getValue(fs.projectPath), "写失败后旧文档被破坏")
        assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "写失败后临时文件未清理")
        assertEquals(200, store.loadProject().ok().updatedAt)
    }

    @Test
    fun a_write_exception_is_reported_not_swallowed() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.failWritePath = "${fs.projectPath}.tmp"
        fs.writeException = IllegalStateException("磁盘已满")
        val error = store.saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("磁盘已满"), error.message)
        assertEquals("磁盘已满", error.cause?.message)
        assertContentEquals(before, fs.files.getValue(fs.projectPath))
    }

    @Test
    fun a_partial_write_is_detected_and_discarded() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.partialWriteBytes = 12
        val error = store.saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("不完整"), error.message)
        assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "不完整写入的临时文件未清理")
        assertContentEquals(before, fs.files.getValue(fs.projectPath))
    }

    @Test
    fun same_size_temporary_content_mismatch_never_replaces_the_original() {
        val base = FakeFs()
        assertTrue(SessionStore(base).saveProject(sampleProject()) is StoreResult.Ok)
        val before = base.files.getValue(base.projectPath).copyOf()
        val tampering = object : ProductFileSystem by base {
            override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                if (!path.endsWith(".tmp")) return base.writeBytes(path, bytes)
                val changed = bytes.copyOf()
                changed[0] = if (changed[0] == '{'.code.toByte()) '['.code.toByte() else '{'.code.toByte()
                return base.writeBytes(path, changed)
            }
        }

        val error = SessionStore(tampering).saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()

        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("内容与待写入文档不一致"), error.message)
        assertContentEquals(before, base.files.getValue(base.projectPath))
        assertFalse(base.files.containsKey("${base.projectPath}.tmp"))
        assertEquals(1, base.renames.size, "错误暂存文件不得触发 rename")
    }

    @Test
    fun an_unreadable_temporary_file_never_replaces_the_original() {
        val base = FakeFs()
        assertTrue(SessionStore(base).saveProject(sampleProject()) is StoreResult.Ok)
        val before = base.files.getValue(base.projectPath).copyOf()
        val unreadableTemp = object : ProductFileSystem by base {
            override fun sha256Of(path: String): String? =
                if (path.endsWith(".tmp")) null else base.sha256Of(path)
        }

        val error = SessionStore(unreadableTemp).saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()

        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("暂存文件无法读取"), error.message)
        assertContentEquals(before, base.files.getValue(base.projectPath))
        assertFalse(base.files.containsKey("${base.projectPath}.tmp"))
        assertEquals(1, base.renames.size, "无法读取的暂存文件不得触发 rename")
    }

    @Test
    fun a_failed_rename_keeps_the_previous_document_and_cleans_the_temp() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.failRename = true
        val error = store.saveProject(sampleProject().copy(updatedAt = 999)).errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.IO_WRITE, error.code)
        assertTrue(error.message.contains("替换文档失败"), error.message)
        assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "rename 失败后临时文件未清理")
        assertContentEquals(before, fs.files.getValue(fs.projectPath))
        assertEquals(200, store.loadProject().ok().updatedAt)
    }

    @Test
    fun a_future_project_schema_is_rejected() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.saveProject(sampleProject()) is StoreResult.Ok)
        val text = fs.files.getValue(fs.projectPath).decodeToString()
        assertTrue(text.contains("\"schemaVersion\":1"))
        fs.files[fs.projectPath] = text.replace("\"schemaVersion\":1", "\"schemaVersion\":2").encodeToByteArray()

        val error = store.loadProject().errorOrNull()
        assertNotNull(error)
        assertTrue(error.message.contains("版本过高"), error.message)
    }

    @Test
    fun a_document_of_another_kind_is_rejected() {
        val fs = FakeFs()
        fs.files[fs.projectPath] =
            """{"document":"framemark.batch","schemaVersion":1,"jobs":[]}""".encodeToByteArray()

        val error = SessionStore(fs).loadProject().errorOrNull()
        assertNotNull(error)
        assertTrue(error.message.contains("文档类型不符"), error.message)
    }

    @Test
    fun unserializable_design_values_fail_the_save_without_writing() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val bad = Project(
            id = "p",
            photos = listOf(photoRef("a")),
            activePhotoId = "a",
            designs = mapOf("a" to PhotoDesign("simple-4", params = mapOf("bad" to listOf(1)))),
        )

        val error = store.saveProject(bad).errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.INTERNAL, error.code)
        assertTrue(fs.files.isEmpty(), "序列化失败仍写入了文档")
    }

    @Test
    fun an_older_project_schema_migrates_through_the_registered_entry() {
        val fs = FakeFs()
        fs.files[fs.projectPath] =
            """{"document":"framemark.project","schemaVersion":0,"id":"p","photos":[],"activePhotoId":null}"""
                .encodeToByteArray()
        val migration = SchemaMigration(DocumentKind.Project, 0, 1) { root -> root + mapOf("schemaVersion" to 1) }

        val project = SessionStore(fs, listOf(migration)).loadProject().ok()
        assertEquals("p", project.id)
        assertEquals(0, project.photos.size)
        assertEquals(PROJECT_SCHEMA_VERSION, project.schemaVersion)
    }

    @Test
    fun shared_design_and_new_photo_inheritance_survive_a_round_trip() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val project = sampleProject().setSharedDesign(PhotoDesign.base("film-15").withParam("inset", 0.33))

        assertTrue(store.saveProject(project) is StoreResult.Ok)
        val loaded = store.loadProject().ok()
        assertEquals(project, loaded)
        assertEquals(project.sharedDesign, loaded.sharedDesign)

        val next = loaded.addPhoto(photoRef("c"))
        assertEquals("film-15", next.designOf("c")!!.cardId)
        assertEquals(0.33, next.designOf("c")!!.params["inset"])
        assertNull(next.designOf("c")!!.overlay, "重载后新照片仍不得继承水印")
    }

    @Test
    fun a_shared_design_that_carries_a_watermark_is_rejected() {
        val fs = FakeFs()
        val store = SessionStore(fs)

        // The encoder refuses to produce a document that violates the policy.
        val bad = sampleProject().copy(sharedDesign = PhotoDesign.base("simple-4").withOverlay(watermarkSpec(5)))
        val saveError = store.saveProject(bad).errorOrNull()
        assertNotNull(saveError)
        assertEquals(StoreCodes.INTERNAL, saveError.code)
        assertTrue(saveError.message.contains("默认设计"), saveError.message)
        assertTrue(fs.files.isEmpty(), "非法文档仍被写出")

        // A hand-edited file is rejected on read instead of silently losing the mark.
        val asset = WatermarkAsset.create("s.png", "image/png", 10, 10, byteArrayOf(1, 2, 3))
        val overlayJson =
            """{"anchor":8,"width":0.3,"opacity":0.8,"dx":0,"dy":0,"asset":{"name":"s.png","mimeType":"image/png","widthPx":10,"heightPx":10,"sha256":"${asset.sha256}","data":"${Base64Codec.encode(asset.bytes)}"}}"""
        fs.files[fs.projectPath] = (
            """{"document":"framemark.project","schemaVersion":1,"id":"p","photos":[],"activePhotoId":null,"designs":{}, """ +
                """"sharedDesign":{"cardId":"simple-4","params":{},"text":{},"overlay":$overlayJson}}"""
            ).encodeToByteArray()

        val loadError = store.loadProject().errorOrNull()
        assertNotNull(loadError)
        assertTrue(loadError.message.contains("水印"), loadError.message)
    }

    @Test
    fun platform_adapter_delegates_to_the_workflow_platform() {
        val platform = FakePlatform("/app-data")
        val fs = PlatformFileSystem(
            platform = platform,
            replaceFile = { from, to ->
                val bytes = platform.files[from]
                if (bytes == null) {
                    false
                } else {
                    platform.files[to] = bytes
                    platform.files.remove(from)
                    platform.replaced++
                    true
                }
            },
            deleteFile = { platform.files.remove(it) != null },
        )
        val store = SessionStore(fs)
        val project = sampleProject()

        assertTrue(store.saveProject(project) is StoreResult.Ok)
        assertEquals(1, platform.replaced)
        assertEquals("/app-data", fs.dataDir)
        assertEquals(project, store.loadProject().ok())
    }

    private class FakePlatform(override val dataDir: String) : WorkflowPlatform {
        override val exportDir: String = "$dataDir/exports"
        val files = LinkedHashMap<String, ByteArray>()
        var replaced = 0

        override fun readTextFile(path: String): String? = files[path]?.decodeToString()

        override fun writeTextFile(path: String, text: String) {
            files[path] = text.encodeToByteArray()
        }

        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes
            return true
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

        override fun readBytes(path: String): ByteArray? = files[path]

        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null

        override fun dateStamp(): String = "20261030"

        override fun timeStamp(): String = "120000"

        override fun formatDateTime(epochMillis: Long): String = ""

        override fun nowMillis(): Long = 0L
    }
}
