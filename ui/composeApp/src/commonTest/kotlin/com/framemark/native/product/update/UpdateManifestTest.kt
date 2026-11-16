package com.framemark.native.product.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManifestTest {
    private val json = """
        {
          "versionName": "0.9.0-rc14",
          "versionCode": 14,
          "channel": "rc",
          "minSupportedVersionCode": 12,
          "notes": "修复\n若干问题",
          "assets": {
            "android": {"url": "https://x/app.apk", "sha256": "abc", "size": 123},
            "windows": {"url": "https://x/app.msi", "sha256": "def", "size": 456}
          },
          "releasePage": "https://x/releases/tag/v0.9.0-rc14"
        }
    """.trimIndent()

    @Test
    fun parsesManifestAndSelectsPlatformAsset() {
        val m = assertNotNull(UpdateManifestJson.parse(json))
        assertEquals("0.9.0-rc14", m.versionName)
        assertEquals(14, m.versionCode)
        assertEquals(12, m.minSupportedVersionCode)
        assertTrue(m.isPrerelease)
        assertEquals("修复\n若干问题", m.notes)
        assertEquals("https://x/app.apk", m.assetFor(UpdatePlatform.Android)?.url)
        assertEquals("https://x/app.msi", m.assetFor(UpdatePlatform.Windows)?.url)
    }

    @Test
    fun rejectsBrokenManifest() {
        assertNull(UpdateManifestJson.parse("{}"))
        assertNull(UpdateManifestJson.parse("not json"))
        assertNull(UpdateManifestJson.parse("""{"versionName":"1.0","versionCode":"x"}"""))
    }

    @Test
    fun assetMissingReturnsNull() {
        val m = assertNotNull(UpdateManifestJson.parse("""{"versionName":"1.0","versionCode":1}"""))
        assertNull(m.android)
        assertNull(m.windows)
    }
}
