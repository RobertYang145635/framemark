package com.framemark.native.shell.phone

import com.framemark.native.shell.EditorTool

/**
 * 手机端「预设」入口与两个主操作（保存 / 应用）的纯决策：无 Compose、无资源、无持久化，
 * `PhonePresetEntryPolicyTest` 直接测这一层。
 *
 * 之前的入口在底板页头的折叠箭头里：只有 **边框** 工具、必须 **长按** 箭头才看得到
 * （`PhoneSheetChrome` 的 WP-TY1 菜单）。验收人反馈「保存预设和使用预设我没看到」，
 * 所以入口改成工具条尾部一枚常驻动作 —— 与当前工具无关，一步打开同一个面板。
 *
 * 面板本身不重构：保存仍走 [com.framemark.native.shell.PhoneProjectStore.savePreset]，
 * 应用仍走 `applyPreset`（写进工程 → 外壳递增 hydrateToken → 会话/舞台/已改项数照常）。
 * 这里只回答「动作出不出现」「按钮能不能点」「保存后说什么」。
 */
internal object PhonePresetEntryPolicy {

    /** 工具条动作的可见文字。**这就是验收人要找的那两个字。** */
    const val ACTION_LABEL: String = "预设"

    /** 面板里保存按钮的文字：保存的是当前照片的整套设计。 */
    const val SAVE_LABEL: String = "保存为预设"

    /** 面板里列表小节的标题：应用已保存预设。 */
    const val APPLY_TITLE: String = "应用预设"

    /**
     * 工具条尾部「预设」动作对哪些工具出现。
     *
     * **每个工具都出现**：保存的是整套设计（边框 + 参数 + 文字 + Logo + 水印 + 导出
     * 设置），用户可能在 文字 / 水印 页上刚改完就想存，不该要求先切回 边框。
     * 旧规则 `if (tool == EditorTool.Border)` 正是不可见的根源之一，别再引回来。
     */
    fun showsAction(tool: EditorTool): Boolean = tool in EditorTool.entries

    /** 「保存为预设」只有在当前照片已有设计时可用（没有设计就没有可存的东西）。 */
    fun saveEnabled(hasActiveDesign: Boolean): Boolean = hasActiveDesign

    /** 列表里的「套用」需要先有一张活动照片可以承载这套设计。 */
    fun applyEnabled(hasActivePhoto: Boolean): Boolean = hasActivePhoto

    /**
     * 保存成功后的状态行。复用面板里已有的状态文本位置（名字 + 内嵌水印大小），
     * 与桌面 `ProjectPresetSheet` 的成功提示同一套字段。
     */
    fun savedMessage(name: String, watermarkBytes: Int): String {
        if (watermarkBytes <= 0) return "已保存「$name」（无水印）"
        val size = if (watermarkBytes < 1024) "$watermarkBytes B" else "${watermarkBytes / 1024} KB"
        return "已保存「$name」，含水印 $size"
    }
}
