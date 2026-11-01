package com.framemark.native.product

import com.framemark.native.engine.EngineCatalog
import com.framemark.native.engine.EngineConfigException
import com.framemark.native.engine.RuntimePaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Relocation/validation tests for the 175-card manifest reader. The synthetic
 * runtime mirrors the packaged layout (`bridge/desktop/cards-175.json` + `data/`
 * + `bin/`) so no engine binary is needed.
 */
class EngineCatalogTest {
    private fun layout(root: Path): RuntimePaths.Layout {
        val data = root.resolve("data")
        return RuntimePaths.Layout(
            root = root.toFile(),
            dataRoot = data.toFile(),
            manifest = root.resolve("bridge/desktop/cards-175.json").toFile(),
            e2b = null,
            e4hand = null,
            e5Worker = null,
            e5Icc = null,
            fontsDir = data.resolve("fonts").toFile(),
            decodedDir = data.resolve("decoded").toFile(),
            handDir = data.resolve("hand").toFile(),
            handFontsDir = data.resolve("fonts").toFile(),
            handExtraFontsDir = data.resolve("e4-extra-fonts").toFile(),
        )
    }

    private fun manifest(style: String = "data/styles/oppo/x.json"): String = """
        {
          "schema": "framemark-cards/v1",
          "photo": {"path":"data/photo.png","w":10,"h":10,"exifSource":"data/exif.jpg"},
          "cards": [
            {"cardId":"ir-1","backend":"ir","engineId":"oppo:x","name":"IR 1","style":"$style","slots":[]},
            {"cardId":"hand-1","backend":"hand","engineId":"hand-1","name":"HAND 1",
             "cardsFile":"data/hand/cards.json","design":"data/designs/hand-1.json","slots":[]}
          ]
        }
    """.trimIndent()

    private fun runtime(root: Path, texts: String = manifest()) {
        Files.createDirectories(root.resolve("bridge/desktop"))
        Files.createDirectories(root.resolve("data/styles/oppo"))
        Files.createDirectories(root.resolve("data/hand"))
        Files.createDirectories(root.resolve("data/designs"))
        Files.createDirectories(root.resolve("data/fonts"))
        Files.createDirectories(root.resolve("data/decoded"))
        Files.writeString(root.resolve("bridge/desktop/cards-175.json"), texts)
        Files.writeString(root.resolve("data/photo.png"), "png")
        Files.writeString(root.resolve("data/exif.jpg"), "jpg")
        Files.writeString(root.resolve("data/styles/oppo/x.json"), "{}")
        Files.writeString(root.resolve("data/hand/cards.json"), "{}")
        Files.writeString(root.resolve("data/designs/hand-1.json"), "{}")
    }

    @Test
    fun package_relative_paths_are_rerooted_at_the_data_root() {
        val root = Files.createTempDirectory("fm-catalog-")
        try {
            runtime(root)
            val catalog = EngineCatalog.load(layout(root))
            assertEquals(2, catalog.cards().size)
            assertTrue(catalog.supports("ir-1"))
            assertTrue(catalog.supports("hand-1"))
            assertTrue(catalog.isPackageRelative("data/styles/oppo/x.json"))
            assertEquals(root.resolve("data/styles/oppo/x.json").toString(), catalog.abs("data/styles/oppo/x.json"))
            assertEquals("styles/oppo/x.json", catalog.engineArg("data/styles/oppo/x.json"))
            assertEquals("hand/cards.json", catalog.engineArg("data/hand/cards.json"))
            assertEquals(root.resolve("data/photo.png").toString(), catalog.photoPath)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_missing_style_names_the_card_file_and_expected_path() {
        val root = Files.createTempDirectory("fm-catalog-missing-")
        try {
            runtime(root)
            Files.delete(root.resolve("data/styles/oppo/x.json"))
            val error = assertFailsWith<EngineConfigException> { EngineCatalog.load(layout(root)) }
            assertEquals("FM-E-RES-001", error.code)
            assertEquals("ir-1", error.cardId)
            assertEquals("data/styles/oppo/x.json", error.fileName)
            assertTrue(error.message!!.contains(root.resolve("data/styles/oppo/x.json").toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun absolute_manifest_paths_are_rejected() {
        val root = Files.createTempDirectory("fm-catalog-absolute-")
        try {
            runtime(root, manifest(style = "D:/border&watermark/secret.json"))
            val error = assertFailsWith<EngineConfigException> { EngineCatalog.load(layout(root)) }
            assertEquals("FM-E-RES-001", error.code)
            assertTrue(error.message!!.contains("绝对路径"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun the_manifest_name_switch_is_explicit() {
        val root = Files.createTempDirectory("fm-catalog-name-")
        try {
            runtime(root)
            val base = layout(root)
            assertTrue(EngineCatalog.load(base).isPackageRelative("data/photo.png"))
            assertTrue(base.isCards175)
            val x1 = base.copy(manifest = root.resolve("bridge/desktop/x1-cards.json").toFile())
            Files.copy(
                root.resolve("bridge/desktop/cards-175.json"),
                root.resolve("bridge/desktop/x1-cards.json"),
            )
            val catalog = EngineCatalog.load(x1)
            assertEquals(2, catalog.cards().size)
            assertTrue(!x1.isCards175)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun the_real_packaged_runtime_exposes_all_180_cards_when_built() {
        val repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().let {
            var dir: Path? = it
            while (dir != null && !Files.isRegularFile(dir.resolve("framemark/bridge/desktop/cards-175.json"))) {
                dir = dir.parent
            }
            dir
        } ?: return
        val runtimeApp = repo.resolve("_research/native/p1c/runtime-app")
        if (!Files.isDirectory(runtimeApp)) return
        val data = runtimeApp.resolve("data")
        val layout = RuntimePaths.Layout(
            root = runtimeApp.toFile(),
            dataRoot = data.toFile(),
            manifest = runtimeApp.resolve("bridge/desktop/cards-175.json").toFile(),
            e2b = runtimeApp.resolve("bin/e2b.exe").toFile(),
            e4hand = runtimeApp.resolve("bin/e4hand.exe").toFile(),
            e5Worker = null,
            e5Icc = null,
            fontsDir = data.resolve("fonts").toFile(),
            decodedDir = data.resolve("decoded").toFile(),
            handDir = data.resolve("hand").toFile(),
            handFontsDir = data.resolve("fonts").toFile(),
            handExtraFontsDir = data.resolve("e4-extra-fonts").toFile(),
        )
        val catalog = EngineCatalog.load(layout)
        assertEquals(180, catalog.cards().size)
        assertEquals(111, catalog.cards().count { it.backend == "ir" })
        assertEquals(69, catalog.cards().count { it.backend == "hand" })
        assertTrue(catalog.cards().all { catalog.supports(it.cardId) })
    }
}
