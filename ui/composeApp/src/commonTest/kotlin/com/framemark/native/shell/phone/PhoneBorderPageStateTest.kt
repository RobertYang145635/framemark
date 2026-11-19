package com.framemark.native.shell.phone

import com.framemark.native.inspector.PHONE_LOGO_PICKER_PANEL_FRACTION
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryFamilyGroup
import com.framemark.native.library.LibraryGroups
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.computePhoneEditorHeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机端边框工具的纯状态契约（无 Compose、无资源、无持久化）：
 *
 *  1. **落地态是面板，不是列表**——边框页本身不是库，库只能由用户明确要求才打开；
 *     没边框时那个要求只有一个来源：画布上的 [PhoneBorderPageState.CHOOSE_LABEL]；
 *  2. 面板的文案（当前边框 / 换一个）与**竖向预算**（不滚动的前提）；
 *  3. 覆盖层里的内容照旧：分类 ⇄ 页码、选中态、2 列网格、条目下标、空态；
 *  4. 「库里只有一个滚动区、面板里一个都没有」这条只能靠结构表达的不变量。
 *
 * 数字都是算出来的，不是「大概放得下」：`PhoneEditorCompactSheetTest` 钉住底板高度，
 * 这里钉住「面板拿到的高度装得下缩略图和动作」。
 */
class PhoneBorderPageStateTest {

    private fun card(
        id: String,
        name: String = id,
        category: String = "vendor",
        categoryName: String = "厂商",
        brand: String? = null,
        templateCategory: String? = null,
    ) = LibraryCard(
        id = id,
        name = name,
        category = category,
        categoryName = categoryName,
        brand = brand,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        templateCategory = templateCategory,
        previewSourcePath = "previews/$id.webp",
        previewFile = "$id.webp",
    )

    private fun group(
        key: String,
        cards: List<LibraryCard>,
        label: String = key,
        note: String? = null,
    ) = LibraryCardGroup(key = key, label = label, note = note, cards = cards)

    private fun category(key: String, name: String = key) = LibraryCategory(key = key, name = name, count = 0)

    private val oppo = card("oppo-fin", name = "OPPO 经典", brand = "OPPO", templateCategory = "经典")
    private val vivo = card("vivo-fin", name = "vivo 标准", brand = "vivo", templateCategory = "标准")
    private val white = card("white-1", name = "白边 细", category = "white", categoryName = "白边", templateCategory = "细")

    private val groups = listOf(
        group("template|vendor|经典", listOf(oppo), label = "经典"),
        group("template|vendor|标准", listOf(vivo, white), label = "标准"),
    )

    /**
     * vivo 在固件里没有族：厂商页里唯一的组是 `no-formal-family` 桶，标签是那句
     * 「未在固件中证实」。拿这个标签去搜索永远没有结果，所以系列跳转必须靠组键。
     */
    private val vivoFamily = LibraryFamilyGroup(
        key = LibraryIndex.NO_FORMAL_FAMILY,
        label = LibraryGroups.NO_FIRMWARE_LABEL,
        note = "no-family-by-design",
        styleFamily = null,
        cards = listOf(vivo),
    )

    /** 厂商页：最前面是「系列目录」入口，然后是 vivo 这一个分组。 */
    private val vendorPageGroups = listOf(
        group(
            key = phoneFamilyGroupKey("brand:vivo", vivoFamily),
            cards = vivoFamily.cards,
            label = vivoFamily.label,
        ),
    )

    /** 分类行的一项一格；顺序就是 taxonomy 的顺序（这里只取前四项）。 */
    private val categories = listOf(
        category(LibraryIndex.CATEGORY_COMMON, "常用"),
        category(LibraryIndex.CATEGORY_VENDOR, "厂商"),
        category("text", "文字"),
        category("white", "白边"),
    )

    // -- 落地 / 覆盖层：库只能由动作打开 ---------------------------------------

    @Test
    fun theLandingBodyIsThePanelNotTheLibrary() {
        // owner 的要求：边框页本身不是库。打开工具的第一拍必须是面板。
        assertEquals(
            PhoneBorderPageState.PhoneBorderPageBody.Panel,
            PhoneBorderPageState.landingBody(),
        )
        assertFalse(PhoneBorderPageState.isLibraryOpen(PhoneBorderPageState.landingBody()))
        // 面板是默认值这件事不随「上次停在哪一页」变化：库里再打开也是同一个入口。
        assertEquals(
            PhoneBorderPageState.landingBody(),
            PhoneBorderPageState.bodyAfterCancel(PhoneBorderPageState.PhoneBorderPageBody.Library),
            "取消之后必须精确回到面板",
        )
    }

