package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 手机「保存为预设 / 套用预设」的语义（同一个面板里验收人找不到的那两个动作）。
 *
 * 保存必须和桌面写同一份文档：`ProjectPreset` = 整套 [PhotoDesign]（边框、参数、
 * 文字、Logo、主题/比例、**内嵌 PNG 字节的水印**）+ 导出设置，经
 * `SessionStore.savePresets` / `PresetCodec` 落盘 —— 手机只把本机 JPEG 质量钉在
 * 平台固定值 100。
 *
 * 套用必须写进当前照片：卡、样式与全部设计字段 + 导出设置，并落盘；外壳随后递增
 * `hydrateToken` 让会话重灌（已改项数、舞台、撤销基线照常）。
 */
class PhonePresetSaveApplyTest {

    @Test
    fun saveCapturesTheDesktopsFieldsIncludingTheEmbeddedWatermark() {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()
        store.adoptPaths(listOf("/photos/a.jpg"), activePath = "/photos/a.jpg")
        val photoId = assertNotNull(store.activePhotoId)
        val design = PhotoDesign.base("simple-4", styleId = "classic")
            .withTheme("white")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("auto")
            .withParam("inset", 0.12)
            .withText("model", "X-T5")
            .withOverlay(watermarkSpec(seed = 1, size = 2048))
        store.setDesign(photoId, design)
        store.setExportSettings(
            ExportSettings(
                format = ExportFormat.Png,
                quality = 100,
                sizeMode = ExportSizeMode.LongEdge,
                longEdge = 4096,
                filenamePattern = "{name}-fm",
                webpLossless = false,
            ),
        )

        val saved = assertNotNull(store.savePreset(" 白边 + 签名 "), store.presetError.orEmpty())

        assertEquals("白边 + 签名", saved.name, "名称按 ProjectPreset.cleanName 去掉首尾空白")
        assertEquals(design, saved.design, "预设必须原样带上边框 / 参数 / 文字 / Logo / 主题 / 比例")
        assertEquals(design.overlay, saved.overlay, "水印连同 PNG 字节一起内嵌，换机/重启后仍能复现")
        assertEquals(design.overlay!!.asset.byteSize, saved.embeddedWatermarkBytes)
        assertEquals(store.project.exportSettings, saved.exportSettings, "导出设置与桌面同一套字段")
        assertEquals(listOf(saved), store.presets, "新保存的预设在最前，紧挨保存卡片，不用滚动")
        assertEquals(
            listOf(saved),
            SessionStore(fs).loadPresets().ok(),
            "同一份文档、同一个编码器（PresetCodec），重开预设库能原样读回",
        )
    }

    @Test
    fun applyWritesThePresetsDesignAndExportSettingsToTheActivePhoto() {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()
        store.adoptPaths(listOf("/photos/a.jpg"), activePath = "/photos/a.jpg")
        val photoId = assertNotNull(store.activePhotoId)
        store.setDesign(photoId, PhotoDesign.base("stock-a", styleId = "style-a"))
        store.setExportSettings(ExportSettings(quality = 100))
        assertTrue(store.saveNow())

        val preset = ProjectPreset(
            id = "preset-apply",
            name = "签名预设",
            design = PhotoDesign.base("other-card", styleId = "other-style")
                .withTheme("paper")
                .withRatio("4:5")
                .withFontScale(1.25)
                .withLogo("leica")
                .withParam("inset", 0.2)
                .withText("model", "X-T5")
                .withOverlay(watermarkSpec(seed = 3)),
            exportSettings = ExportSettings(
                format = ExportFormat.Png,
                quality = 73,
                sizeMode = ExportSizeMode.LongEdge,
                longEdge = 3000,
                filenamePattern = "{name}-fm",
                webpLossless = false,
            ),
            createdAt = 1,
            updatedAt = 2,
        )

        assertTrue(store.applyPreset(preset, styleId = "style-from-library"), store.presetError.orEmpty())

        val expected = PhotoDesign(
            cardId = "other-card",
            styleId = "other-style",
            theme = "paper",
            ratio = "4:5",
            fontScale = 1.25,
            logo = "leica",
            params = preset.design.params,
            text = preset.design.text,
            overlay = preset.design.overlay,
            baseDesign = false,
        )
        assertEquals(expected, store.designOf(photoId), "套用 = 整套设计写到当前照片")
        assertEquals("other-card", store.project.selectedCardId, "工程选中的边框跟着预设走")
        // platform 为 null 时本机质量沿用当前工程的 100：预设文件里的 73 不覆盖本机设置。
        val localExport = preset.exportSettings.copy(quality = 100)
        assertEquals(localExport, store.project.exportSettings, "导出设置也一并套用")

        val reloaded = SessionStore(fs).loadProject().ok()
        assertEquals(expected, reloaded.designOf(photoId), "套用后已落盘：重开工程仍是这套设计")
        assertEquals(localExport, reloaded.exportSettings)
    }
}
