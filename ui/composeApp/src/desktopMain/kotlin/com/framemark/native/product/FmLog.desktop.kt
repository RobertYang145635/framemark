package com.framemark.native.product

/**
 * The desktop build has no release build type of its own: every desktop artifact
 * built from this repo is a QA/dev build, so verbose evidence stays on. The
 * compile-time constant still lives per source set (never a runtime env read), and
 * [FmLog] can be pinned from an embedding host or a test. Redaction applies either way.
 */
internal actual val fmLogVerboseDefault: Boolean = true

/**
 * `error` goes to stderr: the desktop bridge (`BridgeMain`) writes its JSON protocol
 * to stdout, so a failure line must never land in that channel.
 */
internal actual fun fmLogWrite(line: String, toError: Boolean) {
    if (toError) System.err.println(line) else println(line)
}
