package com.framemark.native.product

/**
 * Platform bits of the crop export: measure the photo, and write a cropped copy.
 *
 * Both are platform code because both decode a JPEG/PNG; everything about *which*
 * rectangle to keep is common and unit-tested ([CropGeometry.region]).
 */

/**
 * Pixel size of a photo file's **stored raster**: the width/height in the frame
 * header, before EXIF orientation.
 *
 * The engine does not draw this raster — Skia applies the encoded origin, so the
 * renderer, the crop UI and the export canvas all work in the display frame
 * `PhotoOrientation.displaySize(width, height, orientation)`. Callers convert
 * first; this probe stays raw because it is also what the fallback pre-crop
 * feeds to [cropJpegTo].
 *
 * Null when the file cannot be read or has no decodable header.
 */
internal expect fun photoSourceSize(path: String): IntArray?

/**
 * Writes a JPEG holding only [rect] of the photo at [sourcePath] to [outPath],
 * with the EXIF orientation baked into the pixels.
 *
 * [rect] is `x, y, w, h` in **raw** (stored raster) pixels — callers map a
 * display-space rectangle through `PhotoOrientation.displayRectToRaw` first.
 * [orientation] is applied *after* the crop (the two commute), so the renderer
 * receives an upright, orientation-free copy: a raw crop handed to the engine
 * without this step would render sideways, because re-encoding drops the tag.
 * The rectangle is clamped to the decoded image. Returns true only when
 * [outPath] was written and reads back non-empty.
 */
internal expect fun cropJpegTo(sourcePath: String, rect: DoubleArray, outPath: String, orientation: Int): Boolean
