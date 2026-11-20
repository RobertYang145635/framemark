package com.framemark.native.shell.phone

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The stage's transient overlay slot: one short notice with its way back. It floats
 * over the top of the photo and dismisses itself; its drawing lives in
 * `PhoneStageOverlay.kt`, this file only places it inside the stage Box.
 *
 * Three sentences share the slot:
 *  - 「已撤销 · 重做」 — A-08, after an undo;
 *  - 「已换边框 · 撤销」 — the border switch is now an undoable step, so the stage
 *    says so once and offers the way back instead of leaving the user to hunt for
 *    the toolbar's undo;
 *  - 「已移除 · 撤销」 — 删照片不弹确认框：原件留在 Originals，丢的是那张照片的设计，
 *    所以这一下用可撤销的一步接住（与上面两条同一个提示位，先来后到由 [stageNotice] 定）。
 * 一个提示位只画一句，三条不会叠在一起。
 */
@Composable
internal fun BoxScope.PhoneStageOverlays(
    undoNotice: Boolean,
    borderNotice: Boolean,
    removeNoticeLabel: String? = null,
    onRedo: () -> Unit,
    onUndo: () -> Unit,
    onUndoRemove: () -> Unit = {},
) {
    // The two V2 stage hints float over the photo (report §7.2): the applied
    // border on the left, 按住看原图 on the right. Everything else moved into
    // the dark top bar, so the photo keeps the whole middle of the screen.
    val place = Modifier
        .align(Alignment.TopCenter)
        .padding(top = 54.dp)
    when (val notice = stageNotice(removeNoticeLabel, undoNotice, borderNotice)) {
        is PhoneStageNotice.Removed -> PhoneUndoNotice(
            notice = notice.text,
            actionLabel = "撤销",
            onAction = onUndoRemove,
            modifier = place,
        )
        PhoneStageNotice.RedoAvailable -> PhoneUndoNotice(
            notice = "已撤销",
            actionLabel = "重做",
            onAction = onRedo,
            modifier = place,
        )
        PhoneStageNotice.BorderSwitched -> PhoneUndoNotice(
            notice = "已换边框",
            actionLabel = "撤销",
            onAction = onUndo,
            modifier = place,
        )
        null -> Unit
    }
}

/** 提示位这一拍画哪一句（纯决策，`PhoneStageNoticeTest` 直接测）。 */
internal sealed interface PhoneStageNotice {
    /** 「已移除 <label> · 撤销」。 */
    data class Removed(val label: String) : PhoneStageNotice {
        val text: String get() = "已移除 $label"
    }

    data object RedoAvailable : PhoneStageNotice

    data object BorderSwitched : PhoneStageNotice
}

/**
 * 三条共用一个提示位时的先后：刚发生的、破坏性的排前面。删除只能救 4 秒，撤销与换边框
 * 都能从顶栏再来一次，所以删除优先；撤销的「重做」比换边框的「撤销」更即时，排第二。
 */
internal fun stageNotice(
    removeNoticeLabel: String?,
    undoNotice: Boolean,
    borderNotice: Boolean,
): PhoneStageNotice? = when {
    !removeNoticeLabel.isNullOrBlank() -> PhoneStageNotice.Removed(removeNoticeLabel.trim())
    undoNotice -> PhoneStageNotice.RedoAvailable
    borderNotice -> PhoneStageNotice.BorderSwitched
    else -> null
}
