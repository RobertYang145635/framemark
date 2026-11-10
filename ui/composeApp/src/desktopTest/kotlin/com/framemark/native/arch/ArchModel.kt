package com.framemark.native.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import java.io.File

/**
 * ARCH1: the one place where the module model of `ARCH-MODULES.md` §1 and the
 * `violations-allowlist` ratchet live. Everything here is test-only support code;
 * the rules themselves are asserted by the `*KonsistTest` classes next to it.
 *
 * ## Why Konsist
 * A hand-written import scanner and Konsist are two mechanisms for the same job, and
 * two mechanisms drift. Konsist parses the real Kotlin PSI, so a rule cannot be
 * fooled by line breaks, aliases or multi-line declarations.
 *
 * ## Ratchet
 * Current violations are registered in `src/desktopTest/resources/arch/violations-allowlist.txt`
 * (`repo/relative/File.kt:rule:line:symbol`). The gate FAILS when
 *  - a violation is not registered ("new debt"), or
 *  - a registered entry no longer matches anything ("stale entry" — it must be deleted,
 *    so the list can only shrink).
 * The line number is for audit only; matching uses file + rule + symbol, so unrelated
 * edits elsewhere in a legacy file cannot turn the gate red.
 */
internal object Arch {
    const val RULE_LAYER = "layer-direction"
    const val RULE_PLATFORM = "platform-leak"
    const val RULE_SINGLE = "single-source"
    const val RULE_QA_PACKAGE = "qa-package-isolation"
    const val RULE_LAUNCH_STATE = "launch-state-isolation"
    const val RULE_EVIDENCE_LITERAL = "evidence-hook-literal"
    const val RULE_CONTROL = "control-coverage"

    val rules = setOf(
        RULE_LAYER, RULE_PLATFORM, RULE_SINGLE,
        RULE_QA_PACKAGE, RULE_LAUNCH_STATE, RULE_EVIDENCE_LITERAL, RULE_CONTROL,
    )

    val productionSourceSets = setOf("commonMain", "desktopMain", "androidMain")

    /**
     * Dependency ladder from ARCH-MODULES.md §1, read as "an import may only point
     * strictly downward". `util`/`resources`/`launch-state` are leaves that carry no
     * business logic (JSON reader, generated Compose resources, QA launch flags);
     * `qa` is a leaf that only the composition/QA layers may use.
     */
    private val ranks = mapOf(
        "theme" to 0,
        "util" to 0,
        "resources" to 0,
        "launch-state" to 0,
        "qa" to 0,
        "product" to 1,
        "engine" to 1,
        "userlogo" to 1,
        "workflow" to 2,
        "inspector" to 3,
        "ui-common" to 3,
        "library" to 4,
        "shell" to 4,
        "shell-desktop" to 4,
        "shell-phone" to 4,
        "app" to 5,
        "verify" to 5,
        "qaenv" to 5,
    )

    /** Only the composition layer, the QA layer itself and the entry point may touch `qa`. */
    private val qaPackageUsers = setOf("app", "verify", "qaenv", "qa")

    /** Only composition/QA layers may read the QA launch flags (`LaunchOptions`). */
    private val launchStateUsers = setOf("app", "verify", "qaenv", "qa", "shell", "shell-desktop", "shell-phone")

    /** Longest package prefix wins; keep entries ordered from specific to general. */
    private val packages = listOf(
        "com.framemark.native.shell.desktop" to "shell-desktop",
        "com.framemark.native.shell.phone" to "shell-phone",
        "com.framemark.native.inspector" to "inspector",
        "com.framemark.native.workflow" to "workflow",
        "com.framemark.native.product" to "product",
        "com.framemark.native.engine" to "engine",
        "com.framemark.native.theme" to "theme",
        "com.framemark.native.library" to "library",
        "com.framemark.native.qaenv" to "qaenv",
        "com.framemark.native.verify" to "verify",
        "com.framemark.native.userlogo" to "userlogo",
        "com.framemark.native.qa" to "qa",
        "com.framemark.native.shell" to "shell",
        "com.framemark.native.ui" to "ui-common",
        "com.framemark.native.resources" to "resources",
        "com.framemark.native" to "app",
    )

