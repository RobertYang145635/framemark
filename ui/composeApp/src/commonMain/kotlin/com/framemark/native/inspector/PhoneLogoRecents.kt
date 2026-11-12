package com.framemark.native.inspector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 进程级「最近使用品牌」MRU（最多 [PhoneLogoPickerState.RECENT_CAPACITY] 个）。
 *
 * 持久化决策：仓库里唯一的设置文件是 `settings/appearance.json`，它是**严格按版本
 * 解码的外观决定**（未知字段会让整份文档失效，见 `AppearanceSettingsCodec`），把与
 * 外观无关的 MRU 混进去会破坏那个约定；仓库里也没有通用的 key-value 设置存储。所以
 * 这里按任务允许的做法用**内存 MRU**：进程内跨打开/关闭选择器、跨工具切换保留，
 * 重启应用后清空。
 *
 * 以后若要落盘，按 `library/RecentFramesEnv` 的同一个 seam（接口 + 两个平台的
 * 文件实现 + 入口点安装）接上即可：UI 只读这里的快照状态，记录点只有一处。
 */
internal object PhoneLogoRecents {
    var keys: List<String> by mutableStateOf(emptyList())
        private set

    /** 用户点「添加」把某个品牌写到设计上时调用（浏览不算使用）。 */
    fun record(brandKey: String) {
        keys = PhoneLogoPickerState.recordRecent(keys, brandKey)
    }

    /** 仅测试用：清空进程级状态，避免测试之间互相串味。 */
    fun clearForTest() {
        keys = emptyList()
    }
}
