package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-P1-2 acceptance (§5.1): in the 收起 state the border sheet must show the
 * **128 dp card and its name** (plus the padding around them) and the stage must
 * still keep at least half of the App available height, on 390×844, 360×800 and
 * 412×915 with the insets the brief allows (status bar 24–48 dp, nav bar 16–48 dp).
 *
 * "App available height" is the主管's 11-06 definition (RUNBOOK §4.4): screen height
 * minus the status bar and the nav bar, which is exactly what the phone editor
 * measures as its own height. Pure arithmetic, no Compose.
 */
class PhoneEditorCompactSheetTest {

    private data class Screen(val width: Float, val height: Float) {
        val small: Boolean get() = width < 380f
    }

    private data class Insets(val status: Float, val nav: Float)

    private data class Case(
        val screen: Screen,
        val insets: Insets,
        /** Compact border sheet height the 50 % stage floor leaves. */
        val sheet: Float,
        /** Stage height that remains = App available height - chrome - sheet. */
        val stage: Float,
    )

    private val screens = listOf(
        Screen(390f, 844f),
        Screen(360f, 800f),
        Screen(412f, 915f),
    )

    /** Every combination of the inset ranges the brief allows. */
    private val allInsets = buildList {
        listOf(24f, 32f, 48f).forEach { status ->
            listOf(16f, 24f, 48f).forEach { nav -> add(Insets(status, nav)) }
        }
    }

    private fun available(screen: Screen, insets: Insets): Float =
        screen.height - insets.status - insets.nav

    private fun availableCase(screen: Screen, insets: Insets): PhoneEditorHeights =
        computePhoneEditorHeights(
            availableHeightDp = available(screen, insets),
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = false,
        )

    // -----------------------------------------------------------------------
    // The two rules
    // -----------------------------------------------------------------------

    @Test
    fun the_card_and_its_name_fit_the_compact_sheet_on_the_carried_screens() {
        // WP-PV2 stack (showcase `.grab` 22 / `.psh` 30 / `.ptabs` 38 / `.panchors` 42 /
        // `.pstrip 12 10` around the 128 dp tile / `.nm` 16 + `.ds` 14):
        // 390×844 32/24 leaves 280 dp of sheet cap, tier 3 = 274 dp;
        // 360×800 24/16 leaves 266 dp, tier 4 = 264 dp;
        // 412×915 24/16 leaves 323.5 dp, tier 0 = 315 dp.
        val cases = listOf(
            Case(Screen(390f, 844f), Insets(32f, 24f), sheet = 274f, stage = 400f),
            Case(Screen(360f, 800f), Insets(24f, 16f), sheet = 264f, stage = 382f),
            Case(Screen(412f, 915f), Insets(24f, 16f), sheet = 315f, stage = 446f),
        )
        cases.forEach { case ->
            val h = availableCase(case.screen, case.insets)
            assertFalse(h.fullPage, "border sheet must stay a sheet on ${case.screen}")
            assertEquals(case.sheet, h.sheet, 0.01f, "sheet on ${case.screen} ${case.insets}")
            assertEquals(case.stage, h.stage, 0.01f, "stage on ${case.screen} ${case.insets}")

            // The strip + chrome the sheet hands out to the composables must cover the
            // 128 dp tile, the 12 sp name, the 11 sp description and both paddings.
            val metrics = phoneCompactSheetMetrics(h.sheet)
            assertEquals(128f, PHONE_FRAME_CARD_SIZE_DP)
            assertTrue(
                metrics.contentHeight <= h.sheet + 0.01f,
                "content=${metrics.contentHeight} > sheet=${h.sheet} on ${case.screen}",
            )
            assertTrue(
                metrics.stripHeight >= 128f + metrics.cardNameLine + metrics.cardDescLine,
                "strip=${metrics.stripHeight} loses the tile, the name or the description on ${case.screen}",
            )
            assertTrue(metrics.cardDescLine > 0f, "the description line must be budgeted")
        }
    }

