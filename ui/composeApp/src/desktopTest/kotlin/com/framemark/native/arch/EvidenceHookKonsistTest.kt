package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §2 `no-literal-style` / §1 hard rule 5, the evidence part: product
 * source must not carry `FM_*` hook names. Those literals are how QA hooks leaked into
 * the product (`FM_OPEN_CARD` resetting the design, `FM_DESKTOP_EXPORT` run tags that
 * later read like data).
 *
 * Counter-example this test blocks: adding
 * `println("FM_EXPORT_RERUN quality=$quality")` inside
 * `shell/desktop/ExportDialog.kt` fails with
 * `evidence-hook-literal ... shell-desktop: FM_EXPORT_RERUN` unless the line is accepted
 * in the allowlist. Error codes such as `FM-E-INTERNAL-001` do not match (`FM-E`, not
 * `FM_`) and stay allowed — they are product behaviour, not hooks.
 *
 * The current 300+ `println` evidence probes are on the allowlist as the starting point;
 * every later step deletes lines from it.
 */
class EvidenceHookKonsistTest {
    @Test
    fun productSourceHasNoEvidenceHookLiterals () {
        assertRule(Arch.RULE_EVIDENCE_LITERAL, Arch.evidenceLiteralViolations())
    }
}
