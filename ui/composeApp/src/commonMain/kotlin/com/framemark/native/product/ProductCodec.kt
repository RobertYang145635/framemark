package com.framemark.native.product

import kotlin.math.abs

/** Document ids written into every file; loading checks them before anything else. */
internal object Documents {
    const val PROJECT = "framemark.project"
    const val PRESETS = "framemark.presets"
    const val PRESET = "framemark.preset"
    const val BATCH = "framemark.batch"
}

private fun document(documentId: String, version: Int, body: String): String =
    "{\"document\":${ProductJson.quote(documentId)},\"schemaVersion\":$version,$body}"

internal fun encodeExport(settings: ExportSettings): String = buildString {
    append('{')
    append("\"format\":").append(ProductJson.quote(settings.format.id))
    append(",\"quality\":").append(settings.quality)
    append(",\"sizeMode\":").append(ProductJson.quote(settings.sizeMode.id))
    append(",\"longEdge\":").append(settings.longEdge?.toString() ?: "null")
    append(",\"filenamePattern\":").append(ProductJson.quote(settings.filenamePattern))
    append(",\"webpLossless\":").append(settings.webpLossless)
    append('}')
}

internal fun decodeExport(raw: Any?, where: String): ExportSettings {
    if (raw == null) return ExportSettings()
    val map = requireObject(raw, where)
    val formatId = map.requireString("format", where)
    val format = ExportFormat.fromId(formatId)
        ?: throw ProductDecodeException("$where.format 不支持：$formatId")
    val quality = map.requireInt("quality", where)
    if (quality !in 1..100) throw ProductDecodeException("$where.quality 超出 1..100：$quality")
    val sizeModeId = map.requireString("sizeMode", where)
    val sizeMode = ExportSizeMode.fromId(sizeModeId)
        ?: throw ProductDecodeException("$where.sizeMode 不支持：$sizeModeId")
    val longEdge = map.optionalInt("longEdge", where)
    if (sizeMode == ExportSizeMode.LongEdge) {
        if (longEdge == null) throw ProductDecodeException("$where.longEdge 缺失（自定义长边模式）")
        if (longEdge !in 64..200_000) throw ProductDecodeException("$where.longEdge 超出 64..200000：$longEdge")
    }
    val pattern = map.optionalString("filenamePattern", where) ?: "{photo}-{index}"
    if (pattern.isBlank()) throw ProductDecodeException("$where.filenamePattern 为空")
    return ExportSettings(
        format = format,
        quality = quality,
        sizeMode = sizeMode,
        longEdge = longEdge,
        filenamePattern = pattern,
        webpLossless = map.optionalBool("webpLossless", where) ?: true,
    )
}

private fun encodeAsset(asset: WatermarkAsset): String = buildString {
    append('{')
    append("\"name\":").append(ProductJson.quote(asset.name))
    append(",\"mimeType\":").append(ProductJson.quote(asset.mimeType))
    append(",\"widthPx\":").append(asset.widthPx)
    append(",\"heightPx\":").append(asset.heightPx)
    append(",\"sha256\":").append(ProductJson.quote(asset.sha256.lowercase()))
    append(",\"data\":").append(ProductJson.quote(Base64Codec.encode(asset.bytes)))
    append('}')
}

private fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

