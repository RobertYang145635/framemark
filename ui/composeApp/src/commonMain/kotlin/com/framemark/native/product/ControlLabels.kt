package com.framemark.native.product

/**
 * Human copy for control ids, field names, option ids and stock line tokens.
 *
 * WP-C §4.1 (P0): this object is now the **single** label table. The old
 * inspector-local `HumanLabels` object keeps its signatures but delegates here,
 * so the desktop and the phone can never disagree on a field name:
 *
 *  1. [fields] — canonical display name per design-field key (`footerPct`,
 *     `logoCenterX`, …). This wins over any source label, which is what merges
 *     the two names a field used to have (`背景模糊` / `模糊强度`,
 *     `字标水平中心` / `Logo 水平位置`, …).
 *  2. [sourceLabels] — renames of legacy user-visible copy (`显示标志` →
 *     `显示 Logo`, `底色主题` → `底色`, …). Also the showcase RELABEL set.
 *  3. A source label that is already a raw identifier is looked up in [fields].
 *  4. A source label carrying CJK is kept (after stripping 「internalKey」).
 *
 * A control that cannot be turned into human copy is hidden rather than shown
 * as a raw key (ARCH §5). Internal fields ([internalFields], e.g. `params.slots`)
 * are always hidden. This table is display only — it never changes a value.
 */
internal object ControlLabels {

    /** Fields whose value is engine bookkeeping and must never reach the UI. */
    private val internalFields: Set<String> = setOf("slots")

    /**
     * Canonical name per design-field key. Keyed by the field name (the part
     * after the last `.` and before the first `:` of a design key), which is how
     * `capabilities.json`, `controls.json` and `inspector-meta.json` agree.
     */
    private val fields: Map<String, String> = mapOf(
        // wm-classic terminology (report §9 "术语" table + showcase RELABEL).
        "theme" to "底色",
        "footerMode" to "版式",
        "footerPct" to "底部留白",
        "marginPct" to "照片留白",
        "fontScale" to "整体文字大小",
        "showLogo" to "显示 Logo",
        "logoPos" to "位置",
        "logoScale" to "Logo 大小",
        "logoCenterX" to "水平位置",
        "showDate" to "显示日期",
        "showExif" to "显示拍摄参数",
        "showMake" to "显示厂商",
        "showModel" to "显示机型",
        "showLens" to "显示镜头",
        "brandLogoFile" to "Logo",
        "design.logo" to "Logo",
        // Remaining hand controls (union of the former two tables).
        "radiusPct" to "圆角",
        "blur" to "模糊强度",
        "shadow" to "阴影强度",
        "sidePadPct" to "左右留白",
        "topPadPct" to "顶部留白",
        "logoPct" to "字标大小",
        "paletteGapPct" to "色块间距",
        "palettePct" to "色块大小",
        "paletteRowPct" to "色块行高",
        "brandHeightPct" to "字标高度",
        "brandInsetPct" to "字标内缩",
        "brandTopPct" to "字标顶部位置",
        "cardHeightPct" to "卡片高度",
        "cardInsetPct" to "卡片内缩",
        "cardMattePct" to "卡片留白",
        "cardRadiusPct" to "卡片圆角",
        "contentSide" to "内容位置",
        "rowBoxHPct" to "参数行高度",
        "rowBoxWPct" to "参数行宽度",
        "rowLabelPct" to "参数标签字号",
        "rowPitchPct" to "参数行间距",
        "rowValueGapPct" to "参数值间距",
        "rowValuePct" to "参数值字号",
        "rowsTopPct" to "参数区顶部位置",
        "textInsetPct" to "文字内缩",
        "brandXPct" to "字标横向位置",
        "brandY" to "字标纵向位置",
        "exifY" to "参数行纵向位置",
        "sticker" to "贴纸文字",
        "stickerRightPct" to "贴纸右边距",
        "stickerScalePct" to "贴纸缩放",
        "insetPct" to "内缩",
        "footerOffsetPct" to "底栏偏移",
        "borderPct" to "边框宽度",
        "signature" to "签名文字",
        "frame" to "相框文字",
        "gridFirstXPct" to "网格起点 X",
        "gridFirstYPct" to "网格起点 Y",
        "gridPitchXPct" to "网格列间距",
        "gridPitchYPct" to "网格行间距",
        "labelPct" to "标签字号",
        "panelBottomPct" to "面板底部位置",
        "panelHeightPct" to "面板高度",
        "panelRadiusPct" to "面板圆角",
        "panelWidthPct" to "面板宽度",
        "swatchCols" to "色块列数",
        "swatchPct" to "色块大小",
        "swatchRows" to "色块行数",
        "cardWidthPct" to "卡片宽度",
        "glassLayout" to "玻璃布局",
        "pillOrder" to "胶囊顺序",
        "contentW" to "内容宽度",
        "logoGap" to "字标间距",
        "sepX" to "分隔线位置",
        "brandTextSizePct" to "品牌字号",
        // Fields that used to have two names in the frozen sources.
        "photoAspect" to "画幅",
    )

