package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryFilter
import com.framemark.native.library.LibraryGroups
import com.framemark.native.library.LibrarySubgroup
import com.framemark.native.library.LibraryViewBuilder

// ---------------------------------------------------------------------------
// Area budget
// ---------------------------------------------------------------------------

/** Phone editor area budget in dp, before window insets (the shell owns those). */
data class PhoneEditorHeights(
    val topBar: Float,
    val stage: Float,
    val strip: Float,
    val sheet: Float,
    val toolBar: Float,
    /** True when the constrained parameter page replaces the preview layout. */
    val fullPage: Boolean,
)

/**
 * Single dark top bar (V2 report §7.2): 返回, 照片切换, 撤销 / 重做, 导出.
 * The demo `.ptop` is 52 px on a 412 dp wide screen, i.e. 52 dp.
 */
private const val TOP_BAR_DP = 52f

/** Five-tool bottom bar (4 when the card has no Logo capability), V2 report §7.3. */
private const val TOOL_BAR_DP = 62f

/** Expanded photo strip; collapsed costs nothing. */
private const val STRIP_DP = 64f

/**
 * Compact (收起) sheet floor: the stage keeps at least half of the window
 * (hand-off part 2 §4 WP-P1, report §13 P1 acceptance "舞台高度 ≥ 屏幕 50%").
 * V06 used 0.28 and a 3-step sheet; V2 is a two-step sheet.
 */
private const val STAGE_MIN_FRACTION = 0.50f

/** Expanded (展开) sheet: the stage keeps at least this share of the window. */
private const val EXPANDED_STAGE_MIN_FRACTION = 0.26f

/** Below this the sheet is not usable: fall back to the full parameter page. */
private const val MIN_SHEET_DP = 120f

/**
 * The border card is a hard product spec (report §7.5; WP-P1-2 §3 may not change
 * it): a 128 dp square tile with the name on its own line underneath. The compact
 * sheet content model below is built on this number, so the tile, the name line and
 * the sheet height can never drift apart again.
 */
const val PHONE_FRAME_CARD_SIZE_DP = 128f

/**
 * Border sheet design ceiling: 一行类别 + 一行分组 + the 128 dp card + its name.
 * It is only an upper bound — the compact level is as tall as the card stack in
 * [PhoneCompactSheetMetrics.contentHeight] actually needs (≤ this), so the sheet
 * never carries a dead band and the stage keeps every dp the 50 % floor leaves.
 */
private const val BORDER_SHEET_DP = 326f

/** Expanded sheet target, i.e. the demo's 478 px on an 886 px tall screen. */
private const val EXPANDED_SHEET_FRACTION = 0.54f

/** The three levels of the V2 parameter sheet: 收起 / 半开 / 展开. */
data class PhoneSheetLevels(val compact: Float, val half: Float, val expanded: Float)

/**
 * Three-level sheet of the V2 phone editor: 收起 (compact, the default: category row
 * + group row + the card strip), 半开 (half: the strip plus the first rows of the
 * page) and 展开 (the 3-column grid).
 *
 * The demo deck does not snap at all — `.psheet` is `height: var(--sh, 300px)`
 * dragged freely by its grabber (`design\FrameMark-UI-Redesign-V2-Appearance.html`
 * line 637) — so the level count is a product choice, not a parity requirement.
 * The task book asks for three steps (WP-F2 D3 fixed the old "Two levels only"
 * comment), so the continuous demo height is sampled at three stable stops: the
 * compact content height, the midpoint, and the demo's 54 % expanded height.
 *
 * `null` means the window cannot hold a usable compact sheet above the 50 % stage
 * reserve; the caller then keeps the full parameter page.
 *
 * On the border tool the compact level is exactly as tall as its first screenful
 * needs ([PhoneCompactSheetMetrics.contentHeight]) and never taller: the card, its
 * name and the bands around them decide the height, and the stage keeps everything
 * the floor leaves. The compact level is still capped by [STAGE_MIN_FRACTION], so
 * "128 dp 卡片 + 一行名称 fits" and "舞台 ≥ 50 %" are decided by one number instead
 * of one being traded for the other. The other tools keep their fractional targets
 * because their pages scroll.
 */
