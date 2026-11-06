package com.framemark.native.workflow

/**
 * Platform hooks for the U4 workflow UI.
 *
 * The UI layer never touches `java.io` or platform graphics: presets are stored as
 * a small JSON document, and the sample export image is produced by the platform
 * encoder (AWT on desktop, Bitmap/Canvas on Android). A normal launch installs one
 * implementation in `main.kt` / `MainActivity`; when none is installed the
 * workflow stays in memory and the sample export reports that it cannot write.
 */
interface WorkflowPlatform {
    /** App-private directory for the preset document. */
    val dataDir: String

    /** Temporary directory the sample export writes into. */
    val exportDir: String

    /** Machine-local output preferences; never serialized into portable presets. */
    val canChooseExportDirectory: Boolean get() = false
    fun chooseExportDirectory(): String? = null
    val jpegQualityAdjustable: Boolean get() = false
    fun preferredJpegQuality(): Int = 100
    fun setPreferredJpegQuality(value: Int): String? = "当前平台 JPEG 质量固定为 100"

    /**
     * 本机**显式设置过**的 JPEG 质量；null = 这台机器没有偏好，用工程文档里的值
     * （D-2：`local-export.properties` 的 `jpegQuality` 必须真的到编码器，
     * 而不是被工程里的默认 100 盖掉）。
     */
    fun configuredJpegQuality(): Int? = null

    /** Publish without replacing an existing target. False means a naming conflict. */
    fun commitExport(temporaryPath: String, finalPath: String): Boolean =
        throw UnsupportedOperationException("当前平台不支持安全提交导出")
    fun deleteExportTemporary(path: String) = Unit

    fun readTextFile(path: String): String?

    fun writeTextFile(path: String, text: String)

    fun writeBytes(path: String, bytes: ByteArray): Boolean

    fun exists(path: String): Boolean

    /** Size in bytes, 0 when the file cannot be read. */
    fun fileSize(path: String): Long

    /** Raw bytes of a rendered image (stage preview). */
    fun readBytes(path: String): ByteArray?

    /**
     * Downscaled bytes (JPEG/PNG) of a photo file, long edge ≤ [maxEdge], for the
     * phone photo strip and the import hero. Never decodes a full-resolution
     * image; null when the platform cannot sample or the file is unreadable.
     */
    fun thumbnail(path: String, maxEdge: Int): ByteArray? = null

    /**
     * Draws the sample placeholder at [width]×[height] and encodes it.
     * Returns `null` when the platform cannot encode [format]; the caller then
     * falls back to a copy of the border preview and says so.
     */
    fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray?

    /** `yyyyMMdd` for the filename pattern. */
    fun dateStamp(): String

    /** `HHmmss` for the filename pattern. */
    fun timeStamp(): String

    /** Human date + time for the preset list. */
    fun formatDateTime(epochMillis: Long): String

    fun nowMillis(): Long

    /** OS process start time for the G5 cold-start probe; null when unavailable. */
    fun processStartMillis(): Long? = null

    /**
     * Sample photo used by the batch demo/evidence path. Production photo picking
     * is a later slice; null means the batch can still export (the engine uses its
     * manifest photo) but the queue records no local fingerprint.
     */
    fun samplePhotoPath(): String? = null

    /** SHA-256 hex for the batch photo fingerprint; null when unavailable. */
    fun fileFingerprint(path: String): String? = null

    /** Evidence hook: auto-start a persisted/seed batch queue. */
    fun batchAutoStart(): Boolean = false

    /**
     * Build stamp shown in the shell header. The packaged build injects
     * `-Dframemark.build.label`; a dev run has none and the caller shows a fallback.
     */
    fun buildLabel(): String? = null

    /**
     * Reveals [path] in the system file manager (selecting the file). Returns null
     * on success, or a message when the platform cannot do it; the caller shows it
     * instead of pretending the folder opened.
     */
    fun revealInFolder(path: String): String? = "当前平台不支持打开所在目录"

    /**
     * Hands the exported file to the system share sheet. Returns null when the share
     * started, or the message to show the user — a share that did not happen must
     * never look like it did.
     */
    fun shareFile(path: String): String? = "当前平台没有系统分享入口"
}

/** The image formats the export path can produce. */
enum class SampleFormat(val label: String, val extension: String) {
    Jpeg("JPG", "jpg"),
    Png("PNG", "png"),
    WebP("WebP", "webp"),
}

object WorkflowEnv {
    var platform: WorkflowPlatform? = null

    /** Desktop installs the persisted project controller here (P2a). */
    var projectHolder: ProjectHolder? = null

    /** Installed by the desktop entry point once the engine bridge is available. */
    var nativeRender: NativeRenderApi? = null

    /** Android installs the system photo picker here; other platforms leave it null. */
    var photoPicker: (() -> Unit)? = null

    /** Multi-select variant of [photoPicker]; null when the platform has none. */
    var multiPhotoPicker: (() -> Unit)? = null

    /** Android installs the watermark document picker here (result: [WatermarkSelection]). */
    var watermarkPicker: (() -> Unit)? = null

    /** Opens a portable single-preset JSON document; success(null) means user cancellation. */
    var presetDocumentPicker: (((Result<String?>) -> Unit) -> Unit)? = null

    /** Creates a portable single-preset document; success(false) means user cancellation. */
    var presetDocumentWriter: ((String, String, (Result<Boolean>) -> Unit) -> Unit)? = null
}
