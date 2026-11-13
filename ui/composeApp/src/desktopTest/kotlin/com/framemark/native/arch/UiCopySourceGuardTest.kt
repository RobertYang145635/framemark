package com.framemark.native.arch

import com.framemark.native.product.RealData
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, the guard for the whole class of findings: **no developer
 * marker may sit in a production UI string**.
 *
 * This is the source-sweep half of the audit as a gate, in the same spirit as
 * `EvidenceHookKonsistTest` (which already forbids `FM_*` hook names in product
 * source). It walks `commonMain` / `androidMain` / `desktopMain` — never the tests —
 * and fails on a *string literal* that carries a marker a user would read as
 * unfinished work: `TODO`, `FIXME`, `HACK`, `XXX`, `WIP`, `lorem ipsum`, or the
 * English words `debug` / `placeholder` / `pragma`. Comments are blanked first, so
 * the planning comments in product source stay legal — the rule is about what is
 * *rendered*, not what the team wrote down.
 *
 * The leaks this audit fixed are each pinned by their own test
 * (`PhotoTrayLabelTest`, `LogoVersionSubtitleTest`, `PhoneSettingsTextTest`,
 * `BatchScreenCopyTest`); this one keeps new ones out.
 */
class UiCopySourceGuardTest {

    /** Markers that must never appear inside a production string literal. */
    private val forbidden = listOf(
        Regex("\\bTODO\\b"),
        Regex("\\bFIXME\\b"),
        Regex("\\bHACK\\b"),
        Regex("\\bXXX+\\b"),
        Regex("\\bWIP\\b"),
        Regex("\\blorem ipsum\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdebug\\b", RegexOption.IGNORE_CASE),
        Regex("\\bplaceholder\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpragma\\b", RegexOption.IGNORE_CASE),
    )

    @Test
    fun productionStringLiteralsCarryNoDeveloperMarkers() {
        val root = RealData.root.resolve("ui/composeApp/src")
        val findings = mutableListOf<String>()
        var scanned = 0
        for (sourceSet in Arch.productionSourceSets) {
            val dir = root.resolve(sourceSet)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                    scanned++
                    findings += violationsIn(file, root.relativize(file).toString().replace('\\', '/'))
                }
            }
        }
        assertTrue(scanned > 100, "expected the production tree, scanned only $scanned files")
        assertTrue(
            findings.isEmpty(),
            "developer markers inside production string literals:\n" + findings.joinToString("\n"),
        )
    }

    private fun violationsIn(file: Path, label: String): List<String> {
        val masked = blankComments(file.readText())
        val hits = mutableListOf<String>()
        STRING.findAll(masked).forEach { match ->
            val literal = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val line = masked.substring(0, match.range.first).count { it == '\n' } + 1
            forbidden.forEach { rule ->
                val found = rule.find(literal) ?: return@forEach
                hits += "$label:$line: '$literal' carries '${found.value}'"
            }
        }
        return hits
    }

    /**
     * Replaces every `//` and `/* */` comment character with a space (newlines are
     * kept) so offsets and line numbers survive; string and char literals are copied
     * through untouched, because that is where a marker would be rendered.
     */
    private fun blankComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var state = 0 // 0 code, 1 line comment, 2 block comment, 3 string, 4 char, 5 raw string
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { state = 1; out.append("  "); i += 2 }
                    c == '/' && next == '*' -> { state = 2; out.append("  "); i += 2 }
                    src.startsWith("\"\"\"", i) -> { state = 5; out.append("\"\"\""); i += 3 }
                    c == '"' -> { state = 3; out.append(c); i++ }
                    c == '\'' -> { state = 4; out.append(c); i++ }
                    else -> { out.append(c); i++ }
                }
                1 -> {
                    if (c == '\n') { state = 0; out.append('\n') } else out.append(' ')
                    i++
                }
                2 -> {
                    if (c == '*' && next == '/') { state = 0; out.append("  "); i += 2 } else {
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                3 -> {
                    if (c == '\\') { out.append(src, i, i + 2); i += 2 } else {
                        if (c == '"') state = 0
                        out.append(c)
                        i++
                    }
                }
                4 -> {
                    if (c == '\\') { out.append(src, i, i + 2); i += 2 } else {
                        if (c == '\'') state = 0
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    if (src.startsWith("\"\"\"", i)) { state = 0; out.append("\"\"\""); i += 3 } else {
                        out.append(c)
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    private companion object {
        val STRING = Regex("\"\"\"((?:.|\n)*?)\"\"\"|\"((?:[^\"\\\\\n]|\\\\.)*)\"")
    }
}
