package com.framemark.native.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegistrySourceTest {
    @Test
    fun explicit_repository_root_resolves_the_source_layout() {
        val parent = Files.createTempDirectory("fm-registry-source-root-")
        try {
            val repo = parent.resolve("checkout")
            populateWorkspace(repo)

            val source = RegistrySource(repo.toFile())
            val docs = source.documents()

            assertEquals(repo.toFile().canonicalFile, source.repoRoot?.canonicalFile)
            assertTrue(docs.complete, "source checkout documents should be complete: ${docs.missing}")
            assertEquals(repo.resolve("engine/data/taxonomy.json").toFile(), docs.taxonomy)
            assertEquals(repo.resolve("bridge/desktop/ir-slot-binding.json").toFile(), docs.irSlotBinding)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicit_legacy_parent_resolves_its_framemark_child() {
        val parent = Files.createTempDirectory("fm-registry-source-legacy-")
        try {
            val repo = parent.resolve("framemark")
            populateWorkspace(repo)

            val source = RegistrySource(parent.toFile())
            val docs = source.documents()

            assertEquals(repo.toFile().canonicalFile, source.repoRoot?.canonicalFile)
            assertTrue(docs.complete, "legacy parent documents should be complete: ${docs.missing}")
            assertEquals(repo.resolve("engine/data/taxonomy.json").toFile(), docs.taxonomy)
            assertNotNull(docs.irSlotBinding)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun populateWorkspace(root: Path) {
        listOf(
            "engine/data/taxonomy.json",
            "engine/data/capabilities.json",
            "ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json",
            "engine/data/controls.json",
            "src/renderer/src/core/controls/data/controls.oppo.json",
            "src/renderer/src/core/controls/data/controls.xiaomi.json",
            "src/renderer/src/core/controls/data/controls.vivo.json",
            "engine/data/logos.json",
            "bridge/desktop/ir-slot-binding.json",
        ).forEach { relative ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "{}")
        }
    }
}
