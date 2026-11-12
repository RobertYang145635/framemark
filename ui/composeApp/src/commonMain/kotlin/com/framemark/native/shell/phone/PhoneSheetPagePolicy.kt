package com.framemark.native.shell.phone

import com.framemark.native.shell.EditorTool

/**
 * 手机底板「整页边框库有没有盖在底板上」的纯决策（无 Compose、无资源、无持久化；
 * `PhoneSheetPagePolicyTest` 直接测这一层）。
 *
 * 这一层只回答两个问题：
 *  1. 参数面板用**底板里的页**还是**整页参数页**；
 *  2. `borderPageOpen` 该是开还是关。
 *
 * 工具本身归外壳所有（[EditorTool] 只是入参），所以本对象不保存「当前工具」——抄
 * 一份工具状态，它和外壳迟早会漂移，而 rc14 P3-1 要修的正是「谁改了工具」被当成了
 * 「用户想要哪一页」。
 *
 * 那次 bug：整页边框库曾经由「工具从 A 变成 边框」这个**差值**推导。关掉全高 Logo
 * 页要把工具还原成进入前的 边框，那次还原被差值当成了「用户主动切到 边框」，于是
 * 边框库页在用户背后重新打开——一次「返回预览」先落到边框库页，第二次才回到舞台。
 *
 * 现在的规则：
 *  1. **只有用户自己点**才开页：工具条上的 边框（[pageAfterToolPick]）、底板里的
 *     「换一个边框」、无边框时的「去选边框」。还原不是点，永远不开页。
 *  2. **整页参数页只有窗口能决定**（[usesFullHeightPage]）：窗口太矮 / 字号太大
 *     时预算给出回退页。工具不参与——[pageKindForTool] 对每个工具都返回
 *     [PhoneSheetPageKind.Sheet]，包括 Logo。Logo 曾经被特判成整页，于是「调 Logo
 *     参数」时舞台被整页盖住，用户改了也看不见画布；现在它和 样式 / 文字 / 水印
 *     一样是底板里的一页，舞台留在上面。
 *  3. **全高回退页不留页面**：它接管底板时把底板里的页关掉
 *     （[pageUnderFullHeightPage]），把屏幕交还底板时底板从自己的默认页开始
 *     （[pageAfterFullHeightPageReturn]）。所以「返回预览」（以及同一条路径上的
 *     系统返回）永远一拍回舞台。
 */
internal object PhoneSheetPagePolicy {

    /**
     * 用户在工具条上点了 [next] 之后的 `borderPageOpen`；[current] 是那一拍的工具，
     * [pageOpen] 是那一拍整页边框库的开关。
     *
     * 只有「从别的工具切到 边框」才开整页：启动时本来就停在 边框 的工具不该一进来
     * 就被整页盖住照片，底板里的入口随时可以再开。点当前工具是空操作，保持现状；
     * 切到任何别的工具（包括 Logo）都会把整页关掉——它只属于 边框。
     */
    fun pageAfterToolPick(current: EditorTool, next: EditorTool, pageOpen: Boolean): Boolean =
        when {
            next != EditorTool.Border -> false
            next == current -> pageOpen
            else -> true
        }

    /** 全高参数页（矮窗口的回退页）接管底板：底板里不留任何页面在背后。 */
    fun pageUnderFullHeightPage(): Boolean = false

    /**
     * 全高参数页把屏幕交还底板（它的「返回预览」）：底板回到自己的默认页；全高页
     * 出现之前开着的页面绝不跟着回来。
     */
    fun pageAfterFullHeightPageReturn(): Boolean = false

    /**
     * 工具自己希望的参数面板形态。
     *
     * **每个工具都是 [PhoneSheetPageKind.Sheet]**，包括 Logo：参数页在底板里，舞台
     * 就在它上面，拖滑杆时画布立刻跟着变。加新工具时这个 `when` 必须显式表态，
     * 所以「某个工具偷偷变成整页」不会再靠一行特判溜进来。
     */
    fun pageKindForTool(tool: EditorTool): PhoneSheetPageKind = when (tool) {
        EditorTool.Border,
        EditorTool.Params,
        EditorTool.Text,
        EditorTool.Logo,
        EditorTool.Watermark,
        -> PhoneSheetPageKind.Sheet
    }

    /**
     * 这一拍用不用整页参数页。
     *
     * 只有**窗口**能决定：[constrainedWindow] 是布局预算给出的「窗口太矮 / 字号
     * 太大」，[fullPageDismissed] 是用户已经点过「返回预览」。工具只经
     * [pageKindForTool] 参与，而它对所有工具都是 [PhoneSheetPageKind.Sheet]，所以
     * 正常窗口里没有任何工具（尤其 Logo）能独占整页；矮窗口的回退页也不按工具分叉。
     */
    fun usesFullHeightPage(
        tool: EditorTool,
        constrainedWindow: Boolean,
        fullPageDismissed: Boolean,
    ): Boolean =
        pageKindForTool(tool) == PhoneSheetPageKind.FullHeight ||
            (constrainedWindow && !fullPageDismissed)
}

/** 参数面板的形态：底板里的一页，或接管窗口的整页回退页。 */
internal enum class PhoneSheetPageKind {
    /** 可拖动底板里的一页：舞台留在上面，改一个参数立刻在画布上看见。 */
    Sheet,

    /** 接管整个窗口的参数页（只在窗口放不下可用底板时）：参数头 + 面板。 */
    FullHeight,
}
