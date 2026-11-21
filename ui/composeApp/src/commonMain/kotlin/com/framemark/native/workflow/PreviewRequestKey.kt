package com.framemark.native.workflow

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Immutable value identity for a stage-preview render. The design maps are
 * copied into ordered value snapshots so mutating a caller-owned map cannot
 * mutate a key already used by the cache.
 */
internal class PreviewRequestKey private constructor(
    private val cardId: String,
    private val designCardId: String,
    private val styleId: String?,
    private val params: List<NamedPreviewValue>,
    private val text: List<NamedPreviewValue>,
    private val logoBrand: String?,
    private val userLogoRevision: Int,
    private val photoPath: String?,
    private val selectedPhotoPath: String?,
    private val launchPhotoPath: String?,
    private val photoSelectionVersion: Int,
    private val maxEdge: Int,
    private val overlay: OverlaySpec?,
    private val designJsonOverride: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is PreviewRequestKey && sameInputs(other) && maxEdge == other.maxEdge

    /** Reuse a completed frame only while every non-size render input still matches. */
    fun sameStageInputs(other: PreviewRequestKey): Boolean = sameInputs(other)

    /**
     * Failure recovery may retain stable text identities only for the same card
     * and the same effective photo input. Design edits, overlays and preview size
     * do not change slot ownership; card or photo changes do.
     */
    fun sameTextSlotInputs(other: PreviewRequestKey): Boolean =
        cardId == other.cardId &&
            designCardId == other.designCardId &&
            photoPath == other.photoPath &&
            selectedPhotoPath == other.selectedPhotoPath &&
            launchPhotoPath == other.launchPhotoPath &&
            photoSelectionVersion == other.photoSelectionVersion

    fun withMaxEdge(edge: Int): PreviewRequestKey = PreviewRequestKey(
        cardId = cardId,
        designCardId = designCardId,
        styleId = styleId,
        params = params,
        text = text,
        logoBrand = logoBrand,
        userLogoRevision = userLogoRevision,
        photoPath = photoPath,
        selectedPhotoPath = selectedPhotoPath,
        launchPhotoPath = launchPhotoPath,
        photoSelectionVersion = photoSelectionVersion,
        maxEdge = edge,
        overlay = overlay,
        designJsonOverride = designJsonOverride,
    )

    private fun sameInputs(other: PreviewRequestKey): Boolean =
        cardId == other.cardId &&
            designCardId == other.designCardId &&
            styleId == other.styleId &&
            params == other.params &&
            text == other.text &&
            logoBrand == other.logoBrand &&
            userLogoRevision == other.userLogoRevision &&
            photoPath == other.photoPath &&
            selectedPhotoPath == other.selectedPhotoPath &&
            launchPhotoPath == other.launchPhotoPath &&
            photoSelectionVersion == other.photoSelectionVersion &&
            overlay == other.overlay &&
            designJsonOverride == other.designJsonOverride

    // Hash maps still need a bucket hash; equality always compares the full
    // captured render inputs, so ordinary hash collisions cannot alias renders.
    override fun hashCode(): Int {
        var result = cardId.hashCode()
        result = 31 * result + designCardId.hashCode()
        result = 31 * result + (styleId?.hashCode() ?: 0)
        result = 31 * result + params.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (logoBrand?.hashCode() ?: 0)
        result = 31 * result + userLogoRevision
        result = 31 * result + (photoPath?.hashCode() ?: 0)
        result = 31 * result + (selectedPhotoPath?.hashCode() ?: 0)
        result = 31 * result + (launchPhotoPath?.hashCode() ?: 0)
        result = 31 * result + photoSelectionVersion
        result = 31 * result + maxEdge
        result = 31 * result + (overlay?.hashCode() ?: 0)
        result = 31 * result + (designJsonOverride?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun create(
            cardId: String,
            design: DesignSnapshot,
            photoPath: String?,
            selectedPhotoPath: String?,
            launchPhotoPath: String?,
            photoSelectionVersion: Int,
            maxEdge: Int,
            overlay: OverlaySpec?,
            designJsonOverride: String?,
            userLogoRevision: Int = 0,
        ): PreviewRequestKey = PreviewRequestKey(
            cardId = cardId,
            designCardId = design.cardId,
            styleId = design.styleId,
            params = snapshotMap(design.params),
            text = snapshotMap(design.text),
            logoBrand = design.logoBrand,
            userLogoRevision = userLogoRevision,
            photoPath = photoPath,
            selectedPhotoPath = selectedPhotoPath,
            launchPhotoPath = launchPhotoPath,
            photoSelectionVersion = photoSelectionVersion,
            maxEdge = maxEdge,
            overlay = overlay,
            designJsonOverride = designJsonOverride,
        )
    }
}

/** Copy mutable design containers once so rendering consumes the keyed snapshot. */
internal fun snapshotPreviewDesign(design: DesignSnapshot): DesignSnapshot = design.copy(
    params = snapshotRenderMap(design.params),
    text = snapshotRenderMap(design.text),
)

private fun snapshotRenderMap(values: Map<String, Any?>): Map<String, Any?> =
    LinkedHashMap<String, Any?>(values.size).apply {
        values.forEach { (key, value) -> put(key, copyRenderValue(value)) }
    }

private fun copyRenderValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> LinkedHashMap<Any?, Any?>(value.size).apply {
        value.forEach { (key, item) -> put(copyRenderValue(key), copyRenderValue(item)) }
    }
    is List<*> -> value.map(::copyRenderValue)
    is Set<*> -> value.mapTo(LinkedHashSet()) { copyRenderValue(it) }
    is Iterable<*> -> value.map(::copyRenderValue)
    is Array<*> -> value.map(::copyRenderValue).toTypedArray()
    is ByteArray -> value.copyOf()
    is ShortArray -> value.copyOf()
    is IntArray -> value.copyOf()
    is LongArray -> value.copyOf()
    is FloatArray -> value.copyOf()
    is DoubleArray -> value.copyOf()
    is BooleanArray -> value.copyOf()
    else -> value
}