fun phoneSheetLevels(
    availableHeightDp: Float,
    tool: EditorTool,
    photosOpen: Boolean,
    small: Boolean,
    fontScale: Float = 1f,
): PhoneSheetLevels? {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    val strip = if (photosOpen) STRIP_DP else 0f
    val chrome = TOP_BAR_DP + TOOL_BAR_DP + strip
    val compactMax = available - chrome - available * STAGE_MIN_FRACTION
    if (compactMax < MIN_SHEET_DP) return null
    val compact = if (tool == EditorTool.Border) {
        // The roomiest tier the floor allows. When even the tightest tier is taller
        // than the floor leaves, the sheet gets the cap and the body scrolls (see
        // PhoneCompactSheetMetrics.overflowAt) instead of cutting the name off.
        phoneCompactSheetMetrics(compactMax, fontScale).contentHeight.coerceAtMost(compactMax)
    } else {
        compactSheetTarget(available, tool, small).coerceIn(MIN_SHEET_DP, compactMax)
    }
    val expandedMax = (available - chrome - available * EXPANDED_STAGE_MIN_FRACTION)
        .coerceAtLeast(compact)
    val expanded = (available * EXPANDED_SHEET_FRACTION).coerceIn(compact, expandedMax)
    // 半开: the midpoint of the two decided stops. It is derived, so it can never
    // fall outside [compact, expanded] and never needs its own budget.
    val half = (compact + expanded) / 2f
    return PhoneSheetLevels(compact = compact, half = half, expanded = expanded)
}

/** Height the compact sheet aims for on this tool, before the 50 % stage cap. */
private fun compactSheetTarget(available: Float, tool: EditorTool, small: Boolean): Float = when (tool) {
    // Ceiling only: the border sheet takes its content height (see phoneSheetLevels).
    EditorTool.Border -> BORDER_SHEET_DP
    EditorTool.Params -> available * (if (small) 0.34f else 0.36f)
    EditorTool.Text -> available * (if (small) 0.40f else 0.42f)
    EditorTool.Logo -> available * (if (small) 0.34f else 0.38f)
    EditorTool.Watermark -> available * (if (small) 0.38f else 0.40f)
}

// ---------------------------------------------------------------------------
// Compact sheet stack: what the 收起 sheet is made of, top to bottom
// ---------------------------------------------------------------------------

/**
 * Vertical stack of the compact phone sheet, in dp. Every field is one of the bands
 * WP-P1-2 §3 allows to be squeezed (handle, title row, category row, group-chip row,
 * the strip's padding, the gap under the tile, the name's line height). The card is
 * deliberately **not** a field: it stays [PHONE_FRAME_CARD_SIZE_DP] at every tier.
 *
 * The composable renders exactly these numbers, so the layout and the budget below
 * cannot disagree — that is what caused the 收起 名称被截断 bug (a 48 dp title row +
 * 48 dp category row + 48 dp chip row + a 157 dp strip needed 319 dp while the 50 %
 * floor only granted 260–308 dp).
 */
data class PhoneCompactSheetMetrics(
    val grabber: Float,
    val head: Float,
    val categoryRow: Float,
    val anchorRow: Float,
    val stripTop: Float,
    val cardNameGap: Float,
    val cardNameLineSp: Float,
    /** One name line resolved to dp at this window's font scale. */
    val cardNameLine: Float,
    val stripBottom: Float,
    /** Gap between the name and the 11 sp description line (showcase `.card .ds`). */
    val cardDescGap: Float = 0f,
    /** One description line resolved to dp at this window's font scale. */
    val cardDescLine: Float = 0f,
) {
    /** Card strip: top padding + the 128 dp tile + name + description + bottom padding. */
    val stripHeight: Float
        get() = stripTop + PHONE_FRAME_CARD_SIZE_DP + cardNameGap + cardNameLine +
            cardDescGap + cardDescLine + stripBottom

    /** The whole first screenful of the 收起 border sheet: card + name + every band. */
    val contentHeight: Float
        get() = grabber + head + categoryRow + anchorRow + stripHeight

    /** How much taller the content is than the sheet; `> 0` means the body must scroll. */
    fun overflowAt(sheetHeightDp: Float): Float =
        (contentHeight - sheetHeightDp).coerceAtLeast(0f)
}