    /** Leaf symbols that live in a business package but carry no business logic. */
    private val utilImportPrefixes = listOf(
        "com.framemark.native.library.Json",
        "com.framemark.native.library.json",
        "com.framemark.native.product.FmLog",
        "com.framemark.native.FeelProbe",
        "com.framemark.native.AppVersion",
        "com.framemark.native.appVersion",
        "com.framemark.native.installAppVersion",
        "com.framemark.native.resources.",
    )

    private const val LAUNCH_STATE_IMPORT = "com.framemark.native.LaunchOptions"

    val scope: KoScope by lazy { Konsist.scopeFromProject() }

    val productionFiles: List<KoFileDeclaration> by lazy {
        scope.files.filter { it.sourceSetName in productionSourceSets }
    }

    // ---------------------------------------------------------------- module model

    fun repoPath (path: String): String {
        val normalized = path.replace('\\', '/')
        val marker = normalized.indexOf("/ui/")
        return if (marker >= 0) normalized.substring(marker + 1) else normalized
    }

    fun relativeToProject (path: String): String {
        val normalized = path.replace('\\', '/')
        val root = Konsist.projectRootPath.replace('\\', '/')
        return if (normalized.startsWith(root)) normalized.removePrefix(root).trimStart('/') else repoPath(normalized)
    }

    /** Module of a source file: package-derived, with a few explicit file overrides. */
    fun moduleOfPath (path: String): String {
        val file = path.replace('\\', '/').substringAfterLast('/')
        maybeOverride(file)?.let { return it }
        val marker = "/kotlin/"
        val rest = path.replace('\\', '/').substringAfter(marker, "")
            .substringBeforeLast('/')
        val pkg = rest.replace('/', '.')
        return moduleOfPackage(pkg)
    }

    private fun maybeOverride (fileName: String): String? = when (fileName) {
        // No business logic: JSON reader / version / frame probe / evidence logger.
        "Json.kt", "FeelProbe.kt", "AppVersion.kt", "AppVersion.desktop.kt", "AppVersion.android.kt" -> "util"
        // The one evidence entry point is a leaf like Json.kt: every module may log,
        // and the logger depends on nothing but its own platform write hook.
        "FmLog.kt", "FmLog.desktop.kt", "FmLog.android.kt" -> "util"
        // QA launch state written by the entry point, read by composition.
        "LaunchOptions.kt" -> "launch-state"
        else -> null
    }

    fun moduleOfPackage (pkg: String): String {
        for ((prefix, module) in packages) {
            if (pkg == prefix || pkg.startsWith("$prefix.")) return module
        }
        return "unknown"
    }

    fun moduleOfImport (fq: String): String {
        if (fq == LAUNCH_STATE_IMPORT) return "launch-state"
        utilImportPrefixes.firstOrNull { fq.startsWith(it) }?.let { return "util" }
        for ((prefix, module) in packages) {
            if (fq == prefix || fq.startsWith("$prefix.")) return module
        }
        return "unknown"
    }

    fun rankOf (module: String): Int? = ranks[module]

    // ------------------------------------------------------------------ violations

    data class Violation(
        val rule: String,
        val file: String,
        val line: Int,
        val symbol: String,
        val detail: String,
    ) {
        val key: String get() = "$file|$rule|$symbol"
        override fun toString (): String = "$file:$line [$rule] $detail"
    }

    /** `import a.b.C` inside a production source set. */
    fun productionImports () = scope.imports.filter { it.sourceSetName in productionSourceSets }

    fun layerViolations (): List<Violation> {
        val out = mutableListOf<Violation>()
        for (imp in productionImports()) {
            if (!imp.name.startsWith("com.framemark.native.")) continue
            val from = moduleOfPath(imp.path)
            val to = moduleOfImport(imp.name)
            if (from == to || to == "unknown" || from == "unknown") continue
            val line = lineOf(imp.location)
            val illegal = when (to) {
                "qa" -> from !in qaPackageUsers
                "qaenv" -> true
                // ARCH-MODULES.md §1 is a *ladder*: an import may point sideways (same
                // layer) or down, never up. `shell`↔`shell/desktop` and `product`↔`engine`
                // are the same layer, so their mutual imports are not violations.
                else -> (rankOf(to) ?: continue) > (rankOf(from) ?: continue)
            }
            if (illegal) {
                out += Violation(RULE_LAYER, repoPath(imp.path), line, imp.name, "$from -> $to: ${imp.name}")
            }
        }
        return out
    }

    /**
     * `commonMain`/`commonTest` may only use a platform class through `expect`/`actual`.
     * If the name is declared only in `desktopMain`/`androidMain`, the common source set
     * must not import it.
     */
    fun platformLeakViolations (): List<Violation> {
        val declaredIn = mutableMapOf<String, MutableSet<String>>()
        for (file in scope.files) {
            val pkg = file.packagee ?: continue
            for (decl in file.declarations()) {
                val name = decl.declName() ?: continue
                declaredIn.getOrPut("$pkg.$name") { mutableSetOf() }.add(file.sourceSetName)
            }
        }
        val out = mutableListOf<Violation>()
        for (imp in scope.imports) {
            if (imp.sourceSetName !in setOf("commonMain", "commonTest")) continue
            if (!imp.name.startsWith("com.framemark.native.")) continue
            val sets = declaredIn[imp.name].orEmpty()
            val platformOnly = sets.isNotEmpty() && sets.all { it == "desktopMain" || it == "androidMain" }
            if (platformOnly) {
                out += Violation(
                    RULE_PLATFORM, repoPath(imp.path), lineOf(imp.location), imp.name,
                    "${imp.sourceSetName} imports platform-only ${imp.name} (declared in ${sets.joinToString()})",
                )
            }
        }
        return out
    }

    private data class Singleton(
        val concept: String,
        val canonicalFileSuffix: String,
        val isObject: Boolean,
    )

    /**
     * One concept, one declaration (ARCH-MODULES.md §1 rule 3). The canonical file is
     * fixed here; any second declaration is a violation.
     */
    private val singletons = listOf(
        Singleton("OutputSize", "commonMain/kotlin/com/framemark/native/inspector/OutputSize.kt", true),
        Singleton("FmRhythm", "commonMain/kotlin/com/framemark/native/theme/FmRhythm.kt", true),
        Singleton("FmPalette", "commonMain/kotlin/com/framemark/native/theme/Tokens.kt", true),
        Singleton("FmType", "commonMain/kotlin/com/framemark/native/theme/Tokens.kt", true),
        Singleton("ControlRegistry", "commonMain/kotlin/com/framemark/native/product/ControlRegistry.kt", false),
        Singleton("ExportSettings", "commonMain/kotlin/com/framemark/native/product/ExportSettings.kt", false),
    )

    fun singleSourceViolations (): List<Violation> {
        val out = mutableListOf<Violation>()
        for (concept in singletons) {
            val expectedKind = if (concept.isObject) "object" else "class"
            for (file in productionFiles) {
                val path = repoPath(file.path)
                val declares = file.declarations().any { decl ->
                    decl.declName() == concept.concept && decl.declarationKindName() == expectedKind
                }
                if (!declares) continue
                if (path.endsWith(concept.canonicalFileSuffix)) continue
                out += Violation(
                    RULE_SINGLE, path, 0, concept.concept,
                    "second declaration of ${concept.concept} (canonical: ${concept.canonicalFileSuffix})",
                )
            }
        }
        return out
    }

    fun qaPackageViolations (): List<Violation> {
        val out = mutableListOf<Violation>()
        for (imp in productionImports()) {
            if (imp.name.startsWith("com.framemark.native.resources.")) continue
            val to = moduleOfImport(imp.name)
            if (to != "qa" && to != "qaenv" && to != "verify") continue
            val from = moduleOfPath(imp.path)
            if (from in qaPackageUsers) continue
            out += Violation(
                RULE_QA_PACKAGE, repoPath(imp.path), lineOf(imp.location), imp.name,
                "$from imports the QA layer: ${imp.name}",
            )
        }
        return out
    }