    @Test
    fun the_stage_keeps_half_of_the_app_height_on_every_screen_and_inset() {
        screens.forEach { screen ->
            allInsets.forEach { insets ->
                val available = available(screen, insets)
                val h = availableCase(screen, insets)
                assertFalse(h.fullPage, "fullPage on ${screen.width}x${screen.height} $insets")
                assertTrue(
                    h.stage >= available * 0.5f - 0.01f,
                    "stage=${h.stage} < 50% of $available on ${screen.width}x${screen.height} $insets",
                )
                // The floor is a floor, not a target: the sheet + chrome + stage is the
                // whole window, not more.
                val sum = h.topBar + h.stage + h.strip + h.sheet + h.toolBar
                assertTrue(sum <= available + 0.01f, "overflow ${sum} > $available")
            }
        }
    }

    @Test
    fun every_inset_the_brief_allows_still_fits_the_name_on_390_and_412() {
        // 390×844 and 412×915 keep a whole card + name at *every* inset combination.
        listOf(Screen(390f, 844f), Screen(412f, 915f)).forEach { screen ->
            allInsets.forEach { insets ->
                val h = availableCase(screen, insets)
                val metrics = phoneCompactSheetMetrics(h.sheet)
                assertEquals(
                    0f,
                    metrics.overflowAt(h.sheet),
                    "content=${metrics.contentHeight} > sheet=${h.sheet} on ${screen.width}x${screen.height} $insets",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Documented residuals (reported to the主管, never silently clipped)
    // -----------------------------------------------------------------------

    @Test
    fun the_three_button_nav_corner_on_360x800_is_short_and_scrolls_instead_of_clipping() {
        // 360×800 with a 48 dp status bar and a 48 dp nav bar leaves 704 dp; the 50 %
        // floor leaves 238 dp and the tightest tier (128 dp card + 16 dp name + 14 dp
        // description + 106 dp of chrome = 260 dp) still needs 22 dp more. Both
        // constraints are permanent, so the sheet keeps the floor and the body scrolls:
        // name and description reachable, nothing cut.
        val screen = Screen(360f, 800f)
        val insets = Insets(48f, 48f)
        val available = available(screen, insets)
        val h = availableCase(screen, insets)
        assertEquals(238f, h.sheet, 0.01f)
        assertEquals(352f, h.stage, 0.01f)
        assertTrue(h.stage >= available * 0.5f - 0.01f, "stage=${h.stage}")

        val metrics = phoneCompactSheetMetrics(h.sheet)
        assertEquals(260f, metrics.contentHeight, 0.01f)
        assertEquals(22f, metrics.overflowAt(h.sheet), 0.01f)
    }

    @Test
    fun the_mid_apk_device_now_shows_the_whole_name() {
        // MID-APK measurement (reviews/MID-APK-REVIEW.md): physical screen 3168 px at
        // 1 dp = 3.5 px, App available height 2952 px = 843.4 dp. The compact sheet gets
        // 307.7 dp from the 50 % floor; tier 1 is 300 dp, so the card, its name and its
        // description all fit and the stage keeps the rest.
        val available = 843.4f
        val h = computePhoneEditorHeights(available, 1f, false, EditorTool.Border, false)
        assertFalse(h.fullPage)
        assertEquals(300f, h.sheet, 0.01f)
        assertEquals(429.4f, h.stage, 0.01f)
        assertEquals(0f, phoneCompactSheetMetrics(h.sheet).overflowAt(h.sheet), 0.01f)
        assertTrue(h.stage >= available * 0.5f - 0.01f, "stage=${h.stage}")
    }

    // -----------------------------------------------------------------------
    // The two constraints themselves are not negotiable
    // -----------------------------------------------------------------------

    @Test
    fun the_roomiest_tier_is_the_showcase_stack() {
        // Tier 0 is the demo's own stack: 22 + 30 + 38 + 42 + (12 + 128 + 3 + 16 + 0 + 14 + 10) = 315.
        val m = phoneCompactSheetMetrics(sheetHeightDp = 400f, fontScale = 1f)
        assertEquals(22f, m.grabber)
        assertEquals(30f, m.head)
        assertEquals(38f, m.categoryRow)
        assertEquals(42f, m.anchorRow)
        assertEquals(12f, m.stripTop)
        assertEquals(3f, m.cardNameGap)
        assertEquals(16f, m.cardNameLineSp)
        assertEquals(16f, m.cardNameLine)
        assertEquals(0f, m.cardDescGap)
        assertEquals(14f, m.cardDescLine)
        assertEquals(10f, m.stripBottom)
        assertEquals(183f, m.stripHeight, 0.01f)
        assertEquals(315f, m.contentHeight, 0.01f)
    }

    @Test
    fun the_card_the_name_line_and_the_description_are_never_compressed() {
        assertEquals(128f, PHONE_FRAME_CARD_SIZE_DP)
        // Every tier: same card, same 16 sp name line, same 14 sp description; only the
        // bands around them shrink.
        val ladder = listOf(400f, 310f, 290f, 270f, 0f).map { phoneCompactSheetMetrics(it) }
        ladder.forEach { m ->
            assertEquals(
                128f,
                m.stripHeight - m.stripTop - m.cardNameGap - m.cardNameLine - m.cardDescGap - m.cardDescLine - m.stripBottom,
                0.01f,
            )
            assertEquals(16f, m.cardNameLineSp)
            assertEquals(14f, m.cardDescLine)
        }
        // Monotonic: a shorter window never gets a taller stack.
        ladder.zipWithNext().forEach { (roomy, tighter) ->
            assertTrue(
                roomy.contentHeight >= tighter.contentHeight,
                "tier ladder grows: ${roomy.contentHeight} -> ${tighter.contentHeight}",
            )
        }
        // The tightest tier still fits its own content: an 8 dp handle, a 22 dp title
        // row, a 30 dp tab row and the 30 dp chip row.
        val tightest = ladder.last()
        assertTrue(tightest.grabber >= 8f, "grabber=${tightest.grabber}")
        assertTrue(tightest.head >= 28f, "head=${tightest.head}")
        assertTrue(tightest.categoryRow >= 30f, "categoryRow=${tightest.categoryRow}")
        assertTrue(tightest.anchorRow >= 30f, "anchorRow=${tightest.anchorRow}")
        assertTrue(tightest.stripBottom >= 2f, "stripBottom=${tightest.stripBottom}")
    }

    @Test
    fun a_larger_font_scale_takes_a_tighter_tier_and_still_counts_both_lines() {
        val base = phoneCompactSheetMetrics(sheetHeightDp = 300f, fontScale = 1f)
        assertEquals(300f, base.contentHeight, 0.01f)

        // 16 sp + 14 sp of leading is 39 dp at fontScale 1.3, so the same 300 dp window
        // needs a tighter tier; both lines are still part of the stack.
        val scaled = phoneCompactSheetMetrics(sheetHeightDp = 300f, fontScale = 1.3f)
        assertEquals(16f * 1.3f, scaled.cardNameLine, 0.01f)
        assertEquals(14f * 1.3f, scaled.cardDescLine, 0.01f)
        assertEquals(scaled.cardNameLineSp * 1.3f, scaled.cardNameLine, 0.01f)
        assertTrue(scaled.categoryRow < base.categoryRow, "tier did not tighten")
        assertTrue(scaled.contentHeight <= 300f + 0.01f, "content=${scaled.contentHeight}")
        // The specified type sizes themselves are never scaled down; the bands are.
        assertEquals(16f, scaled.cardNameLineSp)
        assertEquals(14f, scaled.cardDescLine / 1.3f, 0.01f)
    }

    @Test
    fun the_photo_strip_still_comes_out_of_the_sheet_and_never_below_the_floor() {
        // Opening the photo strip costs 64 dp; the stage keeps its floor and the sheet
        // pays (WP-P1 §1.4), now by dropping a tier and, if that is not enough, scrolling.
        val screen = Screen(390f, 844f)
        val insets = Insets(32f, 24f)
        val available = available(screen, insets)
        val closed = availableCase(screen, insets)
        val open = computePhoneEditorHeights(available, 1f, false, EditorTool.Border, true)
        assertEquals(64f, open.strip)
        assertTrue(open.stage >= available * 0.5f - 0.01f, "open stage=${open.stage}")
        assertTrue(open.sheet <= closed.sheet, "open=${open.sheet} closed=${closed.sheet}")
        // 216 dp cannot hold even the tightest tier (260 dp): the sheet keeps the floor
        // and the body scrolls the remaining 44 dp, so name and description stay readable.
        assertEquals(216f, open.sheet, 0.01f)
        val metrics = phoneCompactSheetMetrics(open.sheet)
        assertEquals(260f, metrics.contentHeight, 0.01f)
        assertEquals(44f, metrics.overflowAt(open.sheet), 0.01f)
    }
}