private data class NamedPreviewValue(val name: String, val value: PreviewValue)

/** Values accepted by the render JSON contract, copied recursively for safety. */
private sealed interface PreviewValue {
    data object NullValue : PreviewValue
    data class StringValue(val value: String) : PreviewValue
    data class BooleanValue(val value: Boolean) : PreviewValue
    data class NumberValue(val type: String, val value: String) : PreviewValue
    data class MapValue(val entries: List<Pair<PreviewValue, PreviewValue>>) : PreviewValue
    data class SequenceValue(val entries: List<PreviewValue>) : PreviewValue
    data class OtherValue(val type: String, val value: String) : PreviewValue
}

private fun snapshotMap(values: Map<String, Any?>): List<NamedPreviewValue> =
    values.entries
        .sortedBy { it.key }
        .map { (name, value) -> NamedPreviewValue(name, snapshotValue(value)) }

private fun snapshotValue(value: Any?): PreviewValue = when (value) {
    null -> PreviewValue.NullValue
    is String -> PreviewValue.StringValue(value)
    is Boolean -> PreviewValue.BooleanValue(value)
    is Number -> PreviewValue.NumberValue(numberType(value), value.toString())
    is Map<*, *> -> PreviewValue.MapValue(value.entries.map { (key, item) ->
        snapshotValue(key) to snapshotValue(item)
    })
    is Iterable<*> -> PreviewValue.SequenceValue(value.map(::snapshotValue))
    is Array<*> -> PreviewValue.SequenceValue(value.map(::snapshotValue))
    is ByteArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Byte", it.toString()) })
    is ShortArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Short", it.toString()) })
    is IntArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Int", it.toString()) })
    is LongArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Long", it.toString()) })
    is FloatArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Float", it.toString()) })
    is DoubleArray -> PreviewValue.SequenceValue(value.map { PreviewValue.NumberValue("Double", it.toString()) })
    is BooleanArray -> PreviewValue.SequenceValue(value.map { PreviewValue.BooleanValue(it) })
    else -> PreviewValue.OtherValue(value::class.simpleName.orEmpty(), value.toString())
}

