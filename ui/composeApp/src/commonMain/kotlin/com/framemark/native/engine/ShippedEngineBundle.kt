package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString

/**
 * The engine bundle the Android app ships, and how its identity is read.
 *
 * `assets/x1ir.zip` is a build product the APK version does not cover: a build can swap the
 * zip while the version string — and therefore the extraction left in the files dir — stays
 * the same. So the identity is derived from the bundle's own `manifest.json` ([identityOf]):
 * the same zip always yields the same string, and only a genuinely different bundle yields a
 * different one. Nothing here is a hand-maintained revision number.
 *
 * [ShippedEngineCoverageTest] recomputes this identity from the zip, so the format and the
 * "same bundle ⇒ same identity" property are pinned to the shipped data.
 *
 * Evidence (2026-11-13, 180-card device sweep): five `stock-vivo-disney-*` cards fell back to
 * the packaged static preview because the device read a 106-style bundle extracted on
 * 2026-10-29 while the APK shipped 111 — the bundle gained the five `vivo-disney:*` styles on
 * 2026-10-31 and the old hand-maintained marker never changed.
 */
internal object ShippedEngineBundle {
    /** Asset name of the runtime bundle inside the Android package. */
    const val ANDROID_ASSET = "x1ir.zip"

    /** Entry holding the bundle's own manifest; also the file name inside the extraction. */
    const val MANIFEST_ENTRY = "manifest.json"

    /**
     * Identity of a bundle manifest: `<schema> generatedAt=<generatedAt> styles=<count>`.
     *
     * Both inputs come from the document itself, so an identical bundle is always identical
     * here and never triggers a second unpack. Null when [manifestJson] is not a bundle
     * manifest (unreadable, truncated or wrong document) — callers must not treat that as
     * "the bundle changed", only as "unknown".
     */
    fun identityOf(manifestJson: String): String? {
        val doc = runCatching { Json.parse(manifestJson).jsonMap() }.getOrNull() ?: return null
        val schema = doc.jsonString("schema")?.takeIf { it.isNotBlank() } ?: return null
        val generatedAt = doc.jsonString("generatedAt")?.takeIf { it.isNotBlank() } ?: return null
        val styles = doc["styles"].jsonList().size
        if (styles == 0) return null
        return "$schema generatedAt=$generatedAt styles=$styles"
    }

    /**
     * Whether the extraction on disk can be reused instead of unpacking the bundle again.
     *
     * - nothing on disk, or an incomplete extraction → unpack;
     * - the bundle could not be read ([shipped] is null) → keep what is on disk: it is the
     *   only bundle there is, and a broken zip must not cost the user the working one;
     * - otherwise reuse exactly when the identities match, i.e. never on every launch.
     */
    fun canReuse(extracted: String?, shipped: String?, complete: Boolean): Boolean = when {
        extracted == null || !complete -> false
        shipped == null -> true
        else -> extracted == shipped
    }
}
