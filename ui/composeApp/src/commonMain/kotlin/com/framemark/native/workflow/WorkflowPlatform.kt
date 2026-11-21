package com.framemark.native.workflow

import com.framemark.native.product.ExportFormat

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
     * EXIF Orientation (IFD0 tag 0x0112) of a photo file: 1..8, or 1 when the tag
     * is absent/unreadable. This is the *only* orientation seam the shared code
     * uses (see `product.PhotoOrientation`): the kernel knows the pixel format
     * (JPEG APP1, WebP EXIF chunk, TIFF IFD0) and the shared geometry converts
     * sizes, rectangles and gain maps with the returned value. Header reads only —
     * never decode the image to answer this.
     */
    fun photoOrientation(path: String): Int = 1

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

    /**
     * A photo that is guaranteed to render, for previews that must show *some*
     * frame rather than the user's own picture — the border grid uses it when the
     * active photo has no readable size (a file without EXIF measures 0x0, and
     * every tile rendered nothing, which made the page look broken).
     *
     * Defaults to [samplePhotoPath]; platforms that use that hook for something
     * else (Android points it at a QA-selected file first) override this with the
     * bundled asset only.
     */
    fun bundledSamplePhotoPath(): String? = samplePhotoPath()

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

    /**
     * 打开系统相册 / 图片查看器并定位到刚导出的 [path]。返回 null 表示已经打开，
     * 否则返回给用户看的一句话 —— 「已保存」页上的「查看相册」按这个结果说话，
     * 不能在相册没打开时假装打开了（配色方案 §4.4 / InkFrame 复盘第 9 条）。
     */
    fun openGallery(path: String): String? = "当前平台没有系统相册入口"
}

/** The image formats the export path can produce. */
enum class SampleFormat(val label: String, val extension: String) {
    Jpeg("JPG", "jpg"),
    Png("PNG", "png"),
    WebP("WebP", "webp"),
}

/**
 * The export document declares its format with [com.framemark.native.product.ExportFormat];
 * the platform encoders and the render API still speak [SampleFormat]. The two enums
 * name the same three encoders, so this is the one place the boundary is crossed —
 * never a second export-settings type.
 */
fun ExportFormat.toSampleFormat(): SampleFormat = when (this) {
    ExportFormat.Jpeg -> SampleFormat.Jpeg
    ExportFormat.Png -> SampleFormat.Png
    ExportFormat.WebP -> SampleFormat.WebP
}

/** The other direction, for legacy queues that still store a [SampleFormat]. */
fun SampleFormat.toExportFormat(): ExportFormat = when (this) {
    SampleFormat.Jpeg -> ExportFormat.Jpeg
    SampleFormat.Png -> ExportFormat.Png
    SampleFormat.WebP -> ExportFormat.WebP
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

    /**
     * 自建相册里选好照片后交给宿主导入（Android 注入；参数是 `content://` 地址）。
     *
     * 导入仍然走 MainActivity 里那条相册选择器用的同一条路径（落盘、归一化、带 EXIF），
     * 自建浏览器只负责「选哪几张」，不复制导入逻辑。
     */
    var uriImporter: ((List<String>) -> Unit)? = null
}