    fun launchStateViolations (): List<Violation> {
        val out = mutableListOf<Violation>()
        for (imp in productionImports()) {
            if (imp.name != LAUNCH_STATE_IMPORT) continue
            val from = moduleOfPath(imp.path)
            if (from in launchStateUsers) continue
            out += Violation(
                RULE_LAUNCH_STATE, repoPath(imp.path), lineOf(imp.location), imp.name,
                "$from reads QA launch state: ${imp.name}",
            )
        }
        return out
    }

    private val evidenceLiteral = Regex("""FM_[A-Z0-9_]+""")

    /** QA/evidence hook names must not appear in product string literals. */
    fun evidenceLiteralViolations (): List<Violation> {
        val out = mutableListOf<Violation>()
        for (file in productionFiles) {
            val module = moduleOfPath(file.path)
            if (module == "qa" || module == "qaenv" || module == "verify") continue
            var seen = mutableSetOf<String>()
            file.text.lines().forEachIndexed { index, line ->
                val tokens = evidenceLiteral.findAll(line).map { it.value }.toList()
                if (tokens.isEmpty()) return@forEachIndexed
                for (token in tokens) {
                    if (token in seen) continue
                    seen += token
                    out += Violation(
                        RULE_EVIDENCE_LITERAL, repoPath(file.path), index + 1, token,
                        "evidence hook literal in $module: $token",
                    )
                }
            }
        }
        return out
    }

    /**
     * Every control type the registry can produce must have a renderer/mapping branch.
     * The check is textual on purpose: a new enum entry without a `ControlType.<Entry>`
     * reference anywhere in `inspector/` fails even if some `else` branch would compile.
     */
    fun controlCoverageViolations (): List<Violation> {
        val registry = productionFiles.firstOrNull { repoPath(it.path).endsWith("product/ControlRegistry.kt") }
            ?: return listOf(Violation(RULE_CONTROL, "ui/composeApp/src/commonMain/kotlin/com/framemark/native/product/ControlRegistry.kt", 0, "ControlType", "ControlRegistry.kt not found"))
        val enumClass = registry.classes().firstOrNull { it.name == "ControlType" }
            ?: return listOf(Violation(RULE_CONTROL, repoPath(registry.path), 0, "ControlType", "ControlType enum not found"))
        val rendererText = productionFiles
            .filter { repoPath(it.path).contains("/inspector/") }
            .joinToString("\n") { it.text }
        return enumClass.enumConstants.mapNotNull { constant ->
            if (rendererText.contains("ControlType.${constant.name}")) return@mapNotNull null
            Violation(
                RULE_CONTROL, repoPath(registry.path), 0, constant.name,
                "control type ${constant.name} has no renderer branch in inspector/",
            )
        }
    }

    fun allViolations (): List<Violation> = buildList {
        addAll(layerViolations())
        addAll(platformLeakViolations())
        addAll(singleSourceViolations())
        addAll(qaPackageViolations())
        addAll(launchStateViolations())
        addAll(evidenceLiteralViolations())
        addAll(controlCoverageViolations())
    }

    // ------------------------------------------------------------------- utilities

    fun lineOf (location: String): Int =
        location.substringBeforeLast(':').substringAfterLast(':').toIntOrNull() ?: 0

    private fun com.lemonappdev.konsist.api.declaration.KoBaseDeclaration.declarationKindName (): String {
        return when (this) {
            is com.lemonappdev.konsist.api.declaration.KoObjectDeclaration -> "object"
            is com.lemonappdev.konsist.api.declaration.KoClassDeclaration -> "class"
            else -> "other"
        }
    }

    /** `KoBaseDeclaration` does not expose `name`; only named declarations do. */
    private fun com.lemonappdev.konsist.api.declaration.KoBaseDeclaration.declName (): String? =
        (this as? com.lemonappdev.konsist.api.provider.KoNameProvider)?.name
}

