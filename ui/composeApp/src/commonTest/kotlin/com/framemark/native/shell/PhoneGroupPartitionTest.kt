package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneTestControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺陷 2 的验收（2026-11-11）：**每个组 tab 2..3 行**。
 *
 * 为什么会空一大片：固定画框的面板高度是 `clamp(0.34 × 窗口, 280, 330)`（411×905 dp
 * 上是 307.7 dp），参数区净高 219.7 dp。旧分组里 水印 的「图层」只有图层卡片一行
 * （56 dp），下面 ~150 dp 什么都没有；样式 的「底色」「版式」、文字 的「选择」「大小」、
 * Logo 的「动作」也都是单行组。
 *
 * 这一份钉住三件事：
 *  1. 四个工具的每一组都是 2..3 行（唯一的例外是「这一页本来就有 7 条声明行」的文字页，
 *     它只有一组 —— 一组就是一个会滚动的尾巴，面板高度仍然不变）；
 *  2. 每组在 411×905 dp 的参数区里完整露出（厂商卡的「位置」组见下面那条显式例外）；
 *  3. 组在一起**恰好覆盖**这一页的每一个控件 / 合成行一次（重排不新增、不丢控件）。
 *
 * 分组的数字都写在 [thePartitionsAreTheDecidedOnes] 里；旧的分组是
 * 样式 `[底色｜版式｜留白]`、文字 `[选择｜大小]`、Logo `[调整｜动作]`、
 * 水印 `[图层｜边距｜外观]`。Logo 现在是 `[字标｜调整]`（先选后调，2026-11 复测）。
 */
class PhoneGroupPartitionTest {

    private val panel = phonePanelHeightDp(905f)

