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
     * 随包 zip 在 APK 中央目录里的指纹：**未压缩大小 + CRC32**，不读数据。
     *
     * 只靠 manifest 的 `generatedAt`/样式数做身份是不够的：数据侧改动（设计文档、
     * 变体、槽位表）不会动这两个字段。2026-11 的 rc54→rc55 就是这么踩的——解包身份
     * 一字不差，升级安装直接复用了旧解包，编辑器还画双行的「经典单行」，而缩略图
     * （APK 资源）已经换成新的。
     */
    data class BundleStamp(val size: Long, val crc: Long)

    /**
     * Identity of a bundle manifest: `<schema> generatedAt=<generatedAt> styles=<count>`,
     * 加上 zip 自身的 [stamp]（拿不到时写 `crc=- size=-`）。
     *
     * 同 manifest 同内容的 zip 必定同身份，绝不触发第二次解包；任何内容变化都会改变
     * CRC32，因此升级安装一定重解一次。Null when [manifestJson] is not a bundle
     * manifest (unreadable, truncated or wrong document) — callers must not treat that as
     * "the bundle changed", only as "unknown".
     */
    fun identityOf(manifestJson: String, stamp: BundleStamp? = null): String? {
        val doc = runCatching { Json.parse(manifestJson).jsonMap() }.getOrNull() ?: return null
        val schema = doc.jsonString("schema")?.takeIf { it.isNotBlank() } ?: return null
        val generatedAt = doc.jsonString("generatedAt")?.takeIf { it.isNotBlank() } ?: return null
        val styles = doc["styles"].jsonList().size
        if (styles == 0) return null
        val crc = stamp?.crc?.toString(16) ?: "-"
        val size = stamp?.size?.toString() ?: "-"
        return "$schema generatedAt=$generatedAt styles=$styles crc=$crc size=$size"
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
