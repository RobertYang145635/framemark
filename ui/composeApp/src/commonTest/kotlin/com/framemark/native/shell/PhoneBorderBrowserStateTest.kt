package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Border-browser levels. The fixture mirrors the real taxonomy shapes the V06
 * task calls out: a firmware-verified family, a brand-less vendor card
 * (`unlabeled`), the four brand-less `no-family-by-design` cards (`unverified`)
 * and hand-written cards without vendor (`none`).
 */
class PhoneBorderBrowserStateTest {

    private val verified = LibraryGroups.PROV_VERIFIED_IN_FILE
    private val noFamily = LibraryGroups.PROV_NO_FAMILY

    private fun card(
        id: String,
        category: String,
        brand: String? = null,
        vendor: String? = null,
        familyLabel: String? = null,
        provenance: String? = null,
        familyLabelSource: String? = null,
    ): LibraryCard = LibraryCard(
        id = id,
        name = id,
        category = category,
        categoryName = if (category == "vendor") "厂商边框" else "卡片边框",
        brand = brand,
        vendor = vendor,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        styleFamily = familyLabel,
        styleFamilyLabel = familyLabel,
        familyNameProvenance = provenance,
        styleFamilyLabelSource = familyLabelSource,
        previewSourcePath = "",
        previewFile = "",
    )

    private val oppoA1 = card("oppo-a1", "vendor", "OPPO", "oppo", "OPPO / 徕卡水印", verified)
    private val oppoA2 = card("oppo-a2", "vendor", "OPPO", "oppo", "OPPO / 徕卡水印", verified)
    private val oppoB1 = card("oppo-b1", "vendor", "OPPO", "oppo", "OPPO / 经典水印", verified)
    private val mi1 = card("mi-1", "vendor", "小米", "xiaomi", "小米 / 徕卡水印", verified)
    private val cam1 = card("oppo-cam-1", "vendor", vendor = "oppo", provenance = noFamily)
    private val cam2 = card("oppo-cam-2", "vendor", vendor = "oppo", provenance = noFamily)
    private val cam3 = card("oppo-cam-3", "vendor", vendor = "oppo", provenance = noFamily)
    private val cam4 = card("oppo-cam-4", "vendor", vendor = "oppo", provenance = noFamily)
    private val polaroid = card("oppo-polaroid", "card", vendor = "oppo")
    private val hand1 = card("hand-1", "card")
    private val hand2 = card("hand-2", "card")

    private val cards = listOf(
        oppoA1, oppoA2, oppoB1, mi1,
        cam1, cam2, cam3, cam4,
        polaroid, hand1, hand2,
    )

    // Declared counts are deliberately wrong: the browser must count real cards.
    private val categories = listOf(
        LibraryCategory(key = "vendor", name = "厂商边框", count = 999),
        LibraryCategory(key = "card", name = "卡片边框", count = 999),
    )

    private fun all() = buildPhoneBorderBrowser(cards, categories, PhoneBorderSelection(null, null, null))

    @Test
    fun categories_are_all_plus_the_declared_ones_with_real_counts() {
        val state = all()
        assertEquals(listOf("全部", "厂商边框", "卡片边框"), state.categories.map { it.label })
        assertEquals(listOf(null, "vendor", "card"), state.categories.map { it.key })
        assertEquals(11, state.categories[0].count)
        assertEquals(8, state.categories[1].count)
        assertEquals(3, state.categories[2].count)
    }

    @Test
    fun every_card_belongs_to_exactly_one_family_once() {
        val state = all()
        assertEquals(5, state.vendors.size)
        assertEquals(11, state.totalCards)
        val flattened = state.vendors.flatMap { vendor -> vendor.families.flatMap { it.cards } }
        assertEquals(11, flattened.size, "no card may be listed twice")
        assertEquals(cards.map { it.id }.toSet(), flattened.map { it.id }.toSet(), "no card may be dropped")
        assertEquals(11, flattened.map { it.id }.toSet().size)
    }

    @Test
    fun vendors_keep_the_library_order_and_counts() {
        val state = all()
        assertEquals(
            listOf("brand:OPPO", "brand:小米", LibraryGroups.UNVERIFIED, LibraryGroups.NONE, LibraryGroups.UNLABELED),
            state.vendors.map { it.key },
        )
        assertEquals(listOf(3, 1, 4, 2, 1), state.vendors.map { it.count })
        assertEquals("brand:OPPO", state.selection.vendorKey)
    }

    @Test
    fun firmware_families_use_the_data_family_label_without_the_vendor_prefix() {
        val state = all()
        val oppo = state.vendors.first { it.key == "brand:OPPO" }
        assertEquals(listOf("徕卡水印", "经典水印"), oppo.families.map { it.label })
        assertEquals(listOf(2, 1), oppo.families.map { it.cards.size })
    }

    @Test
    fun the_brand_less_vendor_card_lands_in_the_unlabeled_group() {
        val state = all()
        val unlabeled = state.vendors.first { it.key == LibraryGroups.UNLABELED }
        assertEquals("品牌未标注", unlabeled.label)
        assertEquals(1, unlabeled.count)
        assertEquals(listOf("oppo-polaroid"), unlabeled.families.flatMap { it.cards }.map { it.id })
        assertEquals("品牌未标注", unlabeled.families.first().label)
    }

