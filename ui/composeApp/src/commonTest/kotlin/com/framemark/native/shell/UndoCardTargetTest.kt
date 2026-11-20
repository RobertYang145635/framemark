package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 撤销跨边框要指回的那张卡 —— 真机 rc38 的缺陷（`FM_CARD_RESTORE … via=undo
 * unresolved`）：回调挂在 `LaunchedEffect(Unit)` 里，捕获的是首次组合的库快照
 * （`Loading` ⇒ 空），于是撤销把历史一步一步吃掉，画面一动不动。
 *
 * 这里钉的是**解析时机**：`UndoCardTarget` 每次解析都现读外壳给的读取器，所以库到得
 * 比回调晚也照样能指回去。
 */
class UndoCardTargetTest {

    private fun card(id: String) = LibraryCard(
        id = id,
        name = id,
        category = "white",
        categoryName = "白边边框",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        templateCategory = "白边",
        previewSourcePath = "",
        previewFile = "$id.webp",
    )

    @Test
    fun resolvesAgainstTheSnapshotThatExistsWhenTheUndoHappens() {
        var snapshot: List<LibraryCard> = emptyList() // 首次组合：库还在 Loading
        val target = UndoCardTarget { snapshot }

        assertNull(target.resolve("wm-classic"), "库还没到，解析不到是正常的")

        snapshot = listOf(card("glass-white-1"), card("wm-classic"))
        assertEquals(
            "wm-classic",
            target.resolve("wm-classic")?.id,
            "库到了之后再撤销，必须指回上一张边框",
        )
    }

    @Test
    fun unknownAndMissingIdsResolveToNothing() {
        val target = UndoCardTarget { listOf(card("wm-classic")) }
        assertNull(target.resolve(null), "未选边框那一步没有卡片")
        assertNull(target.resolve("stock-not-in-this-snapshot"))
    }
}