    /** Renames of legacy user-visible copy (showcase RELABEL + the A3 set). */
    private val sourceLabels: Map<String, String> = mapOf(
        // A3: 标志 / 字标 / Logo 混用 → one word.
        "显示标志" to "显示 Logo",
        "显示字标" to "显示 Logo",
        "标志位置" to "位置",
        "字标位置" to "位置",
        "字标缩放" to "Logo 大小",
        "字标水平中心" to "水平位置",
        "品牌标志" to "Logo",
        "品牌字标" to "Logo",
        "标志" to "Logo",
        // Showcase RELABEL / report §9 terms.
        "底色主题" to "底色",
        "页脚版式" to "版式",
        "照片留白" to "照片留白",
        "外边距" to "照片留白",
        "整卡字号缩放" to "整体文字大小",
        "衬底着色不透明度" to "背景透明度",
        "衬底不透明度" to "背景透明度",
        "画布画幅" to "照片比例",
        "方图占比" to "方形区域大小",
        "背景模糊" to "模糊强度",
        // 玻璃族手写卡把同一条滑杆叫「模糊度」：桌面上用的是源标签（保留中文），
        // 手机上走 param 优先的路径，落到 `fields["blur"]` 的「模糊强度」——同一个
        // 控件在两端两个名字。这里统一成「模糊强度」。
        "模糊度" to "模糊强度",
        "照片圆角" to "圆角",
        "圆角半径" to "圆角",
    )

    private val options: Map<String, String> = mapOf(
        "white" to "白色",
        "paper" to "纸白",
        "cream" to "奶油",
        "stone" to "石色",
        "ink" to "墨色",
        "black" to "黑色",
        "left" to "居左",
        "right" to "居右",
        "center" to "居中",
        "none" to "无",
        "lumo" to "Lumo",
        "hasselblad" to "哈苏",
        "HASSELBLAD" to "哈苏",
        "zeiss" to "蔡司",
        "leica" to "徕卡",
        "FUJIFILM" to "富士",
        "h2" to "H2",
        "h3" to "H3",
        "h4" to "H4",
        "l1" to "L1",
        "l2" to "L2",
        "l3" to "L3",
        "exif" to "参数",
        "brand-exif" to "品牌 + 参数",
        "brand" to "品牌",
        "profile-chip" to "品牌胶囊",
        "simple" to "简约",
        "overlay" to "叠加",
        "classic" to "经典",
        "header" to "页头",
        "chip" to "胶囊",
        "footer" to "页脚",
        "text-left" to "文字居左",
        "text-right" to "文字居右",
        "split" to "分栏",
        "pill" to "胶囊",
        "palette" to "色板",
        "vendor" to "厂商默认",
        "title" to "标题",
        "model" to "机型",
        "logo" to "字标",
        "brand-placement" to "品牌摆位",
        "inside" to "照片内",
        "in-photo" to "照片内",
        "stack" to "堆叠",
        "single" to "单张",
        "reference" to "参考",
        "bar" to "条状",
        "vertical" to "竖排",
        "horizontal" to "横排",
        "tl" to "左上",
        "tr" to "右上",
        "bl" to "左下",
        "br" to "右下",
        "graduation1" to "毕业样式 1",
        "graduation2" to "毕业样式 2",
        "leftTop" to "左上",
        "leftCenter" to "左中",
        "leftBottom" to "左下",
        "centerTop" to "上中",
        "centerBottom" to "下中",
        "rightTop" to "右上",
        "rightCenter" to "右中",
        "rightBottom" to "右下",
        "bottomHorCenter" to "底部居中",
        // Stock theme swatches whose ids never had a display label in the app.
        "light_blur" to "浅色模糊",
        "dark_blur" to "深色模糊",
        "transparent_white" to "透明白",
        "transparent_black" to "透明黑",
        "transparent" to "透明",
        "top_bg" to "顶部背景",
        "bottom_bg" to "底部背景",
        "texture_black" to "黑色纹理",
        "texture_white" to "白色纹理",
        "texture_rose_gray" to "玫灰纹理",
        "texture_blue_gray_light" to "浅蓝灰纹理",
        "texture_blue_gray_dark" to "深蓝灰纹理",
        "texture_blue_gray" to "蓝灰纹理",
        "texture_green_dark" to "深绿纹理",
        "texture_green_gray_light" to "浅绿灰纹理",
        "texture_brown_gray" to "棕灰纹理",
        "gradient_color_red" to "红色渐变",
        "gradient_color_blue_dark" to "深蓝渐变",
        "gradient_color_blue_light" to "浅蓝渐变",
        "gradient_color_yellow" to "黄色渐变",
        "noise_blur" to "噪点模糊",
        "glass" to "玻璃",
    )

