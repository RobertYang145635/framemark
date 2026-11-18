package com.framemark.native.shell.phone

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The stage's transient overlay slot: the one-shot 已撤销 notice with its 重做
 * action. It floats over the top of the photo and dismisses itself; its drawing
 * lives in `PhoneStageOverlay.kt`, this file only places it inside the stage Box.
 */
@Composable
internal fun BoxScope.PhoneStageOverlays(
    undoNotice: Boolean,
    onRedo: () -> Unit,
) {
    // The two V2 stage hints float over the photo (report §7.2): the applied
    // border on the left, 按住看原图 on the right. Everything else moved into
    // the dark top bar, so the photo keeps the whole middle of the screen.
    if (undoNotice) {
        PhoneUndoNotice(
            onRedo = onRedo,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 54.dp),
        )
    }
}
