package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §2 `control-coverage`: every control type the registry can produce
 * must have a renderer branch. Otherwise a remote registry row silently renders nothing
 * (or falls into a generic `else`).
 *
 * Counter-example this test blocks: adding `enum class ControlType { …, Range }` to
 * `product/ControlRegistry.kt` without a `ControlType.Range` branch in `inspector/`
 * fails with `control-coverage ... control type Range has no renderer branch in
 * inspector/`. An `else ->` fallback does not satisfy this test on purpose.
 *
 * Current mapping: `inspector/RegistryInspector.kt` (`toControl`) covers all eight
 * registry types, so the gate starts green with no allowlist entry.
 */
class ControlRegistryCoverageKonsistTest {
    @Test
    fun everyRegistryControlTypeHasARendererBranch () {
        assertRule(Arch.RULE_CONTROL, Arch.controlCoverageViolations())
    }
}
