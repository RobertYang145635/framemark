package com.framemark.native.product

/**
 * Whitelist gate for every product string that can reach an engine argv (WP-X1 /
 * AU1-05).
 *
 * The desktop bridge builds `e2b … --theme <value> …` from
 * [DesignSnapshot.params]. A preset document is untrusted input (it is shared and
 * re-imported), and `ProcessBuilder` on Windows/Linux splits one argv element into
 * several arguments when it carries quotes, so a `theme` such as
 * `x" --out C:\evil.png --theme` used to become extra engine arguments.
 *
 * Two different value sets are involved, and conflating them was a bug on the
 * first attempt: the **hand** engine has exactly six backdrop themes, while the
 * **IR** (glass) engine accepts each style's own swatch ids (`glass`, `light_blur`,
 * `texture_*`, `transparent_white`, …). So:
 * - [THEMES] is the hand enum, used wherever a hand theme value is produced;
 * - every value that reaches argv must pass [isArgvSafeLiteral], which is what
 *   actually makes injection impossible;
 * - an IR value outside the hand enum is still legitimate and is passed through
 *   one argv element, while an unknown IR id is ignored by the engine itself.
 */
object EngineArgs {
    /**
     * Hand backdrop themes — the key set of `engine/data/hand/themes.json`
     * (`THEMES` in `core/draw.ts`). Frozen on purpose: a new hand theme needs a
     * new entry here *and* in the engine data.
     */
    val THEMES: List<String> = listOf("white", "paper", "cream", "stone", "ink", "black")

    /** The engine's default backdrop theme (`brandFooter.defaults.theme`). */
    const val DEFAULT_THEME: String = "white"

    /** Returns [raw] when it is a hand theme, otherwise `null`. */
    fun knownTheme(raw: String?): String? = raw?.takeIf { it in THEMES }

    /**
     * True when [value] can be handed to a process as exactly one argument:
     * letters, digits, `-`, `_`, `.` only. No quotes (the OS re-splits those), no
     * whitespace, no control characters, no path separators.
     */
    fun isArgvSafeLiteral(value: String): Boolean =
        value.isNotEmpty() && value.length <= 64 && value.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.'
        }

    /**
     * `theme` for the IR argv: the value when it is a single safe argument, else
     * `null` (the caller drops `--theme` and the engine keeps its own default).
     * The IR engine owns its swatch ids, so unknown-but-safe values pass through.
     */
    fun themeForArgv(raw: String?, onReject: (String) -> Unit = {}): String? {
        if (raw.isNullOrBlank()) return null
        if (isArgvSafeLiteral(raw)) return raw
        onReject(raw)
        return null
    }

    /** `theme` for the hand engine, which only knows the six registered themes. */
    fun handTheme(raw: String?, onReject: (String) -> Unit = {}): String? {
        val known = knownTheme(raw)
        if (raw != null && raw.isNotBlank() && known == null) onReject(raw)
        return known
    }
}
