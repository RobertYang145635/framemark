package com.framemark.native.inspector

import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.groupTextSlots
import com.framemark.native.workflow.isCurrentTextSlot
import com.framemark.native.workflow.textSlotControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-TX1：文字页从「纵向滚动的多行列表」改成「按文本框导航、点一个改一个」。
 *
 * 这些用例锁的是**规则**（不依赖 Compose 运行时）：
 *  - 分组顺序 = 版面阅读顺序（`TextSlotPosition.row/column`，组内 `y/x`）；
 *  - 同一位置多行给语义副标签（品牌 / 机型 / …），没有语义就给序号；
 *  - 只有一个文本框时不出导航条；一个都没有时是空态；
 *  - 只读行（渲染器没给可写身份）要能被选中、标成只读，且不生成输入控件；
 *  - 保存的选择在新一帧里能重建：key 还在就用它，不在了回第一个。
 */
class TextSlotNavigatorTest {

    private fun slot(
        id: String,
        key: String?,
        x: Double,
        y: Double,
        w: Double = 200.0,
        h: Double = 40.0,
        text: String = "原文",
        raw: String = "原文",
        stable: Boolean = key != null,
        present: Boolean = true,
        hidden: Boolean = false,
        located: Boolean = true,
    ): TextSlot = TextSlot(
        id = id,
        text = text,
        x = x,
        y = y,
        w = w,
        h = h,
        fontSize = 20.0,
        baseline = 15.0,
        lines = 1,
        present = present,
        key = key,
        raw = raw,
        hidden = hidden,
        stable = stable,
        located = located,
    )

    private val canvasW = 2000
    private val canvasH = 2000

    // 2000 x 2000 的画布：三分线在 666.7 / 1333.3。
    private fun topLeft(key: String = "hand:card:title", y: Double = 10.0) =
        slot("t-$key", key, x = 10.0, y = y)

    private fun bottomLeft(key: String? = "hand:card:make", y: Double = 1800.0) =
        slot("bl-${key ?: "ro"}", key, x = 10.0, y = y)

    private fun middleRight(key: String? = "hand:card:exif") =
        slot("mr-${key ?: "ro"}", key, x = 1500.0, y = 900.0)

    @Test
    fun navigation_follows_layout_reading_order() {
        // 故意按「右、下、上」的报告顺序给：导航必须按版面重排。
        val entries = textSlotNavEntries(
            slots = listOf(middleRight(), bottomLeft(), topLeft()),
            outputWidth = canvasW,
            outputHeight = canvasH,
        )
        assertEquals(listOf("左上", "右侧居中", "左下"), entries.map { it.positionLabel })
        assertEquals(listOf(1, 2, 3), entries.map { it.order })
    }

    @Test
    fun rows_in_one_position_are_ordered_by_y_then_get_an_ordinal() {
        // 两行都没有演示稿语义（不是品牌/机型/拍摄参数/日期），只能给序号。
        val later = bottomLeft(key = "hand:card:customLine", y = 1900.0)
        val earlier = bottomLeft(key = "hand:card:location", y = 1800.0)
        val entries = textSlotNavEntries(
            slots = listOf(later, earlier),
            outputWidth = canvasW,
            outputHeight = canvasH,
        )
        assertEquals(listOf("第1行", "第2行"), entries.map { it.subLabel })
        assertEquals(listOf("左下 第1行", "左下 第2行"), entries.map { it.label })
        assertEquals("hand:card:location", entries.first().slot.key, "组内按 y 排")
    }

    @Test
    fun classic_rows_get_their_semantic_sublabel_instead_of_an_ordinal() {
        val make = slot("wm-make", "hand:wm-classic:make", x = 72.0, y = 1424.0, w = 267.0, h = 30.0)
        val model = slot("wm-model", "hand:wm-classic:model", x = 72.0, y = 1480.0, w = 129.0, h = 30.0)
        val exif = slot("wm-exif", "hand:wm-classic:exif", x = 1500.0, y = 1420.0, w = 400.0, h = 30.0)
        val date = slot("wm-date", "hand:wm-classic:date", x = 1500.0, y = 1480.0, w = 300.0, h = 30.0)
        val entries = textSlotNavEntries(
            slots = listOf(date, exif, model, make),
            outputWidth = canvasW,
            outputHeight = canvasH,
        )
        assertEquals(
            listOf("左下 品牌", "左下 机型", "右下 拍摄参数", "右下 日期"),
            entries.map { it.label },
        )
        // 眼睛开关走演示稿的 `showMake/showModel/showExif/showDate`，不是另造字段。
        assertEquals(
            listOf("showMake", "showModel", "showExif", "showDate"),
            entries.map { it.showParam },
        )
    }

