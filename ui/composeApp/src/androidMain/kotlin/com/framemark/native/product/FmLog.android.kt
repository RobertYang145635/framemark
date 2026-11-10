package com.framemark.native.product

import com.framemark.BuildConfig

/**
 * Android's compile-time level: the debug build type (all QA/evidence builds) logs
 * verbosely, the release build type keeps warn/error only. `BuildConfig.DEBUG` is a
 * `static final boolean`, so release code drops the verbose branches at R8/compile time.
 */
internal actual val fmLogVerboseDefault: Boolean = BuildConfig.DEBUG

/**
 * Android evidence is read from logcat, which captures both streams. `error` keeps
 * stderr so a failing bridge call is still distinguishable from ordinary output.
 */
internal actual fun fmLogWrite(line: String, toError: Boolean) {
    if (toError) System.err.println(line) else println(line)
}
