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
 * Two sentences share the slot (2026-11 UX audit P0-2):
 *  - 「已撤销 · 重做」 — A-08, after an undo;
 *  - 「已换边框 · 撤销」 — the border switch is now an undoable step, so the stage
 *    says so once and offers the way back instead of leaving the user to hunt for
 *    the toolbar's undo.
 * They are mutually exclusive by construction (an undo clears the border flag), and
 * a single slot keeps the two from stacking on top of each other.
 */
@Composable
internal fun BoxScope.PhoneStageOverlays(
    undoNotice: Boolean,
    borderNotice: Boolean,
    onRedo: () -> Unit,
    onUndo: () -> Unit,
) {
    // The two V2 stage hints float over the photo (report §7.2): the applied
    // border on the left, 按住看原图 on the right. Everything else moved into
    // the dark top bar, so the photo keeps the whole middle of the screen.
    val place = Modifier
        .align(Alignment.TopCenter)
        .padding(top = 54.dp)
    when {
        undoNotice -> PhoneUndoNotice(
            notice = "已撤销",
            actionLabel = "重做",
            onAction = onRedo,
            modifier = place,
        )
        borderNotice -> PhoneUndoNotice(
            notice = "已换边框",
            actionLabel = "撤销",
            onAction = onUndo,
            modifier = place,
        )
    }
}
