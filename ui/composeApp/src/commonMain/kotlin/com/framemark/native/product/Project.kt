package com.framemark.native.product

const val PROJECT_SCHEMA_VERSION = 1

/** A photo in the project. The file is referenced, never copied into the project. */
data class PhotoRef(
    val id: String,
    val path: String,
    val displayName: String,
    /** EXIF orientation 1..8; 0 when the importer could not read it. */
    val orientation: Int = 1,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** Content hash for batch idempotency and relinking; null when unavailable. */
    val fingerprint: String? = null,
    /**
     * 照片自己的拍摄时间（EXIF `DateTimeOriginal`，规范成 `yyyy-MM-dd HH:mm:ss`）。
     * 导出命名的 `{date}` / `{time}` 用它；null = 这张照片没有拍摄时间，命名退回
     * 当前时钟，界面必须说明这一点。
     */
    val capturedAt: String? = null,
    /**
     * 用户实际导入的那个文件（原图）的绝对路径；[path] 是工程真正渲染的副本。
     *
     * 只有格式需要转码时两者不同（桌面导入 TIFF / WebP 会转成引擎能读的 JPEG/PNG）。
     * 留着它有两个用处：**去重**（同一张原图重复导入要点回同一张照片）和**溯源**
     * （原文件被移动/删除时能告诉用户渲染副本的来历）。null = 导入的就是原图。
     */
    val sourcePath: String? = null,
)

/**
 * One editing project: the photo list, one design per photo and the shared export
 * settings. Operations return new instances; a photo switch therefore cannot
 * mutate another photo's design or watermark.
 *
 * New photos inherit [sharedDesign] (falling back to the current photo's design)
 * **without** its watermark. A signature only reaches other photos through the
 * explicit [applyWatermarkToAll] action.
 */
data class Project(
    val schemaVersion: Int = PROJECT_SCHEMA_VERSION,
    val id: String,
    val photos: List<PhotoRef> = emptyList(),
    val activePhotoId: String? = null,
    /** Keyed by [PhotoRef.id]; designs of removed photos are dropped with them. */
    val designs: Map<String, PhotoDesign> = emptyMap(),
    /**
     * Explicit default ("shared") design for photos added later: the card and the
     * parameter/text values a new photo starts from. It never carries a watermark;
     * set it through [setSharedDesign], which strips one if present.
     */
    val sharedDesign: PhotoDesign? = null,
    val selectedCardId: String? = null,
    val exportSettings: ExportSettings = ExportSettings(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val activePhoto: PhotoRef? get() = photos.firstOrNull { it.id == activePhotoId }

    val activeDesign: PhotoDesign? get() = activePhotoId?.let { designs[it] }

    fun designOf(photoId: String): PhotoDesign? = designs[photoId]

    /**
     * The design a newly added photo receives.
     *
     * Priority: explicit [inheritedDesign] → explicit [sharedDesign] → **the current
     * photo's border without its tweaks** → the card selected in the library.
     *
     * 2026-11-21（用户反馈「导入新照片沿用上一张的设置，很没有道理」）：上一张照片的
     * 设计只贡献**边框**（cardId + styleId），参数/文字/主题都回到该边框的默认值 ——
     * 新照片从自己的默认状态开始，用户改过的值不会悄悄复制到下一张。显式的
     * [sharedDesign]（「默认设计」）仍然整套生效：那是用户明确声明的默认值。
     * 水印两种情况下都不继承（只经 [applyWatermarkToAll] 传播）。
     */
    fun designForNewPhoto(inheritedDesign: PhotoDesign? = null): PhotoDesign? =
        (inheritedDesign
            ?: sharedDesign
            ?: activeDesign?.let { PhotoDesign.base(it.cardId, it.styleId) }
            ?: selectedCardId?.takeIf { it.isNotBlank() }?.let { PhotoDesign.base(it) })
            ?.withoutOverlay()

    /**
     * Adds a photo with its own copy of the current/default design. Values are
     * copied, so later edits on either photo stay independent; the watermark is
     * **not** inherited (PRD US-04: a signature is per photo).
     */
    fun addPhoto(photo: PhotoRef, inheritedDesign: PhotoDesign? = null): Project {
        if (photos.any { it.id == photo.id }) return this
        val initial = designForNewPhoto(inheritedDesign)
        return copy(
            photos = photos + photo,
            activePhotoId = activePhotoId ?: photo.id,
            designs = if (initial == null) designs else designs + (photo.id to initial),
        )
    }

    /** Sets (or clears) the default design for future photos; a watermark is stripped. */
    fun setSharedDesign(design: PhotoDesign?): Project {
        val normalized = design?.withoutOverlay()
        if (normalized == sharedDesign) return this
        return copy(
            sharedDesign = normalized,
            selectedCardId = normalized?.cardId ?: selectedCardId,
        )
    }

    /**
     * The explicit "apply to all photos" action. Copies [spec] (default: the active
     * photo's watermark) onto every photo; passing null clears every watermark.
     * Photos without a design get one when a card is known, otherwise they stay
     * untouched (there is no card to render them with).
     */
    fun applyWatermarkToAll(spec: ProjectWatermarkSpec? = activeDesign?.overlay): Project {
        if (photos.isEmpty()) return this
        val fallbackCard = sharedDesign?.cardId ?: selectedCardId ?: activeDesign?.cardId
        val next = LinkedHashMap(designs)
        var changed = false
        photos.forEach { photo ->
            val current = designs[photo.id]
                ?: fallbackCard?.takeIf { it.isNotBlank() }?.let { PhotoDesign.base(it) }
                ?: return@forEach
            if (current.overlay == spec) return@forEach
            next[photo.id] = current.copy(overlay = spec)
            changed = true
        }
        return if (changed) copy(designs = next) else this
    }

    fun removePhoto(photoId: String): Project {
        if (photos.none { it.id == photoId }) return this
        val remaining = photos.filterNot { it.id == photoId }
        return copy(
            photos = remaining,
            activePhotoId = if (activePhotoId == photoId) remaining.firstOrNull()?.id else activePhotoId,
            designs = designs - photoId,
        )
    }

    fun selectPhoto(photoId: String): Project =
        if (photoId == activePhotoId || photos.none { it.id == photoId }) this else copy(activePhotoId = photoId)

    fun setDesign(photoId: String, design: PhotoDesign): Project =
        if (photos.none { it.id == photoId }) this else copy(designs = designs + (photoId to design))

    /** Edits the active photo only; no active photo (or no design) is a no-op. */
    fun updateActiveDesign(transform: (PhotoDesign) -> PhotoDesign): Project {
        val photoId = activePhotoId ?: return this
        val current = designs[photoId] ?: return this
        val next = transform(current)
        return if (next == current) this else copy(designs = designs + (photoId to next))
    }

    /**
     * Applies a border to the active photo. Parameters reset to the card default
     * (a preset's values arrive through [setDesign]); the watermark survives.
     */
    fun applyCard(cardId: String, styleId: String? = null): Project {
        val photoId = activePhotoId ?: return copy(selectedCardId = cardId)
        val overlay = designs[photoId]?.overlay
        val next = PhotoDesign.base(cardId, styleId).let {
            if (overlay != null) it.copy(overlay = overlay) else it
        }
        return copy(selectedCardId = cardId, designs = designs + (photoId to next))
    }

    fun withExportSettings(settings: ExportSettings): Project =
        if (settings == exportSettings) this else copy(exportSettings = settings)

    fun touched(now: Long): Project = copy(updatedAt = now)
}
