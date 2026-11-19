package com.framemark.native.workflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Process-wide preview gate: while a batch export runs, preview rendering stands
 * down.
 *
 * Batch export is CPU work that now keeps several frames in flight (see
 * `BatchConcurrency.kt`), so a stage preview or a library thumbnail rendering at
 * the same time competes for the same cores — and on the phone the preview path
 * also grabs the GPU context. The user's export is the long wait, the preview is
 * the thing that can be deferred, so the export takes the engine and the previews
 * wait.
 *
 * This is **not** a cancel: a frame already in flight is allowed to finish (nobody
 * wants a half-drawn stage), and the gate only stops *new* engine calls. Nothing is
 * dropped either — callers keep publishing their newest input while suspended and
 * read it **after** [awaitResumed] returned, so a run of slider drags during an
 * export re-renders once, with the values the user actually left behind.
 *
 * State lives in the process only: it is not persisted, not in the project, and
 * not part of any document. Desktop and Android share it because both finish the
 * same way — the Kotlin side decides when previews may run again.
 */
object PreviewSuspension {

    private val state = MutableStateFlow(false)

    /** Depth, so a second holder cannot resume rendering the first one still needs. */
    private var holders = 0
    private val lock = Any()

    /** True while preview rendering must not start a new engine call. */
    val active: StateFlow<Boolean> = state.asStateFlow()

    /** Non-suspending read of [active], for hot paths that only need a quick check. */
    val suspended: Boolean get() = state.value

    /**
     * Stops new preview renders. Always paired with [resumeRendering] in a `finally`,
     * so a failed or cancelled export can never leave the stage frozen.
     */
    fun suspendRendering() {
        synchronized(lock) {
            holders += 1
            state.value = true
        }
    }

    /** Allows preview renders again; the last [suspendRendering] wins. */
    fun resumeRendering() {
        synchronized(lock) {
            if (holders > 0) holders -= 1
            state.value = holders > 0
        }
    }

    /**
     * Suspends until preview rendering is allowed again, and returns immediately when
     * it already is. Callers must read their newest input **after** this returns.
     */
    suspend fun awaitResumed() {
        state.first { !it }
    }

    /**
     * Drops every outstanding hold. Verification/QA only: a leaked hold from a killed
     * test would otherwise freeze every later preview in the same JVM.
     */
    internal fun resetForVerify() {
        synchronized(lock) {
            holders = 0
            state.value = false
        }
    }
}
