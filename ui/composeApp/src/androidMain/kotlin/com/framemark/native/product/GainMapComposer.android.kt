package com.framemark.native.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/**
 * Android gain-map compositor: decode the appended gain map, draw the source
 * region the renderer used ([GainMapJob.sourceRect]) into the photo rectangle of
 * a half-size canvas, fill the rest with the 0 dB sentinel, encode.
 *
 * Same contract as the desktop implementation; `FILTER_BITMAP_FLAG` gives the
 * bilinear sampling the format asks for, and the surrounding pixels are a literal
 * constant so a border renders identically in SDR and HDR.
 */
internal actual object GainMapComposer {

    actual fun compose(job: GainMapJob): ByteArray? = runCatching {
        val decoded = BitmapFactory.decodeByteArray(job.source, job.offset, job.size) ?: return null
        // Raw-aligned gain map → display-oriented canvas: rotate before cropping.
        val source = decoded.orientedImage(job.orientation)
        if (source !== decoded) decoded.recycle()
        val divisor = job.divisor.coerceAtLeast(1)
        val width = (job.canvasWidth / divisor).coerceAtLeast(1)
        val height = (job.canvasHeight / divisor).coerceAtLeast(1)

        val canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(canvas)
            c.drawColor(Color.rgb(job.sentinel[0], job.sentinel[1], job.sentinel[2]))
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val dst = RectF(
                (job.photoRect.getOrElse(0) { 0.0 } / divisor).toFloat(),
                (job.photoRect.getOrElse(1) { 0.0 } / divisor).toFloat(),
                ((job.photoRect.getOrElse(0) { 0.0 } + job.photoRect.getOrElse(2) { 0.0 }) / divisor).toFloat(),
                ((job.photoRect.getOrElse(1) { 0.0 } + job.photoRect.getOrElse(3) { 0.0 }) / divisor).toFloat(),
            )
            // Only the cropped part of the source is the gain map of what was
            // drawn; using the whole gain map here would shift every highlight.
            val src = job.gainMapRect(source.width, source.height)
            c.drawBitmap(source, Rect(src[0], src[1], src[2], src[3]), dst, paint)

            val out = ByteArrayOutputStream()
            canvas.compress(Bitmap.CompressFormat.JPEG, GAIN_MAP_QUALITY, out)
            out.toByteArray()
        } finally {
            source.recycle()
            canvas.recycle()
        }
    }.getOrNull()

    /** The spec suggests 85–90 for a gain map; it is a secondary image. */
    private const val GAIN_MAP_QUALITY = 88
}
