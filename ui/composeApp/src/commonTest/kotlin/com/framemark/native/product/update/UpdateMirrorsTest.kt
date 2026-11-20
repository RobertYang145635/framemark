package com.framemark.native.product.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 国内下载渠道（2026-11-20）：清单里可以带**完整 URL** 的资产镜像，下载候选的顺序是
 * 「清单镜像 → 内置加速前缀 → 直连」。实测直连 github.com 资产只有 ~18 KB/s，所以直连
 * 永远放最后；清单镜像放最前，这样以后换渠道（例如挂到国内对象存储）不用发新版。
 */
class UpdateMirrorsTest {

    private val githubAsset =
        "https://github.com/RobertYang145635/framemark/releases/download/v0.9.0-rc45/FrameMark-0.9.0-rc45-arm64.apk"

    @Test
    fun manifestMirrorsComeFirstThenAcceleratorsThenDirect() {
        val extras = listOf("https://cdn.example.cn/framemark/rc45.apk")
        val candidates = UpdateUrls.assetCandidates(githubAsset, extras)
        assertEquals(extras.first(), candidates.first(), "清单镜像第一顺位")
        assertTrue(candidates.any { it == UpdateUrls.MIRRORS.first() + githubAsset }, "内置前缀仍然在候选里")
        assertEquals(githubAsset, candidates.last(), "直连兜底放最后")
    }

    @Test
    fun nonGithubUrlsAreNotProxied() {
        val direct = "https://mirror.example.com/rc45.apk"
        assertEquals(listOf(direct), UpdateUrls.assetCandidates(direct))
        assertEquals(
            listOf("https://cdn.example.cn/x.apk", direct),
            UpdateUrls.assetCandidates(direct, listOf("https://cdn.example.cn/x.apk")),
        )
    }

    @Test
    fun junkMirrorsAreIgnoredAndDuplicatesCollapse() {
        val extras = listOf("  ", "http://insecure.example/x.apk", "", UpdateUrls.MIRRORS.first() + githubAsset)
        val candidates = UpdateUrls.assetCandidates(githubAsset, extras)
        assertTrue(candidates.none { it.startsWith("http://") }, "只接受 https")
        assertEquals(candidates.size, candidates.distinct().size, "重复项要收掉")
    }

    @Test
    fun theManifestCarriesItsMirrorsIntoeachAsset() {
        val json = """
            {
              "versionName": "0.9.0-rc45",
              "versionCode": 44,
              "channel": "rc",
              "notes": "下载走国内镜像",
              "mirrors": ["https://cdn.example.cn/fm/rc45"],
              "assets": {
                "android": {"url": "https://github.com/o/r/releases/download/v1/a.apk", "sha256": "aa", "size": 10},
                "windows": {"url": "https://github.com/o/r/releases/download/v1/a.msi", "sha256": "bb", "size": 20}
              }
            }
        """.trimIndent()
        val manifest = assertNotNull(UpdateManifestJson.parse(json))
        assertEquals(listOf("https://cdn.example.cn/fm/rc45"), manifest.mirrors)
        assertEquals(listOf("https://cdn.example.cn/fm/rc45"), manifest.android?.mirrors)
        assertEquals(listOf("https://cdn.example.cn/fm/rc45"), manifest.windows?.mirrors)
        assertEquals(10L, manifest.android?.size)
    }

    @Test
    fun aManifestWithoutMirrorsStillParses() {
        val json = """{"versionName":"0.9.0-rc45","versionCode":44,"assets":{"android":{"url":"https://x/a","sha256":"aa","size":1}}}"""
        val manifest = assertNotNull(UpdateManifestJson.parse(json))
        assertTrue(manifest.mirrors.isEmpty())
        assertEquals(emptyList(), manifest.android?.mirrors)
    }
}