/**
 * The name keeps 12 sp / 16 sp leading and the description 11 sp / 14 sp at every
 * tier (showcase `.card .nm` / `.card .ds`): the 收起 sheet squeezes the bands
 * around the card, never the text the user reads.
 */
private const val CARD_NAME_LINE_SP = 16f
private const val CARD_DESC_LINE_SP = 14f

private fun compactTier(
    grabber: Float,
    head: Float,
    categoryRow: Float,
    anchorRow: Float,
    stripTop: Float,
    cardNameGap: Float,
    stripBottom: Float,
    fontScale: Float,
    cardDescGap: Float = 0f,
): PhoneCompactSheetMetrics = PhoneCompactSheetMetrics(
    grabber = grabber,
    head = head,
    categoryRow = categoryRow,
    anchorRow = anchorRow,
    stripTop = stripTop,
    cardNameGap = cardNameGap,
    cardNameLineSp = CARD_NAME_LINE_SP,
    cardNameLine = CARD_NAME_LINE_SP * fontScale.coerceIn(1f, 1.5f),
    stripBottom = stripBottom,
    cardDescGap = cardDescGap,
    cardDescLine = CARD_DESC_LINE_SP * fontScale.coerceIn(1f, 1.5f),
)

/**
 * The ladder, roomiest first; each step gives up ~14 dp of chrome. Tier 0 is the
 * showcase's own stack (grabber 22, title row 30, 14 px tab row 38, 30 dp chip row
 * 42, strip `12 / 10` around the 128 dp tile, then a 12 sp name and an 11 sp
 * description), tier 4 is the floor before bands stop being usable. The title row
 * never goes below 28 dp: it carries a 16 sp / 700 page name, which must not clip at
 * a large font scale.
 *
 * | tier | grabber | head | 类别 | 分组 | strip | content |
 * |------|---------|------|------|------|-------|---------|
 * | 0    | 22      | 30   | 38   | 42   | 183   | 315     |
 * | 1    | 18      | 30   | 36   | 38   | 178   | 300     |
 * | 2    | 14      | 28   | 34   | 36   | 174   | 286     |
 * | 3    | 10      | 28   | 32   | 34   | 170   | 274     |
 * | 4    | 8       | 28   | 30   | 32   | 166   | 264     |
 * | 5    | 8       | 28   | 30   | 30   | 164   | 260     |
 */
private fun compactTiers(fontScale: Float): List<PhoneCompactSheetMetrics> = listOf(
    compactTier(22f, 30f, 38f, 42f, 12f, 3f, 10f, fontScale),
    compactTier(18f, 30f, 36f, 38f, 10f, 2f, 8f, fontScale),
    compactTier(14f, 28f, 34f, 36f, 8f, 2f, 6f, fontScale),
    compactTier(10f, 28f, 32f, 34f, 6f, 2f, 4f, fontScale),
    compactTier(8f, 28f, 30f, 32f, 4f, 2f, 2f, fontScale),
    compactTier(8f, 28f, 30f, 30f, 2f, 2f, 2f, fontScale),
)

/**
 * The roomiest tier whose first screenful fits [sheetHeightDp], or the tightest
 * tier when none does — the caller then lets the body scroll
 * ([PhoneCompactSheetMetrics.overflowAt]) instead of clipping the name.
 */
