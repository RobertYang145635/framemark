package com.framemark.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.framemark.BuildConfig
import com.framemark.R
import com.framemark.native.shell.AndroidInspectorHints
import com.framemark.native.engine.AndroidNativeRender
import com.framemark.native.engine.ExifProbe
import com.framemark.native.engine.IrBatchRunner
import com.framemark.native.engine.NativeEngineProbe
import com.framemark.native.engine.OverlayAbRunner
import com.framemark.native.engine.P3BatchRunner
import com.framemark.native.engine.PerCallPhotoRunner
import com.framemark.native.engine.Perf50Runner
import com.framemark.native.engine.WiredMatrixRunner
import com.framemark.native.inspector.InspectorHints
import com.framemark.native.theme.AndroidAppearanceStore
import com.framemark.native.theme.AndroidMotionSettings
import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.AppearanceEnv
import com.framemark.native.theme.FmPalettes
import com.framemark.native.theme.FmPlatformMotionScale
import com.framemark.native.theme.applySystemBarInk
import com.framemark.native.theme.resolveDark
import com.framemark.native.workflow.AndroidProductFileSystem
import com.framemark.native.workflow.AndroidWorkflowPlatform
import com.framemark.native.workflow.AndroidPhoneAssetIo
import com.framemark.native.library.RecentFramesEnv
import com.framemark.native.shell.phone.AndroidRecentFramesStore
import com.framemark.native.shell.PhoneProjectRuntime
import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.shell.PhoneWatermarkPick
import com.framemark.native.userlogo.AndroidUserLogoRepository
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoImportStatus
import com.framemark.native.workflow.UserLogoSupport
import com.framemark.native.workflow.WatermarkSelection
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.PresetTransfer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog
import com.framemark.native.product.Heif
import com.framemark.native.product.update.installUpdateContext

class MainActivity : ComponentActivity() {

    /** Assigned in [onCreate]; the shell reads the same instance through the runtime. */
    private var projectStore: PhoneProjectStore? = null

    private var pendingPresetRead: ((Result<String?>) -> Unit)? = null
    private data class PendingPresetWrite(
        val json: String,
        val callback: (Result<Boolean>) -> Unit,
    )
    private var pendingPresetWrite: PendingPresetWrite? = null

    private val pickPresetDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingPresetRead.also { pendingPresetRead = null } ?: return@registerForActivityResult
        if (uri == null) {
            callback(Result.success(null))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val result = try {
                Result.success(readPresetDocumentText(uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            callback(result)
        }
    }

    private val createPresetDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingPresetWrite.also { pendingPresetWrite = null }
        if (pending == null) {
            if (uri != null) lifecycleScope.launch {
                if (!deleteCreatedPresetDocument(uri)) {
                    Log.w("FrameMarkPreset", "FM_PRESET_ORPHAN_TARGET_CLEANUP_FAIL")
                }
            }
            return@registerForActivityResult
        }
        if (uri == null) {
            pending.callback(Result.success(false))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            pending.callback(writePresetDocument(uri, pending.json))
        }
    }

    private suspend fun readPresetDocumentText(uri: Uri): String = withContext(Dispatchers.IO) {
        readPresetDocumentBytes(uri).decodeToString(throwOnInvalidSequence = true)
    }

    private suspend fun writePresetDocument(uri: Uri, json: String): Result<Boolean> {
        return try {
            withContext(Dispatchers.IO) {
                val bytes = json.encodeToByteArray()
                require(bytes.size <= PresetTransfer.MAX_DOCUMENT_BYTES) { "预设文件超过 50 MiB 上限" }
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: error("系统未能打开目标预设文件")
                output.use { stream ->
                    val chunkSize = 16 * 1024
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(chunkSize, bytes.size - offset)
                        stream.write(bytes, offset, count)
                        offset += count
                    }
                    stream.flush()
                }
                val verified = readPresetDocumentBytes(uri)
                require(verified.contentEquals(bytes)) { "预设文件写入后校验不一致" }
            }
            Result.success(true)
        } catch (cancelled: CancellationException) {
            deleteCreatedPresetDocument(uri)
            throw cancelled
        } catch (error: Throwable) {
            val removed = deleteCreatedPresetDocument(uri)
            Result.failure(
                if (removed) error else IllegalStateException(
                    "${error.message ?: "写入失败"}；系统未能清理可能不完整的目标文件",
                    error,
                ),
            )
        }
    }

