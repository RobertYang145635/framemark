package com.framemark.native.product

/** In-memory [ProductFileSystem] with failure injection for every write/read step. */
internal class FakeFs(override val dataDir: String = "/fm-data") : ProductFileSystem {
    val files = LinkedHashMap<String, ByteArray>()
    val renames = mutableListOf<Pair<String, String>>()

    var failWritePath: String? = null
    var writeException: Throwable? = null
    var partialWriteBytes: Int? = null
    var failRename = false
    var renameException: Throwable? = null
    var unreadablePath: String? = null

    val projectPath: String get() = "$dataDir/project.json"
    val presetsPath: String get() = "$dataDir/presets.json"
    val batchPath: String get() = "$dataDir/batch-queue.json"

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? {
        if (path == unreadablePath) return null
        return files[path]?.decodeToString()
    }

    override fun readBytes(path: String): ByteArray? {
        if (path == unreadablePath) return null
        return files[path]?.copyOf()
    }

    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        if (path == failWritePath) {
            writeException?.let { throw it }
            return false
        }
        val limit = partialWriteBytes
        files[path] = if (limit != null) bytes.copyOf(minOf(limit, bytes.size)) else bytes
        return true
    }

    override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean {
        if (path in files) return false
        if (path == failWritePath) {
            writeException?.let { throw it }
            return false
        }
        files[path] = bytes.copyOf()
        return true
    }

    override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

    override fun rename(from: String, to: String): Boolean {
        if (failRename) return false
        renameException?.let { throw it }
        val bytes = files[from] ?: return false
        files[to] = bytes
        files.remove(from)
        renames += from to to
        return true
    }

    override fun delete(path: String): Boolean = files.remove(path) != null
}

internal fun photoRef(id: String, name: String = "$id.jpg", orientation: Int = 1): PhotoRef = PhotoRef(    id = id,
    path = "/photos/$name",
    displayName = name,
    orientation = orientation,
    widthPx = 6000,
    heightPx = 4000,
    fingerprint = "fp-$id",
)

internal fun watermarkSpec(seed: Int = 1, size: Int = 64): WatermarkSpec {
    val bytes = ByteArray(size) { ((it + seed) and 0xff).toByte() }
    val asset = WatermarkAsset.create("sig-$seed.png", "image/png", 800, 200, bytes)
    return WatermarkSpec(asset = asset, anchor = 8, width = 0.3f, opacity = 0.7f)
}

internal fun sampleProject(): Project {
    val designA = PhotoDesign.base("simple-4")
        .withParam("inset", 0.12)
        .withText("model", "X-T5")
        .withOverlay(watermarkSpec(1))
    val designB = PhotoDesign.base("glass-white-1")
    return Project(
        id = "proj-1",
        photos = listOf(photoRef("a"), photoRef("b", orientation = 6)),
        activePhotoId = "a",
        designs = mapOf("a" to designA, "b" to designB),
        selectedCardId = "simple-4",
        exportSettings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 6000),
        createdAt = 100,
        updatedAt = 200,
    )
}

internal fun samplePreset(assetBytes: ByteArray = ByteArray(96) { it.toByte() }): Preset {
    val asset = WatermarkAsset.create("signature.png", "image/png", 1600, 400, assetBytes)
    return Preset(
        id = "preset-1",
        name = "我的签名",
        design = PhotoDesign.base("simple-4")
            .withParam("inset", 0.1)
            .withText("model", "X-T5")
            .withOverlay(WatermarkSpec(asset, anchor = 8, width = 0.3f, opacity = 0.65f)),
        exportSettings = ExportSettings(
            format = ExportFormat.Jpeg,
            quality = 100,
            sizeMode = ExportSizeMode.LongEdge,
            longEdge = 6000,
        ),
        createdAt = 10,
        updatedAt = 20,
    )
}

/** 1 MiB payload with a real PNG signature; the codec treats assets as opaque bytes. */
internal fun megabytePng(): ByteArray {
    val bytes = ByteArray(1024 * 1024)
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
    for (i in 8 until bytes.size) bytes[i] = ((i * 37) and 0xff).toByte()
    return bytes
}

/** Asserts the load succeeded and returns the value; failures print the structured error. */
internal fun <T> StoreResult<T>.ok(): T = when (this) {
    is StoreResult.Ok -> value
    is StoreResult.Missing -> throw AssertionError("期望 Ok，实际 Missing")
    is StoreResult.Failed -> throw AssertionError("期望 Ok，实际 Failed：${error.code} ${error.message}")
}