/**
 * The allowlist ratchet. See [Arch] for the contract.
 *
 * Matching key = `file|rule|symbol`; the `line` column is audit data only.
 */
internal object ArchAllowlist {
    private const val RESOURCE = "/arch/violations-allowlist.txt"

    data class Entry (val file: String, val rule: String, val line: Int, val symbol: String) {
        val key: String get() = "$file|$rule|$symbol"
        override fun toString (): String = "$file:$rule:$line:$symbol"
    }

    val entries: List<Entry> by lazy { load() }

    private fun load (): List<Entry> {
        val stream = ArchAllowlist::class.java.getResourceAsStream(RESOURCE)
            ?: error("missing allowlist resource $RESOURCE (expected src/desktopTest/resources$RESOURCE)")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
                val parts = line.split(':')
                require(parts.size >= 4) { "$RESOURCE:${index + 1}: expected file:rule:line:symbol, got '$line'" }
                val symbol = parts.drop(3).joinToString(":")
                Entry(parts[0], parts[1], parts[2].toIntOrNull() ?: 0, symbol)
            }.toList()
        }
    }

    fun forRule (rule: String): List<Entry> = entries.filter { it.rule == rule }
}

/** Asserts one rule against the allowlist and prints the numbers the report needs. */
internal fun assertRule (rule: String, violations: List<Arch.Violation>) {
    val allowed = ArchAllowlist.forRule(rule).map { it.key }.toSet()
    val new = violations.filter { it.key !in allowed }
    println("ARCH1-GATE $rule total=${violations.size} allowlisted=${violations.size - new.size} new=${new.size}")
    if (new.isNotEmpty()) {
        error(
            buildString {
                appendLine("ARCH1 $rule: ${new.size} violation(s) outside violations-allowlist.txt:")
                new.take(40).forEach { appendLine("  - $it") }
                if (new.size > 40) appendLine("  ... ${new.size - 40} more")
                appendLine("Fix the code, or add the entry only when the debt is accepted (the list is meant to shrink).")
            },
        )
    }
}

/** Every remaining violation must be justified once, and staleness is a failure. */
internal fun writeAllowlistIfRequested (violations: List<Arch.Violation>) {
    if (System.getenv("FM_ARCH_WRITE_ALLOWLIST") != "1") return
    val target = File(
        Konsist.projectRootPath,
        "composeApp/src/desktopTest/resources/arch/violations-allowlist.txt",
    )
    val grouped = violations.groupBy { it.rule }
    val text = buildString {
        appendLine("# ARCH1 arch gate allowlist — RATCHET: this list may only shrink.")
        appendLine("# Format: repo/relative/File.kt:rule:line:symbol")
        appendLine("#   line   = audit data only (first occurrence); matching uses file + rule + symbol,")
        appendLine("#            so unrelated edits in a legacy file cannot break the gate.")
        appendLine("#   symbol = import fq / concept / control type / evidence hook literal")
        appendLine("# Written by (PowerShell):")
        appendLine("#   \$env:FM_ARCH_WRITE_ALLOWLIST='1'; ./gradlew -p ui :composeApp:desktopTest --tests '*ArchAllowlistRegenerateTest*'")
        appendLine("# A stale entry (no matching violation) FAILS ArchAllowlistHygieneKonsistTest: delete it.")
        appendLine("#")
        appendLine("# Entries per rule:")
        for ((rule, list) in grouped.toSortedMap()) appendLine("#   $rule: ${list.size}")
        appendLine()
        for ((rule, list) in grouped.toSortedMap()) {
            appendLine("# ---- $rule")
            list.sortedWith(compareBy({ it.file }, { it.line }, { it.symbol })).forEach {
                appendLine("${it.file}:${it.rule}:${it.line}:${it.symbol}")
            }
            appendLine()
        }
    }
    target.parentFile.mkdirs()
    target.writeText(text, Charsets.UTF_8)
    println("ARCH1-ALLOWLIST written ${target.absolutePath} entries=${violations.size}")
}
