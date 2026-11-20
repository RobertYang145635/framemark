package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 转码导入留下的原图路径（审计 2026-11-20 的重复导入缺陷）。
 *
 * 工程里存的是 `imports/import-<uuid>.jpg`，去重必须认**用户点的那张原图**，否则同一张
 * TIFF / WebP 可以无限重复导入。字段是可选的：老文档里没有这个键，写出来也必须逐字节
 * 不变（用的是同一份 codec）。
 */
class PhotoSourcePathCodecTest {

    private fun project(photo: PhotoRef) = Project(
        id = "p",
        photos = listOf(photo),
        activePhotoId = photo.id,
        designs = mapOf(photo.id to PhotoDesign.base("simple-1", null)),
    )

    private fun base(sourcePath: String? = null) = PhotoRef(
        id = "photo-1",
        path = "/data/imports/import-1.jpg",
        displayName = "原图.tiff",
        orientation = 1,
        widthPx = 640,
        heightPx = 480,
        fingerprint = "abc",
        sourcePath = sourcePath,
    )

    @Test
    fun sourcePathSurvivesARoundTrip() {
        val encoded = ProjectCodec.encode(project(base("/photos/原图.tiff")))
        val decoded = ProjectCodec.decode(ProductJson.parseRoot(encoded))
        assertEquals("/photos/原图.tiff", decoded.photos.single().sourcePath)
    }

    @Test
    fun withoutASourcePathTheKeyIsNotWritten() {
        val encoded = ProjectCodec.encode(project(base(null)))
        assertFalse(encoded.contains("sourcePath"), "老文档的字节不能因为新字段而变")
        assertNull(ProjectCodec.decode(ProductJson.parseRoot(encoded)).photos.single().sourcePath)
    }

    @Test
    fun aBlankSourcePathIsTreatedAsMissing() {
        val encoded = ProjectCodec.encode(project(base("")))
        assertFalse(encoded.contains("\"sourcePath\""), "空白值不写键")
        assertNull(ProjectCodec.decode(ProductJson.parseRoot(encoded)).photos.single().sourcePath)
    }
}
