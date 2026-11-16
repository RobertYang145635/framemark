package com.framemark.native.product.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 更新检查的纯逻辑：JSON 解析、版本比较、镜像候选顺序。 */
class UpdateCheckTest {

    private val sample = """
        [
          {"tag_name":"v0.9.0-rc14","name":"0.9.0-rc14","body":"修复\n若干问题 \"引号\"","html_url":"https://github.com/RobertYang145635/framemark/releases/tag/v0.9.0-rc14","prerelease":true,"draft":false},
          {"tag_name":"v0.9.0","name":"0.9.0","body":"stable","html_url":"https://github.com/RobertYang145635/framemark/releases/tag/v0.9.0","prerelease":false,"draft":false},
          {"tag_name":"v0.8.0","name":"draft one","body":"","html_url":"https://github.com/x","prerelease":false,"draft":true}
        ]
    """.trimIndent()

    @Test
    fun parsesReleasesAndSkipsDrafts() {
        val releases = UpdateJson.releases(sample)
        assertEquals(2, releases.size)
        assertEquals("v0.9.0-rc14", releases[0].tag)
        assertTrue(releases[0].prerelease)
        assertEquals("修复\n若干问题 \"引号\"", releases[0].notes)
        assertEquals("https://github.com/RobertYang145635/framemark/releases/tag/v0.9.0-rc14", releases[0].url)
        assertTrue(!releases[1].prerelease)
        assertTrue(UpdateJson.releases("not json").isEmpty())
    }

    @Test
    fun comparesSemverAndPrereleases() {
        assertTrue(UpdateVersion.isNewer("0.9.0-rc13", "0.9.0-rc14"))
        assertTrue(!UpdateVersion.isNewer("0.9.0-rc14", "0.9.0-rc14"))
        assertTrue(UpdateVersion.isNewer("0.9.0-rc13", "0.9.0"))
        assertTrue(!UpdateVersion.isNewer("0.9.0", "0.9.0-rc14"))
        assertTrue(UpdateVersion.isNewer("0.9.9", "0.9.10"))
        assertTrue(UpdateVersion.isNewer("0.8.0", "0.9.0-rc1"))
        assertTrue(UpdateVersion.isNewer("v0.9.0", "V1.0.0"))
        assertEquals(0, UpdateVersion.compare("0.9.0", "0.9.0"))
        assertEquals(0, UpdateVersion.compare("dev", "1.0.0"))
    }

    @Test
    fun picksTheHighestNewerRelease() {
        val releases = UpdateJson.releases(sample)
        val newest = UpdateVersion.newestAvailable("0.9.0-rc13", releases)
        assertNotNull(newest)
        // 同一基础版本下正式版高于 rc，所以 0.9.0 胜出。
        assertEquals("v0.9.0", newest!!.tag)
        assertNull(UpdateVersion.newestAvailable("0.9.0", releases))
    }

    @Test
    fun mirrorCandidatesAndPages() {
        val mirror = UpdateUrls.apiCandidates(UpdateSource.Mirror)
        assertEquals(UpdateUrls.MIRRORS.first() + UpdateUrls.API_DIRECT, mirror.first())
        assertEquals(UpdateUrls.API_DIRECT, mirror.last())
        val github = UpdateUrls.apiCandidates(UpdateSource.GitHub)
        assertEquals(UpdateUrls.API_DIRECT, github.first())
        assertEquals(UpdateUrls.MIRRORS.last() + UpdateUrls.API_DIRECT, github.last())

        val page = "https://github.com/RobertYang145635/framemark/releases/tag/v1"
        assertEquals(UpdateUrls.MIRRORS.first() + page, UpdateUrls.releasePage(page, UpdateSource.Mirror))
        assertEquals(page, UpdateUrls.releasePage(page, UpdateSource.GitHub))
    }
}
