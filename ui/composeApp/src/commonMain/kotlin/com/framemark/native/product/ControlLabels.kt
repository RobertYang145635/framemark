package com.framemark.native.product

/**
 * Human copy for control ids, option ids and stock line tokens.
 *
 * The frozen data carries identifiers as labels in two places: hand sliders use
 * the field name (`footerPct`), stock audit labels wrap the internal line key in
 * 「」 (`编辑「exifCompositeLine」文字`). A control that cannot be turned into
 * human copy is hidden rather than shown as a raw key (ARCH §5); this table is
 * the translation layer, never a source of values.
 */
internal object ControlLabels {
    private val controls: Map<String, String> = mapOf(
        "footerPct" to "底部留白",
        "marginPct" to "外边距",
        "radiusPct" to "圆角半径",
        "shadow" to "阴影强度",
        "blur" to "模糊强度",
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

    private val bracketToken = Regex("「([^」]*)」")
    private val whitespace = Regex("\\s+")

    fun forControl(sourceLabel: String, controlId: String): String? {
        val raw = sourceLabel.trim()
        if (raw.isEmpty()) return null
        if (raw.hasCjk()) return sanitize(raw)
        controls[raw]?.let { return it }
        val tail = controlId.substringAfterLast(':').substringAfterLast('.')
        controls[tail]?.let { return it }
        controls[raw.substringAfterLast(':').substringAfterLast('.')]?.let { return it }
        return null
    }

    /** `编辑「exifCompositeLine」文字` → `编辑参数文字`; null when nothing readable is left. */
    fun sanitize(raw: String): String? {
        val cleaned = bracketToken.replace(raw) { match ->
            val key = match.groupValues[1].substringBefore('[').trim()
            lineTokens[key] ?: ""
        }.replace(whitespace, " ").trim()
        return if (cleaned.hasCjk()) cleaned else null
    }

    /** Null means "no human copy"; callers hide the option/control instead of showing the id. */
    fun forOption(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        options[raw]?.let { return it }
        if (raw.hasCjk()) return raw
        return null
    }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }
}

internal fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }
