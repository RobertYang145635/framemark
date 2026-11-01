package com.framemark.native.engine

import java.io.File

/** Structured engine/runtime failure: code + human message + enough detail for a log. */
class EngineConfigException(
    val code: String,
    message: String,
    val cardId: String? = null,
    val fileName: String? = null,
    val expectedPath: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append('[').append(code).append("] ").append(message)
        cardId?.let { append(" card=").append(it) }
        fileName?.let { append(" file=").append(it) }
        expectedPath?.let { append(" expected=").append(it) }
    },
    cause,
)

/**
 * Locates the engine runtime without hard-coded drive letters.
 *
 * Three layouts share one resolver:
 *  - **packaged / portable / install**: `<root>/bin` + `<root>/data` +
 *    `<root>/bridge/desktop/cards-175.json` (`data/` = the Android3 bundle copy);
 *  - **legacy package**: the same app with `engine/framemark_engine.exe` and the
 *    manifest under `workspace/framemark/bridge/desktop/`;
 *  - **dev workspace**: `<root>/framemark/bridge/desktop/cards-175.json` plus a
 *    data root discovered under the repo (`data/` → built `p1c/runtime-app/data`
 *    → upstream `p1c/runtime/data` → raw bundle).
 *
 * A launch through the jpackage launcher searches **only the app folder**: walking
 * up into a repo would let an installed copy pretend to work while reading the
 * development tree. If nothing is found the user gets a modal with the reason and
 * the searched roots (default action quits) instead of a quiet example-mode boot;
 * `FM_ENGINE_PROMPT=0` keeps tooling non-interactive.
 *
 * Manifest paths keep their `data/` prefix; [EngineCatalog] re-roots them at
 * [Layout.dataRoot]. Engine process CWD is [Layout.dataRoot] because hand
 * `cards.json` carries data-root-relative `designs/…`, `photo.png`, `logos/…`.
 *
 * Manifest source: `FM_CARDS_MANIFEST` (path, or a file name next to the packaged
 * manifest) → legacy `FM_X1_MANIFEST` → default `cards-175.json`. The 10-card
 * `x1-cards.json` stays on disk but is never the default.
 */
object RuntimePaths {
    const val DEFAULT_MANIFEST = "cards-175.json"

    data class Layout(
        val root: File,
        val dataRoot: File,
        val manifest: File,
        val e2b: File?,
        val e4hand: File?,
        val e5Worker: File?,
        val e5Icc: File?,
        val fontsDir: File?,
        val decodedDir: File?,
        val handDir: File?,
        val handFontsDir: File?,
        val handExtraFontsDir: File?,
    ) {
        val isCards175: Boolean get() = manifest.name == DEFAULT_MANIFEST
    }

    @Volatile
    private var cached: Layout? = null

    fun layout(): Layout = cached ?: synchronized(this) { cached ?: detect().also { cached = it } }

    /** Test/evidence hook: drop the cache after changing process environment. */
    fun resetForVerify() {
        cached = null
    }

    /** Absolute engine process directory: hand data paths are relative to it. */
    fun engineWorkingDir(layout: Layout): File = layout.dataRoot.absoluteFile

    private fun detect(): Layout {
        val manifestName = System.getenv("FM_CARDS_MANIFEST")?.takeIf { it.isNotBlank() }
        val explicit = System.getenv("FM_X1_MANIFEST")?.takeIf { it.isNotBlank() }?.let(::File)

        val roots = rootCandidates()
        val root = roots.firstOrNull { dir ->
            (explicit?.isFile == true && isDevRoot(dir)) ||
                manifestCandidate(dir, manifestName) != null ||
                hasEngineLayout(dir)
        } ?: failDiscovery(roots, "engine runtime not found")

        val manifest = explicit?.takeIf { it.isFile }
            ?: manifestCandidate(root, manifestName)
            ?: failDiscovery(roots, "no card manifest ('${manifestName ?: DEFAULT_MANIFEST}' or 'x1-cards.json') under $root")
        val dataRoot = dataRootCandidates(root)
            .firstOrNull { File(it, "manifest.json").isFile || File(it, "styles").isDirectory }
            ?: failDiscovery(roots, "engine data root not found under $root (looked for data/, workspace/framemark/engine/data, p1c runtime data, bundle)")

        fun pick(envName: String, vararg rel: String): File? {
            System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return File(it) }
            for (r in rel) {
                val f = if (File(r).isAbsolute) File(r) else File(root, r)
                if (f.isFile) return f
            }
            return null
        }
        fun dir(base: File, vararg rel: String): File? =
            rel.map { File(base, it) }.firstOrNull { it.isDirectory }

        val runtimeApp = File(root, "_research/native/p1c/runtime-app")
        val upstreamRuntime = File(root, "_research/native/p1c/runtime")
        val deliveryEngine = File(root, "_research/native/p1c/tmp/engine")

        val layout = Layout(
            root = root,
            dataRoot = dataRoot,
            manifest = manifest,
            e2b = pick(
                "FM_E2B_EXE",
                "bin/e2b.exe",
                // Legacy package layout: the same binary shipped under engine/ with its product name.
                "engine/framemark_engine.exe",
                "${rel(runtimeApp)}/bin/e2b.exe",
                "${rel(upstreamRuntime)}/bin/e2b.exe",
                "${rel(deliveryEngine)}/framemark_engine.exe",
            ),
            e4hand = pick(
                "FM_E4HAND_EXE",
                "bin/e4hand.exe",
                "engine/e4hand.exe",
                "${rel(runtimeApp)}/bin/e4hand.exe",
                "${rel(upstreamRuntime)}/bin/e4hand.exe",
                "${rel(deliveryEngine)}/e4hand.exe",
            ),
            e5Worker = pick(
                "FM_E5_WORKER",
                "bin/e5-worker.exe",
                "engine/e5-worker.exe",
                "${rel(runtimeApp)}/bin/e5-worker.exe",
                "${rel(deliveryEngine)}/e5-worker.exe",
                "_research/native/e5/bin/e5-worker.exe",
            ),
            e5Icc = pick(
                "FM_E5_ICC",
                "bin/srgb-456.icc",
                "engine/srgb-456.icc",
                "${rel(runtimeApp)}/bin/srgb-456.icc",
                "data/icc/srgb-456.icc",
                "_research/native/e5/ref/srgb-456.icc",
            ),
            fontsDir = dir(dataRoot, "fonts"),
            decodedDir = dir(dataRoot, "decoded"),
            handDir = dir(dataRoot, "hand"),
            handFontsDir = dir(dataRoot, "fonts"),
            handExtraFontsDir = dir(dataRoot, "e4-extra-fonts"),
        )
        println("FM_RUNTIME ${describe(layout)}")
        return layout
    }

    private fun rel(file: File): String = file.path.replace('\\', '/')

    @Volatile
    private var noticedFailure: String? = null

    /**
     * Discovery failed. The user sees why and where we looked, and the app stops there:
     * a frozen package that quietly booted into the example workflow is exactly the bug
     * this closes. Evidence tooling opts out with `FM_ENGINE_PROMPT=0` (or a headless JVM)
     * and keeps the previous "throw, caller decides" behaviour.
     */
    private fun failDiscovery(roots: List<File>, reason: String): Nothing {
        val searched = roots.take(12).joinToString("\n") { "  ${it.path}" }
        println("FM_ENGINE_RUNTIME_MISSING reason=$reason searched=${roots.take(6).joinToString { it.path }}")
        System.err.println("engine runtime not found: $reason\nsearched roots:\n$searched")
        if (noticedFailure == null) {
            noticedFailure = reason
            askUser(reason, searched)
        }
        throw IllegalStateException("engine runtime not found: $reason")
    }

    private fun askUser(reason: String, searched: String) {
        if (System.getenv("FM_ENGINE_PROMPT") == "0") return
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "FrameMark 找不到引擎运行时，无法真实出片，已停止启动。\n\n原因：$reason\n\n已搜索：\n$searched",
            "FrameMark — 引擎运行时缺失",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        kotlin.system.exitProcess(78)
    }

    fun manifestCandidate(root: File, name: String?): File? {
        val names = listOfNotNull(name?.takeIf { it.isNotBlank() }, DEFAULT_MANIFEST, "x1-cards.json").distinct()
        for (n in names) {
            if (File(n).isAbsolute) {
                if (File(n).isFile) return File(n)
                continue
            }
            val relative = manifestRoots(root).map { File("$it/$n") } + File(root, n)
            relative.firstOrNull { it.isFile }?.let { return it }
        }
        return null
    }

    /** Manifest folders: package layout first, then the dev workspace and the legacy stage. */
    private fun manifestRoots(root: File): List<File> = listOf(
        File(root, "bridge/desktop"),
        File(root, "framemark/bridge/desktop"),
        File(root, "workspace/framemark/bridge/desktop"),
    )

    /**
     * Both shipped engine layouts: the relocatable `bin/` (e2b/e4hand names) and the
     * older package that put the same binaries in `engine/` under product names.
     */
    private fun hasEngineLayout(dir: File): Boolean =
        listOf("bin/e2b.exe", "engine/framemark_engine.exe").any { File(dir, it).isFile }

    private fun dataRootCandidates(root: File): List<File> = listOf(
        File(root, "data"),
        File(root, "workspace/framemark/engine/data"),
        File(root, "_research/native/p1c/runtime-app/data"),
        File(root, "_research/native/p1c/runtime/data"),
        File(root, "_research/native/x1/android3/bundle"),
    )

    /**
     * A packaged launch (jpackage sets `jpackage.app-path`) must never fall back to a
     * development workspace found by walking up the directory tree: that is what made
     * an installed copy look healthy while silently reading the repo. Loose `java -cp`
     * runs keep the upward search.
     */
    private fun isPackagedLaunch(): Boolean =
        System.getProperty("jpackage.app-path")?.isNotBlank() == true

    private fun rootCandidates(): List<File> {
        val out = LinkedHashSet<File>()
        for (name in listOf("FM_RUNTIME_ROOT", "FM_WORKSPACE_ROOT")) {
            System.getenv(name)?.takeIf { it.isNotBlank() }?.let { out += File(it).absoluteFile }
        }
        val packaged = isPackagedLaunch()
        for (app in appDirs()) {
            out += File(app, "framemark-runtime").absoluteFile
            if (!packaged) app.parentFile?.let { out += File(it, "framemark-runtime").absoluteFile }
        }
        // The app folder itself wins over anything further away.
        if (packaged) {
            appDirs().forEach { out += it.absoluteFile }
        }
        for (start in startDirs()) {
            var dir: File? = start.absoluteFile
            var depth = 0
            while (dir != null && depth < if (packaged) 1 else 8) {
                out += dir
                dir = dir.parentFile
                depth++
            }
        }
        return out.toList()
    }

    private fun appDirs(): List<File> {
        val dirs = LinkedHashSet<File>()
        System.getProperty("jpackage.app-path")?.let { dirs += File(it).absoluteFile.parentFile }
        if (isPackagedLaunch()) {
            // The jar folder is deliberately not consulted: classes shared from the CDS
            // archive report the code source recorded when the archive was built, which is
            // a build machine path. A packaged launch only ever searches its own folder.
            return dirs.filterNotNull()
        }
        runCatching {
            val loc = RuntimePaths::class.java.protectionDomain?.codeSource?.location
            if (loc != null) {
                val f = File(loc.toURI())
                dirs += if (f.isDirectory) f else f.parentFile
            }
        }
        dirs += File("").absoluteFile
        return dirs.filterNotNull()
    }

    private fun startDirs(): List<File> {
        val dirs = LinkedHashSet<File>()
        dirs += File("").absoluteFile
        System.getProperty("jpackage.app-path")?.let { dirs += File(it).absoluteFile.parentFile }
        if (isPackagedLaunch()) return dirs.filterNotNull()
        runCatching {
            val loc = RuntimePaths::class.java.protectionDomain?.codeSource?.location
            if (loc != null) {
                val f = File(loc.toURI())
                dirs += if (f.isDirectory) f else f.parentFile
            }
        }
        return dirs.filterNotNull()
    }

    private fun isDevRoot(dir: File): Boolean =
        manifestCandidate(dir, DEFAULT_MANIFEST) != null && File(dir, "framemark").isDirectory

    private fun describe(l: Layout): String = listOf(
        "root=${l.root}",
        "data=${l.dataRoot}",
        "manifest=${l.manifest}",
        "e2b=${l.e2b}",
        "e4hand=${l.e4hand}",
        "e5Worker=${l.e5Worker}",
        "e5Icc=${l.e5Icc}",
        "fonts=${l.fontsDir}",
        "decoded=${l.decodedDir}",
        "hand=${l.handDir}",
        "handExtraFonts=${l.handExtraFontsDir}",
    ).joinToString(" ")
}