    @Test
    fun one_text_box_needs_no_navigation_bar() {
        assertFalse(showsTextSlotNavigation(emptyList()), "零个不画导航条")
        assertFalse(
            showsTextSlotNavigation(textSlotNavEntries(listOf(bottomLeft()), canvasW, canvasH)),
            "只有一个文本框时直接给那张编辑卡",
        )
        assertTrue(
            showsTextSlotNavigation(textSlotNavEntries(listOf(bottomLeft(), topLeft()), canvasW, canvasH)),
            "两个以上才需要导航",
        )
    }

    @Test
    fun zero_text_boxes_is_the_empty_page() {
        val declaredText = listOf(declaredTextControl("card-1::text.title"))
        assertEquals(
            TextPageMode.Empty,
            textPageMode(hasReport = true, entries = emptyList(), staticTextControls = declaredText),
            "报告到了但一个文本框都没有：空态，不退回声明出来的行",
        )
        assertEquals(
            TextPageMode.Empty,
            textPageMode(hasReport = false, entries = emptyList(), staticTextControls = emptyList()),
        )
        // 没有报告（非实时渲染的卡）：声明出来的文字行是唯一的改法，不能变成空态。
        assertEquals(
            TextPageMode.StaticRows,
            textPageMode(hasReport = false, entries = emptyList(), staticTextControls = declaredText),
        )
        assertEquals(
            TextPageMode.Selector,
            textPageMode(
                hasReport = true,
                entries = textSlotNavEntries(listOf(bottomLeft()), canvasW, canvasH),
                staticTextControls = declaredText,
            ),
        )
    }

    @Test
    fun readonly_rows_are_listed_but_never_offered_an_input() {
        // 渲染器画了这一行，却没给可写身份（`stable = false`, key = null）。
        val readOnly = slot("legacy-row", key = null, x = 10.0, y = 1800.0, stable = false)
        assertFalse(readOnly.isCurrentTextSlot(), "不可写身份的行不是可编辑行")
        assertTrue(
            groupTextSlots(listOf(readOnly), canvasW, canvasH).isEmpty(),
            "默认分组保持「只收可编辑行」，别的调用点不受影响",
        )
        val entries = textSlotNavEntries(listOf(readOnly, topLeft()), canvasW, canvasH)
        assertEquals(listOf("左上", "左下"), entries.map { it.positionLabel })
        assertFalse(entries.first().readonly)
        assertTrue(entries.last().readonly, "导航项要标成只读")
        assertEquals("legacy-row", entries.last().navKey, "只读行用报告 id 做高亮身份")
        assertTrue(
            textSlotControls("card-1", readOnly).isEmpty(),
            "只读行不生成 slotText 控件 —— 界面不会给它一个能打字却写不进去的输入框",
        )
    }

    @Test
    fun saved_selection_is_rebuilt_and_falls_back_to_the_first_box() {
        val entries = textSlotNavEntries(
            slots = listOf(topLeft(), middleRight(), bottomLeft()),
            outputWidth = canvasW,
            outputHeight = canvasH,
        )
        assertEquals("hand:card:exif", resolveTextSlotSelection(entries, "hand:card:exif")?.navKey)
        assertEquals("hand:card:title", resolveTextSlotSelection(entries, "hand:old:gone")?.navKey)
        assertEquals("hand:card:title", resolveTextSlotSelection(entries, null)?.navKey)
        assertNull(resolveTextSlotSelection(emptyList(), "hand:card:title"))
    }

    @Test
    fun a_hidden_row_keeps_its_navigation_entry() {
        // 显式隐藏的行仍要在导航里：否则关掉的那一行再也没有入口打开。
        val hidden = bottomLeft(key = "hand:card:date").copy(hidden = true, present = false, text = "", raw = "")
        val entries = textSlotNavEntries(listOf(hidden, topLeft()), canvasW, canvasH)
        assertTrue(entries.any { it.navKey == "hand:card:date" })
        assertFalse(entries.first { it.navKey == "hand:card:date" }.readonly)
    }

    @Test
    fun duplicate_identities_render_one_navigation_entry() {
        val first = bottomLeft(key = "hand:card:make", y = 1800.0)
        val second = bottomLeft(key = "hand:card:make", y = 1900.0)
        val entries = textSlotNavEntries(listOf(first, second), canvasW, canvasH)
        assertEquals(1, entries.size, "同一个 key 出现两次只留第一行")
    }

    @Test
    fun text_scale_hint_follows_the_box_count() {
        assertEquals("四行同比缩放", textScaleHint(4))
        assertEquals("所有文本框同比缩放", textScaleHint(3))
    }

