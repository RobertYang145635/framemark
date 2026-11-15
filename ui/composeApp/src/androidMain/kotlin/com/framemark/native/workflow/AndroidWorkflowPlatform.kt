package com.framemark.native.workflow

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ContentUris
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.framemark.native.engine.ExifProbe
import com.framemark.native.engine.NativeEngineProbe
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.framemark.native.product.FmLog

/**
 * Android implementation of the workflow platform: preset JSON in the app's
 * private files dir, sample images in the cache dir, Bitmap/Canvas encoder.
 */
class AndroidWorkflowPlatform(private val context: Context) : WorkflowPlatform {

    override val dataDir: String get() = context.filesDir.absolutePath

    override val exportDir: String
        get() = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, "Pictures"),
            "FrameMark",
        ).absolutePath

    override fun shareFile(path: String): String? = runCatching {
        val source = File(path).canonicalFile
        val allowed = File(exportDir).canonicalFile
        require(source.isFile && source.length() > 0 && source.parentFile == allowed) {
            "只能分享已完成的 FrameMark 导出文件"
        }
        val mime = when (source.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> error("无法分享此文件格式")
        }
        val shareDir = File(context.cacheDir, "shared-exports").apply { mkdirs() }
        // Keep active grants readable; only retire snapshots older than a day.
        shareDir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
            ?.forEach { it.delete() }
        val snapshot = File(shareDir, "${java.util.UUID.randomUUID()}-${source.name}")
        source.copyTo(snapshot, overwrite = false)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", snapshot)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, source.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享导出照片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        null
    }.getOrElse { "打开分享面板失败：${it.message}" }

    /**
     * 打开系统相册并定位到刚导出的照片（配色方案 §4.4「查看成片」）。
     *
     * 导出时 `AndroidNativeRender.publishToGallery` 已经把文件写进 MediaStore 的
     * `Pictures/FrameMark`。这里按「显示名 + 相对路径」把它查回来，再把 content Uri
     * 交给系统相册（ACTION_VIEW）。查不到（用户关掉了相册发布、或文件被移走）就退回到
     * 分享用的缓存快照，至少能看这张照片；两条路都失败时返回一句话，不静默。
     */
    override fun openGallery(path: String): String? = runCatching {
        val source = File(path).canonicalFile
        if (!source.isFile || source.length() <= 0L) return "这张照片还不存在，请重试写入相册。"
        val mime = when (source.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/*"
        }
        val uri = findInMediaStore(source.name) ?: fileProviderSnapshot(source)
            ?: return "相册里找不到这张照片，请重试写入相册。"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(view)
        FmLog.info("OPEN_GALLERY") { "file=${source.name} uri=$uri" }
        null
    }.getOrElse { "打开相册失败：${it.message}" }

    /** MediaStore 里按文件名找刚发布的照片；API 26–28 没有 RELATIVE_PATH 列。 */
    private fun findInMediaStore(name: String): android.net.Uri? = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to
                arrayOf(name, "%${Environment.DIRECTORY_PICTURES}/FrameMark%")
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME}=?" to arrayOf(name)
        }
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
    }.getOrNull()

    /** 相册里没有时，把文件复制成 FileProvider 可读的快照再交给查看器。 */
    private fun fileProviderSnapshot(source: File): android.net.Uri? = runCatching {
        val shareDir = File(context.cacheDir, "shared-exports").apply { mkdirs() }
        val snapshot = File(shareDir, "view-${source.name}")
        source.copyTo(snapshot, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.exports", snapshot)
    }.getOrNull()

    override fun commitExport(temporaryPath: String, finalPath: String): Boolean {
        val temporary = File(temporaryPath).canonicalFile
        val target = File(finalPath).canonicalFile
        require(temporary != target && temporary.isFile && temporary.length() > 0) { "导出临时文件无效" }
        target.parentFile?.mkdirs()
        var created = false
        try {
            java.nio.channels.FileChannel.open(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE).use { output ->
                created = true
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var totalWritten = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val bytes = java.nio.ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) output.write(bytes)
                        totalWritten += count
                        // W3-11 midwrite crash window (debug-only): some bytes are on
                        // disk, force(true) has NOT been called yet.  Park so an external
                        // `adb shell am kill` can be proven to land inside this window.
                        if (totalWritten >= 65_536 &&
                            com.framemark.native.LaunchOptions.crashWindow == "midwrite" &&
                            com.framemark.native.LaunchOptions.crashMarkerDir != null
                        ) {
                            val markerDir = com.framemark.native.LaunchOptions.crashMarkerDir!!
                            val marker = File(markerDir, "marker-midwrite")
                            marker.writeText("midwrite written=$totalWritten pid=${android.os.Process.myPid()}")
                            FmLog.info("FM_CRASH_WINDOW") { "midwrite written=$totalWritten pid=${android.os.Process.myPid()}" }
                            while (true) Thread.sleep(200)
                        }
                    }
                }
                output.force(true)
            }
            return true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            return false
        } catch (failure: Throwable) {
            if (created) target.delete()
            throw failure
        }
    }

    override fun deleteExportTemporary(path: String) { File(path).delete() }

    override fun readTextFile(path: String): String? = runCatching {
        File(path).takeIf { it.isFile }?.readText()
    }.getOrNull()

    override fun writeTextFile(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun exists(path: String): Boolean = File(path).isFile

    override fun fileSize(path: String): Long = File(path).length()

    override fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    override fun photoOrientation(path: String): Int =
        runCatching { ExifProbe.orientation(File(path)) }.getOrDefault(1)

    override fun thumbnail(path: String, maxEdge: Int): ByteArray? = runCatching {
        if (maxEdge <= 0) return null
        val source = File(path)
        // BitmapFactory cannot decode TIFF; the engine transcodes it (and bakes
        // the orientation) into a temporary PNG for the strip.
        var tiffTemp: File? = null
        var decodePath = path
        var orientation = 1
        if (ExifProbe.isTiff(source)) {
            val tmp = File(context.cacheDir, "thumbs/${System.nanoTime()}.png")
            tmp.parentFile?.mkdirs()
            val error = NativeEngineProbe.tiffToPng(path, tmp.absolutePath)
            if (error != null) return null
            tiffTemp = tmp
            decodePath = tmp.absolutePath
        } else {
            // BitmapFactory ignores EXIF; rotate so portrait shots are portrait.
            orientation = ExifProbe.orientation(source)
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(decodePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeFile(decodePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val oriented = ExifProbe.rotate(decoded, orientation)
            val long = maxOf(oriented.width, oriented.height)
            val scaled = if (long > maxEdge) {
                val factor = maxEdge.toFloat() / long
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * factor).toInt().coerceAtLeast(1),
                    (oriented.height * factor).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                oriented
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 84, out)
            if (scaled !== oriented) scaled.recycle()
            oriented.recycle()
            out.toByteArray()
        } finally {
            tiffTemp?.delete()
        }
    }.getOrNull()

    override fun encodeSampleImage(
        width: Int,
        height: Int,
        format: SampleFormat,
        quality: Int,
    ): ByteArray? = runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                Color.rgb(0xE9, 0xDC, 0xCD),
                Color.rgb(0xF6, 0xF0, 0xE6),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        val margin = width * 0.06f
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (width * 0.002f).coerceAtLeast(1.5f)
            color = Color.rgb(0xD6, 0xDD, 0xE6)
        }
        canvas.drawRoundRect(
            RectF(margin, margin, width - margin, height - margin),
            width * 0.05f,
            width * 0.05f,
            frame,
        )

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x5C, 0x66, 0x73)
            textSize = width * 0.045f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("示例导出", width / 2f, height / 2f, title)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x7F, 0x8A, 0x97)
            textSize = width * 0.022f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("FrameMark 占位图", width / 2f, height / 2f + height * 0.07f, sub)

        val out = ByteArrayOutputStream()
        val compress = if (format == SampleFormat.Png) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(compress, quality.coerceIn(60, 100), out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    /** The picked photo, else the bundled sample the renderer would use. */
    override fun samplePhotoPath(): String? =
        com.framemark.native.workflow.PhotoSelection.path
            ?: runCatching { com.framemark.native.engine.EngineData.snapshot(context).photo }.getOrNull()

    /**
     * The bundled engine sample only — never the QA-selected path. The border grid
     * uses this as the "show me the frame" photo, so it must not resolve back to the
     * photo that just failed to preview.
     */
    override fun bundledSamplePhotoPath(): String? =
        runCatching { com.framemark.native.engine.EngineData.snapshot(context).photo }.getOrNull()

    override fun fileFingerprint(path: String): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    override fun batchAutoStart(): Boolean = com.framemark.native.LaunchOptions.batchAutoStart

    override fun dateStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    override fun timeStamp(): String = SimpleDateFormat("HHmmss", Locale.US).format(Date())

    override fun formatDateTime(epochMillis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMillis))

    override fun nowMillis(): Long = System.currentTimeMillis()
}