    /** 四个工具在 411×905 dp 上的标准形状（测试卡的四页控件同形）。 */
    private fun specs(): List<Pair<String, PhonePageSpec>> = listOf(
        "样式" to PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls()),
        "样式 + 导出尺寸" to PhonePageSpec(
            EditorTool.Params,
            PhoneTestControls.styleControls(),
            hasExportSize = true,
        ),
        "水印" to PhonePageSpec(EditorTool.Watermark, emptyList()),
        "文字（有渲染器报告）" to PhonePageSpec(
            EditorTool.Text,
            PhoneTestControls.textControls(),
            hasTextReport = true,
        ),
        "Logo" to PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls()),
    )

    // -----------------------------------------------------------------------
    // 1. 每组 2..3 行
    // -----------------------------------------------------------------------

    @Test
    fun everyGroupHasTwoOrThreeRowsAtTheMeasuredWindow() {
        assertEquals(307.7f, panel, 0.01f, "411×905 dp 的固定面板")
        specs().forEach { (name, spec) ->
            val groups = phonePanelGroups(spec)
            assertTrue(groups.isNotEmpty(), "$name: 一页至少一组")
            groups.forEach { group ->
                assertTrue(
                    group.rows.size in PHONE_GROUP_MIN_ROWS..PHONE_GROUP_MAX_ROWS,
                    "$name · ${group.label}: ${group.rows.size} 行（规则是 ${PHONE_GROUP_MIN_ROWS}..${PHONE_GROUP_MAX_ROWS}）",
                )
            }
        }
    }

    @Test
    fun theSingleScrollableTailIsTheStaticTextPageWithMoreThanThreeControls() {
        // 没有渲染器报告时，文字页把 5 个显示开关 + 自定义行 + 字号画成声明行：这一页
        // 本来就有 7 个控件，拆成 2..3 行的多个 tab 反而要编号（「显示 1 / 显示 2」），
        // 所以它只有一组：一个会滚动的尾巴。这是规则里唯一允许 > 3 行的形状。
        val static = PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls())
        val groups = phonePanelGroups(static)
        assertEquals(1, groups.size, "静态文字行只有一组")
        assertEquals("文字", groups.single().label)
        assertEquals(7, groups.single().rows.size)
        assertTrue(groups.single().rows.size > PHONE_GROUP_MAX_ROWS)
        // 除了这一页，没有别的组超过三行。
        specs().forEach { (name, spec) ->
            phonePanelGroups(spec).forEach { group ->
                assertTrue(group.rows.size <= PHONE_GROUP_MAX_ROWS, "$name · ${group.label}: ${group.rows.size} 行")
            }
        }
    }

    @Test
    fun aCardWithASingleControlKeepsItsSingleGroup() {
        // 轻量卡：样式页只有底色、文字页只有字号、Logo 页只有品牌。一张卡真的只有一个
        // 控件时，编不出第二个行来 —— 这一组保持一行，但它是页面上唯一的一组。
        val light = listOf(
            PhonePageSpec(EditorTool.Params, PhoneTestControls.lightStyleControls()),
            PhonePageSpec(EditorTool.Text, PhoneTestControls.lightTextControls()),
            PhonePageSpec(EditorTool.Logo, PhoneTestControls.lightLogoControls()),
        )
        light.forEach { spec ->
            val groups = phonePanelGroups(spec)
            assertEquals(1, groups.size, "${spec.tool}: 一个控件的卡只有一组")
            assertEquals(1, groups.single().rows.size, "${spec.tool}: 它是那一行的唯一去处")
        }
        // 轻量卡 + 导出尺寸：两行（底色 + 导出尺寸）也不该分成两个单行 tab。
        assertEquals(
            listOf("外观"),
            phoneGroupTabLabels(
                PhonePageSpec(
                    EditorTool.Params,
                    PhoneTestControls.lightStyleControls(),
                    hasExportSize = true,
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 2. 每组在量到的窗口里放得下
    // -----------------------------------------------------------------------

    @Test
    fun everyGroupFitsTheMeasuredParameterArea() {
        specs().forEach { (name, spec) ->
            phonePanelGroups(spec).forEach { group ->
                val visible = phoneVisibleRowCount(panel, group.rows)
                assertTrue(
                    visible >= group.rows.size,
                    "$name · ${group.label}: ${group.rows.size} 行只露出 $visible 行（参数区 " +
                        "${phoneParameterAreaHeightDp(panel)} dp）",
                )
            }
        }
    }

    @Test
    fun theVendorAnchorGroupIsTheOnlySlightlyOverflowingBlock() {
        // 厂商卡的「位置」组是同一个控件的两块：132 dp 的九宫格 + 按规格 / 说明。
        // 拆开就必然留一个单行的 tab，所以它保持 2 行；内容 208 dp 比参数区 203.7 dp
        // 多 ~4 dp —— 滚一下就到底，不把面板撑高。
        val anchor = PhoneTestControls.control(
            id = "params:stockAnchor",
            param = "params.stockAnchor",
            label = "水印位置",
            type = com.framemark.native.inspector.ControlType.Select,
            kind = "anchorGrid",
            initial = com.framemark.native.inspector.ControlValue.Str("leftTop"),
            options = listOf(
                "leftTop", "centerTop", "rightTop", "leftCenter", "center",
                "rightCenter", "leftBottom", "centerBottom", "rightBottom",
            ).map { com.framemark.native.inspector.ControlOption(it, it) },
        )
        val groups = phonePanelGroups(
            PhonePageSpec(tool = EditorTool.Params, controls = listOf(anchor), isIr = true, hasExportSize = true),
        )
        assertEquals(listOf("位置", "规格"), groups.map { it.label })
        val position = groups.first()
        assertEquals(2, position.rows.size)
        val content = phoneGroupHeightDp(position.rows)
        val available = phoneParameterAreaHeightDp(panel) - 2f * PHONE_PARAM_AREA_INSET_DP
        assertTrue(content > available, "这一组本来就会滚一点：$content > $available")
        assertTrue(content - available <= 8f, "但只多几 dp：${content - available}")
    }

    // -----------------------------------------------------------------------
    // 3. 覆盖：重排不新增、不丢控件
    // -----------------------------------------------------------------------

    @Test
    fun everyControlAndCompositeRowIsCoveredExactlyOnce() {
        // 有渲染器报告的文字页由选择器取代声明行（同一个面板规则，见下一条断言），
        // 这里比较的是「声明出来的控件 → 组里的行」。
        specs().filterNot { (_, spec) -> spec.hasTextReport }.forEach { (name, spec) ->
            val rows = phonePanelRows(spec)
            assertEquals(rows.size, rows.map { it.key }.distinct().size, "$name: 行键重复")
            val declared = spec.controls
                .filter { it.label.isNotBlank() && !it.hidden }
                .map { it.key }
            val rowControls = rows.mapNotNull { it.control?.key }
            assertEquals(
                declared.sorted(),
                rowControls.sorted(),
                "$name: 每个声明控件必须在且只在一个组里",
            )
            // 组与组之间没有共享的行。
            val groups = phonePanelGroups(spec)
            val perGroup = groups.flatMap { group -> group.rows.map { it.key } }
            assertEquals(rows.size, perGroup.size, "$name: 组的行数之和必须等于这一页的行数")
        }
        // 有报告的文字页：文本框选择器 + 字号，恰好两行、两行都在「文字」组里。
        val reported = PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true)
        val reportedRows = phonePanelRows(reported)
        assertEquals(listOf(PhoneRowKeys.TEXT_SLOTS, "card::text:fontScale"), reportedRows.map { it.key })
        // 水印的六条合成行恰好一次（图层卡片 + 两条边距 + 宽度 / 不透明度 / 旋转）。
        val wmRows = phonePanelRows(PhonePageSpec(EditorTool.Watermark, emptyList()))
        assertEquals(
            setOf(
                PhoneRowKeys.WATERMARK_LAYER,
                PhoneRowKeys.WATERMARK_MARGIN_X,
                PhoneRowKeys.WATERMARK_MARGIN_Y,
                PhoneRowKeys.WATERMARK_WIDTH,
                PhoneRowKeys.WATERMARK_OPACITY,
                PhoneRowKeys.WATERMARK_ROTATION,
            ),
            wmRows.map { it.key }.toSet(),
        )
    }

    // -----------------------------------------------------------------------
    // 4. 决定下来的分组（数字就是验收报告里的那张表）
    // -----------------------------------------------------------------------

    @Test
    fun thePartitionsAreTheDecidedOnes() {
        fun table(spec: PhonePageSpec): Map<String, List<String>> =
            phonePanelGroups(spec).associate { group -> group.label to group.rows.map { it.key } }

        // 样式：底色 + 版式 合成「外观」（2 行）；留白 = 页脚自适应 + 底部留白（2 行）。
        assertEquals(
            mapOf(
                "外观" to listOf("card::theme.theme", "card::params:footerMode"),
                "留白" to listOf("card::params:footerAuto", "card::params:footerPct"),
            ),
            table(PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls())),
        )
        // 样式 + 导出尺寸：导出尺寸并进「留白」凑成 3 行（不是单行 tab）。
        assertEquals(
            mapOf(
                "外观" to listOf("card::theme.theme", "card::params:footerMode"),
                "留白" to listOf("card::params:footerAuto", "card::params:footerPct", PhoneRowKeys.EXPORT_SIZE),
            ),
            table(PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls(), hasExportSize = true)),
        )
        // 水印：图层卡片 + 距左 + 距上 = 图层（3 行）；宽度 + 不透明度 + 旋转 = 外观（3 行）。
        assertEquals(
            mapOf(
                "图层" to listOf(
                    PhoneRowKeys.WATERMARK_LAYER,
                    PhoneRowKeys.WATERMARK_MARGIN_X,
                    PhoneRowKeys.WATERMARK_MARGIN_Y,
                ),
                "外观" to listOf(
                    PhoneRowKeys.WATERMARK_WIDTH,
                    PhoneRowKeys.WATERMARK_OPACITY,
                    PhoneRowKeys.WATERMARK_ROTATION,
                ),
            ),
            table(PhonePageSpec(EditorTool.Watermark, emptyList())),
        )
        // 文字（有报告）：选择器 + 字号 = 一组「文字」（2 行）。
        assertEquals(
            mapOf(
                "文字" to listOf(PhoneRowKeys.TEXT_SLOTS, "card::text:fontScale"),
            ),
            table(PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true)),
        )
        // Logo（2026-11 复测：先选后调）：品牌字标 + 显示 Logo = 字标（2 行）；
        // 位置 + 大小 + 水平位置 = 调整（3 行）。品牌那一行自己就是「当前字标 + 三个
        // 动作」的整块内容（`PhoneRowKind.LogoActions`），所以它永远在第一页最上面。
        assertEquals(
            mapOf(
                "字标" to listOf("card::design.logo", "card::params:showLogo"),
                "调整" to listOf("card::params:logoPos", "card::params:logoScale", "card::params:logoCenterX"),
            ),
            table(PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls())),
        )
        // 水印空态：只有上传入口一组（空态，不是参数分组）。
        assertEquals(
            mapOf("图层" to listOf(PhoneRowKeys.WATERMARK_EMPTY)),
            table(PhonePageSpec(EditorTool.Watermark, emptyList(), hasWatermarkLayer = false)),
        )
        // 边框工具没有组（落地卡片面板）。
        assertTrue(phoneGroupTabLabels(PhonePageSpec(EditorTool.Border, emptyList())).isEmpty())
    }

    @Test
    fun theTabRowStillFitsOnThePhone() {
        // 分组变了，tab 行也要放得下：全部工具在 411 dp（和 360 dp）都不需要横滑。
        specs().forEach { (name, spec) ->
            val labels = phoneGroupTabLabels(spec)
            assertTrue(labels.isNotEmpty(), name)
            assertTrue(!phoneTabRowFit(411f, labels).needsScroll, "$name @411: $labels")
            assertTrue(!phoneTabRowFit(360f, labels).needsScroll, "$name @360: $labels")
        }
    }

    @Test
    fun theGroupIdsAreUniqueAndStable() {
        // 组 id 只在这一次渲染里用来记住「点了哪个 tab」；重复 id 会让两个 tab 一起亮。
        specs().plus(
            "厂商卡" to PhonePageSpec(EditorTool.Params, emptyList(), isIr = true, hasExportSize = true),
        ).forEach { (name, spec) ->
            val groups = phonePanelGroups(spec)
            assertEquals(groups.size, groups.map { it.id }.distinct().size, "$name: 组 id 重复")
            assertEquals(groups.size, groups.map { it.label }.distinct().size, "$name: tab 文案重复")
        }
    }
}
