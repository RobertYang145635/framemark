package com.framemark.native.product

/**
 * Platform bits of the crop export: measure the photo, and write a cropped copy.
 *
 * Both are platform code because both decode a JPEG/PNG; everything about *which*
 * rectangle to keep is common and unit-tested ([CropGeometry.region]).
 */

/**
 * Pixel size of a photo file as the renderer sees it: the frame the engine
 * decodes, i.e. the raw raster **without** EXIF rotation.
 *
 * That is not an oversight: the engine draws the decoded pixels and the whole HDR
 * alignment contract ([UltraHdrExport.sourceRect], `UltraHdr.frameSize`) is
 * defined in this same raw space, so the crop has to speak it too — an
 * EXIF-oriented size would make the two disagree on every portrait shot.
 *
 * Null when the file cannot be read or has no decodable header.
 */
internal expect fun photoSourceSize(path: String): IntArray?

/**
 * Writes a JPEG holding only [rect] of the photo at [sourcePath] to [outPath].
 *
 * [rect] is `x, y, w, h` in source pixels as produced by [CropGeometry.region];
 * it is clamped to the decoded image, so a rounding difference between the size
 * probe and the decoder cannot read outside the picture. Returns true only when
 * [outPath] was written and reads back non-empty.
 */
internal expect fun cropJpegTo(sourcePath: String, rect: DoubleArray, outPath: String): Boolean
