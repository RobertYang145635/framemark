package com.framemark.native.shell

import androidx.compose.ui.input.key.Key

/**
 * 桌面方向键的归属判定（2026-11-16 UI 评审 P0-1）。
 *
 * 根节点原来在 `onPreviewKeyEvent` 里无条件消费 ←/→/↑/↓（切边框/照片），而焦点在
 * 参数滑杆上时，滑杆自己的方向键调节排在**父节点 preview 之后**，于是「调参数」变成
 * 「切卡片」。现在方向键只在**没有子控件先消费**时才由根节点处理：
 *
 *  - 根节点改在 `onKeyEvent` 里调用本判定（key event 从焦点节点向上冒泡，滑杆会在
 *    自己的 preview 里先消费掉方向键）；
 *  - 文本编辑、弹层打开、批量模式时一律不接管；
 *  - 带 Ctrl / Shift 的方向键留给其它快捷键，不在这里换对象。
 *
 * 纯函数，测试直接覆盖，不需要起桌面窗口。
 */
object DesktopArrowKeys {

    /** 根节点应当执行的导航：`-1` 上一个，`+1` 下一个，`0` 不处理。 */
    fun navigate(
        key: Key,
        ctrl: Boolean,
        shift: Boolean,
        textEditing: Boolean,
        sheetOpen: Boolean,
        editStage: Boolean,
    ): Int {
        if (textEditing || sheetOpen || !editStage) return 0
        if (ctrl || shift) return 0
        return when (key) {
            Key.DirectionLeft, Key.DirectionUp -> -1
            Key.DirectionRight, Key.DirectionDown -> 1
            else -> 0
        }
    }
}