    @Test
    fun onlyThePanelActionOpensTheLibrary() {
        val panel = PhoneBorderPageState.landingBody()
        // 开库的动作只有两个来源：没边框时画布上那枚按钮，有边框时面板里那枚「更换」。
        // 两者走的是同一条转换（面板 → 库），面板里没有第二枚「选择一个边框」。
        val open = PhoneBorderPageState.bodyAfterAction(panel)
        assertEquals(PhoneBorderPageState.PhoneBorderPageBody.Library, open)
        assertTrue(PhoneBorderPageState.isLibraryOpen(open))
        // 库已经开着时再点动作不叠第二层：覆盖层只有一个。
        assertEquals(open, PhoneBorderPageState.bodyAfterAction(open))
        // 别的转换都回面板，没有任何一条能把库「顺带」打开。
        listOf(
            PhoneBorderPageState.bodyAfterPick(open),
            PhoneBorderPageState.bodyAfterCancel(open),
        ).forEach { body ->
            assertEquals(PhoneBorderPageState.PhoneBorderPageBody.Panel, body)
            assertFalse(PhoneBorderPageState.isLibraryOpen(body))
        }
        assertEquals(panel, PhoneBorderPageState.bodyAfterPick(panel))
        assertEquals(panel, PhoneBorderPageState.bodyAfterCancel(panel))
    }

    @Test
    fun pickingACardReturnsToThePanelWithTheNewCard() {
        // 库里的点选就是应用（调用方 pickBorder 写设计），所以关闭后落地的是面板，
        // 而面板上的当前边框读的是「已生效的那张卡」——不需要第二拍确认。
        val afterPick = PhoneBorderPageState.bodyAfterPick(PhoneBorderPageState.PhoneBorderPageBody.Library)
        assertEquals(PhoneBorderPageState.PhoneBorderPageBody.Panel, afterPick)
        // 面板这一行因此恒等于已生效的值：换过卡就是新卡的名字。
        assertEquals("OPPO 经典", PhoneBorderPageState.currentName(oppo))
        assertEquals("白边 细", PhoneBorderPageState.currentName(white))
    }

    @Test
    fun cancellingLeavesThePanelUnchanged() {
        // 取消 = 什么都不改：状态回面板，面板的当前边框仍然只由已生效的设计值决定。
        val afterCancel =
            PhoneBorderPageState.bodyAfterCancel(PhoneBorderPageState.PhoneBorderPageBody.Library)
        assertEquals(PhoneBorderPageState.PhoneBorderPageBody.Panel, afterCancel)
        assertEquals(PhoneBorderPageState.currentName(oppo), PhoneBorderPageState.currentName(oppo))
        assertEquals("OPPO 经典", PhoneBorderPageState.currentName(oppo))
    }

    // -- 面板文案 ---------------------------------------------------------------

    @Test
    fun theOnlyChooseEntryIsTheStageButtonAndThePanelActionIsChange() {
        // 没边框时面板**没有**动作：那一枚「选择一个边框」在画布上（StagePlaceholder
        // 读的就是这个常量），所以常量本身必须还是那句完整的话。
        assertEquals("选择一个边框", PhoneBorderPageState.CHOOSE_LABEL)
        assertEquals("未选边框", PhoneBorderPageState.currentName(null))
        assertEquals(PhoneBorderPageState.NO_CURRENT_LABEL, PhoneBorderPageState.currentName(null))
        // 有边框时面板行内的次级动作是「更换」（配色方案 §4.1），不再占一整行主按钮。
        assertEquals("更换", PhoneBorderPageState.CHANGE_LABEL)
        assertEquals("OPPO 经典", PhoneBorderPageState.currentName(oppo))
        // 两个常量不是同一句话：入口和换边框是两件事，文案不能混。
        assertTrue(PhoneBorderPageState.CHOOSE_LABEL != PhoneBorderPageState.CHANGE_LABEL)
    }