    @Test
    fun the_drawn_row_wins_over_a_declared_placeholder_with_the_same_name() {
        // 目录声明的 id（`wm-classic#model#0`）和渲染器报告的 key
        // （`hand:wm-classic:model`）拼法不同，桥接会给声明留一条 present=false 的
        // 占位行。同一个逻辑名两行都在时必须是画出来的那行拿到「机型」。
        val absent = slot(
            id = "wm-classic#model#0",
            key = null,
            x = 0.0,
            y = 0.0,
            w = 0.0,
            h = 0.0,
            text = "",
            raw = "",
            stable = false,
            present = false,
            located = false,
        )
        val drawn = slot("hand:wm-classic:model", "hand:wm-classic:model", x = 72.0, y = 1480.0, w = 129.0, h = 30.0)
        val entries = textSlotNavEntries(listOf(absent, drawn), canvasW, canvasH)
        assertEquals(1, entries.size, "没画的占位行不进导航")
        assertEquals("机型", entries.single().subLabel)
        assertEquals("hand:wm-classic:model", entries.single().navKey)
    }

    @Test
    fun navigation_rows_keep_one_position_together() {
        val make = slot("wm-make", "hand:wm-classic:make", x = 72.0, y = 1424.0, w = 267.0, h = 30.0)
        val model = slot("wm-model", "hand:wm-classic:model", x = 72.0, y = 1480.0, w = 129.0, h = 30.0)
        val exif = slot("wm-exif", "hand:wm-classic:exif", x = 1500.0, y = 1420.0, w = 400.0, h = 30.0)
        val date = slot("wm-date", "hand:wm-classic:date", x = 1500.0, y = 1480.0, w = 300.0, h = 30.0)
        val entries = textSlotNavEntries(listOf(make, model, exif, date), canvasW, canvasH)
        val rows = textSlotNavRows(entries)
        assertEquals(listOf(2, 2), rows.map { it.size }, "左下 2 + 右下 2 读成两行 = 两个位置导航")
        assertEquals(
            listOf(listOf("品牌", "机型"), listOf("拍摄参数", "日期")),
            rows.map { row -> row.map { it.subLabel } },
        )
        // 每个位置只有一个文本框时，每行一个（桌面合成一排 chip）。
        val singles = textSlotNavEntries(listOf(topLeft(), middleRight(), bottomLeft()), canvasW, canvasH)
        assertEquals(listOf(1, 1, 1), textSlotNavRows(singles).map { it.size })
    }

    @Test
    fun a_reported_canvas_keeps_the_text_page_with_zero_boxes() {
        // 一个文本框都没有，但报告到了：这一页要在（显示空态），不能整页消失 ——
        // 桌面与手机同一个判据。
        assertFalse(hasTextPage(slotCount = 0, canvasWidthPx = 0), "还没渲染完：先不给这一页")
        assertTrue(hasTextPage(slotCount = 0, canvasWidthPx = 2667), "渲染完了但没有文字：空态页")
        assertTrue(hasTextPage(slotCount = 3, canvasWidthPx = 0))
    }

    @Test
    fun the_navigation_band_is_one_ordered_row_of_boxes() {
        val make = slot("wm-make", "hand:wm-classic:make", x = 72.0, y = 1424.0, w = 267.0, h = 30.0)
        val model = slot("wm-model", "hand:wm-classic:model", x = 72.0, y = 1480.0, w = 129.0, h = 30.0)
        val exif = slot("wm-exif", "hand:wm-classic:exif", x = 1500.0, y = 1420.0, w = 400.0, h = 30.0)
        val date = slot("wm-date", "hand:wm-classic:date", x = 1500.0, y = 1480.0, w = 300.0, h = 30.0)
        val entries = textSlotNavEntries(listOf(make, model, exif, date), canvasW, canvasH)
        // 一个文本框一个导航项：4 个框就是 4 项，一条带子摊平（位置不再当行标题）。
        assertEquals(4, entries.size)
        assertEquals(entries.map { it.navKey }, textSlotNavRows(entries).flatten().map { it.navKey })
        assertEquals(
            listOf("左下 品牌", "左下 机型", "右下 拍摄参数", "右下 日期"),
            entries.map { it.label },
            "位置 + 角色，顺序 = 版面阅读顺序",
        )
    }

    private fun declaredTextControl(key: String) = InspectorControl(
        key = key,
        controlId = key,
        param = "text.title",
        label = "标题",
        kind = "text",
        section = InspectorSection.Text,
        type = ControlType.Text,
        initial = ControlValue.Str(""),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )
}
