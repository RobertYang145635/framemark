package com.framemark.native.arch

import kotlin.test.Test

/**
 * Maintainer tool, not a rule: regenerates `violations-allowlist.txt` from the current
 * tree when explicitly asked. It is a no-op in the normal `desktopTest` run.
 *
 * ```
 * ./gradlew -p ui :composeApp:desktopTest \
 *   -Dfm.arch.writeAllowlist=true --tests '*ArchAllowlistRegenerateTest*'
 * ```
 *
 * The file it writes is a *seed*, not a permission slip: the hygiene test fails on stale
 * entries, and every rule test fails on entries that are not listed.
 */
class ArchAllowlistRegenerateTest {
    @Test
    fun regenerateAllowlistWhenAsked () {
        if (System.getenv("FM_ARCH_WRITE_ALLOWLIST") != "1") {
            println("ARCH1-ALLOWLIST regeneration skipped (set FM_ARCH_WRITE_ALLOWLIST=1)")
            return
        }
        writeAllowlistIfRequested(Arch.allViolations())
    }
}
