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

/** Single-layer top bar: file name, photo counter, 预设, 导出, 更多. */
private const val TOP_BAR_DP = 48f

/** Five-tool bottom bar (4 when the card has no Logo capability). */
private const val TOOL_BAR_DP = 52f

/** Expanded photo strip; collapsed costs nothing. */
private const val STRIP_DP = 64f

/** The stage never gives up more than this share of the window. */
private const val STAGE_MIN_FRACTION = 0.28f

/** Below this the sheet is not usable: fall back to the full parameter page. */
private const val MIN_SHEET_DP = 120f

/** Border browser: four rows (分类/厂商/系列/款式) plus the status line. */
private const val BORDER_SHEET_DP = 320f

/**
 * Area budget of the V06 phone editor. `fullPage` keeps the existing
 * [useFullPhoneParameterPage] rule (<480 dp, fontScale >= 1.5, visible IME); a
 * window too short for a usable sheet falls back to it as well. No value is ever
 * negative, and top/stage/strip/sheet/bar always fit inside [availableHeightDp],
 * so the caller can lay them out without another coerceIn.
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
    val strip = if (photosOpen) STRIP_DP else 0f
    val stageMin = available * STAGE_MIN_FRACTION
    val maxSheet = (available - TOP_BAR_DP - TOOL_BAR_DP - strip - stageMin).coerceAtLeast(0f)
    if (maxSheet < MIN_SHEET_DP) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    val target = when (tool) {
        // 320 dp holds 分类 + 厂商 + 系列 + status + the 96 dp style row on 360/390.
        EditorTool.Border -> BORDER_SHEET_DP
        EditorTool.Params -> available * (if (small) 0.34f else 0.36f)
        EditorTool.Text -> available * (if (small) 0.40f else 0.42f)
        EditorTool.Logo -> available * (if (small) 0.34f else 0.38f)
        EditorTool.Watermark -> available * (if (small) 0.38f else 0.40f)
    }
    val sheet = target.coerceIn(MIN_SHEET_DP, maxSheet)
    val stage = available - TOP_BAR_DP - TOOL_BAR_DP - strip - sheet
    if (stage <= 0f) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    return PhoneEditorHeights(
        topBar = TOP_BAR_DP,
        stage = stage,
        strip = strip,
        sheet = sheet,
        toolBar = TOOL_BAR_DP,
        fullPage = false,
    )
}

/**
 * Smallest window that can still show the 48 dp top bar, 52 dp tool bar, a
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