internal fun decodeAsset(raw: Any?, where: String): WatermarkAsset {
    val map = requireObject(raw, where)
    val name = map.requireString("name", where)
    if (name.isBlank()) throw ProductDecodeException("$where.name 为空")
    val mimeType = map.requireString("mimeType", where)
    if (!mimeType.startsWith("image/")) throw ProductDecodeException("$where.mimeType 不是图片：$mimeType")
    val widthPx = map.requireInt("widthPx", where)
    val heightPx = map.requireInt("heightPx", where)
    if (widthPx <= 0 || heightPx <= 0) throw ProductDecodeException("$where 尺寸无效：${widthPx}x$heightPx")
    val sha256 = map.requireString("sha256", where)
    if (!isSha256Hex(sha256)) throw ProductDecodeException("$where.sha256 不是 64 位十六进制")
    val encoded = map.requireString("data", where)
    if (encoded.length > WatermarkAsset.MAX_BASE64_CHARS) {
        throw ProductDecodeException("$where.data 超过 ${WatermarkAsset.MAX_BYTES / 1024 / 1024}MB 上限")
    }
    val bytes = try {
        Base64Codec.decode(encoded)
    } catch (t: IllegalArgumentException) {
        throw ProductDecodeException("$where.data 不是合法 base64：${t.message}", t)
    }
    if (bytes.isEmpty()) throw ProductDecodeException("$where.data 解出空素材")
    if (bytes.size > WatermarkAsset.MAX_BYTES) {
        throw ProductDecodeException("$where.data 解出 ${bytes.size} 字节，超过上限")
    }
    val actual = Sha256.hex(bytes)
    if (!actual.equals(sha256, ignoreCase = true)) {
        throw ProductDecodeException("$where 水印素材校验失败：记录 $sha256，实际 $actual")
    }
    return WatermarkAsset(name, mimeType, widthPx, heightPx, sha256.lowercase(), bytes)
}

private fun encodeOverlay(spec: WatermarkSpec): String = buildString {
    append('{')
    append("\"anchor\":").append(spec.anchor)
    append(",\"width\":").append(ProductJson.number(spec.width))
    append(",\"opacity\":").append(ProductJson.number(spec.opacity))
    append(",\"dx\":").append(ProductJson.number(spec.dx))
    append(",\"dy\":").append(ProductJson.number(spec.dy))
    append(",\"asset\":").append(encodeAsset(spec.asset))
    append('}')
}

internal fun decodeOverlay(raw: Any?, where: String): WatermarkSpec {
    val map = requireObject(raw, where)
    val anchor = map.requireInt("anchor", where)
    if (anchor !in 0..8) throw ProductDecodeException("$where.anchor 超出 0..8：$anchor")
    val width = map.requireFloat("width", where)
    if (width <= 0f || width > 1f) throw ProductDecodeException("$where.width 超出 (0,1]：$width")
    val opacity = map.requireFloat("opacity", where)
    if (opacity < 0f || opacity > 1f) throw ProductDecodeException("$where.opacity 超出 [0,1]：$opacity")
    val dx = map.requireFloat("dx", where)
    val dy = map.requireFloat("dy", where)
    if (abs(dx) > 4f || abs(dy) > 4f) throw ProductDecodeException("$where 位移超出 ±4 归一化范围")
    return WatermarkSpec(decodeAsset(map["asset"], "$where.asset"), anchor, width, opacity, dx, dy)
}

internal fun encodeDesign(design: PhotoDesign): String = buildString {
    append('{')
    append("\"cardId\":").append(ProductJson.quote(design.cardId))
    append(",\"styleId\":").append(design.styleId?.let(ProductJson::quote) ?: "null")
    design.theme?.let { append(",\"theme\":").append(ProductJson.quote(it)) }
    design.ratio?.let { append(",\"ratio\":").append(ProductJson.quote(it)) }
    design.fontScale?.let { append(",\"fontScale\":").append(ProductJson.number(it)) }
    design.logo?.let { append(",\"logo\":").append(ProductJson.quote(it)) }
    append(",\"baseDesign\":").append(design.baseDesign)
    append(",\"params\":").append(ProductJson.encodeValues(design.params))
    append(",\"text\":").append(ProductJson.encodeValues(design.text))
    append(",\"overlay\":").append(design.overlay?.let(::encodeOverlay) ?: "null")
    append('}')
}

