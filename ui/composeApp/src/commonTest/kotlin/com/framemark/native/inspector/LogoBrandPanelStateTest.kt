package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure state contract of the V06 品牌 Logo page: exact cut identity, browse brand
 * lookup, auto-match value and the no-logo / fixed-logo modes. No Compose, no
 * resources and no catalog parsing is re-implemented here.
 */
class LogoBrandPanelStateTest {

    private fun cut(assetKey: String, file: String = "$assetKey.webp") =
        LogoCut(assetKey = assetKey, file = file, preview = "${assetKey}__preview.webp")

    private fun version(
        id: String,
        cuts: List<LogoCut>,
        era: String = "current",
        years: String = "2010–2020",
        surface: String = "light",
    ) = LogoVersion(
        id = id,
        name = id,
        era = era,
        years = years,
        surface = surface,
        cuts = cuts,
    )

    private fun brand(
        key: String,
        versions: List<LogoVersion>,
        label: String = key.uppercase(),
        cn: String = "$key 中文",
        merged: List<String> = emptyList(),
    ) = LogoBrand(
        key = key,
        label = label,
        cn = cn,
        group = "camera",
        canonicalVersion = versions.firstOrNull()?.id,
        versions = versions,
        merged = merged,
    )

    private val canon = brand(
        key = "canon",
        versions = listOf(
            version("canon-1990", listOf(cut("canon_black"), cut("canon_white")), era = "classic"),
            version("canon-2020", listOf(cut("canon_2020")), era = "current"),
        ),
    )

    // Folding source: LUMIX is displayed inside Panasonic, so no separate brand
    // key is expected from the catalog parser.
    private val panasonic = brand(
        key = "panasionic",
        label = "Panasonic",
        cn = "松下",
        versions = listOf(version("lumix-2019", listOf(cut("lumix_2019")))),
        merged = listOf("LUMIX"),
    )

    private val brands = listOf(canon, panasonic)

    private fun control(type: ControlType, id: String = type.name): InspectorControl = InspectorControl(
        key = "card::$id",
        controlId = id,
        label = "label-$id",
        kind = if (type == ControlType.Brand) "brandPicker" else "toggle",
        section = InspectorSection.Logo,
        type = type,
        initial = if (type == ControlType.Brand) ControlValue.Str("") else ControlValue.Flag(true),
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

    // -- exact asset lookup ----------------------------------------------------

    @Test
    fun findsTheBrandThatOwnsAnExactCut() {
        assertEquals("canon", logoBrandForAssetKey(brands, "canon_white")?.key)
        assertEquals("panasionic", logoBrandForAssetKey(brands, "lumix_2019")?.key)
    }

    @Test
    fun unknownOrBlankAssetKeyHasNoBrand() {
        assertNull(logoBrandForAssetKey(brands, ""))
        assertNull(logoBrandForAssetKey(brands, "   "))
        assertNull(logoBrandForAssetKey(brands, "canon"))  // a brand key is not a cut
        assertNull(logoBrandForAssetKey(brands, "sony_1999"))
    }

    @Test
    fun findsTheExactVersionOfAnAppliedCut() {
        assertEquals("canon-1990", logoVersionForAssetKey(brands, "canon_white")?.id)
        assertEquals("canon-2020", logoVersionForAssetKey(brands, "canon_2020")?.id)
        assertNull(logoVersionForAssetKey(brands, "canon_black_2"))
    }

    @Test
    fun neverSubstitutesAnotherCutOrBrand() {
        // A near-miss key must resolve to nothing rather than the black variant.
        assertNull(logoBrandForAssetKey(brands, "canon_white2"))
        assertNull(logoVersionForAssetKey(brands, "lumix_2018"))
    }

    // -- per-cut rows ----------------------------------------------------------

    @Test
    fun rendersOneRowPerExactCutInCatalogOrder() {
        val rows = logoCutRows(canon)
        assertEquals(listOf("canon_black", "canon_white", "canon_2020"), rows.map { it.cut.assetKey })
        assertEquals(listOf("canon-1990", "canon-1990", "canon-2020"), rows.map { it.version.id })
        assertEquals(listOf(1, 2, 1), rows.map { it.variantIndex })
        assertEquals(listOf(2, 2, 1), rows.map { it.variantCount })
    }

    @Test
    fun cutCountAndSubtitleMatchTheFlattenedRows() {
        assertEquals(3, logoCutCount(canon))
        assertEquals(1, logoCutCount(panasonic))
        assertEquals(2, canon.versions.size)
        assertTrue(logoBrandCountLabel(canon).contains("3"))
        assertTrue(logoBrandCountLabel(canon).contains("2"))
    }

    // -- auto match value ------------------------------------------------------

    @Test
    fun blankAndLegacyAutoValueMeanAutoMatch() {
        assertTrue(isAutoMatchBrandValue(""))
        assertTrue(isAutoMatchBrandValue("   "))
        assertTrue(isAutoMatchBrandValue("auto"))
        assertFalse(isAutoMatchBrandValue("canon_black"))
    }

    // -- capability modes ------------------------------------------------------

    @Test
    fun brandControlMeansBranded() {
        assertEquals(
            LogoBrandMode.Branded,
            logoBrandMode(brandControl = control(ControlType.Brand), logoControls = listOf(control(ControlType.Slider))),
        )
    }

    @Test
    fun logoRowsWithoutBrandControlMeanFixedLogo() {
        assertEquals(
            LogoBrandMode.Fixed,
            logoBrandMode(brandControl = null, logoControls = listOf(control(ControlType.Switch), control(ControlType.Slider))),
        )
    }

    @Test
    fun noLogoRowsAtAllMeansAbsent() {
        assertEquals(LogoBrandMode.Absent, logoBrandMode(brandControl = null, logoControls = emptyList()))
    }

    @Test
    fun brandedModeWinsEvenWhenOtherLogoRowsExist() {
        assertEquals(
            LogoBrandMode.Branded,
            logoBrandMode(brandControl = control(ControlType.Brand), logoControls = emptyList()),
        )
    }

    @Test
    fun browseOnlyChangesTheFilterNotTheAppliedValue() {
        // The brand page hands back a brand; the panel only stores it as the
        // browsing key. The applied cut must stay exactly what the session holds.
        val browsed = brands.first { it.key == "panasionic" }
        val applied = "canon_black"
        assertNotNull(logoBrandForAssetKey(brands, applied))
        assertEquals("panasionic", browsed.key)
        assertEquals("canon-1990", logoVersionForAssetKey(brands, applied)?.id)
        assertTrue(logoCutRows(browsed).none { it.cut.assetKey == applied })
    }
}