    @Test
    fun thePanelSecondLineIsTheCardsOwnLineAndNothingWithoutABorder() {
        // 没有边框：第二行整行不画。空状态只靠画布上那一枚按钮表达，**不补说明句**
        //（用户原话：「不要加这种莫名其妙的说明性语句」）。所以这里必须是 null，
        // 而不是换成另一句话。
        assertNull(PhoneBorderPageState.currentDescription(card = null, description = null))
        assertNull(
            PhoneBorderPageState.currentDescription(card = null, description = "不该被用上"),
        )
        // 有边框时用数据自带的那一行（`phoneCardDescription`），不猜、不拼别的。
        assertEquals(
            "OPPO · 经典",
            PhoneBorderPageState.currentDescription(oppo, "OPPO · 经典"),
        )
        // 数据里没有说明时退回「未选边框」，不写一行空白。
        assertEquals(
            PhoneBorderPageState.NO_CURRENT_LABEL,
            PhoneBorderPageState.currentDescription(oppo, "   "),
        )
    }

    @Test
    fun theLibrarySummaryLivesInTheLibraryHeaderNotInThePanel() {
        // 库规模只在浏览库时出现；面板不再有第三行说明（配色方案 §4.1）。
        assertEquals("8 个分类 · 180 个边框", PhoneBorderPageState.librarySummary(8, 180))
        assertEquals(
            PhoneBorderPageState.librarySummary(8, 180),
            PhoneBorderPageState.librarySummary(8, 180),
            "说明必须稳定，不能每次拼出不同的话",
        )
    }

    // -- 面板竖向预算：不留滚动容器 -------------------------------------------

    @Test
    fun thePanelHeightIsTheCompactCurrentRowInBothStates() {
        // 两种状态都是一行当前项：8（上）+ 72（缩略图/行）+ 8（下）= 88。
        // 2026-11：没边框时不再有第二条全宽按钮，所以两档**同高** —— 选完边框
        // sheet 不跳，舞台上的照片也就不会被顶一下。
        assertEquals(88f, PhoneBorderPageState.panelHeight(tileDp = 72f, hasCard = true), 0.01f)
        assertEquals(88f, PhoneBorderPageState.panelHeight(tileDp = 72f, hasCard = false), 0.01f)
        assertEquals(
            PhoneBorderPageState.panelHeight(tileDp = 72f, hasCard = true),
            PhoneBorderPageState.panelHeight(tileDp = 72f, hasCard = false),
            0.01f,
            "选了边框之后面板高度不能变",
        )
        assertEquals(listOf(72f, 64f, 56f), PhoneBorderPageState.PANEL_TILE_LADDER_DP)
        // 字号 1.5×：(21 + 14) × 1.5 + 4 = 56.5 < 72，行高仍由缩略图决定。
        assertEquals(88f, PhoneBorderPageState.panelHeight(72f, hasCard = true, fontScale = 1.5f), 0.01f)
        assertEquals(88f, PhoneBorderPageState.panelHeight(72f, hasCard = false, fontScale = 1.5f), 0.01f)
        // 缩略图小于 44 时「更换」的触控地板才会顶替它决定行高（有边框才有那一枚）。
        assertTrue(
            PhoneBorderPageState.panelHeight(32f, hasCard = true) >
                PhoneBorderPageState.panelHeight(32f, hasCard = false),
        )
    }

    @Test
    fun theLayoutPicksTheBiggestTileThatFitsInsteadOfScrolling() {
        // 有边框：72 → 88，64 → 80，56 → 72。
        assertEquals(72f, PhoneBorderPageState.panelLayout(200f, hasCard = true).tileDp)
        assertEquals(72f, PhoneBorderPageState.panelLayout(88f, hasCard = true).tileDp)
        assertEquals(64f, PhoneBorderPageState.panelLayout(87f, hasCard = true).tileDp)
        assertEquals(56f, PhoneBorderPageState.panelLayout(79f, hasCard = true).tileDp)
        assertEquals(56f, PhoneBorderPageState.panelLayout(0f, hasCard = true).tileDp)
        assertEquals(56f, PhoneBorderPageState.panelLayout(Float.NaN, hasCard = true).tileDp)
        // 无边框走同一条阶梯（面板高度同高）：72 → 88，64 → 80，56 → 72。
        assertEquals(72f, PhoneBorderPageState.panelLayout(88f, hasCard = false).tileDp)
        assertEquals(64f, PhoneBorderPageState.panelLayout(87f, hasCard = false).tileDp)
        assertEquals(56f, PhoneBorderPageState.panelLayout(79f, hasCard = false).tileDp)
        assertEquals(
            PhoneBorderPageState.panelLayout(88f, hasCard = true).tileDp,
            PhoneBorderPageState.panelLayout(88f, hasCard = false).tileDp,
            "同一块 sheet 里两种状态必须挑到同一档缩略图",
        )
        assertEquals(
            PhoneBorderPageState.panelFloorHeight(hasCard = false),
            PhoneBorderPageState.panelLayout(0f, hasCard = false).heightDp,
            0.01f,
        )
        assertFalse(PhoneBorderPageState.panelLayout(88f, hasCard = false).hasCard)
    }