internal fun decodeDesign(raw: Any?, where: String): PhotoDesign {
    val map = requireObject(raw, where)
    val cardId = map.requireString("cardId", where)
    if (cardId.isBlank()) throw ProductDecodeException("$where.cardId 为空")
    val theme = map.optionalString("theme", where)?.also {
        if (it.isBlank()) throw ProductDecodeException("$where.theme 为空")
    }
    val ratio = map.optionalString("ratio", where)?.also {
        if (it.isBlank()) throw ProductDecodeException("$where.ratio 为空")
    }
    val fontScale = map.optionalDouble("fontScale", where)?.also {
        if (it <= 0.0) throw ProductDecodeException("$where.fontScale 必须为正数")
    }
    val logo = map.optionalString("logo", where)?.also {
        if (it.isBlank()) throw ProductDecodeException("$where.logo 为空")
    }
    val params = ProductJson.decodeValues(map["params"], "$where.params")
    val text = ProductJson.decodeValues(map["text"], "$where.text")
    val overlay = map["overlay"]?.let { decodeOverlay(it, "$where.overlay") }
    // Derived, not trusted: a document cannot claim "default" while carrying edits.
    val design = PhotoDesign(
        cardId = cardId,
        styleId = map.optionalString("styleId", where),
        theme = theme,
        ratio = ratio,
        fontScale = fontScale,
        logo = logo,
        params = params,
        text = text,
        overlay = overlay,
    )
    return design.copy(
        baseDesign = (map.optionalBool("baseDesign", where) ?: false) && design.isUntouched,
    )
}

private fun encodePhoto(photo: PhotoRef): String = buildString {
    append('{')
    append("\"id\":").append(ProductJson.quote(photo.id))
    append(",\"path\":").append(ProductJson.quote(photo.path))
    append(",\"displayName\":").append(ProductJson.quote(photo.displayName))
    append(",\"orientation\":").append(photo.orientation)
    append(",\"widthPx\":").append(photo.widthPx)
    append(",\"heightPx\":").append(photo.heightPx)
    append(",\"fingerprint\":").append(photo.fingerprint?.let(ProductJson::quote) ?: "null")
    append('}')
}

private fun decodePhoto(raw: Any?, where: String): PhotoRef {
    val map = requireObject(raw, where)
    val id = map.requireString("id", where)
    if (id.isBlank()) throw ProductDecodeException("$where.id 为空")
    val path = map.requireString("path", where)
    if (path.isBlank()) throw ProductDecodeException("$where.path 为空")
    val displayName = map.requireString("displayName", where)
    if (displayName.isBlank()) throw ProductDecodeException("$where.displayName 为空")
    val orientation = map.optionalInt("orientation", where) ?: 1
    if (orientation !in 0..8) throw ProductDecodeException("$where.orientation 超出 0..8：$orientation")
    val widthPx = map.optionalInt("widthPx", where) ?: 0
    val heightPx = map.optionalInt("heightPx", where) ?: 0
    if (widthPx < 0 || heightPx < 0) throw ProductDecodeException("$where 尺寸为负")
    return PhotoRef(
        id = id,
        path = path,
        displayName = displayName,
        orientation = orientation,
        widthPx = widthPx,
        heightPx = heightPx,
        fingerprint = map.optionalString("fingerprint", where),
    )
}

internal object ProjectCodec {
    fun encode(project: Project): String {
        require(project.sharedDesign?.overlay == null) {
            "默认设计不得携带水印（水印按照片独立，只有“应用到全部”才会复制）"
        }
        val body = buildString {
            append("\"id\":").append(ProductJson.quote(project.id))
            append(",\"activePhotoId\":").append(project.activePhotoId?.let(ProductJson::quote) ?: "null")
            append(",\"selectedCardId\":").append(project.selectedCardId?.let(ProductJson::quote) ?: "null")
            append(",\"createdAt\":").append(project.createdAt)
            append(",\"updatedAt\":").append(project.updatedAt)
            append(",\"export\":").append(encodeExport(project.exportSettings))
            append(",\"photos\":[")
            append(project.photos.joinToString(",") { encodePhoto(it) })
            append("],\"designs\":{")
            append(
                project.designs.entries.sortedBy { it.key }.joinToString(",") { (photoId, design) ->
                    ProductJson.quote(photoId) + ":" + encodeDesign(design)
                },
            )
            append("},\"sharedDesign\":")
            append(project.sharedDesign?.let(::encodeDesign) ?: "null")
        }
        return document(Documents.PROJECT, PROJECT_SCHEMA_VERSION, body)
    }

    fun decode(root: Map<String, Any?>): Project {
        val id = root.requireString("id", "project")
        if (id.isBlank()) throw ProductDecodeException("project.id 为空")
        val photos = requireList(root["photos"] ?: throw ProductDecodeException("project.photos 缺失"), "project.photos")
            .mapIndexed { index, raw -> decodePhoto(raw, "project.photos[$index]") }
        if (photos.map { it.id }.toSet().size != photos.size) {
            throw ProductDecodeException("project.photos 含重复照片 id")
        }
        val activePhotoId = root.optionalString("activePhotoId", "project")
        if (activePhotoId != null && photos.none { it.id == activePhotoId }) {
            throw ProductDecodeException("project.activePhotoId 指向不存在的照片：$activePhotoId")
        }
        val designs = LinkedHashMap<String, PhotoDesign>()
        val designsRaw = root["designs"]
        if (designsRaw != null) {
            requireObject(designsRaw, "project.designs").forEach { (photoId, raw) ->
                if (photos.none { it.id == photoId }) {
                    throw ProductDecodeException("project.designs 含未知照片：$photoId")
                }
                designs[photoId] = decodeDesign(raw, "project.designs.$photoId")
            }
        }
        val sharedDesign = root["sharedDesign"]?.let { raw ->
            val design = decodeDesign(raw, "project.sharedDesign")
            if (design.overlay != null) {
                throw ProductDecodeException(
                    "project.sharedDesign 不得携带水印（水印按照片独立，只有“应用到全部”才会复制）",
                )
            }
            design
        }
        return Project(
            id = id,
            photos = photos,
            activePhotoId = activePhotoId,
            designs = designs,
            sharedDesign = sharedDesign,
            selectedCardId = root.optionalString("selectedCardId", "project"),
            exportSettings = decodeExport(root["export"], "project.export"),
            createdAt = root.optionalLong("createdAt", "project") ?: 0,
            updatedAt = root.optionalLong("updatedAt", "project") ?: 0,
        )
    }
}

internal object PresetCodec {
    fun encodeLibrary(presets: List<Preset>): String =
        document(Documents.PRESETS, PRESET_SCHEMA_VERSION, "\"presets\":[" + presets.joinToString(",") { encodePreset(it) } + "]")

    fun decodeLibrary(root: Map<String, Any?>): List<Preset> {
        val raw = requireList(root["presets"] ?: throw ProductDecodeException("presets.presets 缺失"), "presets.presets")
        val ids = HashSet<String>()
        return raw.mapIndexed { index, entry ->
            val preset = decodePreset(entry, "presets.presets[$index]")
            if (!ids.add(preset.id)) throw ProductDecodeException("预设 id 重复：${preset.id}")
            preset
        }
    }

    fun encodeDocument(preset: Preset): String =
        document(Documents.PRESET, PRESET_SCHEMA_VERSION, "\"preset\":" + encodePreset(preset))

    fun decodeDocument(root: Map<String, Any?>): Preset =
        decodePreset(root["preset"] ?: throw ProductDecodeException("preset 缺失"), "preset")

    private fun encodePreset(preset: Preset): String = buildString {
        append('{')
        append("\"id\":").append(ProductJson.quote(preset.id))
        append(",\"name\":").append(ProductJson.quote(preset.name))
        append(",\"createdAt\":").append(preset.createdAt)
        append(",\"updatedAt\":").append(preset.updatedAt)
        append(",\"design\":").append(encodeDesign(preset.design))
        append(",\"export\":").append(encodePresetExport(preset.exportSettings))
        append('}')
    }