    private val lineTokens: Map<String, String> = mapOf(
        "modelLine" to "机型",
        "dateLine" to "日期",
        "locationLine" to "地点",
        "exifCompositeLine" to "参数",
        "customLine" to "自定义",
        "model" to "机型",
        "dateLine+locationLine" to "日期与地点",
        "greetingLine" to "问候",
        "imageParams" to "图片参数",
        "literalText" to "文字",
        "template" to "模板",
    )

    /** Brand asset keys → brand names (moved here from the old HumanLabels). */
    private val brands: Map<String, String> = mapOf(
        "auto" to "自动匹配",
        "laika" to "徕卡",
        "laika_long" to "徕卡长标",
        "hasu_long_black" to "哈苏长标 · 黑",
        "hasu_long_white" to "哈苏长标 · 白",
        "hassel_h_black" to "哈苏 H · 黑",
        "hassel_wordmark_black" to "哈苏字标 · 黑",
        "lumo_black" to "Lumo · 黑",
        "fushi_white" to "富士 · 白",
        "zeiss" to "蔡司",
    )

    private val bracketToken = Regex("「([^」]*)」")
    private val whitespace = Regex("\\s+")

    /** Field key of a design key: `params.slotText:b0/g0` → `slotText`. */
    fun fieldOf(param: String): String =
        param.substringBefore(':').substringAfterLast('.').trim()

    /** True when this design key addresses engine bookkeeping, not user copy. */
    fun isInternal(param: String): Boolean = fieldOf(param) in internalFields

    /**
     * Human label for one control, or null when there is none (the control is
     * then hidden). [param] is the design/consumer key the control writes.
     */
    fun forControl(sourceLabel: String, controlId: String, param: String = ""): String? {
        if (param.isNotBlank()) {
            if (isInternal(param)) return null
            // Exact design key first (`design.logo`), then its field name.
            fields[param]?.let { return it }
            val field = fieldOf(param)
            fields[field]?.let { return it }
            fields[param.substringAfterLast('.')]?.let { return it }
        }
        val raw = sourceLabel.trim()
        if (raw.isEmpty()) return null
        sourceLabels[raw]?.let { return it }
        if (raw.hasCjk()) return sanitize(raw)
        fields[raw]?.let { return it }
        val tail = controlId.substringAfterLast(':').substringAfterLast('.')
        fields[tail]?.let { return it }
        fields[raw.substringAfterLast(':').substringAfterLast('.')]?.let { return it }
        return null
    }

    /** Back-compat alias for the old table name. */
    fun forLabel(sourceLabel: String, controlId: String): String? = forControl(sourceLabel, controlId)

    /** `编辑「exifCompositeLine」文字` → `编辑参数文字`; null when nothing readable is left. */
    fun sanitize(raw: String): String? {
        val renamed = sourceLabels[raw.trim()] ?: raw
        val cleaned = bracketToken.replace(renamed) { match ->
            val key = match.groupValues[1].substringBefore('[').trim()
            lineTokens[key] ?: ""
        }.replace(whitespace, " ").trim()
        // "Logo" is human copy even without CJK; it is the word the V2 terms use.
        return if (cleaned.hasCjk() || cleaned == "Logo") cleaned else null
    }

    /** Null means "no human copy"; callers hide the option/control instead of showing the id. */
    fun forOption(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        options[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return null
    }

    /** Option display that keeps unknown ids distinguishable from "unselected". */
    fun forDisplayOption(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return raw
        return forOption(raw) ?: "未知选项（$raw）"
    }

    /** Theme / swatch ids use the shared option vocabulary; unknown ids are explicit. */
    fun forThemeOption(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return "未选择"
        val display = forOption(raw)
        return if (display == null || display == "未设置") "未知主题（$raw）" else display
    }

    /** A free-text control may carry an enum token; keep prose, humanise keys. */
    fun forText(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        options[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        if (Regex("^[a-z][a-z0-9_-]*$").matches(raw)) return "未设置"
        return raw
    }

    /**
     * The legacy brand label for [value], or null when the table does not know it.
     * [forBrand] answers 未设置 for an unknown key, which is right for a control that
     * must never look empty but wrong for a display chain that has other resolvers
     * (UI copy audit 2026-11-13, item 7).
     */
    fun knownBrandLabel(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        brands[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return null
    }

    /** Brand logos are asset keys in the frozen source; show the brand name only. */
    fun forBrand(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        brands[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return "未设置"
    }
}

internal fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }
