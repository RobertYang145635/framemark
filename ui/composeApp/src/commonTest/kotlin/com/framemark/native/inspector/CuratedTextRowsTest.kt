package com.framemark.native.inspector

import com.framemark.native.workflow.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-V4 A: 文字页四行的行序与身份判定（`core.js:738 paneClassicText`）。
 *
 * 行序必须是 品牌(make) → 机型(model) → 拍摄参数(exif) → 日期(date)，与演示稿
 * `keys = ['mk','md','ex','dt']` 一致；渲染器只报 `brand` 的时候也按「品牌」认。
 * IR 的 path key 不能被误判成这四行，否则厂商卡会走错版式。
 */
class CuratedTextRowsTest {

    private fun slot(id: String, key: String?, text: String = "value"): TextSlot = TextSlot(
        id = id,
        text = text,
        x = 10.0,
        y = 10.0,
        w = 100.0,
        h = 20.0,
        fontSize = 20.0,
        baseline = 15.0,
        lines = 1,
        key = key,
        raw = text,
        stable = key != null,
    )

    @Test
    fun classic_rows_follow_the_showcase_order() {
        val rows = classicTextRows(
            CuratedTextSlots(
                slots = listOf(
                    slot("a", "hand:wm-classic:date"),
                    slot("b", "hand:wm-classic:exif"),
                    slot("c", "hand:wm-classic:model"),
                    slot("d", "hand:wm-classic:brand"),
                ),
                widthPx = 2667,
                heightPx = 2296,
            ),
        )
        assertEquals(listOf("品牌", "机型", "拍摄参数", "日期"), rows.map { it.label })
        assertEquals(
            listOf("showMake", "showModel", "showExif", "showDate"),
            rows.map { it.showParam },
        )
        assertEquals("hand:wm-classic:brand", rows.first().slot.key)
    }

    @Test
    fun declared_brand_footer_key_spelling_is_recognised() {
        // brandFooter 的历史声明键是 `wm-classic#brand`，没有 `hand:` 前缀。
        val rows = classicTextRows(
            CuratedTextSlots(
                slots = listOf(slot("1", "wm-classic#brand"), slot("2", "wm-classic#date")),
                widthPx = 1000,
                heightPx = 1000,
            ),
        )
        assertEquals(listOf("品牌", "日期"), rows.map { it.label })
    }

    @Test
    fun ir_node_paths_are_not_classic_rows() {
        val rows = classicTextRows(
            CuratedTextSlots(
                slots = listOf(slot("a", "b0/g0"), slot("b", "b7/g2/g1")),
                widthPx = 1000,
                heightPx = 1000,
            ),
        )
        assertTrue(rows.isEmpty(), "IR path keys must keep the generic slot-row page")
    }

    @Test
    fun both_brand_and_make_spellings_do_not_duplicate_the_brand_row() {
        val rows = classicTextRows(
            CuratedTextSlots(
                slots = listOf(
                    slot("a", "hand:wm-classic:make"),
                    slot("b", "hand:wm-classic:brand"),
                    slot("c", "hand:wm-classic:model"),
                ),
                widthPx = 1000,
                heightPx = 1000,
            ),
        )
        assertEquals(listOf("品牌", "机型"), rows.map { it.label })
        // 演示稿的行序里 `brand` 在 `make` 之前；两者同时出现时只保留第一行。
        assertEquals("hand:wm-classic:brand", rows.first().slot.key)
    }

    @Test
    fun missing_canvas_keeps_rows_for_text_but_no_diagram_basis() {
        val rows = classicTextRows(
            CuratedTextSlots(
                slots = listOf(slot("a", "hand:x:brand")),
                widthPx = 0,
                heightPx = 0,
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("brand", rows.single().slot.key?.substringAfterLast(':'))
    }
}
