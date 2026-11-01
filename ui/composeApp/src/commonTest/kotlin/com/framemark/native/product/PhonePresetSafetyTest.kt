package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhonePresetSafetyTest {
    private val legacyPresetText = """
        {"version":1,"presets":[{"id":"legacy-1","name":"旧预设","updatedAt":10,
        "cardId":"simple-4","cardName":"simple-4","adjustedCount":0,
        "export":{"format":"JPG","quality":86,"longEdge":1600,"webpLossless":true,"filename":"{name}-{date}"},
        "values":{}}]}
    """.trimIndent() + "\n"

    @Test
    fun unreadable_preset_library_blocks_import_without_changing_bytes_or_state() {
        val fs = FakeFs()
        val original = """{"document":"framemark.presets","schemaVersion":9,"presets":[]}""".encodeToByteArray()
        fs.files[fs.presetsPath] = original.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertFalse(store.importPreset(samplePreset()))

        assertContentEquals(original, fs.files.getValue(fs.presetsPath))
        assertTrue(store.presets.isEmpty())
        assertTrue(store.presetError?.contains("版本过高") == true, store.presetError.orEmpty())
    }

    @Test
    fun preset_id_conflict_is_read_only_until_replace_is_explicit() {
        val fs = FakeFs()
        val existing = samplePreset()
        assertTrue(SessionStore(fs).savePresets(listOf(existing)) is StoreResult.Ok)
        val original = fs.files.getValue(fs.presetsPath).copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()
        val replacement = existing.copy(name = "明确替换")

        assertFalse(store.importPreset(replacement))
        assertEquals(existing, store.presets.single())
        assertContentEquals(original, fs.files.getValue(fs.presetsPath))
        assertTrue(store.presetError?.contains("ID 已存在") == true, store.presetError.orEmpty())

        assertTrue(store.importPreset(replacement, replaceExisting = true))
        assertEquals(replacement, store.presets.single())
        assertEquals(replacement, SessionStore(fs).loadPresets().ok().single())
    }

    @Test
    fun legacy_migration_requires_full_conversion_and_keeps_exact_backup_on_write_failure() {
        val fs = FakeFs()
        val original = legacyPresetText.encodeToByteArray()
        fs.files[fs.presetsPath] = original.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        assertEquals(0, store.importLegacyPresets(emptyList(), expectedCount = 1))
        assertContentEquals(original, fs.files.getValue(fs.presetsPath))
        assertTrue(fs.files.keys.none { ".legacy-" in it })

        fs.failWritePath = "${fs.presetsPath}.tmp"
        val converted = samplePreset().copy(id = "legacy-1")
        assertEquals(0, store.importLegacyPresets(listOf(converted), expectedCount = 1))

        assertContentEquals(original, fs.files.getValue(fs.presetsPath))
        val backupPath = fs.files.keys.single { ".legacy-" in it }
        assertContentEquals(original, fs.files.getValue(backupPath))
        assertTrue(backupPath.contains(Sha256.hex(original)))
        assertNotNull(store.presetError)
    }

    @Test
    fun legacy_migration_success_replaces_only_after_verification_and_keeps_raw_backup() {
        val fs = FakeFs()
        val original = legacyPresetText.encodeToByteArray()
        fs.files[fs.presetsPath] = original.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.loadPresets()

        val migrated = store.importLegacyPresets(
            listOf(samplePreset().copy(id = "legacy-1")),
            expectedCount = 1,
        )

        assertEquals(1, migrated)
        assertEquals("legacy-1", store.presets.single().id)
        assertTrue(LegacyDocuments.isProductPresetLibrary(fs.files.getValue(fs.presetsPath).decodeToString()))
        val backupPath = fs.files.keys.single { ".legacy-" in it }
        assertContentEquals(original, fs.files.getValue(backupPath))
        assertTrue(backupPath.contains(Sha256.hex(original)))
        assertEquals(store.presets, SessionStore(fs).loadPresets().ok())
    }

    @Test
    fun migration_rejects_tampered_or_unreadable_staging_without_replacing_legacy_bytes() {
        val corruptTemp = "${FakeFs().presetsPath}.tmp"
        val cases = listOf("same-size-tamper", "unreadable-temp")

        cases.forEach { failure ->
            val base = FakeFs()
            val original = legacyPresetText.encodeToByteArray()
            base.files[base.presetsPath] = original.copyOf()
            val guardedFs = object : ProductFileSystem by base {
                override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                    if (failure == "same-size-tamper" && path == corruptTemp) {
                        val changed = bytes.copyOf()
                        if (changed.isNotEmpty()) changed[0] = '['.code.toByte()
                        return base.writeBytes(path, changed)
                    }
                    return base.writeBytes(path, bytes)
                }

                override fun readBytes(path: String): ByteArray? =
                    if (failure == "unreadable-temp" && path == corruptTemp) null else base.readBytes(path)
            }
            val store = PhoneProjectStore(guardedFs, io = null, platform = null)
            store.loadPresets()

            assertEquals(
                0,
                store.importLegacyPresets(listOf(samplePreset().copy(id = "legacy-1")), expectedCount = 1),
                "迁移暂存验证失败必须拒绝替换：$failure",
            )
            assertContentEquals(original, base.files.getValue(base.presetsPath), "原始旧库必须逐字节保留：$failure")
            assertFalse(base.files.containsKey(corruptTemp), "失败暂存文件应清理：$failure")
            val backupPath = base.files.keys.single { ".legacy-" in it }
            assertContentEquals(original, base.files.getValue(backupPath), "备份必须保留原始字节：$failure")
            assertTrue(backupPath.contains(Sha256.hex(original)))
            assertNotNull(store.presetError)
        }
    }
}
