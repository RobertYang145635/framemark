package com.framemark.native.library

import com.framemark.native.library.LibraryDataSource
import com.framemark.native.product.RealData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, item 2: the vendor card's second line repeated the brand.
 *
 * `taxonomy.json` stores the family with the brand already in it (`OPPO 相册`,
 * `OPPO 相机`, `小米相机`, `vivo 相机`) and both the grid (`phoneCardDescription`) and
 * the parameter page's 来源 row joined it with the brand again — `OPPO · OPPO 相册` was
 * on screen 287 times, `小米 · 小米相机` 109, `vivo · vivo 相机` 43. [LibraryLabels] keeps
 * the distinguishing part only.
 */
class VendorLabelTest {

    private fun cards() = LibraryDataSource.parse(
        RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/taxonomy.json"),
        RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/previews.json"),
    ).cards

    @Test
    fun theShippedVendorPairsLoseOnlyTheRepeatedBrand() {
        assertEquals("OPPO · 相册", LibraryLabels.vendorSource("OPPO", "OPPO 相册"))
        assertEquals("OPPO · 相机", LibraryLabels.vendorSource("OPPO", "OPPO 相机"))
        assertEquals("vivo · 相机", LibraryLabels.vendorSource("vivo", "vivo 相机"))
        assertEquals("小米 · 相机", LibraryLabels.vendorSource("小米", "小米相机"))
    }

    @Test
    fun theFirmwareSpellingKeepsTheLeaf() {
        assertEquals("小米 · 徕卡水印", LibraryLabels.vendorSource("小米", "小米 / 徕卡水印"))
        // A family that does not mention the brand is kept whole.
        assertEquals("SONY · 精选", LibraryLabels.vendorSource("SONY", "SONY 精选"))
        assertEquals("OPPO · 哈苏定制", LibraryLabels.vendorSource("OPPO", "哈苏定制"))
    }

    @Test
    fun aMissingBrandOrFamilyDegradesToTheOther() {
        assertEquals("OPPO 相机", LibraryLabels.vendorSource(null, "OPPO 相机"))
        assertEquals("OPPO", LibraryLabels.vendorSource("OPPO", ""))
        assertEquals("OPPO", LibraryLabels.vendorSource("OPPO", "OPPO"))
        assertEquals("", LibraryLabels.vendorSource(null, null))
    }

    @Test
    fun noShippedVendorCardRepeatsItsBrandOnTheCardLine() {
        val vendorCards = cards().filter { it.category == LibraryIndex.CATEGORY_VENDOR }
        assertTrue(vendorCards.size > 100, "expected the shipped vendor cards, saw ${vendorCards.size}")
        vendorCards.forEach { card ->
            val brand = card.brand.orEmpty()
            val line = LibraryLabels.vendorSource(card.brand, card.family)
            val repeats = line.contains("$brand · $brand", ignoreCase = true) ||
                line.startsWith("$brand · $brand", ignoreCase = true)
            assertFalse(repeats, "${card.id}: '$line' repeats the brand")
            if (brand.isNotBlank() && card.family.orEmpty().isNotBlank()) {
                assertTrue(line.startsWith(brand), "${card.id}: '$line' should start with the brand")
            }
        }
    }
}
