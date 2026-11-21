package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 相册按天分组与日期标题（2026-11-21）：用户反馈「一直滑、没有时间提示」，所以网格要有
 * 日期标题、吸顶标签和快速滚动时的时间气泡。分组与标签是纯逻辑，这里用固定「现在」与时区钉住。
 */
class GalleryDateGroupsTest {

    // 2026-11-21 12:00 +08:00（= 2026-11-21T04:00:00Z），单位秒
    private val now = 1_795_233_600L
    private val zone = 8 * 3600L

    /** 本地的「今天 + day 天，hour 点」。 */
    private fun at(day: Int, hour: Int = 9): Long =
        now + day.toLong() * 86_400L + (hour - 12L) * 3600L

    @Test
    fun photosOfTheSameDayShareOneHeader() {
        val entries = GalleryDateGroups.entries(
            photos = listOf("c" to at(0, 18), "b" to at(0, 9), "a" to at(-1, 20)),
            nowSeconds = now,
            zoneOffsetSeconds = zone,
        )
        assertEquals(5, entries.size, "两张今天 + 一张昨天 = 2 个标题 + 3 张照片")
        val header = entries[0] as GalleryDateGroups.Entry.Header
        assertEquals("今天", header.label)
        assertEquals(2, header.photos)
        val older = entries[3] as GalleryDateGroups.Entry.Header
        assertEquals("昨天", older.label)
        assertEquals(1, older.photos)
    }

    @Test
    fun theHeaderLabelFallsBackToTheDateAndTheYear() {
        val thisYear = GalleryDateGroups.label(at(-30), now, zone)
        assertTrue(thisYear.endsWith("日") && thisYear.contains("月"), thisYear)
        val lastYear = GalleryDateGroups.label(at(-400), now, zone)
        assertTrue(lastYear.startsWith("2025年"), lastYear)
    }

    @Test
    fun appendingAPageOnlyAddsNewDays() {
        val first = GalleryDateGroups.entries(
            photos = listOf("b" to at(0, 18), "a" to at(0, 9)),
            nowSeconds = now,
            zoneOffsetSeconds = zone,
        )
        val second = GalleryDateGroups.append(
            existing = first,
            more = listOf("z" to at(0, 8), "y" to at(-1, 21)),
            nowSeconds = now,
            zoneOffsetSeconds = zone,
        )
        assertEquals(6, second.size, "2 张 + 2 张，跨天再插一条标题")
        assertEquals("今天", (second[0] as GalleryDateGroups.Entry.Header).label)
        assertEquals(3, (second[0] as GalleryDateGroups.Entry.Header).photos)
        assertEquals("昨天", (second[4] as GalleryDateGroups.Entry.Header).label)
    }

    @Test
    fun headerAtFindsTheGroupOfAnyRow() {
        val entries = GalleryDateGroups.entries(
            photos = listOf("a" to at(0), "b" to at(-1)),
            nowSeconds = now,
            zoneOffsetSeconds = zone,
        )
        assertEquals("今天", GalleryDateGroups.headerAt(entries, 0)!!.label)
        assertEquals("今天", GalleryDateGroups.headerAt(entries, 1)!!.label)
        assertEquals("昨天", GalleryDateGroups.headerAt(entries, 3)!!.label)
        assertEquals("昨天", GalleryDateGroups.headerAt(entries, 99)!!.label, "越界取最后一条")
    }

    @Test
    fun timezoneMovesTheDayBoundary() {
        // 同一时刻：UTC 是 11-20 20:00，+08:00 已经是 11-21 04:00。
        val moment = 1_795_204_800L
        assertEquals(20_777L, GalleryDateGroups.dayIndex(moment, 0))
        assertEquals(20_778L, GalleryDateGroups.dayIndex(moment, zone))
    }
}
