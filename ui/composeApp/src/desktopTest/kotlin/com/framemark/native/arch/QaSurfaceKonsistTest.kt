package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §1 hard rule 5: QA hooks may only write `qa/` state, never product
 * state. Two machine checks:
 *
 * 1. `qa-package-isolation` — product code must not import `qa/`, `qaenv/` or `verify/`.
 *    Counter-example: `FM_OPEN_CARD` used to reset the whole design; the fix moved the
 *    hook behind `QaSurface.hooks`. Importing `com.framemark.native.qaenv.EnvQaSurface`
 *    from `workflow/` would reintroduce the coupling and fails with
 *    `qa-package-isolation ... workflow imports the QA layer`.
 *
 * 2. `launch-state-isolation` — only the entry point and the composition/QA layers may
 *    read `LaunchOptions` (the object the QA Intent hooks write). Counter-example:
 *    `workflow/NativeRender.kt` reading `LaunchOptions.controlProbe` makes a render path
 *    depend on how the process was launched; the gate reports
 *    `launch-state-isolation ... workflow reads QA launch state`.
 */
class QaSurfaceKonsistTest {
    @Test
    fun productCodeDoesNotImportTheQaLayer () {
        assertRule(Arch.RULE_QA_PACKAGE, Arch.qaPackageViolations())
    }

    @Test
    fun onlyCompositionAndTheEntryPointReadLaunchOptions () {
        assertRule(Arch.RULE_LAUNCH_STATE, Arch.launchStateViolations())
    }
}