    private suspend fun deleteCreatedPresetDocument(uri: Uri): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
        }

    private suspend fun readPresetDocumentBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val reportedSize = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (reportedSize != null && reportedSize >= 0) {
            require(reportedSize <= PresetTransfer.MAX_DOCUMENT_BYTES.toLong()) {
                "预设文件超过 50 MiB 上限"
            }
        }
        val input = contentResolver.openInputStream(uri) ?: error("系统未能打开预设文件")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= PresetTransfer.MAX_DOCUMENT_BYTES) { "预设文件超过 50 MiB 上限" }
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray()
    }

    /**
     * System photo picker (no storage permission). The picked images are imported
     * into the app's external Pictures/Originals dir so the native engine reads
     * plain files; JPEG/PNG keep their bytes, everything else becomes PNG.
     */
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris ->
        if (uris.isEmpty()) {
            FmLog.info("FM_PHOTO_PICK") { "cancelled" }
            return@registerForActivityResult
        }
        Thread { runCatching { importPickedPhotos(uris) }.onFailure { FmLog.warn("FM_PHOTO_PICK_FAIL") { "${it.message}"} } }.start()
    }

    /**
     * 从文件夹导入: system document picker (ACTION_OPEN_DOCUMENT, multi-select).
     * The photo picker cannot browse folders, so the folder entry goes through
     * DocumentsUI; picked URIs are imported exactly like photo-picker URIs.
     */
    /**
     * 水印素材: any image the document picker can reach (PNG/WebP); the engine
     * overlay reads a PNG path, so everything is re-encoded to PNG first.
     */
    private val pickWatermark = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            FmLog.info("FM_WATERMARK_PICK") { "cancelled" }
            return@registerForActivityResult
        }
        Thread {
            runCatching {
                val mime = contentResolver.getType(uri).orEmpty()
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: error("cannot decode watermark $mime")
                importWatermarkBitmap(bitmap, mime)
            }.onFailure { FmLog.warn("FM_WATERMARK_PICK_FAIL") { "${it.message}"} }
        }.start()
    }

    /** The engine overlay reads PNG, so every picked asset lands as PNG. */
    private val pickUserLogo = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            FmLog.info("FM_USER_LOGO_PICK") { "cancelled" }
            return@registerForActivityResult
        }
        val repo = UserLogoEnv.repository
        if (repo == null) {
            UserLogoImportStatus.fail("用户 Logo 库不可用")
            return@registerForActivityResult
        }
        val mime = contentResolver.getType(uri).orEmpty()
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { "用户 Logo" }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > com.framemark.native.workflow.UserLogoLibrary.MAX_BYTES) {
                            throw IllegalArgumentException("图片不能超过 4 MB")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: error("无法读取所选图片")
                repo.import(name, mime, bytes)
            }.onSuccess {
                FmLog.info("FM_USER_LOGO_IMPORTED") { "id=${it.id} ${it.width}x${it.height} ${it.sizeBytes}B" }
            }.onFailure {
                FmLog.warn("FM_USER_LOGO_IMPORT_FAIL") { "${it.message}" }
                UserLogoImportStatus.fail(it.message ?: "导入失败")
            }
        }
    }

    private fun importWatermarkBitmap(bitmap: Bitmap, mime: String) {
        val dir = File(filesDir, "Watermarks").apply { mkdirs() }
        val out = File(dir, "wm-${System.currentTimeMillis()}.png")
        out.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        val width = bitmap.width
        val height = bitmap.height.coerceAtLeast(1)
        val aspect = width.toFloat() / height
        val slot = watermarkSlot
        val wmWidth = watermarkWidth
        val alpha = watermarkOpacity
        FmLog.info("FM_WATERMARK_CONVERT") { "mime=$mime ${width}x$height -> ${out.absolutePath}" }
        bitmap.recycle()
        // The embedded asset has to declare real pixels, not only an aspect.
        PhoneWatermarkPick.set(width, height)
        WatermarkSelection.set(out.absolutePath, aspect, slot, wmWidth, alpha)
    }

    private fun importWatermarkFromPath(path: String) {
        val bitmap = BitmapFactory.decodeFile(path) ?: error("cannot decode $path")
        importWatermarkBitmap(bitmap, "file")
    }

    private var watermarkSlot = 8
    private var watermarkWidth = 0.25f
    private var watermarkOpacity = 0.8f

    private val pickDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) {
            FmLog.info("FM_PHOTO_PICK") { "cancelled" }
            return@registerForActivityResult
        }
        Thread { runCatching { importPickedPhotos(uris) }.onFailure { FmLog.warn("FM_PHOTO_PICK_FAIL") { "${it.message}"} } }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：分享/快捷方式打到已经开着的实例上，换掉 intent 再走同一条处理。
        setIntent(intent)
        handleSharedImages(intent)
        handleImportShortcut(intent)
    }

    /**
     * 相册分享进来的图片：与 [importPickedPhotos] 完全同一条导入路径（落盘、归一化、
     * 带 EXIF），导入完成后 shell 会自动进编辑器。
     */
    private fun handleSharedImages(intent: Intent?) {
        val uris = ShareTarget.imageUris(intent)
        if (uris.isEmpty()) return
        FmLog.info("FM_PHOTO_IMPORT") { "share count=${uris.size}" }
        Thread {
            runCatching { importPickedPhotos(uris) }
                .onFailure { FmLog.warn("FM_PHOTO_PICK_FAIL") { "share ${it.message}" } }
        }.start()
    }

    /**
     * 长按图标的「从相册导入」：首帧之后调起同一个选择器，不做第二套导入。
     * 放在 `decorView.post` 里是因为选择器要在 Activity 走完 onCreate 之后才能 launch。
     */
    private fun handleImportShortcut(intent: Intent?) {
        if (!ShareTarget.isImportShortcut(intent)) return
        FmLog.info("FM_PHOTO_PICK") { "shortcut=import" }
        window.decorView.post { WorkflowEnv.photoPicker?.invoke() }
    }

    private fun importPickedPhotos(uris: List<Uri>) {
        importPhotos(uris.mapIndexed { index, uri ->
            val mime = contentResolver.getType(uri).orEmpty()
            val displayName = sourceDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "图片 ${index + 1}"
            ImportSource(displayName, displayName, mime) { contentResolver.openInputStream(uri) }
        })
    }

    private fun sourceDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** One import source: a log label, preserved display name, provider MIME and stream opener. */
    private class ImportSource(
        val label: String,
        val displayName: String,
        val mime: String,
        val open: () -> java.io.InputStream?,
    )

    /**
     * Import by *content*, not by the provider's MIME string: a DocumentsProvider may
     * report TIFF as application/octet-stream, and BitmapFactory cannot decode TIFF at
     * all. JPEG/PNG are kept as-is; TIFF is transcoded to PNG with the engine's decoder
     * (same one the renderer uses); everything else goes through BitmapFactory.
     *
     * A file that cannot be decoded is reported and skipped — one bad image no longer
     * aborts the whole batch, and the failure is visible instead of silent.
     */
    private fun importPhotos(sources: List<ImportSource>) {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "Originals")
        dir.mkdirs()
        val paths = ArrayList<String>(sources.size)
        val displayNames = LinkedHashMap<String, String>()
        val failures = ArrayList<Pair<String, String>>()
        val stamp = System.currentTimeMillis()
        sources.forEachIndexed { index, source ->
            val outcome = runCatching { normalizeInto(dir, stamp, index, source) }
            outcome
                .onSuccess { path ->
                    if (path != null) {
                        paths += path
                        displayNames[path] = source.displayName
                    } else {
                        failures += source.label to "无法解码（坏图或格式不支持）"
                    }
                }
                .onFailure { failures += source.label to (it.message ?: it::class.simpleName ?: "import failed") }
        }
        PhotoSelection.setPaths(paths, displayNames)
        val reasons = failures.joinToString(";") { (name, why) -> "$name=$why" }.take(400)
        FmLog.info("FM_PHOTO_IMPORT_RESULT") { "ok=${paths.size} failed=${failures.size} reasons=${reasons.ifEmpty { "none" }}" }
        if (failures.isNotEmpty()) {
            // Import runs off the main thread; the toast has to be posted back to it.
            runOnUiThread {
                Toast.makeText(
                    this,
                    "${failures.size} 张图片没能导入：${failures.first().second}（成功 ${paths.size} 张）",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Normalizes one source into a readable file; null means "undecodable, skip it". */
    private fun normalizeInto(dir: File, stamp: Long, index: Int, source: ImportSource): String? {
        val tmp = File(dir, ".import-$stamp-$index")
        source.open()?.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            ?: run { tmp.delete(); return null }
        val kind = sniffKind(tmp)
        val out = when (kind) {
            "jpeg", "png" -> {
                val ext = if (kind == "jpeg") "jpg" else "png"
                File(dir, "user-$stamp-$index.$ext").also { keepFile(tmp, it) }
            }
            "tiff" -> {
                val png = File(dir, "user-$stamp-$index.png")
                val error = NativeEngineProbe.tiffToPng(tmp.absolutePath, png.absolutePath)
                if (error == null && png.isFile && png.length() > 0) {
                    tmp.delete()
                    png
                } else {
                    // Keep the original bytes so the renderer's TIFF path can still
                    // handle it; never silently drop the import.
                    FmLog.info("FM_PHOTO_IMPORT") { "tiff-convert-failed error=$error" }
                    File(dir, "user-$stamp-$index.tif").also { keepFile(tmp, it) }
                }
            }
            "heif" -> {
                // HEIC/HEIF：BitmapFactory 能解码，但不带出容器里的 Exif 项。先把 Exif
                // 项读出来（旋转用它的 Orientation），再写一张只含 APP1 的侧车 JPEG，
                // 引擎就能用现有的 JPEG APP1 解析器读到机型/参数/时间。
                val bytes = runCatching { tmp.readBytes() }.getOrNull()
                val exif = bytes?.let { Heif.exifBlock(it) }
                val hdr = bytes?.let { Heif.hdrInfo(it) }
                val bitmap = BitmapFactory.decodeFile(tmp.absolutePath) ?: run {
                    tmp.delete()
                    return null
                }
                val png = File(dir, "user-$stamp-$index.png")
                val orientation = exif?.orientation ?: 1
                val oriented = ExifProbe.rotate(bitmap, orientation)
                png.outputStream().use { output -> oriented.compress(Bitmap.CompressFormat.PNG, 100, output) }
                oriented.recycle()
                if (exif != null) {
                    val exifDir = File(dir.parentFile, "exif").apply { mkdirs() }
                    val sidecar = File(exifDir, "user-$stamp-$index.jpg")
                    runCatching {
                        sidecar.writeBytes(Heif.exifSidecarJpeg(exif.tiff, orientation = 1))
                    }.onFailure {
                        FmLog.warn("HEIF_EXIF_SIDECAR_FAIL") { "${it.message}" }
                    }
                }
                // HDR 源：HEIC 的增益图在辅助图里，转成 PNG 之后就没有了。保留原始文件
                // 作为「HDR 源」放在同一目录（`<名字>.hdrsrc`），导出面板据此说明
                // 「这是 HDR 照片，但当前版本只能导出 SDR」，也给以后真正的 HDR 导出留料。
                if (hdr?.isHdr == true) {
                    runCatching {
                        File(dir, "${png.name}.hdrsrc").also { keepFile(tmp, it) }
                    }.onFailure { FmLog.warn("HEIF_HDR_SOURCE_FAIL") { "${it.message}" } }
                } else {
                    tmp.delete()
                }
                FmLog.info("HEIF_IMPORT") {
                    "exif=${exif != null} orientation=$orientation hdr=${hdr?.isHdr == true} " +
                        "bitDepth=${hdr?.bitDepth ?: 0} transfer=${hdr?.transfer ?: -1} " +
                        "gainMap=${hdr?.gainMapUrn ?: "-"} toneMap=${hdr?.hasToneMap == true} " +
                        "png=${png.length()} hdrSource=${hdr?.isHdr == true}"
                }
                png
            }
            else -> {
                // WebP/HEIC/etc: BitmapFactory can decode them but ignores EXIF
                // orientation, so bake the rotation into the PNG.
                val bitmap = BitmapFactory.decodeFile(tmp.absolutePath) ?: run {
                    tmp.delete()
                    return null
                }
                val png = File(dir, "user-$stamp-$index.png")
                val oriented = ExifProbe.rotate(bitmap, ExifProbe.orientation(tmp))
                png.outputStream().use { output -> oriented.compress(Bitmap.CompressFormat.PNG, 100, output) }
                oriented.recycle()
                tmp.delete()
                png
            }
        }
        FmLog.info("FM_PHOTO_PICK") { "mime=${source.mime} kind=$kind path=${out.absolutePath} bytes=${out.length()}" }
        return out.absolutePath
    }

    /** Magic bytes win over the provider MIME; returns jpeg/png/tiff/heif/other. */
    private fun sniffKind(file: File): String {
        val head = ByteArray(64)
        val read = FileInputStream(file).use { it.read(head) }
        if (read < 4) return "other"
        val b = head.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF -> "jpeg"
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "png"
            b[0] == 0x49 && b[1] == 0x49 && b[2] == 0x2A && b[3] == 0x00 -> "tiff"
            b[0] == 0x4D && b[1] == 0x4D && b[2] == 0x00 && b[3] == 0x2A -> "tiff"
            Heif.isHeif(head.copyOf(read)) -> "heif"
            else -> "other"
        }
    }

    private fun keepFile(tmp: File, out: File) {
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // WP-X3 §3 (WP-F2 D2): resolve the stored appearance **before** the window is
        // created and pick the launch theme from it. The manifest theme follows the
        // *system* night mode, so with 外观=深色 on a light system the launch window was
        // light: cold starts showed a 700–800 ms white flash (mean 182–226) before the
        // first dark Compose frame. `setTheme` must run before `super.onCreate` to take
        // effect on this window, and the two explicit styles live in values/ so the
        // system night mode cannot re-resolve them.
        val initialSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val launchAppearance = AppearanceController(
            store = AndroidAppearanceStore.forContext(applicationContext),
            initialSystemDark = initialSystemDark,
        )
        setTheme(
            if (launchAppearance.dark) R.style.Theme_FrameMark_Dark else R.style.Theme_FrameMark_Light,
        )
        // 配色方案 §6：两支启动样式只编码默认配色的画布，而四套配色的画布不一样
        // （暖灰 / 冷白 / 靛白 / 纸白）。这里按用户选中的配色覆盖窗口底色，非默认配色
        // 也不会在首帧前闪一帧暖灰。必须在 super.onCreate 之前调用才作用在这一扇窗上。
        window.setBackgroundDrawable(
            ColorDrawable(FmPalettes.of(launchAppearance.settings.palette, launchAppearance.dark).canvas.toArgb()),
        )
        // Evidence: printed before the first frame, so a burst capture can be paired
        // with the theme the launch window actually used.
        FmLog.info("FM_LAUNCH_THEME") {
            "palette=${launchAppearance.settings.palette.wire} dark=${launchAppearance.dark} " +
                "mode=${launchAppearance.settings.mode.wire} systemDark=$initialSystemDark " +
                "theme=${if (launchAppearance.dark) "Theme.FrameMark.Dark" else "Theme.FrameMark.Light"}"
        }
        // WP-T: no more hard-coded light system bars. `enableEdgeToEdge()` with no
        // arguments follows the launch theme (values / values-night themes.xml)
        // so the first frame already has the right icon ink; the composition
        // below re-applies it as soon as the resolved appearance is known, which
        // is UX-RESEARCH A-10 (never rely on the Activity being recreated — the
        // manifest keeps `uiMode` in configChanges).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        installAppVersion(applicationContext)
        installUpdateContext(applicationContext)
        // Motion (2026-11-14): the OS animation scale (which is also what the
        // accessibility "remove animations" toggle writes) is read **before the first
        // composition** and mapped to FrameMark's 0..1 motion scale, so the very first
        // frame already animates (or does not) according to the system. `onResume`
        // refreshes it: the user has to leave the app to change the setting.
        FmPlatformMotionScale.install(AndroidMotionSettings.read(applicationContext))
        // WP-T: the appearance file lives next to project.json / presets.json in
        // the app's private files dir. Installed before the first composition so
        // the very first frame already renders in the stored appearance.
        //
        // T-1: the platform flag is read from the Activity's configuration right
        // here, so "follow the system" is already correct on the first frame
        // instead of waiting for the `SideEffect` inside the composition (which
        // only runs after the first frame has been drawn).
        val appearance = launchAppearance.also {
            AppearanceEnv.controller = it
        }
        FmLog.info("FM_APPEARANCE_INIT") { "${appearance.describe()}" }
        // Evidence for T-1: printed in onCreate, i.e. before setContent and before
        // the first frame.
        FmLog.info("FM_APPEARANCE_PRECOMPOSE") {
            "systemDark=$initialSystemDark dark=${appearance.dark} " +
                "mode=${appearance.settings.mode.wire} source=configuration"
        }
        // WP-D1 phase 2: install the Android 最近使用 file store before the first
        // composition (same `settings/recent-frames.json` format as desktop).
        // Deliberately *above* the first `if (BuildConfig.DEBUG)` block: the
        // release-intent-source-guard gate requires the DEBUG block's closing
        // brace to sit immediately before `setContent {`, with nothing between.
        RecentFramesEnv.store = AndroidRecentFramesStore.forContext(applicationContext)
        // A-02: per-request render scratch (PNG + geom sidecar) is released by its
        // consumer; this sweeps what a crash/kill left behind. IO off the main thread,
        // before the first composition so it cannot compete with the first preview.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.framemark.native.engine.RenderScratch.sweep(cacheDir)
        }
        // W3 crash/migration controls have to be configured before the phone
        // project loads: preset migration is synchronous during installed.load().
        // This entire block is absent from the release behavior by design.
        if (BuildConfig.DEBUG) {
            LaunchOptions.crashWindow = intent?.getStringExtra("fmCrashWindow")?.takeIf { it.isNotBlank() }
            LaunchOptions.queueAutoStart = intent?.getBooleanExtra("fmQueueAutoStart", false) == true
            LaunchOptions.crashResumeOnMarkerRemoval =
                intent?.getBooleanExtra("fmCrashResumeOnMarkerRemoval", false) == true
            LaunchOptions.crashMarkerDir = null
            if (LaunchOptions.crashWindow != null) {
                val markerDir = java.io.File(
                    applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
                    "fm-crash",
                ).apply { mkdirs() }
                // Clear only QA marker files, before the operation under test starts.
                markerDir.listFiles()?.forEach { it.delete() }
                LaunchOptions.crashMarkerDir = markerDir.absolutePath
                FmLog.info("FM_CRASH_HOOK") {
                    "window=${LaunchOptions.crashWindow} markerDir=${markerDir.absolutePath} " +
                        "queueAutoStart=${LaunchOptions.queueAutoStart} " +
                        "resumeOnMarkerRemoval=${LaunchOptions.crashResumeOnMarkerRemoval}"
                }
            }
        }
        // U4: presets live in the app's private files dir; sample exports go to
        // the cache dir. Installed before the first composition.
        WorkflowEnv.platform = AndroidWorkflowPlatform(applicationContext)
        // Global user logos are shared across projects; install before the first
        // composition so the Logo user tab can list and import immediately.
        UserLogoEnv.repository = AndroidUserLogoRepository(applicationContext)
        UserLogoEnv.picker = {
            UserLogoImportStatus.clear()
            pickUserLogo.launch(arrayOf("image/*"))
        }
        // P4: the project document (photos, per-photo design, embedded watermarks,
        // export settings) is owned by product.Project and written atomically by
        // product.SessionStore. Installed before the first composition so the shell
        // never runs against an empty authority.
        val workflowPlatform = WorkflowEnv.platform!!
        val installed = PhoneProjectRuntime.install(
            PhoneProjectStore(
                fs = AndroidProductFileSystem(applicationContext),
                io = AndroidPhoneAssetIo(),
                platform = workflowPlatform,
            ),
        )
        projectStore = installed
        installed.load()
        // 系统「分享」进来的图片（相册 → 分享 → FrameMark）：复用相册选择器的导入路径。
        // 只在真正的冷启动里接一次：进程被回收后重建会把同一份 intent 再交一遍，重复导入。
        if (savedInstanceState == null) {
            handleSharedImages(intent)
            handleImportShortcut(intent)
        }
        // X1: prove the engine .so load path on the device. Rendering stays on the
        // placeholder workflow until the engine core is linked (X2).
        val engineStatus = NativeEngineProbe.load()
        val ping = NativeEngineProbe.ping()
        Log.i("FrameMarkEngine", "FM_ENGINE_JNI status=$engineStatus abi=${NativeEngineProbe.abiBits()} ping=$ping")
        FmLog.info("FM_ENGINE_JNI") { "status=$engineStatus abi=${NativeEngineProbe.abiBits()} ping=$ping" }
        // X1: one real card (vivo-zeiss:inside) renders on device; other cards /
        // ABIs without the Rust lib keep the placeholder workflow.
        val renderer = AndroidNativeRender(applicationContext)
        WorkflowEnv.nativeRender = renderer
        UserLogoSupport.probe = { renderer.supportsUserLogo(it) }
        InspectorHints.provider = AndroidInspectorHints { renderer.hintedIrStyles }
        WorkflowEnv.photoPicker = {
            // PickVisualMedia.ImageOnly is the media *type*; the multi-select
            // contract is PickMultipleVisualMedia above (max MAX_PICK).
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        WorkflowEnv.multiPhotoPicker = {
            pickDocuments.launch(arrayOf("image/*", "image/tiff"))
        }
        // 自建相册选完把 content:// 地址交回这里：走与系统选择器完全相同的导入链。
        WorkflowEnv.uriImporter = import@ { uris ->
            val parsed = uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            if (parsed.isEmpty()) return@import
            FmLog.info("FM_PHOTO_IMPORT") { "gallery count=${parsed.size}" }
            Thread {
                runCatching { importPickedPhotos(parsed) }
                    .onFailure { FmLog.warn("FM_PHOTO_PICK_FAIL") { "gallery ${it.message}" } }
            }.start()
        }
        WorkflowEnv.watermarkPicker = {
            pickWatermark.launch(arrayOf("image/*"))
        }
        WorkflowEnv.presetDocumentPicker = { callback ->
            pendingPresetRead = callback
            pickPresetDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        WorkflowEnv.presetDocumentWriter = { suggestedName, json, callback ->
            pendingPresetWrite = PendingPresetWrite(json, callback)
            createPresetDocument.launch(suggestedName)
        }
        // Evidence-only launch hooks are available in isolated debug QA builds only.
        // The launcher stays exported for normal Android entry, but release builds
        // ignore caller-supplied QA extras and cannot start their import/render jobs.
        if (BuildConfig.DEBUG) {
        // Evidence-only launch hook, same role as FM_OPEN_CARD on desktop:
        // adb shell am start -n com.framemark.native/.MainActivity --es fmCard <id>
        LaunchOptions.openCardId = intent?.getStringExtra("fmCard")?.takeIf { it.isNotBlank() }
        LaunchOptions.userPhotoPath = intent?.getStringExtra("fmPhoto")?.takeIf { it.isNotBlank() }
        // Evidence-only: a design document merged into every render, so the
        // JNI "designJson" channel can be proven without relying on a specific
        // inspector control being wired yet.
        LaunchOptions.designJsonOverride = intent?.getStringExtra("fmDesignJson")?.takeIf { it.isNotBlank() }
        // Evidence: choose the export format/size the same way the desktop entry does,
        // so a PNG export can be driven without touching the sheet.
        LaunchOptions.exportFormat = intent?.getStringExtra("fmFormat")?.takeIf { it.isNotBlank() }
        // Evidence: WebP VP8 q100 instead of the VP8L default.
        LaunchOptions.exportWebpLossless = intent?.getBooleanExtra("fmWebpLossy", false) != true
        LaunchOptions.exportLongEdge = intent?.getIntExtra("fmLongEdge", -1)?.takeIf { it > 0 }
        // Evidence: stage a multi-selection without driving the system picker.
        // setPaths is exactly what the picker's import ends with, so the shell's
        // adopt/inherit path runs unchanged.
        intent?.getStringExtra("fmImportPaths")?.takeIf { it.isNotBlank() }?.let { raw ->
            val paths = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (paths.isNotEmpty()) {
                projectStore?.adoptPaths(paths, paths.last())
                PhotoSelection.setPaths(paths)
                FmLog.info("FM_EVIDENCE_IMPORT") { "count=${paths.size} photos=${projectStore?.photos?.size ?: -1}" }
            }
        }
        // Evidence: select one photo by path — the same call the strip makes.
        intent?.getStringExtra("fmSelectPath")?.takeIf { it.isNotBlank() }?.let { path ->
            val store = projectStore ?: return@let
            store.select(store.idForPath(path))
            PhotoSelection.set(path)
            FmLog.info("FM_EVIDENCE_SELECT") { "path=$path id=${store.idForPath(path)}" }
        }
        // Evidence: run local files through the real import path (normalisation,
        // decoding, per-file failure), so a corrupt image can be mixed into a batch
        // without driving the system picker.
        intent?.getStringExtra("fmImportFiles")?.takeIf { it.isNotBlank() }?.let { raw ->
            val files = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (files.isNotEmpty()) {
                Thread {
                    importPhotos(
                        files.map { entry ->
                            val label = entry.substringAfterLast('/')
                            ImportSource(label, label, "file") {
                                runCatching { java.io.FileInputStream(File(entry)) }
                                    .onFailure { FmLog.warn("FM_PHOTO_IMPORT_OPEN_FAIL") { "$entry ${it.message}"} }
                                    .getOrNull()
                            }
                        },
                    )
                }.start()
            }
        }
        LaunchOptions.previewMaxEdge = intent?.getIntExtra("fmMaxEdge", -1)?.takeIf { it >= 0 }
        // X1-ANDROID5 export evidence, same role as the desktop env hooks:
        // adb shell am start -n com.framemark.native/.MainActivity --es fmCard <id> --ez fmOpenExport true --ez fmRunExport true
        LaunchOptions.openExportSheet = intent?.getBooleanExtra("fmOpenExport", false) == true
        LaunchOptions.runSampleExport = intent?.getBooleanExtra("fmRunExport", false) == true
        // X1-ANDROID6 batch evidence: seed N engine-wired cards, optional reset,
        // foreground auto-start, optional one-shot failure for the retry path.
        LaunchOptions.openBatch = intent?.getBooleanExtra("fmOpenBatch", false) == true
        LaunchOptions.pairDemo = intent?.getBooleanExtra("fmPairDemo", false) == true
        LaunchOptions.batchCount = intent?.getIntExtra("fmBatchCount", 10) ?: 10
        LaunchOptions.batchReset = intent?.getBooleanExtra("fmBatchReset", false) == true
        LaunchOptions.batchAutoStart = intent?.getBooleanExtra("fmBatchAutoStart", false) == true
        LaunchOptions.batchFailCard = intent?.getStringExtra("fmBatchFailCard")?.takeIf { it.isNotBlank() }
        if (LaunchOptions.openBatch) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        watermarkSlot = intent?.getIntExtra("fmWmAnchor", 8) ?: 8
        watermarkWidth = intent?.getFloatExtra("fmWmWidth", 0.25f) ?: 0.25f
        watermarkOpacity = intent?.getFloatExtra("fmWmOpacity", 0.8f) ?: 0.8f
        intent?.getStringExtra("fmWatermark")?.takeIf { it.isNotBlank() }?.let { path ->
            Thread {
                runCatching { importWatermarkFromPath(path) }
                    .onFailure { FmLog.warn("FM_WATERMARK_PICK_FAIL") { "${it.message}"} }
            }.start()
        }
        if (intent?.getBooleanExtra("fmRenderAll", false) == true) {
            // Evidence-only batch: keep the process foreground while the 106 IR
            // styles render; the UI itself is unchanged.
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            IrBatchRunner.start(this, intent.getIntExtra("fmRenderEdge", 1400), intent.getStringExtra("fmRenderFilter"))
        }
        if (intent?.getBooleanExtra("fmRenderHandAll", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            IrBatchRunner.startHand(this, intent.getIntExtra("fmRenderEdge", 0))
        }
        // X1-ANDROID12 evidence: per-call photoPath A/B through the app renderer.
        if (intent?.getBooleanExtra("fmPerCallTest", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val cards = (intent.getStringExtra("fmPerCallCards") ?: "stock-xiaomi-classic-22,vendor-hasselblad-h")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            PerCallPhotoRunner.start(
                this,
                renderer,
                cards,
                photoG = intent.getStringExtra("fmPhotoG").orEmpty(),
                photoA = intent.getStringExtra("fmPhotoA").orEmpty(),
                photoB = intent.getStringExtra("fmPhotoB").orEmpty(),
            )
        }
        // X1-ANDROID13 evidence: phone P3 batch (2 borders + 1 watermark) through
        // exportCard(), overlay A/B on classic-22 and multi-band vivo-postcard,
        // plus the old P3 source photo that already carries a baked watermark.
        if (intent?.getBooleanExtra("fmP3Test", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            P3BatchRunner.start(
                this,
                renderer,
                photo1 = intent.getStringExtra("fmPhoto1").orEmpty(),
                photo2 = intent.getStringExtra("fmPhoto2").orEmpty(),
                photo3 = intent.getStringExtra("fmPhoto3").orEmpty(),
                photoOld = intent.getStringExtra("fmPhotoOld").orEmpty(),
                overlayPath = intent.getStringExtra("fmOverlayPath").orEmpty(),
            )
        }
        // X1-ANDROID11 evidence: render-v1 overlay A/B through the app renderer.
        if (intent?.getBooleanExtra("fmOverlayTest", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val overlayCard = intent.getStringExtra("fmOverlayCard") ?: "stock-xiaomi-classic-22"
            val overlayFile = intent.getStringExtra("fmOverlayPath").orEmpty()
            val rect = (intent.getStringExtra("fmOverlayRect") ?: "0.1,0.8,0.3,0.1")
                .split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
            val opacity = intent.getIntExtra("fmOverlayOpacityPct", 60).coerceIn(0, 100) / 100f
            OverlayAbRunner.start(this, renderer, overlayCard, overlayFile, rect, opacity)
        }
        // P4c evidence-only: repeated 50 MP perf exports through the app renderer.
        if (intent?.getBooleanExtra("fmPerf50", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Perf50Runner.start(
                context = this,
                renderer = renderer,
                cards = intent.getStringExtra("fmPerfCards").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                photo = intent.getStringExtra("fmPerfPhoto").orEmpty(),
                formats = intent.getStringExtra("fmPerfFormats").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                edge = intent.getIntExtra("fmPerfEdge", 0).takeIf { it > 0 },
                runs = intent.getIntExtra("fmPerfRuns", 5),
                webpLossless = intent.getBooleanExtra("fmPerfWebpLossless", true),
            )
        }
        // X1-ANDROID7 evidence: drive every wired card through the app's own
        // renderer (preview + export) and record one JSONL row per call.
        if (intent?.getBooleanExtra("fmMatrix", false) == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WiredMatrixRunner.start(
                this,
                renderer,
                previewEdge = intent.getIntExtra("fmMatrixPreviewEdge", 1280),
                exportEdge = intent.getIntExtra("fmMatrixExportEdge", 1600),
                kinds = intent.getStringExtra("fmMatrixKinds") ?: "all",
            )
        }
        }
        setContent {
            // WP-T: system dark comes from the configuration, and `uiMode` is in
            // configChanges, so the Activity is not recreated on a system switch —
            // this recomposes instead and re-applies the bar styles right here.
            val systemDark = isSystemInDarkTheme()
            SideEffect { appearance.updateSystemDark(systemDark) }
            val dark = resolveDark(appearance.settings.mode, systemDark)
            LaunchedEffect(dark) {
                this@MainActivity.applySystemBarInk(dark)
                FmLog.info("FM_APPEARANCE_APPLIED") { "${appearance.describe()}" }
            }
            FrameMarkApp()
        }
    }

    override fun onResume() {
        super.onResume()
        // Motion (2026-11-14): re-read the system "reduce motion" inputs on every
        // return to the foreground. Changing them requires leaving the app (Settings /
        // developer options), so this is the one moment the value can have changed.
        FmPlatformMotionScale.install(AndroidMotionSettings.read(applicationContext))
    }

    override fun onPause() {
        // ColorOS freezes the whole uid cgroup seconds after HOME, so a debounced
        // write that has not run yet would be lost with the process.
        val flushed = projectStore?.flushBlocking() ?: true
        if (!flushed) {
            // The store also refuses to write when the project could not be read, so
            // this is either a real IO error or that guard. Either way the last edit
            // did not reach the disk; leave a trace instead of failing silently.
            FmLog.warn("FM_PROJECT_FLUSH_FAIL") { "onPause flush did not persist the project" }
        }
        super.onPause()
    }

    private companion object {

        /** Same ceiling the tray renders for one import batch (old app: 可多选). */
        const val MAX_PICK = 9
    }
}