fun phoneCompactSheetMetrics(sheetHeightDp: Float, fontScale: Float = 1f): PhoneCompactSheetMetrics {
    val height = if (sheetHeightDp.isFinite()) sheetHeightDp.coerceAtLeast(0f) else 0f
    val tiers = compactTiers(fontScale)
    return tiers.firstOrNull { it.contentHeight <= height } ?: tiers.last()
}

/**
 * Area budget of the V2 phone editor at the sheet's **compact** level.
 * `fullPage` keeps the existing [useFullPhoneParameterPage] rule (<480 dp,
 * fontScale >= 1.5); a window too short for a usable sheet falls back to it as
 * well. No value is ever negative, and top/stage/strip/sheet/bar always fit
 * inside [availableHeightDp], so the caller can lay them out without another
 * coerceIn.
 */
fun computePhoneEditorHeights(
    availableHeightDp: Float,
    fontScale: Float,
    imeVisible: Boolean,
    tool: EditorTool,
    photosOpen: Boolean,
    small: Boolean,
): PhoneEditorHeights {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    if (useFullPhoneParameterPage(available, fontScale, imeVisible)) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    val levels = phoneSheetLevels(available, tool, photosOpen, small, fontScale)
        ?: return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    val strip = if (photosOpen) STRIP_DP else 0f
    val stage = available - TOP_BAR_DP - TOOL_BAR_DP - strip - levels.compact
    if (stage <= 0f) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    return PhoneEditorHeights(
        topBar = TOP_BAR_DP,
        stage = stage,
        strip = strip,
        sheet = levels.compact,
        toolBar = TOOL_BAR_DP,
        fullPage = false,
    )
}

/**
 * Smallest window that can still show the 52 dp top bar, 62 dp tool bar, a
 * usable stage and a scrollable parameter sheet after the user leaves the
 * constrained full-parameter page. Below this the full page is the only valid
 * layout and must stay.
 */
const val FORCED_PREVIEW_MIN_DP = 220f

fun canShowForcedPreview(availableHeightDp: Float): Boolean =
    availableHeightDp.isFinite() && availableHeightDp >= FORCED_PREVIEW_MIN_DP

/**
 * Budget used when a constrained window (large font / short window) leaves the
 * automatic full-parameter page: keep the real chrome and a small stage so the
 * editor never collapses to a zero-height shell. The sheet takes the rest and
 * scrolls, so the user can still see the photo and switch tools.
 */
fun forcedPreviewHeights(availableHeightDp: Float): PhoneEditorHeights {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    if (!canShowForcedPreview(available)) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    val stage = (available * 0.20f).coerceIn(
        60f,
        (available - TOP_BAR_DP - TOOL_BAR_DP - 60f).coerceAtLeast(60f),
    )
    val sheet = (available - TOP_BAR_DP - TOOL_BAR_DP - stage).coerceAtLeast(60f)
    return PhoneEditorHeights(
        topBar = TOP_BAR_DP,
        stage = stage,
        strip = 0f,
        sheet = sheet,
        toolBar = TOOL_BAR_DP,
        fullPage = false,
    )
}

// ---------------------------------------------------------------------------
// Parameter page: pinned band vs the single scrolling tool zone
// ---------------------------------------------------------------------------

/**
 * The pinned 设计 band of the parameter page, in dp: one 12.5 sp row (17) plus 8 dp
 * above and below (the `FmSpacing.sm` band padding) plus its 1 dp hairline.
 *
 * The band is the page's **common zone** — the export size of the selected design,
 * identical on all four pages — so it may never be the band that costs the tool zone
 * the last of its room. See [showsPhoneParameterSummary].
 */
const val PHONE_PARAMETER_SUMMARY_DP = 34f

