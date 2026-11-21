package com.framemark.native.shell

import com.framemark.native.library.LibraryCard

/**
 * 恢复工程时右栏要「点亮」的那张卡。
 *
 * 一张照片的设计存在工程里，卡片目录来自边框库；冷启动时 `selectedCard` 是空的，
 * 于是右栏写「未选择边框」，而底部胶片条已经显示「3/3 张已选边框」——同一份工程
 * 两种说法。这里只做「设计里的卡 id → 边框库里的卡」这一次查表，不写设计、不改
 * 舞台模式：恢复工程只是把这张卡点亮，参数面板自然按工程里的设计回来。
 *
 * 没有设计、或者这张卡已经不在库里（下架、改名）时返回 null —— 右栏那时确实
 * 没有可展示的边框，不猜。
 */
internal fun restoredCardOf(designCardId: String?, cards: List<LibraryCard>): LibraryCard? {
    val id = designCardId?.takeIf { it.isNotBlank() } ?: return null
    return cards.firstOrNull { it.id == id }
}
