package com.framemark.native.shell

/**
 * Skia can blur composable content, but **not a `GraphicsLayer` drawn with
 * `drawLayer`**: measured on this Compose Multiplatform 1.9.0 / Skiko path, a
 * recorded layer painted inside a `Modifier.blur` node — or with its own
 * `GraphicsLayer.renderEffect` — comes out pixel-identical to the unblurred copy
 * (`FmNavGlassRenderTest.theCapsuleNeverPaintsAnUnblurredGhost` renders exactly that
 * case). Drawing a sharp copy under a translucent wash reads as a mis-aligned
 * ghost, which is worse than no glass at all, so desktop reports "no blur" and the
 * capsule draws its opaque-ish fallback fill.
 *
 * Android is a different renderer (`RenderEffect` on API 31+); the capture path
 * there still needs an on-device check before it is switched on — see the QA steps
 * in `FmNavGlassRenderTest`'s KDoc.
 */
internal actual fun fmBackdropBlurSupported(): Boolean = false
