package com.framemark.native.workflow

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Desktop implementation of the workflow platform: preset JSON under a private
 * app folder, sample images under the system temp folder, AWT placeholder encoder.
 */
class DesktopWorkflowPlatform(
    override val dataDir: String = defaultDataDir(),
    exportDir: String? = null,
) : WorkflowPlatform {

    private val preferencesFile = File(dataDir, "local-export.properties")
    private val preferences = java.util.Properties().apply {
        if (preferencesFile.isFile) runCatching { preferencesFile.inputStream().use { load(it) } }
    }
    private var selectedExportDir = exportDir ?: System.getenv("FM_EXPORT_DIR")?.takeIf { it.isNotBlank() }
        ?: preferences.getProperty("directory")?.takeIf { it.isNotBlank() } ?: defaultExportDir()
    override val exportDir: String get() = selectedExportDir
    override val canChooseExportDirectory: Boolean get() = true
    override val jpegQualityAdjustable: Boolean get() = true

    /**
     * D-2：`local-export.properties` 里的 `jpegQuality`。
     *
     * [configuredJpegQuality] 区分「没写过」（null，工程值说了算）与「写了 100」，
     * 好让 `exportSettingsForUi()` 只在真有本机偏好时覆盖工程文档里的质量。
     */
    override fun configuredJpegQuality(): Int? =
        preferences.getProperty("jpegQuality")?.toIntOrNull()?.takeIf { it in 60..100 }

    override fun preferredJpegQuality(): Int = configuredJpegQuality() ?: 100

    private fun savePreference(key: String, value: String) {
        val next = java.util.Properties().apply { putAll(preferences); setProperty(key, value) }
        preferencesFile.parentFile.mkdirs()
        val temporary = File(preferencesFile.parentFile, preferencesFile.name + ".tmp")
        temporary.outputStream().use { next.store(it, "FrameMark local export preferences") }
        java.nio.file.Files.move(temporary.toPath(), preferencesFile.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        preferences.clear()
        preferences.putAll(next)
    }

    override fun setPreferredJpegQuality(value: Int): String? = runCatching {
        require(value in 60..100) { "JPEG 质量应为 60–100" }
        savePreference("jpegQuality", value.toString())
        null
    }.getOrElse { "保存 JPEG 质量失败：${it.message}" }

    override fun chooseExportDirectory(): String? {
        val chooser = javax.swing.JFileChooser(File(exportDir)).apply {
            dialogTitle = "选择 FrameMark 输出目录"
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        val directory = chooser.selectedFile.canonicalFile
        require(directory.isDirectory && directory.canWrite()) { "所选目录不可写" }
        savePreference("directory", directory.path)
        selectedExportDir = directory.path
        return directory.path
    }


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
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val bytes = java.nio.ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) output.write(bytes)
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

    /**
     * Packaged builds carry `-Dframemark.build.label` (the packaging launcher
     * injects it; jpackage itself only sets `jpackage.app-version`); a run
     * started from the workspace falls back to the stamp file the packaging step
     * writes next to the runtime. Neither present means a dev build, and the shell
     * says so instead of showing a stale hard-coded version.
     */
    override fun buildLabel(): String? {
        System.getProperty("framemark.build.label")?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = runCatching {
            listOf(
                File("FRAMEMARK-PACKAGE.json"),
                File(com.framemark.native.engine.RuntimePaths.layout().root, "FRAMEMARK-PACKAGE.json"),
            )
        }.getOrDefault(emptyList())
        for (file in candidates.filter { it.isFile }) {
            val label = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"").find(file.readText())?.groupValues?.getOrNull(1)
            if (!label.isNullOrBlank()) return label
        }
        return null
    }

    override fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    /**
     * Opens Explorer with the exported file selected. Explorer exits non-zero even
     * when it succeeded, so only a launch failure counts as a failure here.
     */
    override fun revealInFolder(path: String): String? {
        val file = File(path)
        if (!file.exists()) return "文件还不存在：$path"
        val args = if (file.isFile) {
            listOf("explorer.exe", "/select,${file.absolutePath}")
        } else {
            listOf("explorer.exe", file.absolutePath)
        }
        return runCatching {
            val process = ProcessBuilder(args).start()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            null
        }.getOrElse { "打开所在目录失败：${it.message}" }
    }

    override fun thumbnail(path: String, maxEdge: Int): ByteArray? = runCatching {
        if (maxEdge <= 0) return null
        val source = ImageIO.read(File(path)) ?: return null
        val long = maxOf(source.width, source.height)
        val factor = if (long > maxEdge) maxEdge.toFloat() / long else 1f
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(scaled, "jpg", it) }.toByteArray()
    }.getOrNull()

    override fun encodeSampleImage(
        width: Int,
        height: Int,
        format: SampleFormat,
        quality: Int,
    ): ByteArray? = runCatching {
        if (format == SampleFormat.WebP) return@runCatching null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(
                0f,
                0f,
                Color(0xFFE9DCCD.toInt()),
                width.toFloat(),
                height.toFloat(),
                Color(0xFFF6F0E6.toInt()),
            )
            g.fillRect(0, 0, width, height)

            val margin = (width * 0.06f).toInt()
            g.color = Color(0xFFD6DDE6.toInt())
            g.drawRoundRect(margin, margin, width - margin * 2, height - margin * 2, (width * 0.05f).toInt(), (width * 0.05f).toInt())

            g.color = Color(0xFF5C6673.toInt())
            val titleFont = pickFont(Font.BOLD, (width * 0.045f).toInt())
            g.font = titleFont
            val title = "示例导出"
            val titleWidth = g.fontMetrics.stringWidth(title)
            g.drawString(title, (width - titleWidth) / 2, height / 2)

            g.color = Color(0xFF7F8A97.toInt())
            g.font = pickFont(Font.PLAIN, (width * 0.022f).toInt())
            val sub = "FrameMark 占位图"
            val subWidth = g.fontMetrics.stringWidth(sub)
            g.drawString(sub, (width - subWidth) / 2, height / 2 + (height * 0.07f).toInt())
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        if (format == SampleFormat.Png) {
            ImageIO.write(image, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = quality.coerceIn(60, 100) / 100f
                }
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(image, null, null), param)
                }
            } finally {
                writer.dispose()
            }
        }
        out.toByteArray()
    }.getOrNull()

    override fun dateStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    override fun timeStamp(): String = SimpleDateFormat("HHmmss", Locale.US).format(Date())

    override fun formatDateTime(epochMillis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMillis))

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun processStartMillis(): Long? =
        ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null)

    override fun samplePhotoPath(): String? {
        System.getenv("FM_BATCH_PHOTO")?.takeIf { it.isNotBlank() }?.let { return it }
        // QA runs point FM_WORKSPACE_ROOT at the materials workspace. A packaged
        // build has no materials tree, so the sample is looked up next to the
        // runtime and is simply absent otherwise. A developer path used to be the
        // fallback, which made the sample depend on this machine's disk layout and
        // shipped a D: path inside the product.
        val candidates = buildList {
            System.getenv("FM_WORKSPACE_ROOT")?.takeIf { it.isNotBlank() }?.let {
                add(File(it, "_research/native/t1b/assets/img/photo-pil.png"))
            }
            // The launcher pushes the working directory to the package root, so a
            // packaged sample is found relatively — without baking a developer path
            // into the product and without a new environment hook.
            add(File("data/t1b/assets/img/photo-pil.png"))
            add(File("data/assets/img/photo-pil.png"))
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    override fun fileFingerprint(path: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(File(path).readBytes())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    override fun batchAutoStart(): Boolean = System.getenv("FM_BATCH_AUTOSTART") == "1"

    private fun pickFont(style: Int, size: Int): Font {
        val preferred = listOf("Microsoft YaHei", "SimHei", "SansSerif")
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val name = preferred.firstOrNull { it in available } ?: "SansSerif"
        val font = Font(name, style, size.coerceAtLeast(10))
        return if (font.canDisplayUpTo("示例导出") >= 0) {
            Font("SansSerif", style, size.coerceAtLeast(10))
        } else {
            font
        }
    }

    companion object {
        fun defaultDataDir(): String {
            System.getenv("FM_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return it }
            val local = System.getenv("LOCALAPPDATA")
            if (!local.isNullOrBlank()) return "$local/FrameMarkNative"
            return System.getProperty("user.home") + "/.framemark-native"
        }

        fun defaultExportDir(): String {
            System.getenv("FM_EXPORT_DIR")?.takeIf { it.isNotBlank() }?.let { return it }
            val pictures = if (System.getProperty("os.name").startsWith("Windows")) runCatching {
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); [Environment]::GetFolderPath('MyPictures')")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    null
                } else process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim().takeIf { it.isNotBlank() }
            }.getOrNull() else null
            return File(pictures ?: File(System.getProperty("user.home"), "Pictures").path, "FrameMark").path
        }
    }
}
