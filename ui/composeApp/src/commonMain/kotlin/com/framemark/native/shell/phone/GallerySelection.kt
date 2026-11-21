package com.framemark.native.shell.phone

/**
 * 自建相册的多选状态（2026-11-21）。
 *
 * 为什么单独写在 commonMain：这套「勾选顺序、上限、去重」的规则是自建浏览器里唯一
 * 有分支逻辑的部分，放在纯 Kotlin 里就能直接用单元测试钉住（相册查询与缩略图是
 * Android 侧的事）。
 *
 * 行为对齐系统选择器与微信/小红书这类自建相册：
 *  - 点一下选中，再点一下取消；
 *  - 勾选角标显示**选择顺序**（1、2、3…），不是简单的对勾 —— 导入顺序即用户选择的顺序；
 *  - 到上限后点新的照片不再加入（由调用方提示），取消已有的永远可以；
 *  - [ordered] 永远等于用户点选的先后顺序。
 */
class GallerySelection(private val max: Int) {

    private val order = LinkedHashSet<String>()

    val size: Int get() = order.size
    val isEmpty: Boolean get() = order.isEmpty()
    val isFull: Boolean get() = order.size >= max
    val limit: Int get() = max

    /** 已选数量；同一张照片只算一次。 */
    fun count(): Int = order.size

    fun contains(id: String): Boolean = id in order

    /** 第几顺位（从 1 开始）；未选中返回 0。 */
    fun positionOf(id: String): Int {
        var index = 0
        for (item in order) {
            index++
            if (item == id) return index
        }
        return 0
    }

    /**
     * 点一下。返回 true 表示状态变了（选中或取消）；到上限时点未选中的照片返回 false，
     * 调用方据此给一句提示（提示语由界面决定，这里不产出文案）。
     */
    fun toggle(id: String): Boolean {
        if (id.isBlank()) return false
        if (order.remove(id)) return true
        if (order.size >= max) return false
        order += id
        return true
    }

    fun clear() = order.clear()

    /** 导入顺序：用户点选的先后。 */
    fun ordered(): List<String> = order.toList()

    /** 切换相册（相册分组）时保留已选，只换列表 —— 与系统选择器一致。 */
    fun retainOnly(available: Set<String>) {
        order.retainAll(available)
    }
}
