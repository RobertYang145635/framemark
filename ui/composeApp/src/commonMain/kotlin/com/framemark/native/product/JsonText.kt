package com.framemark.native.product

/**
 * Escapes UTF-16 surrogate halves of a serialized JSON text as `\uXXXX`.
 *
 * The Android engine bridge hands the design document to native code through a
 * JNI string, and `GetStringUTFChars` produces *modified* UTF-8: a character
 * outside the Basic Multilingual Plane (an emoji, `𠮷`, some symbols) becomes two
 * three-byte surrogate encodings, which is not valid UTF-8. The engine's Rust
 * side decodes that with `str::from_utf8` and fails, so a single emoji anywhere
 * in a text slot made **every** render and export fail with "design_json is not
 * UTF-8".
 *
 * Escaping the surrogate halves keeps the payload pure ASCII; JSON reconstruction
 * happens in the engine's own parser, which turns the two `\uXXXX` sequences back
 * into the original character. BMP text (Chinese, Japanese, accented Latin) is
 * already valid when re-encoded and is returned untouched, so the common case
 * pays nothing.
 *
 * Only string *contents* can contain characters above U+007F, because JSON syntax
 * is ASCII, so a blanket pass over the text is safe.
 */
fun jsonWithSurrogatesEscaped(json: String): String {
    var needsEscape = false
    for (c in json) {
        if (c.code in 0xD800..0xDFFF) { needsEscape = true; break }
    }
    if (!needsEscape) return json
    val out = StringBuilder(json.length + 16)
    for (c in json) {
        if (c.code in 0xD800..0xDFFF) {
            out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        } else {
            out.append(c)
        }
    }
    return out.toString()
}
