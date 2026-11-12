package com.framemark.native.shell.phone

import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection

/**
 * 测试用的控件工厂：只造 `InspectorControl` 的公开字段，不碰 Compose、不碰资源。
 *
 * 版式政策的输入是「这一页自己的控件」（`phonePageControls`），所以每一条页高 /
 * 分组的测试都需要几张形状真实的控件表。这些工厂放在 commonTest 里，`PhonePageLayoutTest`
 * 与更新过的底板高度测试共用同一份，免得每个测试各写一套构造参数而慢慢分叉。
 */
internal object PhoneTestControls {

    fun control(
        id: String,
        param: String,
        label: String,
        type: ControlType,
        kind: String = when (type) {
            ControlType.Switch -> "switch"
            ControlType.Slider -> "slider"
            ControlType.Select -> "gravitySegment"
            ControlType.Text -> "text"
            ControlType.Color -> "themeSwatches"
            ControlType.Brand -> "brandPicker"
        },
        section: InspectorSection = InspectorSection.Layout,
        initial: ControlValue = when (type) {
            ControlType.Switch -> ControlValue.Flag(true)
            ControlType.Text -> ControlValue.Str("")
            ControlType.Select, ControlType.Color, ControlType.Brand -> ControlValue.Str("")
            ControlType.Slider -> ControlValue.Num(10f)
        },
        min: Float? = if (type == ControlType.Slider) 0f else null,
        max: Float? = if (type == ControlType.Slider) 100f else null,
        step: Float? = if (type == ControlType.Slider) 1f else null,
        options: List<ControlOption> = emptyList(),
        hidden: Boolean = false,
    ) = InspectorControl(
        key = "card::$id",
        controlId = id,
        param = param,
        label = label,
        kind = kind,
        section = section,
        type = type,
        initial = initial,
        min = min,
        max = max,
        step = step,
        options = options,
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = hidden,
    )

    fun slider(
        id: String,
        param: String = "params.$id",
        label: String = id,
        section: InspectorSection = InspectorSection.Layout,
    ) = control(id, param, label, ControlType.Slider, section = section)

    fun switch(
        id: String,
        param: String = "text.$id",
        label: String = id,
        section: InspectorSection = InspectorSection.Layout,
    ) = control(id, param, label, ControlType.Switch, section = section)

    fun segment(
        id: String,
        param: String = "params.$id",
        label: String = id,
        options: List<String> = listOf("classic", "simple", "overlay"),
        section: InspectorSection = InspectorSection.Layout,
    ) = control(
        id,
        param,
        label,
        ControlType.Select,
        section = section,
        initial = ControlValue.Str(options.first()),
        options = options.map { ControlOption(it, it) },
    )

    fun text(
        id: String,
        param: String = "text.$id",
        label: String = id,
        section: InspectorSection = InspectorSection.Text,
    ) = control(id, param, label, ControlType.Text, section = section)

    fun brand(
        id: String = "design.logo",
        label: String = "品牌字标",
        section: InspectorSection = InspectorSection.Logo,
    ) = control(id, "design.logo", label, ControlType.Brand, section = section)

    fun theme(
        id: String = "theme.theme",
        options: List<String> = listOf("white", "paper", "cream", "stone", "ink", "black"),
    ) = control(
        id,
        "theme",
        "底色主题",
        ControlType.Select,
        kind = "themeSwatches",
        section = InspectorSection.Theme,
        initial = ControlValue.Str(options.first()),
        options = options.map { ControlOption(it, it) },
    )

    /** 默认边框卡的 Logo 页控件：显示 / 位置 / 大小 / 水平位置 + 品牌。 */
    fun logoControls(): List<InspectorControl> = listOf(
        switch("params:showLogo", param = "params.showLogo", label = "显示标志", section = InspectorSection.Logo),
        segment("params:logoPos", param = "params.logoPos", label = "标志位置", options = listOf("left", "center", "right"), section = InspectorSection.Logo),
        slider("params:logoScale", param = "params.logoScale", label = "字标缩放", section = InspectorSection.Logo),
        slider("params:logoCenterX", param = "params.logoCenterX", label = "字标水平中心", section = InspectorSection.Logo),
        brand(),
    )

    /**
     * 一张「典型手写卡」的四页控件（18 个，覆盖四个页签；与 `wm-classic` 同形）：
     * 样式 4 项（底色 / 页脚自适应 / 版式 / 底部留白），文字 7 项（5 个显示开关 +
     * 自定义行 + 字号），Logo 5 项，水印页没有声明控件。
     */
    fun styleControls(): List<InspectorControl> = listOf(
        theme(),
        control(
            "params:footerAuto",
            "params.footerAuto",
            "页脚自适应",
            ControlType.Switch,
            kind = "toggle",
        ),
        segment("params:footerMode", param = "params.footerMode", label = "页脚版式"),
        slider("params:footerPct", param = "params.footerPct", label = "底部留白"),
    )

    fun textControls(): List<InspectorControl> = listOf(
        switch("text:showMake", label = "显示厂商"),
        switch("text:showModel", label = "显示机型"),
        switch("text:showLens", label = "显示镜头"),
        switch("text:showDate", label = "显示日期行"),
        switch("text:showExif", label = "显示参数行"),
        text("text:customLine", label = "自定义行文字"),
        slider("text:fontScale", param = "fontScale", label = "整卡字号缩放"),
    )

    /** 轻量卡：样式页只有底色，文字页只有一个开关，Logo 页只有品牌。 */
    fun lightStyleControls(): List<InspectorControl> = listOf(theme())

    fun lightTextControls(): List<InspectorControl> = listOf(
        slider("text:fontScale", param = "fontScale", label = "整卡字号缩放"),
    )

    fun lightLogoControls(): List<InspectorControl> = listOf(brand())
}