/**
 * Smallest parameter-page viewport (dp) that still shows the pinned 设计 band.
 *
 * [PHONE_PARAMETER_SUMMARY_DP] plus one 72 dp touch-slider row (`PhoneSliderRow`:
 * 8 + 20 label + 40 track + 4). Below it the band is dropped instead of shrinking
 * the tool zone: the sheet floor is [MIN_SHEET_DP] = 120 dp, whose page viewport
 * after the grabber and the head is ~68 dp, and [forcedPreviewHeights] can hand out
 * an even shorter one. Two pinned bands over a zero-height scroller would make the
 * controls unreachable, which is the single thing this sheet must never do.
 *
 * `Float.POSITIVE_INFINITY` (an unbounded page) keeps the band.
 */
const val PHONE_PARAMETER_SUMMARY_MIN_DP = 106f

fun showsPhoneParameterSummary(pageHeightDp: Float): Boolean =
    pageHeightDp >= PHONE_PARAMETER_SUMMARY_MIN_DP

// ---------------------------------------------------------------------------
// Tool routing
// ---------------------------------------------------------------------------

/**
 * Tools shown in the bottom bar. Logo is a real tool only when the card offers a
 * Logo capability (a Brand control or a rendered Logo-section control), so a card
 * without one gets four cells instead of a dead fifth.
 */
fun phoneEditorTools(hasLogoCapability: Boolean): List<EditorTool> = buildList {
    add(EditorTool.Border)
    add(EditorTool.Params)
    add(EditorTool.Text)
    if (hasLogoCapability) add(EditorTool.Logo)
    add(EditorTool.Watermark)
}

/** A Logo capability exists when the panel model carries a Brand or Logo control. */
fun phoneEditorHasLogoCapability(model: InspectorCardModel?): Boolean {
    if (model == null) return false
    if (model.controls.any { it.type == ControlType.Brand }) return true
    return model.sections.any { section ->
        section.section == InspectorSection.Logo && section.controls.isNotEmpty()
    }
}

/** The section an [EditorTabList] tab is opened on; null for the browser and watermark panels. */
fun phoneEditorPanelSection(tool: EditorTool): InspectorSection? = when (tool) {
    EditorTool.Border -> null
    EditorTool.Params -> InspectorSection.Layout
    EditorTool.Text -> InspectorSection.Text
    EditorTool.Logo -> InspectorSection.Logo
    EditorTool.Watermark -> null
}

// ---------------------------------------------------------------------------
// Border browser (pure data model; the composable only renders it)
// ---------------------------------------------------------------------------

/**
 * One step of the hierarchy. `categoryKey == null` is the 全部 chip; a null
 * vendor/family key means "let the builder pick the first real one".
 */
data class PhoneBorderSelection(
    val categoryKey: String?,
    val vendorKey: String?,
    val familyKey: String?,
)

/** Category row: 全部 first, then the declared categories with their real counts. */
data class PhoneBorderCategoryOption(
    val key: String?,
    val label: String,
    val count: Int,
)

/**
 * Series row. [key] is internal and stable (a real firmware family or the
 * section-level bucket); [label] is never invented — it is `subgroup.label`, or
 * the section label when the group has no formal series.
 */
data class PhoneBorderFamilyOption(
    val key: String,
    val label: String,
    val note: String?,
    val cards: List<LibraryCard>,
)

/** Vendor row. Every card of the vendor group lives in exactly one family. */
data class PhoneBorderVendorOption(
    val key: String,
    val label: String,
    val note: String?,
    val families: List<PhoneBorderFamilyOption>,
) {
    val count: Int get() = families.sumOf { it.cards.size }
}

/** The resolved levels of one category selection. */
data class PhoneBorderBrowserState(
    val categories: List<PhoneBorderCategoryOption>,
    val vendors: List<PhoneBorderVendorOption>,
    val selection: PhoneBorderSelection,
) {
    val activeVendor: PhoneBorderVendorOption?
        get() = vendors.firstOrNull { it.key == selection.vendorKey } ?: vendors.firstOrNull()

    val families: List<PhoneBorderFamilyOption>
        get() = activeVendor?.families.orEmpty()

    val activeFamily: PhoneBorderFamilyOption?
        get() = families.firstOrNull { it.key == selection.familyKey } ?: families.firstOrNull()

    val cards: List<LibraryCard> get() = activeFamily?.cards.orEmpty()

    /** Cards reachable under the active category (180 with 全部). */
    val totalCards: Int get() = vendors.sumOf { it.count }
}

