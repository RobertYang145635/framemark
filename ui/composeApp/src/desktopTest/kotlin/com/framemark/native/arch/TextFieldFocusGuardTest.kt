package com.framemark.native.arch

import com.framemark.native.product.RealData
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W-01 structural guard: **every editable text field must join the text-focus
 * registry**, otherwise the shell keeps stealing Backslash / the arrow keys /
 * Ctrl+Z from that one field.
 *
 * The audit's complaint was that the old guard named a single field
 * (`librarySearchFocused`), so each new input re-created the bug. Per-field focus
 * reporting is unavoidable (Compose exposes no "is a text field focused" query),
 * so this test enforces the invariant mechanically: a `BasicTextField(` or
 * `OutlinedTextField(` call in production source must carry
 * `.trackTextEditing()` inside its argument list. It walks the same production
 * tree as the other arch guards and reports every offender with a line number.
 */
class TextFieldFocusGuardTest {

    private val fieldCall = Regex("""(?:androidx\.compose\.foundation\.text\.)?(?:BasicTextField|OutlinedTextField)\s*\(""")

    @Test
    fun every_editable_text_field_tracks_its_focus() {
        val root = RealData.root.resolve("ui/composeApp/src")
        val findings = mutableListOf<String>()
        var scanned = 0
        var fields = 0
        for (sourceSet in Arch.productionSourceSets) {
            val dir = root.resolve(sourceSet)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                    scanned++
                    val text = file.readText()
                    val label = root.relativize(file).toString().replace('\\', '/')
                    fieldCall.findAll(text).forEach { match ->
                        fields++
                        val call = callText(text, match.range.last)
                        if (!call.contains("trackTextEditing")) {
                            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                            findings += "$label:$line: text field without .trackTextEditing()"
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 100, "expected the production tree, scanned only $scanned files")
        assertTrue(fields >= 15, "expected the text fields to be found, saw $fields")
        assertTrue(
            findings.isEmpty(),
            "text fields that would keep the shell's shortcuts:\\n" + findings.joinToString("\\n"),
        )
    }

    /**
     * The full call text from the opening `(` to its matching `)`, skipping string
     * and char literals so a placeholder containing parentheses cannot end the call
     * early. Returns "" when the text is unbalanced (a finding in itself).
     */
    private fun callText(text: String, openParen: Int): String {
        var depth = 0
        var i = openParen
        var inString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    c == '\\' -> i++
                    c == '"' -> inString = false
                }
            } else if (inChar) {
                when {
                    c == '\\' -> i++
                    c == '\'' -> inChar = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '\'' -> inChar = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return text.substring(openParen, i + 1)
                    }
                }
            }
            i++
        }
        return ""
    }
}
