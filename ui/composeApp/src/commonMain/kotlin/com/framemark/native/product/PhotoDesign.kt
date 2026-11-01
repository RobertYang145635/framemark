package com.framemark.native.product

/**
 * The design of one photo: border card, control overrides, text overrides and
 * the user's watermark. Every photo owns its own instance, so switching photos
 * can neither leak parameters nor inherit somebody else's signature.
 */
data class PhotoDesign(
    val cardId: String,
    val styleId: String? = null,
    /** Template theme id (`white`, `paper`, …); null keeps the card's default. */
    val theme: String? = null,
    /** Output aspect (`original`, `4:5`, …); null keeps the original ratio. */
    val ratio: String? = null,
    /** Card-level text scale; null keeps the card's default. */
    val fontScale: Double? = null,
    /** Brand/logo key (`auto`, `leica`, …); null keeps the card's default. */
    val logo: String? = null,
    val params: Map<String, Any?> = emptyMap(),
    val text: Map<String, Any?> = emptyMap(),
    val overlay: WatermarkSpec? = null,
    /**
     * True while this design still renders the card's shipped defaults. The flag
     * is derived on load (`params`/`text`/`theme`/`fontScale`/`logo` empty) so a
     * hand-edited document cannot claim "default" for an edited design.
     */
    val baseDesign: Boolean = false,
) {
    val isUntouched: Boolean
        get() = params.isEmpty() && text.isEmpty() &&
            theme == null && fontScale == null && logo == null

    fun withParam(key: String, value: Any?): PhotoDesign =
        copy(params = DesignValues.normalize(params + (key to value), "params"), baseDesign = false)

    fun withParams(values: Map<String, Any?>): PhotoDesign =
        copy(params = DesignValues.normalize(params + values, "params"), baseDesign = false)

    fun withText(key: String, value: Any?): PhotoDesign =
        copy(text = DesignValues.normalize(text + (key to value), "text"), baseDesign = false)

    fun withTheme(value: String?): PhotoDesign = copy(theme = value, baseDesign = false)

    fun withRatio(value: String?): PhotoDesign = copy(ratio = value)

    fun withFontScale(value: Double?): PhotoDesign = copy(fontScale = value, baseDesign = false)

    fun withLogo(value: String?): PhotoDesign = copy(logo = value, baseDesign = false)

    fun withOverlay(spec: WatermarkSpec?): PhotoDesign = copy(overlay = spec)

    /**
     * Drops the watermark (and keeps the "default" flag honest). Used when a design
     * is handed to another photo: a signature only spreads through an explicit
     * "apply to all", never as a side effect of adding photos.
     */
    fun withoutOverlay(): PhotoDesign = copy(
        overlay = null,
        baseDesign = baseDesign && isUntouched,
    )

    /**
     * Drops every control override but keeps the watermark (independent of the
     * border) and the output ratio (a document-level choice, not a card default).
     */
    fun resetToBase(): PhotoDesign =
        if (isUntouched && baseDesign) {
            this
        } else {
            copy(
                params = emptyMap(),
                text = emptyMap(),
                theme = null,
                fontScale = null,
                logo = null,
                baseDesign = true,
            )
        }

    companion object {
        fun base(cardId: String, styleId: String? = null): PhotoDesign =
            PhotoDesign(cardId = cardId, styleId = styleId, baseDesign = true)
    }
}

/**
 * Design values are Boolean/String/Double only. They map 1:1 onto the engine's
 * `params`/`text` objects and onto the JSON document; anything else is a bug and
 * fails loudly instead of being dropped on save.
 */
object DesignValues {
    fun normalize(values: Map<String, Any?>, where: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(values.size)
        values.forEach { (key, raw) ->
            require(key.isNotBlank()) { "$where 含空键" }
            when (raw) {
                null -> Unit
                is Boolean, is String -> out[key] = raw
                is Double -> out[key] = requireFinite(raw, key, where)
                is Float -> out[key] = requireFinite(raw.toString().toDouble(), key, where)
                is Number -> out[key] = requireFinite(raw.toDouble(), key, where)
                else -> throw IllegalArgumentException("$where.$key 不支持的值类型 ${raw::class.simpleName}")
            }
        }
        return out
    }

    private fun requireFinite(value: Double, key: String, where: String): Double {
        require(value.isFinite()) { "$where.$key 不是有限数值" }
        return value
    }
}
