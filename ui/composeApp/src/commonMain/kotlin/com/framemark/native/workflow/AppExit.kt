package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * W-02: what a refused window close shows the user.
 *
 * [reason] is the storage failure (`saveError`) verbatim; the copy fields are set
 * only by the dialog's 「另存副本」 button, so the dialog can say where the edits
 * went instead of pretending the document was saved.
 */
data class ExitBlock(
    val reason: String,
    val copyPath: String? = null,
    val copyError: String? = null,
)

/** The close decision: leave, or stay with a reason. */
sealed interface ExitDecision {
    data object Exit : ExitDecision
    data class Stay(val block: ExitBlock) : ExitDecision
}

/**
 * The one rule: the window may close only after a write that made the project
 * durable. A failed flush keeps it open with an actionable reason — the
 * in-memory project holds edits no file contains, and exiting would silently
 * drop them (the pre-W-02 bug).
 */
fun decideExit(saved: Boolean, saveError: String?, writesBlocked: Boolean): ExitDecision =
    if (saved) {
        ExitDecision.Exit
    } else {
        ExitDecision.Stay(
            ExitBlock(
                reason = saveError?.takeIf { it.isNotBlank() }
                    ?: if (writesBlocked) {
                        "工程尚未成功读取，修改暂时不能写入磁盘"
                    } else {
                        "工程未能写入磁盘"
                    },
            ),
        )
    }

/**
 * Process-wide exit gate between the window close handler (`desktopMain/main.kt`)
 * and the dialog the shell draws.
 *
 * The close handler decides; the dialog reacts. Keeping the two apart is what
 * makes the flow testable without a window: [decideExit] is pure, and the
 * transitions here are plain state changes over an injected [exit] lambda.
 */
object AppExitGate {

    /** Non-null while the dialog is up; [ExitBlock.reason] is always non-blank. */
    var block by mutableStateOf<ExitBlock?>(null)
        private set

    /** Installed by the desktop entry point; null in tests/hosts without a window. */
    var exit: (() -> Unit)? = null

    fun requestClose(decision: ExitDecision) {
        when (decision) {
            ExitDecision.Exit -> exitNow()
            is ExitDecision.Stay -> block = decision.block
        }
    }

    /** 「重试保存」: a successful write closes the window it was refusing to close. */
    fun retry(saved: Boolean, saveError: String?) {
        if (saved) {
            exitNow()
        } else {
            block = (block ?: ExitBlock(DEFAULT_REASON)).copy(
                reason = saveError?.takeIf { it.isNotBlank() } ?: "工程仍未写入磁盘",
            )
        }
    }

    /** 「另存副本」 succeeded: stay open, but say where the edits are. */
    fun copySaved(path: String) {
        block = (block ?: ExitBlock(DEFAULT_REASON)).copy(copyPath = path, copyError = null)
    }

    fun copyFailed(reason: String?) {
        block = (block ?: ExitBlock(DEFAULT_REASON)).copy(
            copyError = reason?.takeIf { it.isNotBlank() } ?: "工程副本没有写入",
        )
    }

    fun dismiss() {
        block = null
    }

    /** 「放弃并退出」: the user explicitly chose to lose the unsaved edits. */
    fun discardAndExit() = exitNow()

    fun exitNow() {
        block = null
        exit?.invoke()
    }

    private const val DEFAULT_REASON = "工程未能写入磁盘"
}
