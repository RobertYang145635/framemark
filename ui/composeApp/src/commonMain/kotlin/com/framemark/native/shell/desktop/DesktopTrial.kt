package com.framemark.native.shell.desktop

/**
 * V2 左栏行为里的纯逻辑（无 Compose，便于单测）：
 *
 *  1. **逐个试的撤销合并**：连续切卡，停下 0.7 秒才记一步撤销；撤销一次回到这段连续切换
 *     开始之前的那张卡。见 UI 报告 §6“连续切换停下 0.7 秒才记一步”、UX-RESEARCH R-12。
 *  2. **悬停 0.4 秒出预览**：快速扫过不触发；没有预览图的卡由界面给“暂无预览”（A-09）。
 *  3. **FM_SAMPLE_PHOTO 只导入一次**：值为 1 且项目里还没有照片时才导入。
 *
 * 三个阈值都用常量暴露，测试直接引用常量，避免实现和测试各写一个 700。
 */
object DesktopTrial {

    /** 连续切换停下多久算一步撤销（毫秒）。 */
    const val UNDO_MERGE_WINDOW_MS = 700L

    /** 悬停多久才在舞台预览（毫秒）。 */
    const val HOVER_PREVIEW_DELAY_MS = 400L

    /** 撤销合并的当前状态：一段连续切换的起点卡，以及最近一次切换的时刻。 */
    data class UndoStep(
        val anchorCardId: String?,
        val lastSwitchAtMs: Long?,
        val switches: Int,
        val pending: Boolean,
    ) {
        companion object {
            val Empty = UndoStep(anchorCardId = null, lastSwitchAtMs = null, switches = 0, pending = false)
        }
    }

    /**
     * 记录一次切卡。[anchor] 是切换前的卡（可为 null）。窗口内的连续切换只保留第一个起点，
     * 窗口外开始新的一段。
     */
    fun recordSwitch(
        state: UndoStep,
        previousCardId: String?,
        nowMs: Long,
        windowMs: Long = UNDO_MERGE_WINDOW_MS,
    ): UndoStep {
        if (state.pending && state.lastSwitchAtMs != null && nowMs - state.lastSwitchAtMs < windowMs) {
            return state.copy(
                lastSwitchAtMs = nowMs,
                switches = state.switches + 1,
                pending = true,
            )
        }
        return UndoStep(
            anchorCardId = previousCardId,
            lastSwitchAtMs = nowMs,
            switches = 1,
            pending = previousCardId != null,
        )
    }

    /**
     * 撤销目标：一段连续切换的起点卡。没有起点（例如第一张卡）就没有可撤销的步骤，
     * 调用方应回退到参数撤销。
     */
    fun undoAnchor(state: UndoStep): String? = if (state.pending) state.anchorCardId else null

    /** 撤销后清空这一段，避免同一步被撤销两次。 */
    fun afterUndo(state: UndoStep): UndoStep = UndoStep.Empty

    /** 悬停是否已经够 0.4 秒。 */
    fun hoverReady(
        hoveredAtMs: Long?,
        nowMs: Long,
        delayMs: Long = HOVER_PREVIEW_DELAY_MS,
    ): Boolean = hoveredAtMs != null && nowMs - hoveredAtMs >= delayMs

    /**
     * 启动时要不要导入 sample photo：开关为真、项目里还没有照片、本次运行还没尝试过、
     * 平台确实给得出路径。项目文件持久化，所以“重启不重复导入”由 [hasPhotos] 保证。
     */
    fun shouldImportSamplePhoto(
        flagEnabled: Boolean,
        hasPhotos: Boolean,
        alreadyAttempted: Boolean,
        samplePath: String?,
    ): Boolean = flagEnabled && !hasPhotos && !alreadyAttempted && !samplePath.isNullOrBlank()
}
