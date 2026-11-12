package com.framemark.native.product

/**
 * The one entry point for the QA evidence protocol.
 *
 * QA automation greps these lines; the format is therefore part of the contract:
 * a line starts with the tag verbatim (`"<tag> key=value …"`) and keeps its
 * key=value shape. Call sites name the tag as a **literal first argument** so the
 * arch allowlist's evidence-hook entries keep matching the source text.
 *
 * Two rules the call sites do not repeat:
 *
 *  - **Gating.** `info` is verbose-only; `warn` and `error` are always written.
 *    The verbose flag is a compile-time constant per source set (see
 *    [fmLogVerboseDefault]) so a release build pays neither the string work nor
 *    the I/O for the 300+ evidence probes. Messages are lazy
 *    (`message: () -> String`), so a gated-off line never builds its string.
 *  - **Redaction.** Absolute paths become their basename, `content://` URIs keep
 *    scheme + last path segment, and over-long single-token payloads (EXIF blobs,
 *    base64 dumps) become `<N chars>`. Redaction runs on the built line, so the
 *    shape stays parseable while no user path leaves the device.
 *
 * Output goes through [fmLogWrite] so `error` can keep going to stderr (the desktop
 * bridge's stdout is a JSON protocol channel); tests replace [sink] to capture lines.
 */
object FmLog {

    /** Verbose in debug/QA builds; warn/error only in release. Compile-time per source set. */
    internal var verbose: Boolean = fmLogVerboseDefault

    /** Where a line lands. `toError` selects the error channel (stderr) over stdout. */
    internal var sink: (line: String, toError: Boolean) -> Unit = { line, toError -> fmLogWrite(line, toError) }

    /** Verbose-only evidence line (the bulk of the probes). */
    fun info(tag: String, message: () -> String) = emit(tag, message, toError = false, verboseOnly = true)

    /** A probe that carries no fields: the tag is the whole line. */
    fun info(tag: String) = emit(tag, { "" }, toError = false, verboseOnly = true)

    /** Something the user lost or the app refused; kept in release builds. */
    fun warn(tag: String, message: () -> String) = emit(tag, message, toError = false, verboseOnly = false)

    fun warn(tag: String) = emit(tag, { "" }, toError = false, verboseOnly = false)

    /** A failed step; kept in release builds and written to stderr. */
    fun error(tag: String, message: () -> String) = emit(tag, message, toError = true, verboseOnly = false)

    fun error(tag: String) = emit(tag, { "" }, toError = true, verboseOnly = false)

