package com.framemark.native.arch

import kotlin.test.Test

/**
 * ARCH-MODULES.md §2 `module-boundary`, rule 1: imports may only point downward
 * (`shell` → `inspector` → `workflow` → `product`/`engine` → `theme`). A reverse edge
 * is what makes "change one screen, break another".
 *
 * Each test documents the counter-example it is meant to stop.
 *
 * Counter-example this test blocks: adding
 * `import com.framemark.native.shell.DesktopShell` to
 * `com/framemark/native/workflow/DesignSession.kt` (workflow rank 2 pulling in
 * shell rank 4) compiles fine and destroys the boundary. The gate fails with
 * `layer-direction ... workflow -> shell` unless the entry is explicitly accepted.
 */
class LayerDirectionKonsistTest {
    @Test
    fun productionImportsOnlyPointDownTheLayerLadder () {
        assertRule(Arch.RULE_LAYER, Arch.layerViolations())
    }
}