    /** JPEG quality is a machine preference and must not travel with a preset. */
    private fun encodePresetExport(settings: ExportSettings): String = buildString {
        append('{')
        append("\"format\":").append(ProductJson.quote(settings.format.id))
        append(",\"sizeMode\":").append(ProductJson.quote(settings.sizeMode.id))
        append(",\"longEdge\":").append(settings.longEdge?.toString() ?: "null")
        append(",\"filenamePattern\":").append(ProductJson.quote(settings.filenamePattern))
        append(",\"webpLossless\":").append(settings.webpLossless)
        append('}')
    }

    private fun decodePreset(raw: Any?, where: String): Preset {
        val map = requireObject(raw, where)
        val id = map.requireString("id", where)
        if (id.isBlank()) throw ProductDecodeException("$where.id 为空")
        val name = map.requireString("name", where)
        if (name.isBlank()) throw ProductDecodeException("$where.name 为空")
        return Preset(
            id = id,
            name = name,
            design = decodeDesign(map["design"], "$where.design"),
            exportSettings = decodePresetExport(map["export"], "$where.export"),
            createdAt = map.optionalLong("createdAt", where) ?: 0,
            updatedAt = map.optionalLong("updatedAt", where) ?: 0,
        )
    }

    /** Accept old preset files that carried quality; new files default to 100. */
    private fun decodePresetExport(raw: Any?, where: String): ExportSettings {
        val map = requireObject(raw, where)
        val compatible = if ("quality" in map) map else map + ("quality" to 100)
        return decodeExport(compatible, where)
    }
}

internal object BatchCodec {
    fun encode(queue: BatchQueue): String =
        document(Documents.BATCH, BATCH_SCHEMA_VERSION, "\"jobs\":[" + queue.jobs.joinToString(",") { encodeJob(it) } + "]")

    fun decode(root: Map<String, Any?>): BatchQueue {
        val raw = requireList(root["jobs"] ?: throw ProductDecodeException("batch.jobs 缺失"), "batch.jobs")
        val ids = HashSet<String>()
        val jobs = raw.mapIndexed { index, entry ->
            val job = decodeJob(entry, "batch.jobs[$index]")
            if (!ids.add(job.id)) throw ProductDecodeException("batch.jobs 含重复任务 id：${job.id}")
            job
        }
        return BatchQueue(jobs)
    }

    private fun encodeJob(job: BatchJob): String = buildString {
        append('{')
        append("\"id\":").append(ProductJson.quote(job.id))
        append(",\"photoPath\":").append(job.photoPath?.let(ProductJson::quote) ?: "null")
        append(",\"photoFingerprint\":").append(job.photoFingerprint?.let(ProductJson::quote) ?: "null")
        append(",\"displayName\":").append(ProductJson.quote(job.displayName))
        append(",\"design\":").append(encodeDesign(job.design))
        append(",\"overlay\":").append(job.overlay?.let(::encodeOverlay) ?: "null")
        append(",\"export\":").append(encodeExport(job.exportSettings))
        append(",\"status\":").append(ProductJson.quote(job.status.name))
        append(",\"attempts\":").append(job.attempts)
        append(",\"outputPath\":").append(job.outputPath?.let(ProductJson::quote) ?: "null")
        append(",\"nameIndex\":").append(job.nameIndex?.toString() ?: "null")
        append(",\"outputDirectory\":").append(job.outputDirectory?.let(ProductJson::quote) ?: "null")
        append(",\"preparedOutputPath\":").append(job.preparedOutputPath?.let(ProductJson::quote) ?: "null")
        append(",\"preparedOutputBytes\":").append(job.preparedOutputBytes?.toString() ?: "null")
        append(",\"preparedOutputSha256\":").append(job.preparedOutputSha256?.let(ProductJson::quote) ?: "null")
        append(",\"preparedInputKey\":").append(job.preparedInputKey?.let(ProductJson::quote) ?: "null")
        append(",\"error\":").append(
            job.error?.let { "{\"code\":${ProductJson.quote(it.code)},\"message\":${ProductJson.quote(it.message)}}" } ?: "null",
        )
        append(",\"createdAt\":").append(job.createdAt)
        append(",\"startedAt\":").append(job.startedAt?.toString() ?: "null")
        append(",\"finishedAt\":").append(job.finishedAt?.toString() ?: "null")
        append('}')
    }