    /**
     * The redaction rule, exposed for tests. Only the message is redacted — the
     * tag is a fixed identifier.
     *
     * Two escapes are closed here:
     *  - a path may contain spaces (`C:\Users\John Doe\pic.jpg`), so a value that
     *    starts like an absolute path keeps consuming the following non-key tokens
     *    and the *whole* path is reduced to its basename;
     *  - a CR/LF inside the message would emit a second, unredacted line whose shape
     *    is indistinguishable from a real evidence line (log forging). They are
     *    replaced by the literal `\r` / `\n` before tokenising, so one call is
     *    always exactly one line.
     */
    fun redact(message: String): String {
        if (message.isEmpty()) return message
        val singleLine = message.replace("\r", "\\r").replace("\n", "\\n")
        val tokens = singleLine.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val out = ArrayList<String>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val attribute = attributeOf(token)
            when {
                attribute != null && startsPath(attribute.second) -> {
                    var whole = attribute.second
                    var next = index + 1
                    while (next < tokens.size && attributeOf(tokens[next]) == null) {
                        whole += " " + tokens[next]
                        next++
                    }
                    out += "${attribute.first}=${redactValue(whole)}"
                    index = next
                }
                attribute == null && startsPath(token) -> {
                    var whole = token
                    var next = index + 1
                    while (next < tokens.size && attributeOf(tokens[next]) == null) {
                        whole += " " + tokens[next]
                        next++
                    }
                    out += redactValue(whole)
                    index = next
                }
                else -> {
                    out += redactToken(token)
                    index++
                }
            }
        }
        return out.joinToString(" ")
    }

    private fun emit(tag: String, message: () -> String, toError: Boolean, verboseOnly: Boolean) {
        if (verboseOnly && !verbose) return
        val body = message()
        val raw = if (body.isEmpty()) tag else "$tag $body"
        // Always through redact: it is also the CR/LF sanitiser, so even a tag-only
        // line cannot smuggle a second line into the evidence stream.
        sink(redact(raw), toError)
    }

    // ------------------------------------------------------------------ redaction

    /** `key=value` when [token] is an attribute pair; null for tags, paths and bare words. */
    private fun attributeOf(token: String): Pair<String, String>? {
        val eq = token.indexOf('=')
        if (eq <= 0 || eq == token.lastIndex) return null
        if (!isAttributeKey(token.substring(0, eq))) return null
        return token.substring(0, eq) to token.substring(eq + 1)
    }

    /** True when a bare token or an attribute value begins an absolute path. */
    private fun startsPath(value: String): Boolean =
        WINDOWS_ABSOLUTE.containsMatchIn(value) || value.startsWith('/')

    /**
     * One whitespace-delimited token. `key=value` keeps its key; the value is what
     * may be a path, a URI or a payload. A token without '=' is redacted whole.
     */
    private fun redactToken(token: String): String {
        val eq = token.indexOf('=')
        if (eq <= 0 || eq == token.lastIndex) return redactValue(token)
        val key = token.substring(0, eq)
        // Only attribute-looking keys are split; "a=b=c" (a base64 blob) stays whole.
        if (!isAttributeKey(key)) return redactValue(token)
        val redacted = redactValue(token.substring(eq + 1))
        return if (redacted == token.substring(eq + 1)) token else "$key=$redacted"
    }

    private fun isAttributeKey(key: String): Boolean =
        key.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == ':' || it == '-' }

    private fun redactValue(value: String): String {
        if (value.isEmpty()) return value
        // Keep trailing punctuation ("a=b.jpg," / "(see a=b.jpg)") out of the basename.
        val trailing = value.takeLastWhile { it in TRAILING }
        val core = value.dropLast(trailing.length)
        if (core.isEmpty()) return value
        val redacted = when {
            core.startsWith("content://") -> "content://" + lastSegment(core)
            isAbsolutePath(core) -> basename(core)
            core.length > MAX_VERBATIM_CHARS -> "<${core.length} chars>"
            else -> return value
        }
        return redacted + trailing
    }

    private fun isAbsolutePath(value: String): Boolean =
        WINDOWS_ABSOLUTE.containsMatchIn(value) || POSIX_ABSOLUTE.matches(value)

    private fun basename(path: String): String {
        val cut = path.indexOfLast { it == '/' || it == '\\' }
        return if (cut < 0 || cut == path.lastIndex) path else path.substring(cut + 1)
    }

    /** Scheme + last path segment; the authority and directory structure are dropped. */
    private fun lastSegment(uri: String): String {
        val rest = uri.removePrefix("content://")
        val cut = rest.indexOfLast { it == '/' }
        return if (cut < 0) rest else rest.substring(cut + 1)
    }

    private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:\\""")

    /** Any whitespace run — a newline is a delimiter too, so it cannot forge a line. */
    private val WHITESPACE = Regex("\\s+")

    /** An absolute POSIX path: at least two separators and a non-empty basename. */
    private val POSIX_ABSOLUTE = Regex("""^/(?:[^/]+/)+[^/]+$""")

    private const val MAX_VERBATIM_CHARS = 200
    private const val TRAILING = ",;)]}。，"
}

/**
 * True when this build logs verbosely (debug/QA). Each source set supplies its own
 * compile-time constant: Android reads `BuildConfig.DEBUG`, the desktop build is a
 * QA build with no release build type of its own. Never a runtime env read.
 */
internal expect val fmLogVerboseDefault: Boolean

/** Platform write: `toError` keeps the desktop bridge's stdout free for its JSON protocol. */
internal expect fun fmLogWrite(line: String, toError: Boolean)
