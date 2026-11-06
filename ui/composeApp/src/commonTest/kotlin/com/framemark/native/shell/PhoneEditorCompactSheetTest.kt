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
            small = screen.small,
        )

    // -----------------------------------------------------------------------
    // The two rules
    // -----------------------------------------------------------------------

    @Test
    fun the_card_and_its_name_fit_the_compact_sheet_on_the_carried_screens() {
        // 390×844 with a 32 dp status bar and a 24 dp nav bar: the floor leaves 280 dp,
        // tier 2 is 280 dp exactly (12 handle + 40 title + 40 类别 + 36 分组 + 152 strip).
        // 360×800 24/16: 266 dp cap, tier 3 = 262 dp.
        // 412×915 24/16: 323.5 dp cap, tier 0 (the unchanged WP-P1 design) = 319 dp.
        val cases = listOf(
            Case(Screen(390f, 844f), Insets(32f, 24f), sheet = 280f, stage = 394f),
            Case(Screen(360f, 800f), Insets(24f, 16f), sheet = 262f, stage = 384f),
            Case(Screen(412f, 915f), Insets(24f, 16f), sheet = 319f, stage = 442f),
        )
        cases.forEach { case ->
            val h = availableCase(case.screen, case.insets)
            assertFalse(h.fullPage, "border sheet must stay a sheet on ${case.screen}")
            assertEquals(case.sheet, h.sheet, 0.01f, "sheet on ${case.screen} ${case.insets}")
            assertEquals(case.stage, h.stage, 0.01f, "stage on ${case.screen} ${case.insets}")

            // The strip + chrome the sheet hands out to the composables must cover the
            // 128 dp tile, the name line and both paddings.
            val metrics = phoneCompactSheetMetrics(h.sheet)
            assertEquals(128f, PHONE_FRAME_CARD_SIZE_DP)
            assertTrue(
                metrics.contentHeight <= h.sheet + 0.01f,
                "content=${metrics.contentHeight} > sheet=${h.sheet} on ${case.screen}",
            )
            assertTrue(
                metrics.stripHeight >= 128f + metrics.cardNameLine,
                "strip=${metrics.stripHeight} loses the tile or the name on ${case.screen}",
            )
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
        // floor leaves 238 dp and the tightest tier (128 dp card + 14 dp name + 112 dp
        // of chrome) still needs 254 dp. Both constraints are permanent, so the sheet
        // keeps the floor and the body scrolls: 16 dp short, name reachable, nothing cut.
        val screen = Screen(360f, 800f)
        val insets = Insets(48f, 48f)
        val available = available(screen, insets)
        val h = availableCase(screen, insets)
        assertEquals(238f, h.sheet, 0.01f)
        assertEquals(352f, h.stage, 0.01f)
        assertTrue(h.stage >= available * 0.5f - 0.01f, "stage=${h.stage}")

        val metrics = phoneCompactSheetMetrics(h.sheet)
        assertEquals(254f, metrics.contentHeight, 0.01f)
        assertEquals(16f, metrics.overflowAt(h.sheet), 0.01f)
    }

    @Test
    fun the_mid_apk_device_now_shows_the_whole_name() {
        // MID-APK measurement (reviews/MID-APK-REVIEW.md): physical screen 3168 px at
        // 1 dp = 3.5 px, App available height 2952 px = 843.4 dp. The compact sheet got
        // 307.7 dp while the old fixed stack needed 319 dp, so 极简 01 / 玻璃 01 lost
        // 11.3 dp of the name. Tier 1 is 300 dp, so it fits and the stage grows.
        val available = 843.4f
        val h = computePhoneEditorHeights(available, 1f, false, EditorTool.Border, false, true)
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
    fun the_roomiest_tier_is_the_unchanged_wp_p1_design() {
        // Tier 0 is what WP-P1 shipped: 18 + 48 + 48 + 48 + (4 + 128 + 3 + 14 + 8) = 319.
        val m = phoneCompactSheetMetrics(sheetHeightDp = 400f, fontScale = 1f)
        assertEquals(18f, m.grabber)
        assertEquals(48f, m.head)
        assertEquals(48f, m.categoryRow)
        assertEquals(48f, m.anchorRow)
        assertEquals(4f, m.stripTop)
        assertEquals(3f, m.cardNameGap)
        assertEquals(14f, m.cardNameLineSp)
        assertEquals(14f, m.cardNameLine)
        assertEquals(8f, m.stripBottom)
        assertEquals(157f, m.stripHeight, 0.01f)
        assertEquals(319f, m.contentHeight, 0.01f)
    }

    @Test
    fun the_card_and_the_name_line_are_never_compressed() {
        assertEquals(128f, PHONE_FRAME_CARD_SIZE_DP)
        // Every tier: same card, same 14 sp name line; only the bands around them shrink.
        val ladder = listOf(400f, 310f, 290f, 270f, 0f).map { phoneCompactSheetMetrics(it) }
        ladder.forEach { m ->
            assertEquals(128f, m.stripHeight - m.stripTop - m.cardNameGap - m.cardNameLine - m.stripBottom, 0.01f)
            assertEquals(14f, m.cardNameLineSp)
        }
        // Monotonic: a shorter window never gets a taller stack.
        ladder.zipWithNext().forEach { (roomy, tighter) ->
            assertTrue(
                roomy.contentHeight >= tighter.contentHeight,
                "tier ladder grows: ${roomy.contentHeight} -> ${tighter.contentHeight}",
            )
        }
        // The tightest tier still fits its own content: a 4 dp handle, a 16 sp title,
        // a 14 sp category chip and the 30 dp group pill.
        val tightest = ladder.last()
        assertTrue(tightest.grabber >= 8f, "grabber=${tightest.grabber}")
        assertTrue(tightest.head >= 32f, "head=${tightest.head}")
        assertTrue(tightest.categoryRow >= 32f, "categoryRow=${tightest.categoryRow}")
        assertTrue(tightest.anchorRow >= 32f, "anchorRow=${tightest.anchorRow}")
        assertTrue(tightest.stripBottom >= 2f, "stripBottom=${tightest.stripBottom}")
    }

    @Test
    fun a_larger_font_scale_takes_a_tighter_tier_and_still_counts_the_name_line() {
        val base = phoneCompactSheetMetrics(sheetHeightDp = 300f, fontScale = 1f)
        assertEquals(300f, base.contentHeight, 0.01f)

        // 14 sp of leading is 18.2 dp at fontScale 1.3, so the same 300 dp window needs
        // the next tier down; the line is still part of the stack.
        val scaled = phoneCompactSheetMetrics(sheetHeightDp = 300f, fontScale = 1.3f)
        assertEquals(14f * 1.3f, scaled.cardNameLine, 0.01f)
        assertEquals(scaled.cardNameLineSp * 1.3f, scaled.cardNameLine, 0.01f)
        assertTrue(scaled.categoryRow < base.categoryRow, "tier did not tighten")
        assertTrue(scaled.contentHeight <= 300f + 0.01f, "content=${scaled.contentHeight}")
        // The 14 sp line itself is never scaled down; the compactor is the bands.
        assertEquals(14f, scaled.cardNameLineSp)
    }

    @Test
    fun the_photo_strip_still_comes_out_of_the_sheet_and_never_below_the_floor() {
        // Opening the photo strip costs 64 dp; the stage keeps its floor and the sheet
        // pays (WP-P1 §1.4), now by dropping a tier and, if that is not enough, scrolling.
        val screen = Screen(390f, 844f)
        val insets = Insets(32f, 24f)
        val available = available(screen, insets)
        val closed = availableCase(screen, insets)
        val open = computePhoneEditorHeights(available, 1f, false, EditorTool.Border, true, screen.small)
        assertEquals(64f, open.strip)
        assertTrue(open.stage >= available * 0.5f - 0.01f, "open stage=${open.stage}")
        assertTrue(open.sheet <= closed.sheet, "open=${open.sheet} closed=${closed.sheet}")
        // 216 dp cannot hold even the tightest tier (254 dp): the sheet keeps the floor
        // and the body scrolls the remaining 38 dp, so the name stays readable.
        assertEquals(216f, open.sheet, 0.01f)
        val metrics = phoneCompactSheetMetrics(open.sheet)
        assertEquals(254f, metrics.contentHeight, 0.01f)
        assertEquals(38f, metrics.overflowAt(open.sheet), 0.01f)
    }
}
