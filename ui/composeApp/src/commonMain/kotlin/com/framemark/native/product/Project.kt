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
     * The design a newly added photo receives, in priority order: explicit
     * [inheritedDesign], the project's [sharedDesign], the current photo's design,
     * the card selected in the library. The watermark is always dropped — a
     * signature reaches other photos only through [applyWatermarkToAll].
     */
    fun designForNewPhoto(inheritedDesign: PhotoDesign? = null): PhotoDesign? =
        (inheritedDesign ?: sharedDesign ?: activeDesign
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
