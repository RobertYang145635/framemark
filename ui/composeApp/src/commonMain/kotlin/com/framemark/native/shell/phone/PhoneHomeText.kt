package com.framemark.native.shell.phone

import com.framemark.native.product.ControlLabels

/**
 * 首页的文案与行格式，逐字取自演示稿 `phone.js` 的 `buildHome()`（25-52 行）。
 *
 * 纯函数 + 常量：界面只读这里，测试直接断言字符串，所以"文案和演示稿不一样"
 * 这种回归在 commonTest 里就能挡住，不用等真机截图。
 */
object PhoneHomeText {
    /** `h('h1', '框印')` */
    const val WORDMARK: String = "框印"

    /** `h('p.hsub', …)` */
    const val SUBTITLE: String = "给照片加上相机边框与参数水印"

    /** hero 卡：`h3` / `p` / `.btn.primary` */
    const val HERO_TITLE: String = "从相册选择照片"
    const val HERO_SUBTITLE: String = "可多选，每张照片单独保存设计"
    const val HERO_ACTION: String = "选照片"

    /** 两张功能卡 */
    const val BATCH_TITLE: String = "批量"
    const val BATCH_SUBTITLE: String = "多张照片一起加边框"
    const val PRESETS_TITLE: String = "预设"

    /** 最近编辑卡：标题与右上「全部」链接 */
    const val RECENT_TITLE: String = "最近编辑"
    const val RECENT_ALL: String = "全部"

    /** 首页齿轮的无障碍名（演示稿 `aria-label="设置"`）。 */
    const val SETTINGS_LABEL: String = "设置"

    /**
     * 预设卡的副文案。演示稿写死 `PRESETS.length + ' 个常用组合'`（4 个内置组合）；
     * 原生这边只有用户自己存的预设，0 个时不能印「0 个常用组合」，就退回标题词。
     */
    fun presetSubtitle(count: Int): String = if (count > 0) "$count 个常用组合" else "常用组合"

    /** 主题 id（`white` / `ink` …）转演示稿的中文名；未知 id 返回 null，不印 id。 */
    fun themeName(themeKey: String?): String? {
        val raw = themeKey?.trim().orEmpty()
        return if (raw.isEmpty()) null else ControlLabels.forOption(raw)
    }

    /**
     * 最近编辑行的第二行：`边框名 · 主题`，即演示稿
     * `CARD[s.card].name + ' · ' + THEME_LABEL[s.p.theme]`。
     *
     * 没有主题覆盖时只印边框名；连边框都没有时印 [NO_BORDER]。序号不在这里——
     * 它占行尾那一列（[recentPosition]），两处都印就重复了。
     */
    fun recentSubtitle(entry: PhoneRecentEdit): String {
        val border = entry.borderName?.takeIf { it.isNotBlank() } ?: return NO_BORDER
        val theme = themeName(entry.themeKey) ?: return border
        return "$border · $theme"
    }

    /** 这一行还没有边框时的措辞（与 [PhoneRecentEdit.subtitle] 的首词一致）。 */
    const val NO_BORDER: String = "未应用边框"

    /**
     * 最近编辑行右端的元信息。演示稿是相对时间（`刚刚` / `昨天`），但项目里没有
     * 每张照片的时间戳（`PhotoRef` 只有 id/path/尺寸），所以这一列放真实的
     * 「序号/总数」；单张照片时不占这一列。
     */
    fun recentPosition(entry: PhoneRecentEdit): String =
        if (entry.count > 1) "${entry.index}/${entry.count}" else ""
}