    private fun decodeJob(raw: Any?, where: String): BatchJob {
        val map = requireObject(raw, where)
        val id = map.requireString("id", where)
        if (id.isBlank()) throw ProductDecodeException("$where.id 为空")
        val displayName = map.requireString("displayName", where)
        if (displayName.isBlank()) throw ProductDecodeException("$where.displayName 为空")
        val statusId = map.requireString("status", where)
        val status = BatchJobStatus.entries.firstOrNull { it.name == statusId }
            ?: throw ProductDecodeException("$where.status 不支持：$statusId")
        val attempts = map.optionalInt("attempts", where) ?: 0
        if (attempts < 0) throw ProductDecodeException("$where.attempts 为负")
        val design = decodeDesign(map["design"], "$where.design")
        // The overlay field is authoritative; an overlay still inside the design is
        // lifted instead of dropped so a legacy/buggy writer cannot lose the mark.
        val overlay = map["overlay"]?.let { decodeOverlay(it, "$where.overlay") } ?: design.overlay
        val nameIndex = map.optionalInt("nameIndex", where)
        if (nameIndex != null && nameIndex < 1) throw ProductDecodeException("$where.nameIndex 小于 1")
        val outputDirectory = map.optionalString("outputDirectory", where)
        val preparedPath = map.optionalString("preparedOutputPath", where)
        val preparedBytes = map.optionalLong("preparedOutputBytes", where)
        val preparedSha = map.optionalString("preparedOutputSha256", where)
        val preparedInputKey = map.optionalString("preparedInputKey", where)
        val hasReceiptField = preparedPath != null || preparedBytes != null || preparedSha != null || preparedInputKey != null
        if (hasReceiptField && (preparedPath.isNullOrBlank() || preparedBytes == null || preparedBytes < 0 ||
                preparedSha == null || preparedSha.length != 64 || preparedInputKey.isNullOrBlank())
        ) {
            throw ProductDecodeException("$where 导出回执字段不完整")
        }
        val error = map["error"]?.let { rawError ->
            val errorMap = requireObject(rawError, "$where.error")
            val code = errorMap.requireString("code", "$where.error")
            if (code.isBlank()) throw ProductDecodeException("$where.error.code 为空")
            BatchJobError(code, errorMap.optionalString("message", "$where.error") ?: "")
        }
        return BatchJob(
            id = id,
            photoPath = map.optionalString("photoPath", where),
            photoFingerprint = map.optionalString("photoFingerprint", where),
            displayName = displayName,
            design = design.copy(overlay = null),
            overlay = overlay,
            exportSettings = decodeExport(map["export"], "$where.export"),
            status = status,
            attempts = attempts,
            outputPath = map.optionalString("outputPath", where),
            nameIndex = nameIndex,
            outputDirectory = outputDirectory,
            preparedOutputPath = preparedPath,
            preparedOutputBytes = preparedBytes,
            preparedOutputSha256 = preparedSha,
            preparedInputKey = preparedInputKey,
            error = error,
            createdAt = map.optionalLong("createdAt", where) ?: 0,
            startedAt = map.optionalLong("startedAt", where),
            finishedAt = map.optionalLong("finishedAt", where),
        )
    }
}
