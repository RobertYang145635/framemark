package com.framemark.native.shell.phone

/**
 * 自建相册的「按天分组」（2026-11-21 用户反馈：一直滑动找照片、没有时间提示）。
 *
 * 相册按拍摄日期倒序排列，同一天的照片归到一组，每组前面一条日期标题（今天 / 昨天 /
 * M月D日 / YYYY年M月D日）。分组是**纯逻辑**，所以放在 commonMain，用固定 `now` 与固定
 * 时区做单元测试；时间格式化不依赖 `java.time`，Android 与桌面两端都能跑。
 */
object GalleryDateGroups {

    /** 一条网格内容：日期标题或照片。 */
    sealed interface Entry {
        val key: String

        data class Header(override val key: String, val label: String, val photos: Int) : Entry

        data class Item(override val key: String, val photoId: String) : Entry
    }

    /**
     * 把按时间倒序的 [photos]（id → 秒级时间戳）展开成「标题 + 照片」的条目流。
     *
     * 相邻同一天合并成一组；跨天时插入一条标题。已有的 [existing] 条目可以传进来做增量
     * 追加（分页加载），只有当新照片属于新的一天时才会追加新标题。
     */
    fun entries(
        photos: List<Pair<String, Long>>,
        nowSeconds: Long,
        zoneOffsetSeconds: Long = 0,
    ): List<Entry> {
        val out = ArrayList<Entry>(photos.size + photos.size / 8 + 1)
        var currentDay: Long? = null
        for ((id, at) in photos) {
            val day = dayIndex(at, zoneOffsetSeconds)
            if (day != currentDay) {
                currentDay = day
                out += Entry.Header(
                    key = "day:$day",
                    label = label(at, nowSeconds, zoneOffsetSeconds),
                    photos = 0,
                )
            }
            out += Entry.Item(key = id, photoId = id)
        }
        return withCounts(out)
    }

    /** 追加一页到已经展开的条目流末尾（分页加载用）。 */
    fun append(existing: List<Entry>, more: List<Pair<String, Long>>, nowSeconds: Long, zoneOffsetSeconds: Long): List<Entry> {
        if (more.isEmpty()) return existing
        val out = ArrayList<Entry>(existing.size + more.size + 1)
        out += existing
        var currentDay = existing.asReversed()
            .filterIsInstance<Entry.Header>()
            .firstOrNull()
            ?.key
        for ((id, at) in more) {
            val day = "day:${dayIndex(at, zoneOffsetSeconds)}"
            if (day != currentDay) {
                currentDay = day
                out += Entry.Header(key = day, label = label(at, nowSeconds, zoneOffsetSeconds), photos = 0)
            }
            out += Entry.Item(key = id, photoId = id)
        }
        return withCounts(out)
    }

    /** 某条索引所属的日期标题（标题本身返回自己），用于吸顶日期与快速滚动气泡。 */
    fun headerAt(entries: List<Entry>, index: Int): Entry.Header? {
        if (entries.isEmpty()) return null
        var i = index.coerceIn(0, entries.size - 1)
        while (i >= 0) {
            val entry = entries[i]
            if (entry is Entry.Header) return entry
            i--
        }
        return null
    }

    /** 一次线性扫描：数出每组照片数，回填到标题上（网格里显示「12 张」）。 */
    private fun withCounts(entries: List<Entry>): List<Entry> {
        val counts = HashMap<String, Int>()
        var current: String? = null
        for (entry in entries) {
            when (entry) {
                is Entry.Header -> current = entry.key
                is Entry.Item -> current?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        return entries.map { entry ->
            if (entry is Entry.Header) entry.copy(photos = counts[entry.key] ?: 0) else entry
        }
    }

    /** 本地时间的「第几天」，与 1970-01-01 的差。 */
    fun dayIndex(seconds: Long, zoneOffsetSeconds: Long): Long =
        Math.floorDiv(seconds + zoneOffsetSeconds, 86_400L)

    /**
     * 日期标题：今天 / 昨天 / 今年内用「M月D日」，跨年用「YYYY年M月D日」。
     *
     * 注意「今年」按 [nowSeconds] 与 [zoneOffsetSeconds] 推出来的年份判断，不读系统时区，
     * 这样测试可以固定时间。
     */
    fun label(seconds: Long, nowSeconds: Long, zoneOffsetSeconds: Long): String {
        val day = dayIndex(seconds, zoneOffsetSeconds)
        val today = dayIndex(nowSeconds, zoneOffsetSeconds)
        val year = yearOf(seconds, zoneOffsetSeconds)
        return when (day) {
            today -> "今天"
            today - 1 -> "昨天"
            else -> if (year == yearOf(nowSeconds, zoneOffsetSeconds)) {
                "${monthOf(seconds, zoneOffsetSeconds)}月${dayOfMonth(seconds, zoneOffsetSeconds)}日"
            } else {
                "${year}年${monthOf(seconds, zoneOffsetSeconds)}月${dayOfMonth(seconds, zoneOffsetSeconds)}日"
            }
        }
    }

    // 用「从 1970-01-01 起的天数」做公历换算（Howard Hinnant 的 civil_from_days），
    // 这样不依赖平台日历实现，两端与测试结果一致。
    private fun civil(seconds: Long, zoneOffsetSeconds: Long): Triple<Int, Int, Int> {
        val z = dayIndex(seconds, zoneOffsetSeconds) + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }

    fun yearOf(seconds: Long, zoneOffsetSeconds: Long): Int = civil(seconds, zoneOffsetSeconds).first
    fun monthOf(seconds: Long, zoneOffsetSeconds: Long): Int = civil(seconds, zoneOffsetSeconds).second
    fun dayOfMonth(seconds: Long, zoneOffsetSeconds: Long): Int = civil(seconds, zoneOffsetSeconds).third
}
