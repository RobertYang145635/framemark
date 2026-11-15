package com.framemark.native.product

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * Desktop crop primitives: a header-only size probe and an AWT crop.
 *
 * The size probe reads the frame header (`ImageReader.getWidth/getHeight`) rather
 * than decoding, so measuring a 50 MP photo costs one file open. The crop decodes
 * once (the export is about to encode the whole canvas anyway) and re-encodes the
 * region at quality 100 so the renderer receives the user's pixels, not a
 * second-generation lossy approximation of them.
 */
internal actual fun photoSourceSize(path: String): IntArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    ImageIO.createImageInputStream(file).use { input ->
        if (input == null) return@runCatching null
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@runCatching null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width > 0 && height > 0) intArrayOf(width, height) else null
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

internal actual fun cropJpegTo(
    sourcePath: String,
    rect: DoubleArray,
    outPath: String,
    orientation: Int,
): Boolean =
    runCatching {
        if (rect.size < 4) return@runCatching false
        val source = ImageIO.read(File(sourcePath)) ?: return@runCatching false
        if (source.width <= 0 || source.height <= 0) return@runCatching false

        val x = rect[0].roundToInt().coerceIn(0, source.width - 1)
        val y = rect[1].roundToInt().coerceIn(0, source.height - 1)
        val width = rect[2].roundToInt().coerceIn(1, source.width - x)
        val height = rect[3].roundToInt().coerceIn(1, source.height - y)

        // `getSubimage` shares the source raster; the rotation (when any) returns a
        // detached surface, and the RGB canvas below detaches the orientation-1
        // path too, so the encoder and the source decode stay independent.
        val region = source.getSubimage(x, y, width, height).orientedImage(orientation)
        val canvas = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.drawImage(region, 0, 0, null)
        } finally {
            graphics.dispose()
        }

        val target = File(outPath)
        target.parentFile?.mkdirs()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = CROP_QUALITY
            ImageIO.createImageOutputStream(target).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(canvas, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        target.isFile && target.length() > 0L
    }.getOrDefault(false)

/** The cropped copy is the renderer's input, so keep it lossless-in-JPEG (1.0). */
private const val CROP_QUALITY = 1.0f
