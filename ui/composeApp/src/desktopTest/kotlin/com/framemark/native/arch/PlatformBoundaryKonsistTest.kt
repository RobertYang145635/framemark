package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §2 `module-boundary`, hard rule 2: `commonMain` must not import a
 * class that only exists in `desktopMain`/`androidMain` (only `expect`/`actual`).
 *
 * Counter-example this test blocks: adding
 * `import com.framemark.native.theme.WindowsTheme` to a `commonMain` file. The Kotlin
 * compiler happens to catch the simple case, but not a wildcard import
 * (`import com.framemark.native.engine.*`) that resolves differently per target, and not
 * the intent at review time. The gate reports
 * `platform-leak ... commonMain imports platform-only com.framemark.native.theme.WindowsTheme`.
 */
class PlatformBoundaryKonsistTest {
    @Test
    fun commonSourceSetsNeverImportPlatformOnlyDeclarations () {
        assertRule(Arch.RULE_PLATFORM, Arch.platformLeakViolations())
    }
}