    @Test
    fun thePanelFitsTheDefaultBorderSheetOnEveryCarriedScreen() {
        // 底板高度由 `computePhoneEditorHeights` 决定（`PhoneEditorCompactSheetTest` 钉住
        // 390×844 / 360×800 / 412×915 的具体值），面板拿到的是「底板 − 把手行 − 标题行」。
        data class Case(val width: Float, val height: Float, val status: Float, val nav: Float)

        val cases = listOf(
            Case(390f, 844f, 32f, 24f),
            Case(360f, 800f, 24f, 16f),
            Case(412f, 915f, 24f, 16f),
            // 三键导航条最矮的那一档（目录里已登记的 residual）。
            Case(360f, 800f, 48f, 48f),
        )
        cases.forEach { case ->
            val available = case.height - case.status - case.nav
            val heights = computePhoneEditorHeights(
                availableHeightDp = available,
                fontScale = 1f,
                imeVisible = false,
                tool = EditorTool.Border,
                photosOpen = false,
            )
            assertFalse(heights.fullPage, "边框工具在 ${case.width}x${case.height} 应留在底板里")
            // 2026-11：边框 sheet = 16 把手 + 44 标题 + 88 面板（两种状态同高）。
            val chrome = com.framemark.native.shell.PHONE_BORDER_SHEET_GRABBER_DP +
                com.framemark.native.shell.PHONE_BORDER_SHEET_HEAD_DP
            val expectedSheet = chrome + PhoneBorderPageState.panelHeight(
                tileDp = PhoneBorderPageState.PANEL_TILE_DP,
                hasCard = false,
            )
            assertEquals(expectedSheet, heights.sheet, 0.01f, "sheet on ${case.width}x${case.height}")
            val panelAvailable = heights.sheet - chrome
            listOf(true, false).forEach { hasCard ->
                val layout = PhoneBorderPageState.panelLayout(panelAvailable, hasCard)
                assertTrue(
                    layout.heightDp <= panelAvailable + 0.01f,
                    "hasCard=$hasCard panel=${layout.heightDp} > available=$panelAvailable " +
                        "on ${case.width}x${case.height} (sheet=${heights.sheet})",
                )
                assertTrue(
                    layout.tileDp >= PhoneBorderPageState.PANEL_TILE_MIN_DP,
                    "缩略图不应小于最小一档：${layout.tileDp}",
                )
            }
        }
    }

    @Test
    fun thePanelStillFitsWithThePhotoStripOpen() {
        // 照片条只从舞台拿 64 dp；边框 sheet 与面板高度不变。
        val available = 844f - 32f - 24f
        val heights = computePhoneEditorHeights(
            availableHeightDp = available,
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = true,
        )
        val chrome = com.framemark.native.shell.PHONE_BORDER_SHEET_GRABBER_DP +
            com.framemark.native.shell.PHONE_BORDER_SHEET_HEAD_DP
        val expectedSheet = chrome + PhoneBorderPageState.panelHeight(
            tileDp = PhoneBorderPageState.PANEL_TILE_DP,
            hasCard = false,
        )
        assertEquals(expectedSheet, heights.sheet, 0.01f)
        val panelAvailable = heights.sheet - chrome
        val layout = PhoneBorderPageState.panelLayout(panelAvailable, hasCard = true)
        assertTrue(layout.heightDp <= panelAvailable + 0.01f, "layout=${layout.heightDp}")
        assertTrue(layout.tileDp >= PhoneBorderPageState.PANEL_TILE_MIN_DP)
    }

