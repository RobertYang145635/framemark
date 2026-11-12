package com.framemark.native.shell.phone

import com.framemark.native.inspector.PHONE_LOGO_PICKER_PANEL_FRACTION
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.PHONE_FRAME_CARD_SIZE_DP
import com.framemark.native.shell.computePhoneEditorHeights
import com.framemark.native.shell.phoneCompactSheetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机端边框工具的纯状态契约（无 Compose、无资源、无持久化）：
 *
 *  1. **落地态是面板，不是列表**——边框页本身不是库，库只能由面板上的动作打开；
 *  2. 面板的文案（当前边框 / 用一个 / 换一个）与**竖向预算**（不滚动的前提）；
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
        // 唯一开库的入口：面板上的「选择一个边框 / 换一个边框」。
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
    fun thePanelActionSaysChooseOrChangeAndTheCurrentBorderIsNeverBlank() {
        // 没选过：主按钮是「选择一个边框」，当前边框写「未选边框」。
        assertEquals("选择一个边框", PhoneBorderPageState.actionLabel(null))
        assertEquals(PhoneBorderPageState.CHOOSE_LABEL, PhoneBorderPageState.actionLabel(null))
        assertEquals("未选边框", PhoneBorderPageState.currentName(null))
        assertEquals(PhoneBorderPageState.NO_CURRENT_LABEL, PhoneBorderPageState.currentName(null))
        // 选过：主按钮变成「换一个边框」。
        assertEquals("换一个边框", PhoneBorderPageState.actionLabel(oppo))
        assertEquals(PhoneBorderPageState.CHANGE_LABEL, PhoneBorderPageState.actionLabel(oppo))
        assertEquals("OPPO 经典", PhoneBorderPageState.currentName(oppo))
        // 两个文案不能是同一句：用户要能一眼看出「这是第一次选」还是「换掉现在这张」。
        assertTrue(PhoneBorderPageState.CHOOSE_LABEL != PhoneBorderPageState.CHANGE_LABEL)
    }

    @Test
    fun thePanelSecondLineExplainsWhatHappensWithoutABorderAndNamesTheCardWithOne() {
        val empty = PhoneBorderPageState.currentDescription(card = null, description = null)
        assertTrue(empty.contains("选一个边框"), "empty=$empty")
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
    fun thePanelNoteStatesHowBigTheLibraryIsFromTheCatalogItself() {
        // 8 个分类 / 180 张卡是目录给的数字，不写死在文案里。
        val note = PhoneBorderPageState.libraryNote(categoryCount = 8, cardCount = 180)
        assertTrue(note.contains("8"), "note=$note")
        assertTrue(note.contains("180"), "note=$note")
        assertEquals(note, PhoneBorderPageState.libraryNote(8, 180), "说明必须稳定，不能每次拼出不同的话")
    }

    // -- 面板竖向预算：不留滚动容器 -------------------------------------------

    @Test
    fun thePanelHeightIsTheSumOfItsBandsAndNothingElse() {
        // 8（上内边距）+ 128（缩略图：库里那张卡自己的尺寸）+ 8（到按钮的间距）+
        // 48（主按钮）+ 4 + 14（说明）+ 8（下内边距）= 218 dp。
        assertEquals(218f, PhoneBorderPageState.panelHeight(tileDp = PHONE_FRAME_CARD_SIZE_DP), 0.01f)
        // 阶梯中段：96 dp 的缩略图是 186 dp。
        assertEquals(186f, PhoneBorderPageState.panelHeight(tileDp = 96f), 0.01f)
        // 说明行拿掉就是 120 dp：这是面板的绝对下限（缩略图最小一档 + 主按钮）。
        assertEquals(120f, PhoneBorderPageState.panelHeight(tileDp = 48f, showsNote = false), 0.01f)
        assertEquals(138f, PhoneBorderPageState.panelHeight(tileDp = 48f, showsNote = true), 0.01f)
        // 字号放大时两行文字（21 sp 名称 + 14 sp 说明）也可能高过缩略图，预算跟着长大，
        // 缩略图不缩文字：(21 + 14) × 1.5 + 4 = 56.5 dp 的那一行。
        val scaled = PhoneBorderPageState.panelHeight(tileDp = 48f, showsNote = false, fontScale = 1.5f)
        assertEquals(8f + 56.5f + 8f + 48f + 8f, scaled, 0.01f)
        // 阶梯的第一档就是库里的卡片尺寸：面板上的「当前边框」和网格里的同一张卡同大。
        assertEquals(PHONE_FRAME_CARD_SIZE_DP, PhoneBorderPageState.PANEL_TILE_LADDER_DP.first())
        assertEquals(
            PhoneBorderPageState.PANEL_TILE_LADDER_DP.size,
            PhoneBorderPageState.PANEL_TILE_LADDER_DP.distinct().size,
        )
    }

    @Test
    fun theLayoutPicksTheBiggestTileThatFitsInsteadOfScrolling() {
        // 218 装得下 128；再矮一档降到 112（202），说明行保留。
        assertEquals(128f, PhoneBorderPageState.panelLayout(236f).tileDp)
        assertTrue(PhoneBorderPageState.panelLayout(236f).showsNote)
        assertEquals(218f, PhoneBorderPageState.panelLayout(236f).heightDp, 0.01f)
        assertEquals(128f, PhoneBorderPageState.panelLayout(218f).tileDp)
        assertEquals(112f, PhoneBorderPageState.panelLayout(217f).tileDp)
        assertEquals(112f, PhoneBorderPageState.panelLayout(202f).tileDp)
        val tighter = PhoneBorderPageState.panelLayout(180f)
        assertEquals(84f, tighter.tileDp)
        assertTrue(tighter.showsNote, "说明行还能放下时不该先丢掉它")
        assertEquals(174f, tighter.heightDp, 0.01f)
        // 都放不下：先丢说明行，缩略图退到最小一档——仍然不是滚动容器。
        val floor = PhoneBorderPageState.panelLayout(130f)
        assertEquals(48f, floor.tileDp)
        assertFalse(floor.showsNote)
        assertEquals(PhoneBorderPageState.panelFloorHeight(), floor.heightDp, 0.01f)
        assertEquals(120f, floor.heightDp, 0.01f)
        // 阶梯是单调的：窗口越矮，图不会变大。
        PhoneBorderPageState.PANEL_TILE_LADDER_DP.zipWithNext().forEach { (roomy, tight) ->
            assertTrue(roomy > tight, "ladder=$roomy -> $tight")
        }
        // 高度没有约束时（非有限值）按 0 处理，取最小一档：与
        // `phoneCompactSheetMetrics` 对非有限入参的约定一致，绝不返回一个「看起来
        // 很宽裕」的假数字。
        assertEquals(48f, PhoneBorderPageState.panelLayout(Float.POSITIVE_INFINITY).tileDp)
        assertEquals(48f, PhoneBorderPageState.panelLayout(Float.NaN).tileDp)
        assertEquals(48f, PhoneBorderPageState.panelLayout(0f).tileDp)
    }

    @Test
    fun thePanelFitsTheDefaultBorderSheetOnEveryCarriedScreen() {
        // 底板高度由 `computePhoneEditorHeights` 决定（`PhoneEditorCompactSheetTest` 钉住
        // 390×844 / 360×800 / 412×915 的具体值），面板拿到的是「底板 − 把手行 − 标题行」。
        // 这里把整条链算一遍：面板必须在这块高度里放得下，否则就会裁掉按钮。
        data class Case(
            val width: Float,
            val height: Float,
            val status: Float,
            val nav: Float,
            /** 这块面板应该拿到的缩略图边长。 */
            val tile: Float,
        )

        val cases = listOf(
            Case(390f, 844f, 32f, 24f, tile = 128f),
            Case(360f, 800f, 24f, 16f, tile = 128f),
            Case(412f, 915f, 24f, 16f, tile = 128f),
            // 三键导航条最矮的那一档（目录里已登记的 residual）：面板降一档。
            Case(360f, 800f, 48f, 48f, tile = 112f),
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
            val metrics = phoneCompactSheetMetrics(heights.sheet)
            val panelAvailable = heights.sheet - metrics.grabber - metrics.head
            val layout = PhoneBorderPageState.panelLayout(panelAvailable)
            assertTrue(
                layout.heightDp <= panelAvailable + 0.01f,
                "panel=${layout.heightDp} > available=$panelAvailable on " +
                    "${case.width}x${case.height} (sheet=${heights.sheet})",
            )
            // 常规窗口里缩略图是最大一档（= 库里那张卡的尺寸），不是被挤小的那一档。
            assertEquals(
                case.tile,
                layout.tileDp,
                "screen=${case.width}x${case.height} available=$panelAvailable",
            )
            assertTrue(layout.showsNote, "屏幕 ${case.width}x${case.height} 上说明行应当还在")
            // 面板占底板的比例落在 owner 给的 34–38 % 区间里（这里是整块面板，含说明行）。
            assertTrue(heights.sheet / available in 0.33f..0.39f, "sheet%=${heights.sheet / available}")
        }
    }

    @Test
    fun thePanelStillFitsWithThePhotoStripOpenAndDropsTheNoteOnlyWhenItHasTo() {
        // 照片条打开会从底板里拿走 64 dp（`PhoneEditorCompactSheetTest` 里那条 residual）：
        // 面板这时候降一档缩略图，说明行仍在。
        val available = 844f - 32f - 24f
        val heights = computePhoneEditorHeights(
            availableHeightDp = available,
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = true,
        )
        assertEquals(216f, heights.sheet, 0.01f)
        val metrics = phoneCompactSheetMetrics(heights.sheet)
        val panelAvailable = heights.sheet - metrics.grabber - metrics.head
        val layout = PhoneBorderPageState.panelLayout(panelAvailable)
        assertEquals(84f, layout.tileDp)
        assertTrue(layout.showsNote)
        assertTrue(layout.heightDp <= panelAvailable + 0.01f, "layout=${layout.heightDp}")
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

    // -- 空态 ----------------------------------------------------------------

    @Test
    fun theEmptyNoteSaysWhetherItIsTheCategoryOrTheSearch() {
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote(""))
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote("   "))
        assertEquals("没有匹配「OPPO」的边框。", PhoneBorderPageState.emptyNote(" OPPO "))
        assertNotNull(PhoneBorderPageState.EMPTY_CATEGORY_NOTE)
    }
}
