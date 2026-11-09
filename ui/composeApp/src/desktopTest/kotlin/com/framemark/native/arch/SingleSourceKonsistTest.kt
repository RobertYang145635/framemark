package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §1 hard rule 3: one concept, one implementation.
 * Canonical declarations: `inspector/OutputSize.kt`, `theme/FmRhythm.kt`,
 * `theme/Tokens.kt` (`FmPalette`, `FmType`), `product/ControlRegistry.kt`,
 * `product/ExportSettings.kt`.
 *
 * Counter-example this test blocks: the historical bug where `FmRhythm.kt` existed in
 * two packages and the merge picked one; or a second
 * `object OutputSize` in `workflow/` computing `round()` instead of `roundToInt()` —
 * that made export sizes disagree by one pixel. Adding such a declaration fails with
 * `single-source ... second declaration of OutputSize`.
 *
 * Today one entry is on the allowlist: `ExportSettings` is declared in both
 * `product/ExportSettings.kt` and `workflow/DesignSession.kt`. Removing the workflow
 * copy must delete that allowlist line (stale entries fail the hygiene test).
 */
class SingleSourceKonsistTest {
    @Test
    fun everyKeyConceptIsDeclaredExactlyOnce () {
        assertRule(Arch.RULE_SINGLE, Arch.singleSourceViolations())
    }
}
