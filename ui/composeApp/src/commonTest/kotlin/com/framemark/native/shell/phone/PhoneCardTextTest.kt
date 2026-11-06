package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.shell.phoneCompactSheetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-PV2: the border card's two text lines and the chip wording are the showcase's
 * (`.card .ds` = `cardDesc(c)`, `ANCHOR_LABEL` = 在用 / 最近 / 热门). Pure functions,
 * so the copy can be checked without a composition.
 */
class PhoneCardTextTest {

    private fun card(
        name: String = "经典双行",
        category: String = "text",
        categoryName: String = "文字",
        brand: String? = null,
        family: String? = null,
        templateCategory: String? = null,
    ) = LibraryCard(
        id = "test-$name",
        name = name,
        category = category,
        categoryName = categoryName,
        brand = brand,
        vendor = null,
        family = family,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        templateCategory = templateCategory,
        previewSourcePath = "",
        previewFile = "",
    )

    @Test
    fun a_non_vendor_card_reads_its_category_and_sub_category() {
        assertEquals(
            "文字 · 厂商水印",
            phoneCardDescription(card(templateCategory = "厂商水印")),
        )
        assertEquals("文字", phoneCardDescription(card()))
    }

    @Test
    fun a_vendor_card_reads_brand_and_series() {
        assertEquals(
            "Panasonic · DC-S1",
            phoneCardDescription(
                card(category = "vendor", brand = "Panasonic", family = "DC-S1"),
            ),
        )
        assertEquals("Panasonic", phoneCardDescription(card(category = "vendor", brand = "Panasonic")))
    }

    @Test
    fun the_common_chips_use_the_showcase_wording_and_keep_every_other_group_name() {
        assertEquals("在用", phoneAnchorLabel("本项目在用"))
        assertEquals("最近", phoneAnchorLabel("最近使用"))
        // 热门 is already the showcase word and every other group keeps its own name.
        assertEquals("热门", phoneAnchorLabel("热门"))
        assertEquals("Panasonic", phoneAnchorLabel("Panasonic"))
    }

    @Test
    fun the_type_sizes_of_the_card_are_the_showcase_ones() {
        // `.card .nm` is 12 px and `.card .ds` 11 px; both lines are budgeted at
        // 16 / 14 dp of leading so the 收起 sheet cannot clip them.
        val metrics = phoneCompactSheetMetrics(sheetHeightDp = 400f, fontScale = 1f)
        assertEquals(16f, metrics.cardNameLineSp)
        assertEquals(14f, metrics.cardDescLine)
    }
}