    // -- 覆盖层的分区：只有一个滚动区 -----------------------------------------

    @Test
    fun theGridIsTheOnlyScrollableBandInsideTheLibrary() {
        assertEquals(
            listOf(PhoneBorderPageBand.Grid),
            PhoneBorderPageState.LIBRARY_SCROLLABLE_BANDS,
        )
        // 标题行、分类行、搜索行全部固定：竖着滚的容器不会再套一层横着滚的列表。
        assertEquals(
            listOf(
                PhoneBorderPageBand.Header,
                PhoneBorderPageBand.Categories,
                PhoneBorderPageBand.Search,
            ),
            PhoneBorderPageState.libraryFixedBands(),
        )
        assertFalse(PhoneBorderPageBand.Categories in PhoneBorderPageState.LIBRARY_SCROLLABLE_BANDS)
        assertFalse(PhoneBorderPageBand.Search in PhoneBorderPageState.LIBRARY_SCROLLABLE_BANDS)
    }

    @Test
    fun theLibraryBandsPartitionTheOverlayFromHeaderToGrid() {
        assertEquals(
            PhoneBorderPageState.libraryFixedBands().size + PhoneBorderPageState.LIBRARY_SCROLLABLE_BANDS.size,
            PhoneBorderPageState.LIBRARY_BANDS.size,
        )
        assertEquals(
            PhoneBorderPageState.LIBRARY_BANDS.size,
            PhoneBorderPageState.LIBRARY_BANDS.distinct().size,
        )
        // 顺序就是布局顺序：标题在顶，唯一的滚动区在底；没有页脚（点卡片就应用）。
        assertEquals(PhoneBorderPageBand.Header, PhoneBorderPageState.LIBRARY_BANDS.first())
        assertEquals(PhoneBorderPageBand.Grid, PhoneBorderPageState.LIBRARY_BANDS.last())
        assertFalse(PhoneBorderPageBand.Grid in PhoneBorderPageState.libraryFixedBands())
    }

    @Test
    fun theLandingPanelHasNoScrollableBandAtAll() {
        // owner 第 5 条：面板上的缩略图、动作、说明都不用滚一下才够得到。
        assertTrue(PhoneBorderPageState.PANEL_SCROLLABLE_BANDS.isEmpty())
        assertTrue(
            PhoneBorderPageState.PANEL_SCROLLABLE_BANDS.none { it in PhoneBorderPageState.LIBRARY_BANDS },
        )
    }

    @Test
    fun theOverlayUsesTheSamePanelFractionAsTheBrandPicker() {
        // 两处覆盖层是同一套呈现（遮罩 + 圆角底部面板），高度必须同一个数字。
        assertEquals(0.88f, PHONE_BORDER_LIBRARY_PANEL_FRACTION)
        assertEquals(PHONE_LOGO_PICKER_PANEL_FRACTION, PHONE_BORDER_LIBRARY_PANEL_FRACTION)
    }

    @Test
    fun theGridIsTwoColumnsSoTheTileStaysLegible() {
        // 3 列时 128 dp 的方图在手机宽度下看不清、点不准；两列是阅读决定。
        assertEquals(2, PhoneBorderPageState.GRID_COLUMNS)
        assertTrue(PhoneBorderPageState.GRID_COLUMNS >= 2)
    }

    // -- 分类 ⇄ 页码 -----------------------------------------------------------

    @Test
    fun onePagePerCategoryInTheTaxonomyOrder() {
        // 分类不从这一页“发明”：8 页 = `LibraryIndex.CATEGORY_ORDER` 的 8 项。
        val cards = LibraryIndex.CATEGORY_ORDER.map { key -> card("c-$key", category = key) }
        val index = LibraryIndex.of(
            LibrarySnapshot(
                cards = cards,
                categories = LibraryIndex.CATEGORY_ORDER.map { key -> LibraryCategory(key, key, 1) },
                mappedPreviews = cards.size,
                unmappedPreviewIds = emptyList(),
                generatedAt = "test",
            ),
        )
        val real = index.categories()
        assertEquals(LibraryIndex.CATEGORY_ORDER.size, PhoneBorderPageState.pageCount(real))
        assertEquals(8, PhoneBorderPageState.pageCount(real))
        assertEquals(LibraryIndex.CATEGORY_ORDER, real.map { it.key })
        assertEquals(0, PhoneBorderPageState.pageForCategory(real, LibraryIndex.CATEGORY_COMMON))
        assertEquals(1, PhoneBorderPageState.pageForCategory(real, LibraryIndex.CATEGORY_VENDOR))
        assertEquals(7, PhoneBorderPageState.pageForCategory(real, "minimal"))
    }

