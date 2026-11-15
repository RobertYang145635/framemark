package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryFilter
import com.framemark.native.library.LibraryGroups
import com.framemark.native.library.LibrarySubgroup
import com.framemark.native.library.LibraryViewBuilder
import com.framemark.native.shell.phone.PhoneBorderPageState

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
internal const val TOP_BAR_DP = 52f

/** Five-tool bottom bar (4 when the card has no Logo capability), V2 report §7.3. */
internal const val TOOL_BAR_DP = 62f

/** Expanded photo strip; collapsed costs nothing. */
internal const val STRIP_DP = 64f

/**
 * Compact (收起) sheet floor: the stage keeps at least half of the window
 * (hand-off part 2 §4 WP-P1, report §13 P1 acceptance "舞台高度 ≥ 屏幕 50%").
 * V06 used 0.28 and a 3-step sheet; V2 is a two-step sheet.
 */
internal const val STAGE_MIN_FRACTION = 0.50f

/** Expanded (展开) sheet: the stage keeps at least this share of the window. */
internal const val EXPANDED_STAGE_MIN_FRACTION = 0.26f

/**
 * The border card is a hard product spec (report §7.5; WP-P1-2 §3 may not change
 * it): a 128 dp square tile with the name on its own line underneath. The compact
 * sheet content model below is built on this number, so the tile, the name line and
 * the sheet height can never drift apart again.
 */
const val PHONE_FRAME_CARD_SIZE_DP = 128f

/** Expanded sheet target, i.e. the demo's 478 px on an 886 px tall screen. */
private const val EXPANDED_SHEET_FRACTION = 0.54f

/** The three levels of the V2 parameter sheet: 收起 / 半开 / 展开. */
data class PhoneSheetLevels(val compact: Float, val half: Float, val expanded: Float)

/**
 * Three-level sheet of the V2 phone editor: 收起 (compact), 半开 (half) and 展开
 * (expanded). The drag-to-snap behaviour survives; only the **default level** moved.
 *
 * **The 收起 level is now one fixed frame** (2026-11-10, measured from Lightroom on
 * this phone): `clamp(0.34 × window, 280, 330)` dp, the same number for 样式 / 文字 /
 * Logo / 水印 and for every group inside them — see `phonePanelHeightDp`. It does not
 * read the tool's controls, so switching tools or groups cannot move the panel's top
 * edge by a pixel. The border tool keeps its own compact landing panel: fixed chrome
 * ([PHONE_BORDER_SHEET_GRABBER_DP] + [PHONE_BORDER_SHEET_HEAD_DP]) plus the
 * current-item panel height (配色方案 §4.1), so the sheet is as tall as its content.
 *
 * `null` means the window cannot hold the fixed panel above the stage floor
 * ([PHONE_PANEL_MIN_STAGE_DP]), or — for the border landing panel — cannot hold the
 * compact sheet at all; the caller then keeps the full parameter page.
 */
fun phoneSheetLevels(
    availableHeightDp: Float,
    tool: EditorTool,
    photosOpen: Boolean,
    fontScale: Float = 1f,
): PhoneSheetLevels? {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    val chrome = TOP_BAR_DP + TOOL_BAR_DP + (if (photosOpen) STRIP_DP else 0f)
    val compactMax = available - chrome - available * STAGE_MIN_FRACTION
    val compact = if (tool == EditorTool.Border) {
        // 配色方案 §4.1：边框落地板 = 固定 chrome + 当前项面板；面板按「还没选边框」
        // 的高度算（144 dp），已选边框时只是行内更矮（88 dp），sheet 高度保持稳定，
        // 不会在点选边框后跳一下。
        val panel = PhoneBorderPageState.panelHeight(
            tileDp = PhoneBorderPageState.PANEL_TILE_DP,
            hasCard = false,
            fontScale = fontScale,
        )
        val sheet = PHONE_BORDER_SHEET_GRABBER_DP + PHONE_BORDER_SHEET_HEAD_DP + panel
        if (compactMax < sheet) return null
        sheet
    } else {
        val panel = phonePanelHeightDp(available)
        if (panel <= 0f) return null
        // The fixed panel needs a photo to sit under; without one the full page wins.
        if (available - chrome - panel < PHONE_PANEL_MIN_STAGE_DP) return null
        panel
    }
    val expandedMax = (available - chrome - available * EXPANDED_STAGE_MIN_FRACTION)
        .coerceAtLeast(compact)
    val expanded = (available * EXPANDED_SHEET_FRACTION).coerceIn(compact, expandedMax)
    // 半开: the midpoint of the two decided stops. It is derived, so it can never
    // fall outside [compact, expanded] and never needs its own budget.
    val half = (compact + expanded) / 2f
    return PhoneSheetLevels(compact = compact, half = half, expanded = expanded)
}


/**
 * Area budget of the V2 phone editor at the sheet's **compact** level.
 * `fullPage` keeps the existing [useFullPhoneParameterPage] rule (<480 dp,
 * fontScale >= 1.5); a window too short for the fixed panel plus a usable stage
 * falls back to it as well. No value is ever negative, and
 * top/stage/strip/sheet/bar always fit inside [availableHeightDp], so the caller
 * can lay them out without another coerceIn.
 *
 * **2026-11-10 固定画框**：非边框工具的收起高度不再由内容决定，也不再接受控件表
 * （`controls` / `isIr` / `hasTextReport` / `hasWatermarkLayer` 四个入参随内容驱动的
 * 分页政策一起删除）：它就是 [phonePanelHeightDp]，所以同一个窗口里四个工具、每一组
 * 的 `sheet` 是同一个数。画布（`stage`）因此也不随工具变。
 */
fun computePhoneEditorHeights(
    availableHeightDp: Float,
    fontScale: Float,
    imeVisible: Boolean,
    tool: EditorTool,
    photosOpen: Boolean,
): PhoneEditorHeights {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    if (useFullPhoneParameterPage(available, fontScale, imeVisible)) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    val levels = phoneSheetLevels(
        availableHeightDp = available,
        tool = tool,
        photosOpen = photosOpen,
        fontScale = fontScale,
    ) ?: return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
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
 * Smallest window that can still show the 52 dp top bar, 62 dp tool bar, the fixed
 * parameter panel and [PHONE_PANEL_MIN_STAGE_DP] of photo after the user leaves the
 * constrained full-parameter page. Below this the full page is the only valid
 * layout and must stay.
 */
const val FORCED_PREVIEW_MIN_DP = PHONE_PANEL_MIN_STAGE_DP + TOP_BAR_DP + TOOL_BAR_DP + PHONE_PANEL_MIN_DP

fun canShowForcedPreview(availableHeightDp: Float): Boolean =
    availableHeightDp.isFinite() && availableHeightDp >= FORCED_PREVIEW_MIN_DP

/**
 * Budget used when a constrained window (large font / short window) leaves the
 * automatic full-parameter page: the real chrome plus the **same fixed panel** every
 * other state uses, so leaving the full page never resurrects a second panel height.
 * A window that cannot hold the panel and a usable stage keeps the full page.
 */
fun forcedPreviewHeights(availableHeightDp: Float): PhoneEditorHeights {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    if (!canShowForcedPreview(available)) {
        return PhoneEditorHeights(0f, 0f, 0f, available, 0f, fullPage = true)
    }
    val sheet = phonePanelHeightDp(available)
    val stage = (available - TOP_BAR_DP - TOOL_BAR_DP - sheet).coerceAtLeast(0f)
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
