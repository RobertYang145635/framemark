package com.framemark.native.shell.phone

import com.framemark.native.shell.EditorTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 手机底板页面状态机的纯契约（rc14 P3-1）。
 *
 * 设备复现路径（历史）：打开 hand-audited 边框（纯白 01）→ 点 Logo 工具 → 全高
 * `参数 · Logo` 页 → 一次「返回预览」曾经落到 **整页边框库**，第二次点击才回舞台。
 *
 * 根因不在「还原成哪个工具」：整页边框库本来由「工具从 A 变成 边框」这个差值推导，
 * 而关掉全高 Logo 页要把工具还原成进入前的 边框，那次**还原**就被差值当成了用户
 * 主动切到 边框。[PhoneSheetPagePolicy] 不保存工具（工具是每一拍的入参），所以还原
 * 在它眼里根本不是一个开页事件；只有用户自己点，才可能开页。
 *
 * 之后 Logo 连整高页都不是了：它和 样式 / 文字 / 水印 一样是底板里的一页，参数和
 * 舞台同时在场（改一个参数立刻在画布上看见），所以「返回」只是收起底板，不存在
 * 「还原工具」这一步，页状态也就没有可以被抬回来的入口。
 */
class PhoneSheetPagePolicyTest {

    private val nonBorderTools =
        listOf(EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark)

    // -----------------------------------------------------------------------
    // Logo 是底板页：点它不开整页边框库，「返回」回舞台
    // -----------------------------------------------------------------------

    @Test
    fun aLogoPickIsASheetPageAndNeverOpensTheBorderLibrary() {
        // Logo 是普通底板页：舞台留在参数页上面。
        assertEquals(
            PhoneSheetPageKind.Sheet,
            PhoneSheetPagePolicy.pageKindForTool(EditorTool.Logo),
            "Logo 必须画在底板里，不能走整页分支",
        )
        assertFalse(
            PhoneSheetPagePolicy.usesFullHeightPage(
                tool = EditorTool.Logo,
                constrainedWindow = false,
                fullPageDismissed = false,
            ),
            "正常窗口里点 Logo 不能进整页参数页：舞台必须留在上面",
        )

        // 点 Logo 不打开整页边框库：从别的工具切过来不开……
        assertFalse(
            PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Params, EditorTool.Logo, pageOpen = false),
            "Logo 不是 边框：点它不开整页边框库",
        )
        // ……哪怕整页边框库正开着（底板里的「换一个边框」），点 Logo 也会把它关掉：
        // 底板上就是 Logo 页，背后没有第二层可以「返回」到的页。
        assertFalse(
            PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Border, EditorTool.Logo, pageOpen = true),
            "点 Logo 时整页边框库必须关掉：Logo 页背后没有第二层",
        )
        // 「返回」= 收起底板（sheetLevel → 0），工具停在 Logo。没有整高页就没有
        // 「还原工具」这条路径；哪怕走整高页的返回规则，也绝不把任何页抬回来。
        assertFalse(
            PhoneSheetPagePolicy.pageAfterFullHeightPageReturn(),
            "返回之后底板从默认页开始，不能把边框库页抬回来",
        )
        // 回到舞台后再点当前 Logo 工具，也不会把任何一整页重开。
        assertFalse(
            PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Logo, EditorTool.Logo, pageOpen = false),
            "点当前工具是空操作：不会重开任何页",
        )
        // 整高页在它仅剩的场合（矮窗口的回退页）仍然不在背后留页面。
        assertFalse(PhoneSheetPagePolicy.pageUnderFullHeightPage())
    }

    // -----------------------------------------------------------------------
    // 每个工具的参数页都在底板里
    // -----------------------------------------------------------------------

    @Test
    fun everyToolRendersItsParameterPageInTheSheet() {
        assertEquals(
            EditorTool.entries.filter { it != EditorTool.Border },
            nonBorderTools,
            "这个测试要覆盖工具条上除 边框 以外的每一个工具",
        )
        EditorTool.entries.forEach { tool ->
            assertEquals(
                PhoneSheetPageKind.Sheet,
                PhoneSheetPagePolicy.pageKindForTool(tool),
                "tool=$tool 的参数页必须在底板里：整页会盖住舞台，参数改了也看不见",
            )
            assertFalse(
                PhoneSheetPagePolicy.usesFullHeightPage(
                    tool = tool,
                    constrainedWindow = false,
                    fullPageDismissed = false,
                ),
                "tool=$tool 在正常窗口里不该用整页参数页",
            )
        }
    }

    @Test
    fun theConstrainedWindowFallbackIsNotToolSpecific() {
        // 窗口太矮 / 字号太大时，每个工具（包括 Logo）都走同一个回退页；点过
        // 「返回预览」之后同一个工具回到底板。这是窗口规则，不是哪个工具的例外。
        EditorTool.entries.forEach { tool ->
            assertTrue(
                PhoneSheetPagePolicy.usesFullHeightPage(
                    tool = tool,
                    constrainedWindow = true,
                    fullPageDismissed = false,
                ),
                "tool=$tool 在矮窗口里应走整页回退页",
            )
            assertFalse(
                PhoneSheetPagePolicy.usesFullHeightPage(
                    tool = tool,
                    constrainedWindow = true,
                    fullPageDismissed = true,
                ),
                "tool=$tool 点过「返回预览」后必须回到底板",
            )
        }
    }

    // -----------------------------------------------------------------------
    // 开页只有「用户自己点」这一个来源
    // -----------------------------------------------------------------------

    @Test
    fun onlyAPickFromAnotherToolOpensTheFullPageLibrary() {
        nonBorderTools.forEach { tool ->
            assertTrue(
                PhoneSheetPagePolicy.pageAfterToolPick(tool, EditorTool.Border, pageOpen = false),
                "$tool → 边框 是用户主动切工具，应当打开整页边框库",
            )
        }
        // 启动工具本来就是 边框：那一刻不该用整页盖住照片，底板里的入口仍然在。
        assertFalse(
            PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Border, EditorTool.Border, pageOpen = false),
            "停在 边框 上不算「切到 边框」",
        )
        // 点当前工具是空操作：已经开着的页面保持开着。
        assertTrue(
            PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Border, EditorTool.Border, pageOpen = true),
            "点当前工具不改变现状",
        )
    }

    @Test
    fun pickingAnotherToolClosesTheFullPageLibrary() {
        nonBorderTools.forEach { tool ->
            assertFalse(
                PhoneSheetPagePolicy.pageAfterToolPick(EditorTool.Border, tool, pageOpen = true),
                "切到 $tool 时整页边框库必须关掉：它只属于 边框",
            )
        }
    }
}