    @Test
    fun everyPageMapsBackToItsOwnCategoryKey() {
        assertEquals(categories.size, PhoneBorderPageState.pageCount(categories))
        categories.indices.forEach { page ->
            val key = PhoneBorderPageState.categoryKeyForPage(categories, page)
            assertEquals(categories[page].key, key)
            // 页码 → 键 → 页码 是恒等：左右滑过去再滑回来落在同一页。
            assertEquals(page, PhoneBorderPageState.pageForCategory(categories, key))
        }
    }

    @Test
    fun anUnknownCategoryKeyOpensTheFirstPageInsteadOfAnIllegalPageIndex() {
        // `rememberPagerState` 的首页必须是合法页：未知 / 空键都回第一页，绝不返回 -1。
        assertEquals(0, PhoneBorderPageState.pageForCategory(categories, "not-a-category"))
        assertEquals(0, PhoneBorderPageState.pageForCategory(categories, null))
        assertEquals(0, PhoneBorderPageState.pageForCategory(emptyList(), "vendor"))
        assertEquals(1, PhoneBorderPageState.pageForCategory(categories, LibraryIndex.CATEGORY_VENDOR))
    }

    @Test
    fun anOutOfRangePageAnswersNullSoTheCallerKeepsTheCurrentCategory() {
        // 越界不是“第一页”：调用方应当保持现状，而不是替用户换分类。
        assertNull(PhoneBorderPageState.categoryKeyForPage(categories, -1))
        assertNull(PhoneBorderPageState.categoryKeyForPage(categories, categories.size))
        assertNull(PhoneBorderPageState.categoryKeyForPage(emptyList(), 0))
        assertEquals("text", PhoneBorderPageState.categoryKeyForPage(categories, 2))
    }

    // -- 选中态（覆盖层里标「当前」）-----------------------------------------

    @Test
    fun selectedStateFollowsTheAppliedBorderId() {
        assertTrue(PhoneBorderPageState.isSelected(oppo, "oppo-fin"))
        assertFalse(PhoneBorderPageState.isSelected(oppo, "vivo-fin"))
        // 没有已应用的边框时没有任何一格是“选中”。
        assertFalse(PhoneBorderPageState.isSelected(oppo, null))
    }

    @Test
    fun theSelectedBorderIsTrackedAnywhereInTheLibrary() {
        assertEquals(oppo, PhoneBorderPageState.selectedCard(groups, "oppo-fin"))
        assertEquals(white, PhoneBorderPageState.selectedCard(groups, "white-1"))
        assertNull(PhoneBorderPageState.selectedCard(groups, "sony-fin"))
        assertNull(PhoneBorderPageState.selectedCard(groups, null))
        assertNull(PhoneBorderPageState.selectedCard(emptyList(), "oppo-fin"))
    }

    // -- 网格条目下标 ---------------------------------------------------------

    @Test
    fun groupHeaderIndexCountsOneHeaderPerGroup() {
        assertEquals(2, PhoneBorderPageState.groupItems(groups[0]))
        assertEquals(3, PhoneBorderPageState.groupItems(groups[1]))
        // 第一组：标题 0 + 卡片 1；第二组标题紧跟在 1 + 张数之后。
        assertEquals(0, PhoneBorderPageState.groupItemIndex(groups, groups[0].key))
        assertEquals(1 + groups[0].count, PhoneBorderPageState.groupItemIndex(groups, groups[1].key))
        assertEquals(2, PhoneBorderPageState.groupItemIndex(groups, groups[1].key))
    }

    @Test
    fun aLeadingItemShiftsEveryGroupByItsOwnCount() {
        // 厂商页最前面是「系列目录」入口，分组标题因此整体后移一位。
        assertEquals(1, PhoneBorderPageState.groupItemIndex(groups, groups[0].key, leadingItems = 1))
        assertEquals(3, PhoneBorderPageState.groupItemIndex(groups, groups[1].key, leadingItems = 1))
    }