    @Test
    fun the_four_brand_less_no_family_cards_stay_reachable() {
        val state = all()
        val unverified = state.vendors.first { it.key == LibraryGroups.UNVERIFIED }
        assertEquals(LibraryGroups.NO_FIRMWARE_LABEL, unverified.label)
        assertEquals(
            listOf("oppo-cam-1", "oppo-cam-2", "oppo-cam-3", "oppo-cam-4"),
            unverified.families.flatMap { it.cards }.map { it.id },
        )
        assertEquals(LibraryGroups.NO_FIRMWARE_LABEL, unverified.families.first().label)
    }

    @Test
    fun hand_written_cards_without_a_vendor_land_in_none() {
        val state = all()
        val none = state.vendors.first { it.key == LibraryGroups.NONE }
        assertEquals("无厂商 / 手写", none.label)
        assertEquals(listOf("hand-1", "hand-2"), none.families.flatMap { it.cards }.map { it.id })
    }

    @Test
    fun the_active_category_limits_the_vendor_level() {
        val state = buildPhoneBorderBrowser(
            cards,
            categories,
            PhoneBorderSelection(categoryKey = "card", vendorKey = null, familyKey = null),
        )
        assertEquals("card", state.selection.categoryKey)
        assertEquals(3, state.totalCards)
        assertEquals(listOf(LibraryGroups.NONE, LibraryGroups.UNLABELED), state.vendors.map { it.key })
        assertEquals(listOf("hand-1", "hand-2"), state.cards.map { it.id })
    }

    @Test
    fun the_default_selection_is_the_selected_card_own_branch() {
        val selection = phoneBorderDefaultSelection(cards, "oppo-a2")
        assertEquals(PhoneBorderSelection("vendor", "brand:OPPO", "family:徕卡水印"), selection)
        val state = buildPhoneBorderBrowser(cards, categories, selection)
        assertEquals(selection, state.selection)
        assertTrue(state.cards.any { it.id == "oppo-a2" })
        assertEquals("徕卡水印", state.activeFamily?.label)
    }

    @Test
    fun the_default_selection_follows_the_no_family_buckets() {
        val unverified = phoneBorderDefaultSelection(cards, "oppo-cam-3")
        assertEquals("vendor", unverified.categoryKey)
        assertEquals(LibraryGroups.UNVERIFIED, unverified.vendorKey)
        assertEquals("section:${LibraryGroups.UNVERIFIED}", unverified.familyKey)
        // LibraryViewBuilder keeps UNVERIFIED as one section-level bucket: all
        // four no-family cards belong to that row (the `LibraryGroups` contract),
        // so the active family must expose all of them, not just the one clicked.
        assertEquals(
            listOf("oppo-cam-1", "oppo-cam-2", "oppo-cam-3", "oppo-cam-4"),
            buildPhoneBorderBrowser(cards, categories, unverified).cards.map { it.id },
        )

        val unlabeled = phoneBorderDefaultSelection(cards, "oppo-polaroid")
        assertEquals("card", unlabeled.categoryKey)
        assertEquals(LibraryGroups.UNLABELED, unlabeled.vendorKey)
        assertEquals("section:${LibraryGroups.UNLABELED}", unlabeled.familyKey)
    }

    @Test
    fun the_default_selection_is_stable_and_unknown_cards_open_on_all() {
        assertEquals(
            phoneBorderDefaultSelection(cards, "mi-1"),
            phoneBorderDefaultSelection(cards, "mi-1"),
        )
        val unknown = phoneBorderDefaultSelection(cards, "not-a-card")
        assertEquals(PhoneBorderSelection(null, null, null), unknown)
        val state = buildPhoneBorderBrowser(cards, categories, unknown)
        assertEquals(null, state.selection.categoryKey)
        assertEquals("brand:OPPO", state.selection.vendorKey)
        assertEquals("family:徕卡水印", state.selection.familyKey)
    }

    @Test
    fun a_stale_selection_degrades_to_the_first_available_level() {
        val state = buildPhoneBorderBrowser(
            cards,
            categories,
            PhoneBorderSelection(
                categoryKey = "does-not-exist",
                vendorKey = "brand:vivo",
                familyKey = "family:does-not-exist",
            ),
        )
        assertEquals(null, state.selection.categoryKey)
        assertEquals("brand:OPPO", state.selection.vendorKey)
        assertEquals("family:徕卡水印", state.selection.familyKey)
        assertEquals(11, state.totalCards)
    }

    @Test
    fun an_empty_library_renders_no_levels_instead_of_crashing() {
        val state = buildPhoneBorderBrowser(emptyList(), categories, PhoneBorderSelection(null, null, null))
        assertEquals(1, state.categories.size)
        assertEquals(0, state.categories.first().count)
        assertTrue(state.vendors.isEmpty())
        assertTrue(state.cards.isEmpty())
        assertEquals(0, state.totalCards)
    }
}
