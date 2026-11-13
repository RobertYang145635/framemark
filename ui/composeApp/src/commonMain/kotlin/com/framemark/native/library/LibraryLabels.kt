package com.framemark.native.library

/**
 * Display copy for a vendor card's second line.
 *
 * The catalogue's `family` field already carries the brand
 * (`OPPO 相册`, `OPPO 相机`, `小米相机`, `vivo 相机`), and both the library grid and the
 * parameter page joined it with the brand again — `OPPO · OPPO 相册` was on screen 459
 * times across the device dumps (UI copy audit 2026-11-13, item 2). The repeated leading
 * token is dropped here and only the distinguishing part is kept (`相册`, `相机`).
 *
 * Display only: `LibraryCard.brand` / `LibraryCard.family` are untouched, and the
 * persisted card id, style id and params are unaffected.
 */
internal object LibraryLabels {

    /** `brand · family` with the brand repeated at most once. */
    fun vendorSource(brand: String?, family: String?): String {
        val name = brand?.trim().orEmpty()
        val rest = family?.trim().orEmpty()
        if (name.isEmpty()) return rest
        if (rest.isEmpty()) return name
        if (rest.equals(name, ignoreCase = true)) return name
        if (rest.length > name.length && rest.startsWith(name, ignoreCase = true)) {
            val tail = rest.substring(name.length).trim(' ', '·', '/', '-', '—', '、')
            return if (tail.isEmpty()) name else "$name · $tail"
        }
        // The firmware-style spelling is `小米 / 徕卡水印`: keep the leaf either way.
        val leaf = rest.substringAfterLast('/').trim()
        if (leaf.isNotEmpty() && leaf != rest) return "$name · $leaf"
        return "$name · $rest"
    }
}