/**
 * Builds the four levels with [LibraryViewBuilder] so the vendor/series split is
 * exactly the library's own data-driven grouping (`LibraryGroups.keyOf`,
 * `LibraryCard.styleFamilyLabel` / `familyNameProvenance`). A stale or missing
 * selection degrades to the first available level instead of rendering nothing.
 */
fun buildPhoneBorderBrowser(
    cards: List<LibraryCard>,
    categories: List<LibraryCategory>,
    selection: PhoneBorderSelection,
): PhoneBorderBrowserState {
    val categoryOptions = buildList {
        add(PhoneBorderCategoryOption(key = null, label = "全部", count = cards.size))
        categories.forEach { category ->
            val count = cards.count { it.category == category.key }
            if (count > 0) {
                add(
                    PhoneBorderCategoryOption(
                        key = category.key,
                        label = category.name.ifBlank { category.key },
                        count = count,
                    ),
                )
            }
        }
    }
    val categoryKey = selection.categoryKey?.takeIf { key -> categoryOptions.any { it.key == key } }
    val view = LibraryViewBuilder.build(cards, LibraryFilter(categoryKey = categoryKey))
    val vendors = view.sections.mapNotNull { section ->
        val families = section.subgroups.map { subgroup ->
            PhoneBorderFamilyOption(
                key = familyKey(section.vendorKey, subgroup),
                label = subgroup.label ?: section.label,
                note = subgroup.note ?: section.note,
                cards = subgroup.cards,
            )
        }.filter { it.cards.isNotEmpty() }
        if (families.isEmpty()) {
            null
        } else {
            PhoneBorderVendorOption(
                key = section.vendorKey,
                label = section.label,
                note = section.note,
                families = families,
            )
        }
    }
    val vendorKey = selection.vendorKey?.takeIf { key -> vendors.any { it.key == key } }
        ?: vendors.firstOrNull()?.key
    val families = vendors.firstOrNull { it.key == vendorKey }?.families.orEmpty()
    val resolvedFamilyKey = selection.familyKey?.takeIf { key -> families.any { it.key == key } }
        ?: families.firstOrNull()?.key
    return PhoneBorderBrowserState(
        categories = categoryOptions,
        vendors = vendors,
        selection = PhoneBorderSelection(categoryKey, vendorKey, resolvedFamilyKey),
    )
}

/**
 * Opening selection: the category/vendor/series the active card already belongs
 * to, so re-entering 边框 lands on the applied style instead of the first row.
 */
fun phoneBorderDefaultSelection(cards: List<LibraryCard>, cardId: String?): PhoneBorderSelection {
    val card = cards.firstOrNull { it.id == cardId } ?: return PhoneBorderSelection(null, null, null)
    val categoryKey = card.category.takeIf { it.isNotBlank() && cards.any { other -> other.category == it } }
    val view = LibraryViewBuilder.build(cards, LibraryFilter(categoryKey = categoryKey))
    val section = view.sections.firstOrNull { it.vendorKey == LibraryGroups.keyOf(card) }
        ?: return PhoneBorderSelection(categoryKey, null, null)
    val subgroup = section.subgroups.firstOrNull { group -> group.cards.any { it.id == card.id } }
        ?: return PhoneBorderSelection(categoryKey, section.vendorKey, null)
    return PhoneBorderSelection(categoryKey, section.vendorKey, familyKey(section.vendorKey, subgroup))
}

/**
 * Stable identity of a series row. A formal firmware family is keyed by its own
 * label; the section-level bucket (no formal series) is keyed by the section, so
 * a label-less group can never collide with a real family name.
 */
private fun familyKey(sectionKey: String, subgroup: LibrarySubgroup): String =
    subgroup.label?.let { "family:$it" } ?: "section:$sectionKey"