private fun numberType(value: Number): String = when (value) {
    is Byte -> "Byte"
    is Short -> "Short"
    is Int -> "Int"
    is Long -> "Long"
    is Float -> "Float"
    is Double -> "Double"
    else -> "Number"
}

/** One cache value keeps pixels, geometry, and text metadata from the same render. */
internal data class PreviewCacheEntry(
    val bytes: ByteArray,
    val photoRect: ImageRect?,
    val textSlots: List<TextSlot>,
    /** Already decoded preview pixels; absent for older/tests-only cache entries. */
    val bitmap: ImageBitmap? = null,
    /** Engine `fit.source_canvas` (final canvas at photo pixels); null when absent. */
    val canvasPx: Pair<Int, Int>? = null,
)

/** A decoded hit needs neither a PNG byte copy nor another decoder pass. */
internal data class PreviewDecodedEntry(
    val bitmap: ImageBitmap,
    val photoRect: ImageRect?,
    val textSlots: List<TextSlot>,
    val canvasPx: Pair<Int, Int>? = null,
)

/** Small synchronized LRU whose lookup returns bytes and geometry atomically. */
internal class PreviewRenderCache(private val capacity: Int = 6) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<PreviewRequestKey, PreviewCacheEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PreviewRequestKey, PreviewCacheEntry>?): Boolean =
            size > capacity
    }

    init {
        require(capacity > 0) { "preview cache capacity must be positive" }
    }

    fun get(key: PreviewRequestKey): PreviewCacheEntry? = synchronized(lock) {
        entries[key]?.let {
            PreviewCacheEntry(it.bytes.copyOf(), it.photoRect, it.textSlots.toList(), it.bitmap, it.canvasPx)
        }
    }

    /** Avoids copying the PNG when the exact decoded frame is already available. */
    fun getDecoded(key: PreviewRequestKey): PreviewDecodedEntry? = synchronized(lock) {
        entries[key]?.let { entry ->
            entry.bitmap?.let { bitmap ->
                PreviewDecodedEntry(bitmap, entry.photoRect, entry.textSlots.toList(), entry.canvasPx)
            }
        }
    }

    fun put(
        key: PreviewRequestKey,
        bytes: ByteArray,
        photoRect: ImageRect?,
        textSlots: List<TextSlot>,
        bitmap: ImageBitmap? = null,
        canvasPx: Pair<Int, Int>? = null,
    ) = synchronized(lock) {
        entries[key] = PreviewCacheEntry(bytes.copyOf(), photoRect, textSlots.toList(), bitmap, canvasPx)
        Unit
    }

    fun attachDecoded(key: PreviewRequestKey, bitmap: ImageBitmap) = synchronized(lock) {
        entries[key]?.let { entries[key] = it.copy(bitmap = bitmap) }
        Unit
    }
}

/** Prevents an in-flight older request from publishing state for a newer one. */
internal class PreviewRequestGate(initialKey: PreviewRequestKey) {
    private var currentKey = initialKey
    private var currentVersion = 0L

    val version: Long
        get() = currentVersion

    fun update(key: PreviewRequestKey) {
        if (key == currentKey) return
        currentKey = key
        currentVersion++
    }

    fun publishIfCurrent(key: PreviewRequestKey, version: Long, publish: () -> Unit): Boolean {
        if (!isCurrent(key, version)) return false
        publish()
        return true
    }

    fun publishIfCurrent(key: PreviewRequestKey, publish: () -> Unit): Boolean =
        publishIfCurrent(key, currentVersion, publish)

    fun isCurrent(key: PreviewRequestKey, version: Long): Boolean =
        key == currentKey && version == currentVersion

    fun isCurrent(key: PreviewRequestKey): Boolean = key == currentKey
}
