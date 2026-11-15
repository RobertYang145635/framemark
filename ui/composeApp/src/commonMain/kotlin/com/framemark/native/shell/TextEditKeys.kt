package com.framemark.native.shell

import androidx.compose.ui.input.key.Key

/**
 * W-01: the keys a focused text field must receive instead of the shell.
 *
 * The desktop shell handles its shortcuts in `onPreviewKeyEvent`, which runs from
 * the root down — before the focused editor. Returning `true` there swallows the
 * event. These are exactly the keys where that swallowing breaks editing:
 *
 *  - `\` is 按住看原图 outside a field, but it is an ordinary character inside one;
 *  - the arrow keys move the text cursor (or the caret in a number box), they must
 *    not step to the previous/next border and reset the inspector to 样式;
 *  - Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y belong to the editor when one is focused; the
 *    design undo must not fire from inside a text row.
 *
 * Everything else (F1, Esc, Ctrl+E, Ctrl+B, …) keeps working while typing: those
 * do not fight text input and the user expects them globally.
 */
object TextEditKeys {

    fun belongsToTextEditor(key: Key, ctrl: Boolean, keyDown: Boolean): Boolean = when {
        key == Key.Backslash -> true
        !keyDown -> false
        key == Key.DirectionLeft || key == Key.DirectionRight ||
            key == Key.DirectionUp || key == Key.DirectionDown -> true
        ctrl && (key == Key.Z || key == Key.Y) -> true
        else -> false
    }
}
