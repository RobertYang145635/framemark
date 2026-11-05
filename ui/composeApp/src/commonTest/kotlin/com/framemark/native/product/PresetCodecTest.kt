package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PresetCodecTest {
    private fun v0Library(): String =
        """{"document":"framemark.presets","schemaVersion":0,"presets":[""" +
            """{"id":"old","name":"旧预设","design":{"cardId":"simple-4","params":{},"text":{}}}]}"""

    private fun exportMigration(): SchemaMigration = SchemaMigration(
        document = DocumentKind.PresetLibrary,
        fromVersion = 0,
        toVersion = 1,
    ) { root ->
        val presets = (root["presets"] as? List<*>)?.map { entry ->
            val map = (entry as Map<String, Any?>).toMutableMap()
            map["export"] = mapOf(
                "format" to "jpeg",
                "quality" to 100,
                "sizeMode" to "original",
                "webpLossless" to true,
            )
            map
        } ?: emptyList()
        root + mapOf("schemaVersion" to 1, "presets" to presets)
    }

    @Test
    fun preset_round_trip_embeds_a_one_megabyte_png() {
        val asset = WatermarkAsset.create("signature.png", "image/png", 1600, 400, megabytePng())
        val preset = Preset(
            id = "preset-1",
            name = "我的签名",
            design = PhotoDesign.base("simple-4")
                .withParam("inset", 0.1)
                .withText("model", "X-T5")
                .withOverlay(
                    WatermarkSpec(
                        asset,
                        anchor = 8,
                        width = 0.3f,
                        opacity = 0.65f,
                        rotation = 33f,
                        photoAspect = 1.5f,
                    ),
                ),
            exportSettings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 6000),
            createdAt = 10,
            updatedAt = 20,
        )
        val fs = FakeFs()
        val store = SessionStore(fs)

        assertTrue(store.savePresets(listOf(preset)) is StoreResult.Ok)

        val text = fs.files.getValue(fs.presetsPath).decodeToString()
        assertTrue(text.length > 1_398_000, "预设文件没有内嵌 1MB 素材（${text.length} 字符）")

        val restored = store.loadPresets().ok().single()
        assertEquals(preset, restored)
        assertContentEquals(asset.bytes, restored.design.overlay!!.asset.bytes)
        assertTrue(restored.design.overlay!!.asset.isIntact())
        assertEquals(1600, restored.design.overlay!!.asset.widthPx)
        assertEquals(asset.sha256, restored.design.overlay!!.asset.sha256)
        assertEquals(33f, restored.design.overlay!!.rotation)
    }

    @Test
    fun preset_file_export_import_round_trip() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val preset = samplePreset()
        val path = "/out/我的预设.fmpreset"

        assertTrue(store.exportPreset(preset, path) is StoreResult.Ok)
        assertTrue(fs.exists(path), "导出文件不存在")
        assertEquals(preset, store.importPreset(path).ok())
        assertTrue(store.importPreset("/out/missing.fmpreset") is StoreResult.Missing)
    }

    @Test
    fun tampered_watermark_payload_is_rejected() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val preset = samplePreset()
        assertTrue(store.savePresets(listOf(preset)) is StoreResult.Ok)

        val original = fs.files.getValue(fs.presetsPath).decodeToString()
        val sha = preset.design.overlay!!.asset.sha256
        val tampered = original.replace(sha, "0".repeat(64))
        assertTrue(tampered != original, "没有找到可篡改的哈希")
        fs.files[fs.presetsPath] = tampered.encodeToByteArray()

        val error = store.loadPresets().errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.PRESET, error.code)
        assertTrue(error.message.contains("校验失败"), error.message)
    }

    @Test
    fun a_newer_schema_is_rejected_instead_of_half_read() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] =
            """{"document":"framemark.presets","schemaVersion":2,"presets":[{"id":"x","name":"x","design":{"cardId":"c","params":{},"text":{}}}]}"""
                .encodeToByteArray()

        val result = SessionStore(fs).loadPresets()
        val error = result.errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.PRESET, error.code)
        assertTrue(error.message.contains("版本过高"), error.message)
        assertTrue(result.valueOrNull() == null, "高版本文件不得返回任何预设")
    }

    @Test
    fun an_older_schema_without_a_migrator_is_rejected() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = v0Library().encodeToByteArray()

        val error = SessionStore(fs).loadPresets().errorOrNull()
        assertNotNull(error)
        assertTrue(error.message.contains("过旧"), error.message)
    }

    @Test
    fun an_older_schema_migrates_through_the_registered_entry() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = v0Library().encodeToByteArray()

        val presets = SessionStore(fs, listOf(exportMigration())).loadPresets().ok()
        assertEquals(1, presets.size)
        assertEquals("旧预设", presets.single().name)
        assertEquals(ExportSettings(), presets.single().exportSettings)
        assertEquals("simple-4", presets.single().design.cardId)
    }

    @Test
    fun a_migrator_that_throws_reports_the_failure() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = v0Library().encodeToByteArray()
        val broken = SchemaMigration(DocumentKind.PresetLibrary, 0, 1) { throw IllegalStateException("boom") }

        val error = SessionStore(fs, listOf(broken)).loadPresets().errorOrNull()
        assertNotNull(error)
        assertTrue(error.message.contains("迁移失败"), error.message)
        assertEquals("boom", error.cause?.message)
    }

    @Test
    fun a_migrator_that_does_not_bump_the_version_is_rejected() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = v0Library().encodeToByteArray()
        val stuck = SchemaMigration(DocumentKind.PresetLibrary, 0, 1) { it }

        val error = SessionStore(fs, listOf(stuck)).loadPresets().errorOrNull()
        assertNotNull(error)
        assertTrue(error.message.contains("未推进"), error.message)
    }

    @Test
    fun a_document_cannot_claim_a_default_design_while_carrying_edits() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = (
            """{"document":"framemark.presets","schemaVersion":1,"presets":[""" +
                """{"id":"p","name":"x","design":{"cardId":"simple-4","baseDesign":true,"params":{"inset":0.3},"text":{}},"export":{"format":"jpeg","sizeMode":"original","longEdge":null,"filenamePattern":"{name}-{date}","webpLossless":true}}]}"""
            ).encodeToByteArray()

        val preset = SessionStore(fs).loadPresets().ok().single()
        assertFalse(preset.design.baseDesign, "带参数的文档不得声明为默认设计")
        assertEquals(0.3, preset.design.params["inset"])
    }

    @Test
    fun portable_preset_omits_machine_quality_and_old_quality_remains_readable() {
        val preset = samplePreset().copy(
            exportSettings = samplePreset().exportSettings.copy(quality = 73),
        )
        val portable = PresetCodec.encodeDocument(preset)
        assertFalse(portable.contains("\"quality\""), "可移植预设不得写入本机 JPEG 质量")
        assertEquals(100, PresetCodec.decodeDocument(ProductJson.parseRoot(portable)).exportSettings.quality)

        val legacy = portable.replace(",\"sizeMode\":", ",\"quality\":73,\"sizeMode\":")
        assertTrue(legacy != portable)
        assertEquals(73, PresetCodec.decodeDocument(ProductJson.parseRoot(legacy)).exportSettings.quality)
    }

    @Test
    fun user_logo_id_survives_preset_round_trip() {
        val preset = samplePreset().copy(design = samplePreset().design.withLogo("user:ul-test"))
        val fs = FakeFs()
        val store = SessionStore(fs)
        assertTrue(store.savePresets(listOf(preset)) is StoreResult.Ok)
        val restored = store.loadPresets().ok().single()
        assertEquals("user:ul-test", restored.design.logo)
    }

    @Test
    fun one_bad_entry_rejects_the_whole_document() {
        val fs = FakeFs()
        fs.files[fs.presetsPath] = (
            """{"document":"framemark.presets","schemaVersion":1,"presets":[""" +
                """{"id":"ok","name":"可用","design":{"cardId":"simple-4","params":{},"text":{}}},""" +
                """{"id":"","name":"坏","design":{"cardId":"simple-4","params":{},"text":{}}}]}"""
            ).encodeToByteArray()

        val result = SessionStore(fs).loadPresets()
        val error = result.errorOrNull()
        assertNotNull(error)
        assertEquals(StoreCodes.PRESET, error.code)
        assertTrue(result.valueOrNull() == null, "坏文档不得返回部分预设")
    }
}
