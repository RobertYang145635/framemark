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
 * 位置名按**边框内部的行列**定（用户 2026-11-09 口径：「右侧有两个文本框，上面那个
 * 叫右上，下面那个叫右下，而不是根据图片来定位」）：
 *  - 列 = 文本框中心 x 相对边框画布宽度的三分（<1/3、1/3–2/3、>2/3）；
 *  - 行 = **同一列内**按 y 排：两个 → 上/下、三个及以上 → 上/中/下、只有一个 → 它自己
 *    的高度带；
 *  - 标签 = 列 + 行；角色（品牌/机型/…）是 chip 的第二行小字，不进主标签；
 *  - 顺序 = 列（左→右）→ 行（上→下）。
 *
 * 另外锁住：单框不出导航带、零框空态、只读行禁用、选择状态重建、声明占位行让位。
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
    private fun leftColumn(key: String, y: Double) = slot("l-$key", key, x = 10.0, y = y)

    private fun middleColumn(key: String, y: Double) = slot("m-$key", key, x = 900.0, y = y)

    private fun rightColumn(key: String, y: Double) = slot("r-$key", key, x = 1500.0, y = y)

    // ------------------------------------------- EXIF 残缺：缺值行必须能填

    /**
     * 照片缺拍摄参数时，引擎会声明这一行但这一帧没有可画的内容
     * （[TextSlot.present] = false、`hidden` = false）。这种行以前被整条过滤掉，
     * 用户既看不到「这里缺东西」，也无法补填 —— 2026-11-21 用户反馈
     * 「exif 残缺的照片，不全的地方不能修改了，直接被关掉」。
     */
    @Test
    fun a_rowWithNoContentThisFrameIsStillListedAndEditable() {
        val missing = slot(
            id = "r-exif",
            key = "hand:card:exif",
            x = 1500.0,
            y = 1700.0,
            text = "",
            raw = "",
            present = false,
            hidden = false,
        )
        val entries = textSlotNavEntries(listOf(missing), canvasW, canvasH)
        assertEquals(1, entries.size, "缺值行要留在列表里，不能被过滤掉")
        assertFalse(entries.single().readonly, "缺值行要可编辑，用户才能把参数补上")
    }

    // ------------------------------------------------------------ 列 + 行命名

    @Test
    fun two_rows_in_one_column_are_top_and_bottom() {
        val upper = leftColumn("hand:card:brand", y = 1700.0)
        val lower = leftColumn("hand:card:model", y = 1800.0)
        val entries = textSlotNavEntries(listOf(lower, upper), canvasW, canvasH)
        assertEquals(listOf("左上", "左下"), entries.map { it.positionLabel }, "同列两行 = 上/下")
        assertEquals(listOf(0, 2), entries.map { it.axis.row })
    }

    @Test
    fun three_rows_in_one_column_are_top_middle_bottom() {
        val entries = textSlotNavEntries(
            listOf(
                leftColumn("hand:card:third", y = 1600.0),
                leftColumn("hand:card:first", y = 100.0),
                leftColumn("hand:card:second", y = 800.0),
            ),
            canvasW,
            canvasH,
        )
        assertEquals(listOf("左上", "左中", "左下"), entries.map { it.positionLabel })
        assertEquals(listOf(0, 1, 2), entries.map { it.axis.row })
    }

    @Test
    fun a_single_box_takes_its_own_height_band() {
        val top = textSlotNavEntries(listOf(leftColumn("hand:card:a", y = 100.0)), canvasW, canvasH).single()
        val middle = textSlotNavEntries(listOf(leftColumn("hand:card:b", y = 900.0)), canvasW, canvasH).single()
        val bottom = textSlotNavEntries(listOf(leftColumn("hand:card:c", y = 2700.0)), canvasW, canvasH).single()
        assertEquals("左上", top.positionLabel)
        assertEquals("左中", middle.positionLabel)
        assertEquals("左下", bottom.positionLabel)
        // 同列单行又落在中部：不写「中中」，用现有词表的「居中」。
        val center = textSlotNavEntries(listOf(middleColumn("hand:card:d", y = 900.0)), canvasW, canvasH).single()
        assertEquals("居中", center.positionLabel)
    }

    @Test
    fun the_column_boundary_is_one_third_and_two_thirds() {
        // 3000 宽：三分线正好是 1000 / 2000，用整数几何测边界（相等时归「中/右」）。
        val width = 3000
        fun columnAt(x: Double): Int? =
            textSlotColumn(slot("k$x", "hand:card:k", x = x, y = 100.0, w = 200.0, h = 200.0), width)
        assertEquals(0, columnAt(800.0), "中心 900 < 1/3：左")
        assertEquals(1, columnAt(900.0), "中心正好 1000 = 1/3：中（边界归右一档）")
        assertEquals(1, columnAt(1800.0), "中心 1900 < 2/3：中")
        assertEquals(2, columnAt(1900.0), "中心正好 2000 = 2/3：右")
    }

    @Test
    fun the_navigation_order_is_column_then_row() {
        // 左列上下两行 + 中列一行（y 夹在两者中间）：列优先，所以中列排最后，
        // 不是按整张画布的「行」来排（那样中列会插到两行之间）。
        val entries = textSlotNavEntries(
            listOf(
                leftColumn("hand:card:left-bottom", y = 1500.0),
                middleColumn("hand:card:center", y = 800.0),
                leftColumn("hand:card:left-top", y = 100.0),
            ),
            canvasW,
            canvasH,
        )
        assertEquals(
            listOf("hand:card:left-top", "hand:card:left-bottom", "hand:card:center"),
            entries.map { it.navKey },
        )
        assertEquals(listOf("左上", "左下", "居中"), entries.map { it.positionLabel })
        assertEquals(listOf(1, 2, 3), entries.map { it.order })
    }

    @Test
    fun classic_footer_rows_are_named_by_border_column_and_row() {
        // wm-classic 的真实报告几何（画布 841 x 724）：左列品牌/机型，右列拍摄参数/日期。
        val brand = slot("wm-brand", "hand:wm-classic:brand", x = 33.0, y = 655.0, w = 235.0, h = 18.0)
        val model = slot("wm-model", "hand:wm-classic:model", x = 33.0, y = 682.0, w = 235.0, h = 17.0)
        val exif = slot("wm-exif", "hand:wm-classic:exif", x = 569.0, y = 657.0, w = 237.0, h = 15.0)
        val date = slot("wm-date", "hand:wm-classic:date", x = 569.0, y = 686.0, w = 237.0, h = 12.0)
        val entries = textSlotNavEntries(listOf(date, exif, model, brand), 841, 724)
        assertEquals(listOf("左上", "左下", "右上", "右下"), entries.map { it.positionLabel })
        assertEquals(listOf("品牌", "机型", "拍摄参数", "日期"), entries.map { it.role })
        assertEquals(
            listOf("左上 品牌", "左下 机型", "右上 拍摄参数", "右下 日期"),
            entries.map { it.fullLabel },
            "主标签只有位置名，角色是第二行小字",
        )
        // 眼睛开关走演示稿的 `showMake/showModel/showExif/showDate`，不是另造字段。
        assertEquals(
            listOf("showMake", "showModel", "showExif", "showDate"),
            entries.map { it.showParam },
        )
        // 分组数据（按列）与平铺顺序一致，留给舞台高亮按列分组用。
        assertEquals(listOf(2, 2), textSlotNavRows(entries).map { it.size })
        assertEquals(entries.map { it.navKey }, textSlotNavRows(entries).flatten().map { it.navKey })
    }

    // ------------------------------------------------------------ 版式与交互

    @Test
    fun one_text_box_needs_no_navigation_bar() {
        assertFalse(showsTextSlotNavigation(emptyList()), "零个不画导航带")
        assertFalse(
            showsTextSlotNavigation(
                textSlotNavEntries(listOf(leftColumn("hand:card:a", y = 1800.0)), canvasW, canvasH),
            ),
            "只有一个文本框时直接给那张编辑卡",
        )
        assertTrue(
            showsTextSlotNavigation(
                textSlotNavEntries(
                    listOf(leftColumn("hand:card:a", y = 100.0), leftColumn("hand:card:b", y = 1800.0)),
                    canvasW,
                    canvasH,
                ),
            ),
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
                entries = textSlotNavEntries(listOf(leftColumn("hand:card:a", y = 1800.0)), canvasW, canvasH),
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
        val entries = textSlotNavEntries(
            listOf(readOnly, leftColumn("hand:card:title", y = 100.0)),
            canvasW,
            canvasH,
        )
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
    fun a_text_box_without_a_trustworthy_rect_keeps_its_role_instead_of_a_placeholder() {
        // 位置由边框决定；这一帧没画出矩形时不写「待定位」（用户 2026-11-21 明确反感），
        // 位置名留空，界面只显示角色名；探针量出矩形后会补上「右上 / 右下」。
        val unlocated = slot("hand:card:ghost", "hand:card:ghost", x = 0.0, y = 0.0, located = false)
        val entries = textSlotNavEntries(
            listOf(unlocated, leftColumn("hand:card:title", y = 100.0)),
            canvasW,
            canvasH,
        )
        assertEquals(listOf("左上", ""), entries.map { it.positionLabel })
        assertNull(entries.last().axis.column)
    }

    @Test
    fun saved_selection_is_rebuilt_and_falls_back_to_the_first_box() {
        val entries = textSlotNavEntries(
            listOf(
                leftColumn("hand:card:title", y = 100.0),
                rightColumn("hand:card:exif", y = 1500.0),
                leftColumn("hand:card:model", y = 1800.0),
            ),
            canvasW,
            canvasH,
        )
        assertEquals("hand:card:exif", resolveTextSlotSelection(entries, "hand:card:exif")?.navKey)
        assertEquals("hand:card:title", resolveTextSlotSelection(entries, "hand:old:gone")?.navKey)
        assertEquals("hand:card:title", resolveTextSlotSelection(entries, null)?.navKey)
        assertNull(resolveTextSlotSelection(emptyList(), "hand:card:title"))
    }

    @Test
    fun a_hidden_row_keeps_its_navigation_entry() {
        // 显式隐藏的行仍要在导航里：否则关掉的那一行再也没有入口打开。
        val hidden = leftColumn("hand:card:date", y = 1800.0)
            .copy(hidden = true, present = false, text = "", raw = "")
        val entries = textSlotNavEntries(listOf(hidden, leftColumn("hand:card:title", y = 100.0)), canvasW, canvasH)
        assertTrue(entries.any { it.navKey == "hand:card:date" })
        assertFalse(entries.first { it.navKey == "hand:card:date" }.readonly)
    }

    @Test
    fun duplicate_identities_render_one_navigation_entry() {
        val first = leftColumn("hand:card:make", y = 1800.0)
        val second = leftColumn("hand:card:make", y = 1900.0)
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
        assertEquals("机型", entries.single().role)
        assertEquals("hand:wm-classic:model", entries.single().navKey)
    }

    @Test
    fun a_reported_canvas_keeps_the_text_page_with_zero_boxes() {
        // 一个文本框都没有，但报告到了：这一页要在（显示空态），不能整页消失 ——
        // 桌面与手机同一个判据。
        assertFalse(hasTextPage(slotCount = 0, canvasWidthPx = 0), "还没渲染完：先不给这一页")
        assertTrue(hasTextPage(slotCount = 0, canvasWidthPx = 2667), "渲染完了但没有文字：空态页")
        assertTrue(hasTextPage(slotCount = 3, canvasWidthPx = 0))
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
