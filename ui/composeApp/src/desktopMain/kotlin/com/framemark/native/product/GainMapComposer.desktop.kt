package com.framemark.native.product

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * Desktop gain-map compositor: decode the appended gain map, draw the source
 * region the renderer used ([GainMapJob.sourceRect]) into the photo rectangle of
 * a half-size canvas, fill the rest with the 0 dB sentinel, encode.
 *
 * Bilinear scaling is the spec's minimum sampling quality. The gain map is a
 * low-frequency signal, so this is not a quality bottleneck; the surrounding
 * pixels being an exact constant is what keeps a border identical in SDR and HDR.
 */
internal actual object GainMapComposer {

    actual fun compose(job: GainMapJob): ByteArray? = runCatching {
        val source = ImageIO.read(ByteArrayInputStream(job.source, job.offset, job.size)) ?: return null
        val divisor = job.divisor.coerceAtLeast(1)
        val width = (job.canvasWidth / divisor).coerceAtLeast(1)
        val height = (job.canvasHeight / divisor).coerceAtLeast(1)

        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.color = Color(job.sentinel[0], job.sentinel[1], job.sentinel[2])
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val x = (job.photoRect.getOrElse(0) { 0.0 } / divisor).roundToInt()
            val y = (job.photoRect.getOrElse(1) { 0.0 } / divisor).roundToInt()
            val w = (job.photoRect.getOrElse(2) { 0.0 } / divisor).roundToInt().coerceAtLeast(1)
            val h = (job.photoRect.getOrElse(3) { 0.0 } / divisor).roundToInt().coerceAtLeast(1)
            // Only the cropped part of the source is the gain map of what was
            // drawn; using the whole gain map here would shift every highlight.
            val src = job.gainMapRect(source.width, source.height)
            g.drawImage(source, x, y, x + w, y + h, src[0], src[1], src[2], src[3], null)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = GAIN_MAP_QUALITY
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(canvas, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        out.toByteArray()
    }.getOrNull()

    /** The spec suggests 85–90 for a gain map; it is a secondary image. */
    private const val GAIN_MAP_QUALITY = 0.88f
}
