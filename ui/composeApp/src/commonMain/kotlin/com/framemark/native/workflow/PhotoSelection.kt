package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The photo the native renderer works on: a file picked on the phone, or null for
 * the bundled sample. [version] changes on every pick so the stage preview
 * recomposes even when the same card/design is on screen.
 *
 * [batch] carries every path that arrived in the same pick, so the phone's photo
 * strip can add a multi-selection at once; [path] is the one the engine renders
 * (the last of the batch).
 */
object PhotoSelection {
    var path: String? by mutableStateOf(null)
        private set

    var batch: List<String> by mutableStateOf(emptyList())
        private set

    /** Original provider/file names keyed by the normalized app-local path. */
    var displayNamesByPath: Map<String, String> by mutableStateOf(emptyMap())
        private set

    var version: Int by mutableIntStateOf(0)
        private set

    fun set(newPath: String?) {
        batch = listOfNotNull(newPath)
        displayNamesByPath = emptyMap()
        path = newPath
        version++
    }

    fun setPaths(paths: List<String>, displayNamesByPath: Map<String, String> = emptyMap()) {
        val valid = paths.filter { it.isNotBlank() }
        batch = valid
        this.displayNamesByPath = displayNamesByPath
            .filterKeys { it in valid }
            .filterValues { it.isNotBlank() }
        path = valid.lastOrNull()
        version++
    }
}
