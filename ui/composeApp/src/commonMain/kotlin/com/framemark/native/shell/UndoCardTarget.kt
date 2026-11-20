package com.framemark.native.shell

import com.framemark.native.library.LibraryCard

/**
 * 撤销 / 重做跨了边框时要指回的那张卡。
 *
 * 为什么要这个小类：`DesignSession.onCardRestored` 是在 `LaunchedEffect(Unit)` 里挂上
 * 去的，它捕获的是**首次组合**的闭包。手机外壳用 `cardOf(...)`（组成期闭包里的
 * `libraryCards`）、桌面外壳用 `libraryIndex?.card(...)`，两者在首次组合时都还是空的
 * （库还在 Loading），于是撤销跨边框永远解析不到卡片：历史被一步一步消耗掉，画面却
 * 一动不动。真机实测（rc38，OPPO）的证据行是「card=glass-white-1 via=undo
 * unresolved」——解析到的是空表，不是「这一步没有卡片」。
 *
 * 所以卡片**每次解析时现读**（外壳传 `{ currentCards }` / `{ currentLibraryIndex }`
 * 这类 `rememberUpdatedState` 的读取器），而不是捕获一份快照。
 */
internal class UndoCardTarget(private val cards: () -> List<LibraryCard>) {

    fun resolve(cardId: String?): LibraryCard? {
        val id = cardId ?: return null
        return cards().firstOrNull { it.id == id }
    }
}
