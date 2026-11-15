package com.framemark.native.workflow

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * W-01: which editable text fields currently own the keyboard focus.
 *
 * The shell handles shortcuts in `onPreviewKeyEvent`, i.e. **before** the focused
 * text field. "Focus is not the left-rail search box" was the old guard, and it
 * could only ever name one field: arrows in the inspector's text/number boxes
 * stepped the border, `\` never reached any field, and Ctrl+Z undid the design
 * instead of the text. This registry is the general answer — every editable field
 * joins while it is focused, and the shell yields exactly the keys an editor
 * needs ([com.framemark.native.shell.TextEditKeys]).
 *
 * A unit-testable class with no Compose dependency in its state, so the counting
 * rules are pinned without a UI test runner (this project has none).
 */
class TextEditFocus {

    private val owners = mutableSetOf<Any>()

    /** Compose state: a shell's key handler reads it and recomposes when it flips. */
    var isEditing by mutableStateOf(false)
        private set

    /** Number of focused editable fields; exposed for tests and diagnostics. */
    val focusedCount: Int get() = owners.size

    fun setFocused(owner: Any, focused: Boolean) {
        val changed = if (focused) owners.add(owner) else owners.remove(owner)
        if (changed) isEditing = owners.isNotEmpty()
    }

    /** Drops [owner] without a focus-lost callback (field disposed while focused). */
    fun release(owner: Any) {
        if (owners.remove(owner)) isEditing = owners.isNotEmpty()
    }
}

/** Provided by the composing shell; null in hosts that never handle shortcuts. */
val LocalTextEditFocus = staticCompositionLocalOf<TextEditFocus?> { null }

/**
 * Reports this field's focus to the nearest [LocalTextEditFocus].
 *
 * Apply it to the modifier chain of every editable `BasicTextField` /
 * `OutlinedTextField`. It is a no-op when no shell provides the registry (tests,
 * previews). `DisposableEffect` is the safety net: a field removed from the
 * composition while focused must not leave the shell yielding shortcuts forever.
 */
fun Modifier.trackTextEditing(): Modifier = composed {
    val registry = LocalTextEditFocus.current ?: return@composed this
    val owner = remember { Any() }
    DisposableEffect(registry, owner) {
        onDispose { registry.release(owner) }
    }
    Modifier.onFocusChanged { state -> registry.setFocused(owner, state.isFocused) }
}
