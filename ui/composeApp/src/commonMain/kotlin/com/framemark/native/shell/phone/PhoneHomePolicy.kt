package com.framemark.native.shell.phone

/**
 * 首页 routing (report §7.1, hand-off part 2 §3 decision 3).
 *
 * The phone home has four entries plus the gear: the big card picks photos,
 * 批量 goes to the per-photo batch export (WP-P2 §3.4 moved 批量导出 here from the
 * editor's stage menu), 预设 opens the preset board and the gear opens 设置.
 * The rules below are pure so the routing can be tested without a composition;
 * [com.framemark.native.shell.PhoneShell] only executes the returned destination.
 */
enum class PhoneHomeDestination {
    /** The system photo picker (the hero card and 批量 on an empty project). */
    PickPhotos,

    /** 按照片批量导出: the project already has photos. */
    BatchExport,

    /** The preset board; needs at least one photo to apply to. */
    Presets,

    /** The entry needs a photo first; the caller shows a hint instead of a dead tap. */
    PickPhotosFirst,

    /** 设置 (appearance + version). */
    Settings,

    /** Open that photo in the editor. */
    Editor,
}

/**
 * One 最近编辑 row. The project keeps photos in list order and each photo carries
 * its own design (M01 rule 1), so the row shows the photo plus the border it wears.
 */
data class PhoneRecentEdit(
    val photoId: String,
    val path: String?,
    val displayName: String,
    /** The border of this photo's own design, or null when it has none yet. */
    val borderName: String? = null,
    /** 1-based position in the project, shown as 2/3. */
    val index: Int = 1,
    val count: Int = 1,
) {
    /** The second line of the row: border · position, never an empty string. */
    fun subtitle(): String {
        val border = borderName?.takeIf { it.isNotBlank() } ?: "未应用边框"
        return if (count > 1) "$border · $index/$count" else border
    }
}

object PhoneHomePolicy {
    /** How many 最近编辑 rows the home shows (the demo shows three). */
    const val RECENT_LIMIT: Int = 3

    fun hero(): PhoneHomeDestination = PhoneHomeDestination.PickPhotos

    /** 批量: an empty project has nothing to export, so it opens the multi picker. */
    fun batch(photoCount: Int): PhoneHomeDestination =
        if (photoCount > 0) PhoneHomeDestination.BatchExport else PhoneHomeDestination.PickPhotos

    /** 预设: the board applies to the active photo, so an empty project asks first. */
    fun presets(photoCount: Int): PhoneHomeDestination =
        if (photoCount > 0) PhoneHomeDestination.Presets else PhoneHomeDestination.PickPhotosFirst

    fun settings(): PhoneHomeDestination = PhoneHomeDestination.Settings

    /** A 最近编辑 row always opens its photo; without one the card is not shown. */
    fun recent(rowExists: Boolean): PhoneHomeDestination =
        if (rowExists) PhoneHomeDestination.Editor else PhoneHomeDestination.PickPhotos

    /** Newest first, capped at [limit]; the project list is append-ordered. */
    fun recentEdits(all: List<PhoneRecentEdit>, limit: Int = RECENT_LIMIT): List<PhoneRecentEdit> =
        all.reversed().take(limit.coerceAtLeast(0))

    /** What the caller says when an entry needs a photo first. */
    const val PICK_PHOTOS_FIRST_HINT: String = "先选择照片，再使用这一项。"
}