    @Test
    fun anUnknownGroupAnswersMinusOneBecauseZeroIsARealDestination() {
        // 0 是真实目标（第一组标题），所以“找不到”只能是 -1。
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, "template|vendor|不存在"))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, null))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(emptyList(), groups[0].key))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, groups[0].key, leadingItems = -1))
        assertEquals(0, PhoneBorderPageState.groupItemIndex(groups, groups[0].key))
    }

    // -- 系列目录 → 分组定位（不是搜索）----------------------------------------

    @Test
    fun a_series_pick_lands_on_the_real_group_key_not_on_a_search_word() {
        // 目录给的是厂商页里的真实分组键；vivo 的桶键是 no-formal-family，不是显示名。
        val key = phoneFamilyGroupKey("brand:vivo", vivoFamily)
        assertEquals(LibraryIndex.vendorGroupKey("brand:vivo", LibraryIndex.NO_FORMAL_FAMILY), key)
        assertEquals("vendor|brand:vivo|no-formal-family", key)

        val landing = assertNotNull(PhoneBorderPageState.seriesLanding(categories, key))
        assertEquals(LibraryIndex.CATEGORY_VENDOR, landing.categoryKey)
        assertEquals(1, landing.page, "厂商是分类行里的第二页")
        assertEquals(key, landing.groupKey)

        // 厂商页最前面是「系列目录」入口：第 1 项是 vivo 分组标题，第一张卡紧跟其后。
        assertEquals(
            1,
            PhoneBorderPageState.seriesScrollTarget(vendorPageGroups, landing.groupKey, leadingItems = 1),
        )
        // 系列跳转不把词写进搜索框；已输入的词也会被清掉（跳转是定位，不是筛选）。
        assertTrue(PhoneBorderPageState.SERIES_CLEARS_SEARCH)
    }

    @Test
    fun a_series_living_in_another_category_switches_to_that_page_first() {
        // 目录现在只列厂商系列，但键里带着分类；别的类别若有了系列，落地先切页再定位。
        val key = LibraryIndex.templateGroupKey("white", "细")
        val landing = assertNotNull(PhoneBorderPageState.seriesLanding(categories, key))
        assertEquals("white", landing.categoryKey)
        assertEquals(3, landing.page)
        assertEquals(key, landing.groupKey)

        // 白边页：第一组「留白」1 张卡 = 标题 + 卡片（2 项），「细」组的标题因此是第 2 项。
        val whiteGroups = listOf(
            group(LibraryIndex.templateGroupKey("white", "留白"), listOf(white)),
            group(key, listOf(white)),
        )
        assertEquals(2, PhoneBorderPageState.seriesScrollTarget(whiteGroups, landing.groupKey))
    }

    @Test
    fun an_unrecognisable_series_key_never_falls_back_to_searching_a_name() {
        // 空键 / 认不出的键 / 分类行里没有的分类：不落地，更不拼搜索词。
        assertNull(PhoneBorderPageState.seriesLanding(categories, null))
        assertNull(PhoneBorderPageState.seriesLanding(categories, ""))
        assertNull(PhoneBorderPageState.seriesLanding(categories, "   "))
        assertNull(PhoneBorderPageState.seriesLanding(categories, vivoFamily.label))
        assertNull(PhoneBorderPageState.seriesLanding(categories, "brand:vivo"))
        assertNull(PhoneBorderPageState.seriesLanding(categories, "template|video|短剧"))
        assertNull(PhoneBorderPageState.seriesLanding(emptyList(), LibraryIndex.vendorGroupKey("brand:vivo", "x")))
        // 目标分组不在这页的网格里时，滚动下标是 -1：调用方保留 pending，等目标分类页。
        assertEquals(
            -1,
            PhoneBorderPageState.seriesScrollTarget(groups, "vendor|brand:vivo|no-formal-family", leadingItems = 1),
        )
    }

    // -- 空态 ----------------------------------------------------------------

    @Test
    fun theEmptyNoteSaysWhetherItIsTheCategoryOrTheSearch() {
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote(""))
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote("   "))
        assertEquals("没有匹配「OPPO」的边框。", PhoneBorderPageState.emptyNote(" OPPO "))
        assertNotNull(PhoneBorderPageState.EMPTY_CATEGORY_NOTE)
    }
}
