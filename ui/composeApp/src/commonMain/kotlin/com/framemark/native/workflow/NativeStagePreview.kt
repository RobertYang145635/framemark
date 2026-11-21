package com.framemark.native.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.framemark.native.LaunchOptions
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.library.PreviewDiagnostics
import com.framemark.native.product.PreviewQuality
import com.framemark.native.product.PreviewTier
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import com.framemark.native.product.FmLog

private const val PREVIEW_SUPERSAMPLE = 1.25f
// The engine itself serializes frames. Keep only a short UI breathing gap so a
// fast desktop renderer can track the pointer; slower devices are naturally
// paced by their render time instead of an artificial fixed delay.
private const val INTERACTIVE_FRAME_INTERVAL_MS = 33L
private const val FINAL_DEBOUNCE_MS = 120L
private const val PREVIEW_GATE_RETRY_MS = 16L
/** How often the A-07 wait badge re-evaluates which phase it is in. */
private const val RENDER_PHASE_TICK_MS = 50L

/** One immutable input snapshot; only the latest submitted by the UI can render. */
private data class StagePreviewInput(
    val card: LibraryCard,
    val design: DesignSnapshot,
    val overlay: OverlaySpec?,
    val photoPath: String?,
    val key: PreviewRequestKey,
    val interactiveKey: PreviewRequestKey,
    val maxEdge: Int,
    val interactiveMaxEdge: Int,
    val displayEdge: Int,
)

private class LatestStagePreviewInput(
    var value: StagePreviewInput,
    var changedAt: TimeMark,
)

/** Shared engine lock; schedulers try-lock and coalesce instead of joining a wait queue. */
private val previewGate = Mutex()
private val renderCache = PreviewRenderCache()

private sealed interface StageRender {
    data object Loading : StageRender
    data class Ready(
        val requestKey: PreviewRequestKey,
        val bitmap: ImageBitmap,
        val photoRect: ImageRect? = null,
        val textSlots: List<TextSlot> = emptyList(),
        /** Engine `fit.source_canvas` (final canvas at photo pixels); null when absent. */
        val canvasPx: Pair<Int, Int>? = null,
    ) : StageRender
    data class Failed(val requestKey: PreviewRequestKey, val reason: String) : StageRender
}

/**
 * Stage preview for the cards wired to the real engine: the static border image
 * stays visible while the native render runs, then the rendered image replaces
 * it. Edits re-render after a short debounce; leaving a card cancels its render.
 */
@Composable
fun NativeStagePreview(
    card: LibraryCard,
    design: DesignSnapshot,
    modifier: Modifier = Modifier,
    /** Committed watermark; folded into the cache key so a change re-renders. */
    overlay: OverlaySpec? = null,
    /** Per-call source photo; null keeps the manifest sample photo. */
    photoPath: String? = null,
    /** Rendered pixel size + the engine photo box (the overlay anchor). */
    onRendered: ((Int, Int, ImageRect?) -> Unit)? = null,
    /**
     * Engine `fit.source_canvas`: the finished canvas at the photo's own pixels —
     * the size "原尺寸" writes, including a template's own crop. Delivered with the
     * same frame as [onRendered]; null when the engine did not report it.
     */
    onCanvas: ((Int, Int) -> Unit)? = null,
    /** Current render's text metadata, delivered only with a matching Ready key. */
    onTextSlots: ((Int, Int, List<TextSlot>) -> Unit)? = null,
    /** Fired when a new render key starts; the old photo box must not be reused. */
    onRenderStarted: (() -> Unit)? = null,
    /** Reports a render/decode failure; the static card preview stays visible. */
    onFailed: ((String) -> Unit)? = null,
    /**
     * §3.3: stable key (`TextSlot.key`) of the text row being edited. The stage
     * draws its frame only on the current valid render; null draws nothing.
     */
    highlightedTextSlotKey: String? = null,
    /**
     * §3.3: the display name of that row (`左上 · 品牌`), already computed by the
     * page that owns the 文字 nav band (`textSlotNavEntries`). The label travels as
     * a parameter on purpose: the workflow package must not grow a second naming
     * scheme next to the inspector's. Null (or blank) keeps the old stage — the
     * chip then names the position alone.
     */
    highlightedTextSlotLabel: String? = null,
    /**
     * WP-C §4.5: the watermark layer to drag directly on the photo. Null keeps
     * the historical behaviour (no handle, no zone frame).
     */
    watermark: WatermarkSpec? = null,
    /**
     * Called with the **absolute** normalised `dx/dy` after a drag; the value is
     * already clamped to the watermark's own cell zone and matches what the
     * margin sliders write.
     */
    onWatermarkNudge: ((dx: Float, dy: Float) -> Unit)? = null,
    /**
     * WP-R D8: 水印的虚线区域框 + 十字辅助线只在**编辑水印时**画。
     * 演示稿的普通编辑态（样式/文字/Logo 页）既有水印也没有这层线，
     * 所以默认 false，由桌面 shell 在打开水印页时打开。
     */
    watermarkGuides: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier) {
        val displayEdge = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            max(constraints.maxWidth, constraints.maxHeight)
        } else {
            PreviewTier.MAX_EDGE
        }
        val maxEdge = LaunchOptions.previewMaxEdge ?: adaptivePreviewEdge(displayEdge)
        val interactiveMaxEdge = PreviewTier.interactiveEdge(maxEdge)
        val selectedPhotoPath = PhotoSelection.path
        val photoSelectionVersion = PhotoSelection.version
        val launchPhotoPath = LaunchOptions.userPhotoPath
        val designJsonOverride = LaunchOptions.designJsonOverride
        val userLogoRevision = UserLogoLibrary.revision
        // Snapshot and key construction walk nested maps. Remember them so unrelated
        // Compose state cannot repeat that work on every recomposition.
        val renderDesign = remember(design) { snapshotPreviewDesign(design) }
        val key = remember(
            renderDesign,
            photoPath,
            selectedPhotoPath,
            photoSelectionVersion,
            launchPhotoPath,
            maxEdge,
            overlay,
            designJsonOverride,
            userLogoRevision,
        ) {
            PreviewRequestKey.create(
                cardId = card.id,
                design = renderDesign,
                photoPath = photoPath,
                selectedPhotoPath = selectedPhotoPath,
                launchPhotoPath = launchPhotoPath,
                photoSelectionVersion = photoSelectionVersion,
                maxEdge = maxEdge,
                overlay = overlay,
                designJsonOverride = designJsonOverride,
                userLogoRevision = userLogoRevision,
            )
        }
        val interactiveKey = remember(key, interactiveMaxEdge) { key.withMaxEdge(interactiveMaxEdge) }
        val requestGate = remember(card.id) { PreviewRequestGate(key) }
        val latestKey by rememberUpdatedState(key)
        val latestOnRendered by rememberUpdatedState(onRendered)
        val latestOnCanvas by rememberUpdatedState(onCanvas)
        val latestOnTextSlots by rememberUpdatedState(onTextSlots)
        val latestOnRenderStarted by rememberUpdatedState(onRenderStarted)
        val latestOnFailed by rememberUpdatedState(onFailed)
        val currentInput = StagePreviewInput(
            card = card,
            design = renderDesign,
            overlay = overlay,
            photoPath = photoPath,
            key = key,
            interactiveKey = interactiveKey,
            maxEdge = maxEdge,
            interactiveMaxEdge = interactiveMaxEdge,
            displayEdge = displayEdge,
        )
        val latestInput = remember(card.id) {
            LatestStagePreviewInput(currentInput, TimeSource.Monotonic.markNow())
        }
        val requestSignal = remember(card.id) { Channel<Unit>(Channel.CONFLATED) }
        DisposableEffect(requestSignal) {
            onDispose { requestSignal.close() }
        }
        // A-07 "取消渲染": while an engine frame is in flight the user can drop it. The
        // request coroutine is keyed on this flag, so flipping it cancels the running
        // render (the desktop renderer runs the engine inside `runInterruptible`, so
        // the worker thread is interrupted) and no stale result is ever published.
        // The last finished frame stays on the stage; the next real edit clears the
        // flag (see the SideEffect below) and renders again.
        var cancelRequested by remember(card.id) { mutableStateOf(false) }
        // Compose owns the immediate UI value. This side effect publishes only a
        // successfully committed render input and wakes the one scheduler; changing
        // a key no longer cancels or restarts a native render.
        SideEffect {
            requestGate.update(key)
            if (latestInput.value.key != key) {
                latestInput.value = currentInput
                latestInput.changedAt = TimeSource.Monotonic.markNow()
                // A new input always revives rendering: the previous cancel only
                // applied to the frame the user had given up on.
                cancelRequested = false
                requestSignal.trySend(Unit)
            }
        }
        var state by remember(card.id) { mutableStateOf<StageRender>(StageRender.Loading) }
        // A re-render must not blank the stage: the previous frame stays until the new
        // one is ready, with a small busy badge on top.
        var busy by remember(card.id) { mutableStateOf(false) }
        // UX-RESEARCH A-07 / WP-C §4.5: three-stage wait feedback. The engine call
        // is blocking, so an in-flight render cannot be cancelled from here; the
        // phase only controls what the user sees (see the WP-C report).
        var renderPhase by remember(card.id) { mutableStateOf(StageRenderPhase.Idle) }
        LaunchedEffect(card.id, busy) {
            if (!busy) {
                renderPhase = StageRenderPhase.Idle
                return@LaunchedEffect
            }
            val startedAt = TimeSource.Monotonic.markNow()
            while (busy) {
                renderPhase = StageRenderTiming.phaseAt(startedAt.elapsedNow().inWholeMilliseconds)
                delay(RENDER_PHASE_TICK_MS)
            }
            renderPhase = StageRenderPhase.Idle
        }
        // Preview-box pixels, needed to project an output-pixel slot rectangle onto
        // the ContentScale.Fit frame. Remembered per card like the render state.
        var boxPx by remember(card.id) { mutableStateOf(IntSize.Zero) }

        LaunchedEffect(card.id, requestSignal, cancelRequested) {
            if (cancelRequested) {
                // The user cancelled the frame that was running. Restarting the effect
                // (the key change) already cancelled the render coroutine; nothing
                // stale may be published, so the stage keeps its last frame.
                busy = false
                renderPhase = StageRenderPhase.Idle
                FmLog.info("FM_PREVIEW_CANCEL") { "card=${card.id} reason=user" }
                requestSignal.trySend(Unit)
                return@LaunchedEffect
            }
            // One coroutine serializes submissions for this stage. The conflated
            // channel carries only a wake-up; all render parameters are read from
            // latestInput, so intermediate slider/drag samples are never queued.
            requestSignal.trySend(Unit)
            var lastNativeSubmitAt: TimeMark? = null
            var lastInteractiveVersion = -1L

            fun drainSignals() {
                while (requestSignal.tryReceive().isSuccess) {
                    // Wake-ups are level-triggered by latestInput, not work items.
                }
            }

            fun isCurrentRequest(request: StagePreviewInput, version: Long): Boolean =
                requestGate.isCurrent(request.key, version) &&
                    latestKey == request.key &&
                    latestInput.value.key == request.key

            suspend fun awaitFixedSlot() {
                val last = lastNativeSubmitAt ?: return
                while (true) {
                    val remaining = INTERACTIVE_FRAME_INTERVAL_MS -
                        last.elapsedNow().inWholeMilliseconds
                    if (remaining <= 0) return
                    val changed = withTimeoutOrNull(remaining) { requestSignal.receive() }
                    if (changed == null) return
                }
            }

            suspend fun waitForFinalIdle(
                request: StagePreviewInput,
                version: Long,
            ): Boolean {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!isCurrentRequest(request, version)) {
                        requestSignal.trySend(Unit)
                        return false
                    }
                    val remaining = FINAL_DEBOUNCE_MS -
                        latestInput.changedAt.elapsedNow().inWholeMilliseconds
                    if (remaining <= 0) return true
                    val changed = withTimeoutOrNull(remaining) { requestSignal.receive() }
                    if (changed == null) return true
                    if (!isCurrentRequest(request, version)) {
                        // This wake-up represents real pending state; put it back for
                        // the outer loop instead of losing the last update.
                        requestSignal.trySend(Unit)
                        return false
                    }
                }
            }

            suspend fun applyCached(
                request: StagePreviewInput,
                requestKey: PreviewRequestKey,
                edge: Int,
                quality: String,
                version: Long,
                requestStarted: TimeMark,
            ): Boolean {
                val decoded = renderCache.getDecoded(requestKey)?.takeIf { it.photoRect != null }
                if (decoded != null) {
                    if (!isCurrentRequest(request, version)) return false
                    if (quality == "interactive") {
                        lastInteractiveVersion = maxOf(lastInteractiveVersion, version)
                    }
                    state = StageRender.Ready(
                        requestKey,
                        decoded.bitmap,
                        decoded.photoRect,
                        decoded.textSlots,
                        decoded.canvasPx,
                    )
                    busy = quality == "interactive"
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=cache-hit " +
                            "quality=$quality edge=$edge cache=decoded " +
                            "wallMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                    return true
                }

                val cached = renderCache.get(requestKey)?.takeIf { it.photoRect != null }
                if (cached != null) {
                    val decodeStarted = TimeSource.Monotonic.markNow()
                    val bitmap = withContext(Dispatchers.Default) {
                        LibraryPreviewLoader.decode(cached.bytes)
                    }
                    if (!isCurrentRequest(request, version)) return false
                    if (quality == "interactive") {
                        lastInteractiveVersion = maxOf(lastInteractiveVersion, version)
                    }
                    renderCache.attachDecoded(requestKey, bitmap)
                    state = StageRender.Ready(requestKey, bitmap, cached.photoRect, cached.textSlots, cached.canvasPx)
                    busy = quality == "interactive"
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=cache-hit " +
                            "quality=$quality edge=$edge cache=bytes " +
                            "decodeMs=${decodeStarted.elapsedNow().inWholeMilliseconds} " +
                            "wallMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                    return true
                }
                return false
            }

            suspend fun renderEngine(
                request: StagePreviewInput,
                requestKey: PreviewRequestKey,
                edge: Int,
                quality: String,
                version: Long,
                requestStarted: TimeMark,
            ): Boolean {
                var gateHeld = false
                val image = try {
                    while (true) {
                        coroutineContext.ensureActive()
                        if (!isCurrentRequest(request, version)) {
                            FmLog.info("FM_PREVIEW_REQUEST") {
                                "card=${request.card.id} version=$version " +
                                    "phase=stale-drop quality=$quality edge=$edge reason=coalesced"
                            }
                            return false
                        }
                        if (previewGate.tryLock()) {
                            gateHeld = true
                            break
                        }
                        // Never wait in the mutex queue: poll, then coalesce to the
                        // newest field value while another preview owns the engine.
                        delay(PREVIEW_GATE_RETRY_MS)
                    }
                    // The field may have changed between the last check and lock
                    // acquisition. Do not spend a full engine render on a request
                    // that is already known to be obsolete.
                    if (!isCurrentRequest(request, version)) {
                        FmLog.info("FM_PREVIEW_REQUEST") {
                            "card=${request.card.id} version=$version " +
                                "phase=stale-drop quality=$quality edge=$edge reason=acquired-stale"
                        }
                        return false
                    }
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=submit " +
                            "quality=$quality edge=$edge " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                    NativeEngine.preview(
                        request.card,
                        request.design,
                        edge,
                        request.overlay,
                        request.photoPath,
                        interactive = quality == PreviewQuality.Interactive.wire,
                    )
                } finally {
                    if (gateHeld) previewGate.unlock()
                }
                // A-02: the engine wrote this preview to a per-request PNG (and a
                // geom sidecar on Android). The bytes above are the only thing the
                // stage needs, so every exit — published, stale-drop, publish-gate,
                // exception — must release those files, or the cache grows without
                // bound (the audit found 5,324 files / 1.94 GiB on a device).
                try {
                val currentOnCompletion = isCurrentRequest(request, version)
                if (quality != "interactive" && !currentOnCompletion) {
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=superseded " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                    return false
                }
                if (quality == "interactive" && version <= lastInteractiveVersion) {
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=already-progressed"
                    }
                    return false
                }
                if (image == null) throw IllegalStateException("no native renderer")
                val bytes = withContext(Dispatchers.Default) {
                    WorkflowEnv.platform?.readBytes(image.path)
                } ?: throw IllegalStateException("cannot read ${image.path}")
                val bitmap = withContext(Dispatchers.Default) { LibraryPreviewLoader.decode(bytes) }
                // Interactive frames are progress feedback, so a frame completed
                // while the pointer moved on is still useful. Publish newer progress
                // in strict version order. A final-quality frame remains strictly
                // latest-only, so it can never overwrite a newer design or photo.
                val published = if (quality == "interactive") {
                    if (version <= lastInteractiveVersion) {
                        false
                    } else {
                        lastInteractiveVersion = version
                        renderCache.put(requestKey, bytes, image.photoRect, image.textSlots, bitmap, image.canvasPx)
                        state = StageRender.Ready(requestKey, bitmap, image.photoRect, image.textSlots, image.canvasPx)
                        busy = true
                        image.photoRect?.let { rect ->
                            PreviewGeometry.record(
                                request.card.id,
                                rect.w / rect.h.coerceAtLeast(1f),
                            )
                        }
                        true
                    }
                } else {
                    requestGate.publishIfCurrent(request.key, version) {
                        if (latestKey == request.key && latestInput.value.key == request.key) {
                            renderCache.put(requestKey, bytes, image.photoRect, image.textSlots, bitmap, image.canvasPx)
                            state = StageRender.Ready(requestKey, bitmap, image.photoRect, image.textSlots, image.canvasPx)
                            busy = false
                            image.photoRect?.let { rect ->
                                PreviewGeometry.record(
                                    request.card.id,
                                    rect.w / rect.h.coerceAtLeast(1f),
                                )
                            }
                        }
                    }
                }
                if (published) {
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version " +
                            "phase=${if (currentOnCompletion) "applied" else "progress"} " +
                            "quality=$quality edge=$edge size=${image.width}x${image.height} " +
                            "engineMs=${image.ms} " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                } else {
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=publish-gate " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                }
                return published
                } finally {
                    PreviewScratch.release(WorkflowEnv.platform, image?.path)
                }
            }

            while (true) {
                coroutineContext.ensureActive()
                requestSignal.receive()
                awaitFixedSlot()
                drainSignals()

                // A batch export is running: do not submit anything to the engine (it is
                // the export's CPU and, on the phone, its GPU context). A frame already
                // in flight above was allowed to finish; this only stops the *next* one.
                // `latestInput` keeps taking the user's edits while we wait here, and it
                // is read **after** the gate opens, so the first frame of the resumed
                // stage is already the newest design instead of the one from before the
                // export — the stage catches up by itself, with no extra request.
                PreviewSuspension.awaitResumed()

                val request = latestInput.value
                val requestVersion = requestGate.version
                val requestStarted = TimeSource.Monotonic.markNow()
                fun current(): Boolean = isCurrentRequest(request, requestVersion)
                // One pure tier decision drives every submit in this pass: the
                // bounded interactive frame while input keeps changing, the final
                // edge after the debounce. Both describe the same design.
                val finalTier = PreviewTier.request(request.maxEdge, interactive = false)
                val interactiveTier = PreviewTier.request(request.maxEdge, interactive = true)

                busy = true
                FmLog.info("FM_PREVIEW_REQUEST") {
                    "card=${request.card.id} version=$requestVersion phase=begin " +
                        "interactiveEdge=${interactiveTier.edge} finalEdge=${finalTier.edge} " +
                        "sourceEdge=${interactiveTier.sourceEdge} " +
                        "displayEdge=${request.displayEdge}"
                }

                try {
                    // Exact final pixels win immediately. Otherwise the stage renders
                    // one small frame at most every fixed interval, then one final
                    // frame after the latest input has stayed quiet.
                    if (applyCached(
                            request,
                            request.key,
                            finalTier.edge,
                            finalTier.quality.wire,
                            requestVersion,
                            requestStarted,
                        )
                    ) {
                        busy = false
                        drainSignals()
                        continue
                    }
                    if (!current()) continue

                    if (request.interactiveKey == request.key) {
                        latestOnRenderStarted?.invoke()
                        lastNativeSubmitAt = TimeSource.Monotonic.markNow()
                        renderEngine(
                            request,
                            request.key,
                            finalTier.edge,
                            finalTier.quality.wire,
                            requestVersion,
                            requestStarted,
                        )
                        if (current()) {
                            busy = false
                            drainSignals()
                        }
                        continue
                    }

                    var interactiveReady = applyCached(
                        request,
                        request.interactiveKey,
                        interactiveTier.edge,
                        interactiveTier.quality.wire,
                        requestVersion,
                        requestStarted,
                    )
                    if (!current()) continue
                    if (!interactiveReady) {
                        latestOnRenderStarted?.invoke()
                        lastNativeSubmitAt = TimeSource.Monotonic.markNow()
                        interactiveReady = renderEngine(
                            request,
                            request.interactiveKey,
                            interactiveTier.edge,
                            interactiveTier.quality.wire,
                            requestVersion,
                            requestStarted,
                        )
                    }
                    if (!interactiveReady || !waitForFinalIdle(request, requestVersion)) continue

                    // Another stage may have filled this exact cache entry while the
                    // interactive frame ran; still emit the clear frame exactly once.
                    if (applyCached(
                            request,
                            request.key,
                            finalTier.edge,
                            finalTier.quality.wire,
                            requestVersion,
                            requestStarted,
                        )
                    ) {
                        busy = false
                        drainSignals()
                        continue
                    }
                    if (!current()) continue
                    drainSignals()
                    lastNativeSubmitAt = TimeSource.Monotonic.markNow()
                    renderEngine(
                        request,
                        request.key,
                        finalTier.edge,
                        finalTier.quality.wire,
                        requestVersion,
                        requestStarted,
                    )
                    if (current()) {
                        busy = false
                        drainSignals()
                    }
                } catch (c: CancellationException) {
                    FmLog.info("FM_PREVIEW_REQUEST") {
                        "card=${request.card.id} version=$requestVersion phase=cancelled " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}"
                    }
                    throw c
                } catch (t: Throwable) {
                    if (!isCurrentRequest(request, requestVersion)) continue
                    val reason = t.message ?: t::class.simpleName ?: "render failed"
                    PreviewDiagnostics.record(request.card.id, reason)
                    FmLog.warn("FM_PREVIEW_RENDER_FAIL") { "card=${request.card.id} reason=$reason" }
                    val previousReady = state as? StageRender.Ready
                    val visible = previousReady?.requestKey?.sameStageInputs(request.key) == true
                    if (!visible) {
                        state = StageRender.Failed(request.key, reason)
                        val recoverySlots = previousReady
                            ?.takeIf { it.requestKey.sameTextSlotInputs(request.key) }
                            ?.textSlots
                            ?.asFailureRecoverySlots()
                            .orEmpty()
                        latestOnTextSlots?.invoke(0, 0, recoverySlots)
                    }
                    busy = false
                    latestOnFailed?.invoke(reason)
                    drainSignals()
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val ready = state as? StageRender.Ready
            val readyIsCurrent = ready?.requestKey?.sameStageInputs(key) == true
            // The static card stays as the loading placeholder only; once the native
            // frame exists it is the whole stage image (stale preview bands would
            // read as part of the photo).
            if (ready == null) {
                LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
            } else {
                var reported by remember(ready.requestKey) { mutableStateOf(false) }
                var layoutLogged by remember(ready.requestKey) { mutableStateOf(false) }
                val layoutStartedAt = remember(ready.requestKey, ready.bitmap) {
                    TimeSource.Monotonic.markNow()
                }
                LaunchedEffect(key, ready.requestKey, ready.bitmap) {
                    if (ready.requestKey.sameStageInputs(key) && requestGate.isCurrent(key) && latestKey == key && !reported) {
                        reported = true
                        latestOnRendered?.invoke(ready.bitmap.width, ready.bitmap.height, ready.photoRect)
                        latestOnTextSlots?.invoke(ready.bitmap.width, ready.bitmap.height, ready.textSlots)
                        ready.canvasPx?.let { latestOnCanvas?.invoke(it.first, it.second) }
                    }
                }
                Image(
                    bitmap = ready.bitmap,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { boxPx = it }
                        .onGloballyPositioned { coords ->
                            if (ready.requestKey.sameStageInputs(key) && !layoutLogged) {
                                layoutLogged = true
                                FmLog.info("FM_PREVIEW_LAYOUT") {
                                    "card=${card.id} box=${coords.size.width}x${coords.size.height} " +
                                        "bitmap=${ready.bitmap.width}x${ready.bitmap.height} " +
                                        "wallMs=${layoutStartedAt.elapsedNow().inWholeMilliseconds}"
                                }
                            }
                        },
                )
            }
            // §3.3 text-row highlight. Draw-only: it never enters PreviewRequestKey,
            // the render cache, the project, a preset or an export, and it changes no
            // onTextSlots pairing/clearing. It sits above the Image inside this Box,
            // so a caller's zoom/pan graphicsLayer moves the frame with the photo.
            // No clickable/pointerInput: touch passes through to the stage gestures.
            val highlightKey = highlightedTextSlotKey
            if (ready != null && readyIsCurrent && highlightKey != null &&
                boxPx.width > 0 && boxPx.height > 0
            ) {
                // 可写行按稳定 key 命中；只读行没有可写身份，文字页选它时传的是报告
                // id，这里补一次兜底，舞台照样框出用户正在看的那个文本框。
                val highlightSlot = ready.textSlots.firstOrNull { it.key == highlightKey }
                    ?: ready.textSlots.firstOrNull { it.key == null && it.id == highlightKey }
                val highlightRect = highlightSlot
                    ?.let { slot ->
                        slotHighlightRect(
                            slot = slot,
                            bitmapW = ready.bitmap.width,
                            bitmapH = ready.bitmap.height,
                            boxW = boxPx.width.toFloat(),
                            boxH = boxPx.height.toFloat(),
                        )
                    }
                FmLog.info("FM_TEXT_HIGHLIGHT") {
                    "key=$highlightKey located=${highlightSlot?.located} " +
                        "rect=$highlightRect label=${highlightedTextSlotLabel?.takeIf { it.isNotBlank() } ?: "-"}"
                }
                if (highlightRect != null) {
                    // The chip names the box the user is editing. The position + role
                    // text comes from the page that owns the nav band; when a caller
                    // passes none, the existing nine-grid word (左上 / 底部居中 …) is
                    // enough for the stage to still say which box is framed.
                    TextSlotHighlightOverlay(
                        rect = highlightRect,
                        label = highlightedTextSlotLabel?.trim()?.takeIf(String::isNotEmpty)
                            ?: highlightSlot?.let { slot ->
                                classifyTextSlotPosition(slot, ready.bitmap.width, ready.bitmap.height)?.label
                            },
                        blankText = highlightSlot?.text.isNullOrBlank(),
                    )
                } else {
                    // The focused row has no trustworthy rectangle in this frame
                    // (missing, unlocated or zero-size): tell the truth at the top of
                    // the stage instead of drawing a frame the text may not be in.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(FmPalette.stage.copy(alpha = 0.86f))
                            .border(1.dp, FmPalette.stageLine, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("待定位", style = FmType.labelSmall, color = FmPalette.stageMuted)
                    }
                }
            }
            // WP-C §4.5: drag the watermark on the photo. The dashed frame marks
            // the cell's zone (the area it may move in); the value written back is
            // the same absolute `dx/dy` the margin sliders write.
            val mark = watermark
            if (mark != null && onWatermarkNudge != null && ready != null && readyIsCurrent &&
                boxPx.width > 0 && boxPx.height > 0
            ) {
                // The engine places the watermark inside its photo draw box, not in
                // the whole bordered canvas. Use the engine-reported photoRect (in
                // bitmap pixels) mapped through the same ContentScale.Fit; without it
                // the frame drifts down by exactly the bottom border band (D1-3).
                val frame = photoFrameFor(
                    boxW = boxPx.width.toFloat(),
                    boxH = boxPx.height.toFloat(),
                    bitmapW = ready.bitmap.width.toFloat(),
                    bitmapH = ready.bitmap.height.toFloat(),
                    photoRect = ready.photoRect,
                )
                val photoAspect = ready.photoRect
                    ?.let { it.w / it.h.coerceAtLeast(1f) }
                    ?: frame.aspect
                val zoneEdge = FmPalette.primary
                val zoneWash = FmPalette.primary.copy(alpha = 0.06f)
                val markRect = watermarkRectPx(mark, photoAspect, frame)
                // 拖动中也要能看到区域框；其余时间只有水印页才画（WP-R D8）。
                var watermarkDragging by remember(mark.anchor) { mutableStateOf(false) }
                val showWatermarkGuides = watermarkGuides || watermarkDragging
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(mark.anchor, mark.width, mark.aspect, photoAspect, frame, ready.photoRect) {
                            detectDragGestures(
                                onDragStart = { watermarkDragging = true },
                                onDragEnd = { watermarkDragging = false },
                                onDragCancel = { watermarkDragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                val (dx, dy) = com.framemark.native.inspector.WatermarkZones.moveByPixels(
                                    spec = mark,
                                    photoAspect = photoAspect,
                                    dxPx = dragAmount.x,
                                    dyPx = dragAmount.y,
                                    photoWidthPx = frame.width,
                                    photoHeightPx = frame.height,
                                )
                                // D1-3: write the same 0.1 % grid the margin sliders use,
                                // so the stored dx/dy and the displayed percentage agree.
                                val qx = com.framemark.native.inspector.FmSliderMath.quantize(
                                    value = dx,
                                    min = -4f,
                                    max = 4f,
                                    step = 0.001f,
                                )
                                val qy = com.framemark.native.inspector.FmSliderMath.quantize(
                                    value = dy,
                                    min = -4f,
                                    max = 4f,
                                    step = 0.001f,
                                )
                                FmLog.info("FM_WATERMARK_DRAG") {
                                    "dx=${"%.4f".format(qx)} " +
                                        "dy=${"%.4f".format(qy)}"
                                }
                                onWatermarkNudge(qx, qy)
                            }
                        },
                ) {
                    // 拖动把手始终在（命中区域在 pointerInput 里），但虚线区域框只在
                    // 水印页 / 拖动中显示：普通编辑态不该多一层引导线（WP-R D8）。
                    if (showWatermarkGuides) {
                        val zone = com.framemark.native.inspector.WatermarkZones.zoneFor(mark.anchor)
                        val zoneLeft = frame.left + frame.width * zone.xMin
                        val zoneTop = frame.top + frame.height * zone.yMin
                        val zoneRectW = frame.width * (zone.xMax - zone.xMin)
                        val zoneRectH = frame.height * (zone.yMax - zone.yMin)
                        drawRect(
                            color = zoneWash,
                            topLeft = Offset(zoneLeft, zoneTop),
                            size = Size(zoneRectW, zoneRectH),
                        )
                        drawRect(
                            color = zoneEdge.copy(alpha = 0.75f),
                            topLeft = Offset(zoneLeft, zoneTop),
                            size = Size(zoneRectW, zoneRectH),
                            style = Stroke(
                                width = 1.2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                            ),
                        )
                        drawRect(
                            color = zoneEdge,
                            topLeft = Offset(markRect[0], markRect[1]),
                            size = Size(markRect[2].coerceAtLeast(1f), markRect[3].coerceAtLeast(1f)),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }
            if (busy || (ready != null && !readyIsCurrent)) {
                // Busy badge instead of a blank stage: the previous frame stays visible
                // while the engine runs, so an edit never looks like a freeze.
                // A-07: nothing for the first 200 ms, "渲染中" from 200 ms, progress
                // (elapsed seconds) after 1 s, and from the first visible phase on a
                // 取消 action that drops the frame (WP-X3 §6).
                val phase = renderPhase
                if (phase != StageRenderPhase.Idle || !busy) {
                    StageBusyBadge(
                        phase = phase,
                        onCancel = if (phase != StageRenderPhase.Idle) {
                            { cancelRequested = true }
                        } else {
                            null
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// §3.3: text-row highlight (frame outside the glyphs + a chip that names the row)
// ---------------------------------------------------------------------------

/** The frame is drawn this far outside the reported row, so it never crosses glyphs. */
private val TextHighlightInflate = 2.5.dp
/** Blue frame stroke (drawn fully inside the inflated rectangle). */
private val TextHighlightStroke = 2.dp
/** Dark neutral ring just outside it: keeps the frame visible on white cards *and* photos. */
private val TextHighlightRing = 1.dp
private val TextHighlightRadius = 6.dp
/** Chip: a pill that names the box (`左上 · 品牌`, `左下 · 机型（空）`). */
private val TextHighlightChipPadX = 6.dp
private val TextHighlightChipPadY = 2.dp
private val TextHighlightChipGap = 3.dp
/**
 * The stage's bottom pill row (经典双行 / 按住看原图, `StagePillInset` + ~2 line heights).
 * The chip may not be drawn into this band: it would sit under the pills.
 */
private val StagePillBand = 56.dp
private const val TEXT_HIGHLIGHT_WASH_ALPHA = 0.06f
private const val TEXT_HIGHLIGHT_CHIP_ALPHA = 0.92f

/**
 * §3.3 text-row highlight. Draw-only on purpose: no clickable / pointerInput, so
 * every touch keeps reaching the stage gestures, and nothing here is written into
 * `PreviewRequestKey`, the render cache, the project, a preset or an export.
 *
 * Geometry, from the row outwards:
 *  - 6 % primary wash on the row itself (was 10 %, which dimmed the very glyphs the
 *    user is editing);
 *  - the row inflated by 2.5 dp and clamped to the canvas, stroked 2 dp in the
 *    primary colour **inside** the inflated rectangle: the line stays outside the
 *    glyphs and an edge row still gets its full width instead of a clipped half;
 *  - a 1 dp dark-neutral ring just outside that stroke, which is what makes the
 *    frame readable on a white card background and on the photo alike;
 *  - a chip at the frame's top-left ([placeSlotChip]) carrying the row's name in
 *    `FmType.labelSmall`; `（空）` is appended when the renderer reported no text,
 *    which is exactly the "empty slot looks like a bare line" case.
 */
@Composable
internal fun TextSlotHighlightOverlay(
    /** The row's rectangle in preview-box pixels (already mapped through ContentScale.Fit). */
    rect: FitRectPx,
    /** Position + role text from the 文字 nav band; null/blank draws no chip. */
    label: String?,
    /** The renderer reported no text for this row → the chip says `（空）`. */
    blankText: Boolean,
) {
    // WP-T: read the ink during composition; the Canvas draw scope is not composable.
    val wash = FmPalette.primary.copy(alpha = TEXT_HIGHLIGHT_WASH_ALPHA)
    val edge = FmPalette.primary
    val ring = FmPalette.stage
    val chipFill = FmPalette.stage.copy(alpha = TEXT_HIGHLIGHT_CHIP_ALPHA)
    val chipLine = FmPalette.stageLine
    val chipInk = FmPalette.stageInk
    val chipStyle = FmType.labelSmall
    val chipText = remember(label, blankText) {
        val name = label?.trim().orEmpty()
        when {
            name.isEmpty() -> ""
            blankText -> "$name（空）"
            else -> name
        }
    }
    val measurer = rememberTextMeasurer()
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height
        if (canvasW <= 0f || canvasH <= 0f) return@Canvas
        val frame = inflateHighlightRect(rect, TextHighlightInflate.toPx(), canvasW, canvasH)
        if (frame.w <= 0f || frame.h <= 0f) return@Canvas
        val stroke = TextHighlightStroke.toPx()
        val ringStroke = TextHighlightRing.toPx()
        val radius = TextHighlightRadius.toPx()
        val washRect = inflateHighlightRect(rect, 0f, canvasW, canvasH)
        if (washRect.w > 0f && washRect.h > 0f) {
            drawRoundRect(
                color = wash,
                topLeft = Offset(washRect.x, washRect.y),
                size = Size(washRect.w, washRect.h),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        // 2 dp stroke inside the inflated frame: at the photo edge the clamp above keeps
        // the whole line on the canvas instead of cutting it in half.
        drawRoundRect(
            color = edge,
            topLeft = Offset(frame.x + stroke / 2f, frame.y + stroke / 2f),
            size = Size(
                (frame.w - stroke).coerceAtLeast(stroke),
                (frame.h - stroke).coerceAtLeast(stroke),
            ),
            cornerRadius = CornerRadius(
                (radius - stroke / 2f).coerceAtLeast(0f),
                (radius - stroke / 2f).coerceAtLeast(0f),
            ),
            style = Stroke(width = stroke),
        )
        // 1 dp dark neutral just outside it. `FmPalette.stage` is the one neutral that is
        // dark in both appearances, so a white card preview cannot swallow the frame.
        // At a canvas edge only this ring is clipped off; the 2 dp stroke stays whole.
        drawRoundRect(
            color = ring,
            topLeft = Offset(frame.x - ringStroke / 2f, frame.y - ringStroke / 2f),
            size = Size(frame.w + ringStroke, frame.h + ringStroke),
            cornerRadius = CornerRadius(radius + ringStroke / 2f, radius + ringStroke / 2f),
            style = Stroke(width = ringStroke),
        )
        if (chipText.isEmpty()) return@Canvas
        val padX = TextHighlightChipPadX.toPx()
        val padY = TextHighlightChipPadY.toPx()
        // Ellipsis, never a clip: the chip is measured against the canvas and the
        // placement below keeps it inside, so no glyph can be cut by the stage edge.
        val layout = measurer.measure(
            text = AnnotatedString(chipText),
            style = chipStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (canvasW - padX * 2f).toInt().coerceAtLeast(1)),
        )
        val chipW = (layout.size.width + padX * 2f).coerceAtMost(canvasW)
        val chipH = (layout.size.height + padY * 2f).coerceAtMost(canvasH)
        val place = placeSlotChip(
            rect = frame,
            canvasW = canvasW,
            canvasH = canvasH,
            chipW = chipW,
            chipH = chipH,
            reservedBottom = StagePillBand.toPx(),
            gap = TextHighlightChipGap.toPx(),
        )
        val pill = CornerRadius(chipH / 2f, chipH / 2f)
        drawRoundRect(
            color = chipFill,
            topLeft = Offset(place.x, place.y),
            size = Size(chipW, chipH),
            cornerRadius = pill,
        )
        drawRoundRect(
            color = chipLine,
            topLeft = Offset(place.x + ringStroke / 2f, place.y + ringStroke / 2f),
            size = Size((chipW - ringStroke).coerceAtLeast(1f), (chipH - ringStroke).coerceAtLeast(1f)),
            cornerRadius = pill,
            style = Stroke(width = ringStroke),
        )
        drawText(
            textLayoutResult = layout,
            color = chipInk,
            topLeft = Offset(place.x + padX, place.y + padY),
        )
    }
}

/** A-07 wait phases: silence, then "渲染中", then progress. */
internal enum class StageRenderPhase { Idle, Hint, Progress }

internal object StageRenderTiming {
    /** Nothing is shown before this (report §4.5 / UX-RESEARCH A-07). */
    const val HINT_MS = 200L
    /** From here the badge also shows progress (elapsed time). */
    const val PROGRESS_MS = 1000L

    fun phaseAt(elapsedMs: Long): StageRenderPhase = when {
        elapsedMs < HINT_MS -> StageRenderPhase.Idle
        elapsedMs < PROGRESS_MS -> StageRenderPhase.Hint
        else -> StageRenderPhase.Progress
    }

    fun label(phase: StageRenderPhase, elapsedMs: Long = 0L): String = when (phase) {
        StageRenderPhase.Idle -> ""
        StageRenderPhase.Hint -> "渲染中"
        StageRenderPhase.Progress -> "渲染中 ${elapsedMs / 1000}s"
    }
}

@Composable
private fun StageBusyBadge(phase: StageRenderPhase, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    val tint = FmPalette.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(FmPalette.stage.copy(alpha = 0.86f))
            .border(1.dp, FmPalette.stageLine, RoundedCornerShape(999.dp))
            .padding(start = 8.dp, end = if (onCancel != null) 2.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase == StageRenderPhase.Progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = tint,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tint),
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = StageRenderTiming.label(phase),
            style = FmType.labelSmall,
            color = FmPalette.stageMuted,
        )
        if (onCancel != null) {
            Spacer(Modifier.size(6.dp))
            // 取消渲染: a real target (32 dp tall) rather than a decorative cross, with a
            // readable label so it is obvious what it drops.
            Box(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .semantics { contentDescription = "取消渲染" }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = FmType.labelSmall, color = FmPalette.stageInk)
            }
        }
    }
}

/** Keep preview pixels near the physical stage size, with a small zoom headroom. */
private fun adaptivePreviewEdge(displayEdge: Int): Int =
    PreviewTier.finalEdge(displayEdge, PREVIEW_SUPERSAMPLE)

// ---------------------------------------------------------------------------
// WP-C §4.5: stage geometry for the watermark drag handle
// ---------------------------------------------------------------------------

/** Which rectangle of the stage box the photo occupies under ContentScale.Fit. */
internal data class StagePhotoFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val aspect: Float get() = if (height > 0f) width / height else 1.5f
}

/** Pure `ContentScale.Fit` projection; testable without Compose. */
internal fun fitPhotoFrame(
    boxW: Float,
    boxH: Float,
    bitmapW: Float,
    bitmapH: Float,
): StagePhotoFrame {
    if (boxW <= 0f || boxH <= 0f || bitmapW <= 0f || bitmapH <= 0f) {
        return StagePhotoFrame(0f, 0f, boxW.coerceAtLeast(0f), boxH.coerceAtLeast(0f))
    }
    val scale = minOf(boxW / bitmapW, boxH / bitmapH)
    val w = bitmapW * scale
    val h = bitmapH * scale
    return StagePhotoFrame((boxW - w) / 2f, (boxH - h) / 2f, w, h)
}

/** The watermark's rectangle in stage pixels: x, y, w, h. */
internal fun watermarkRectPx(
    spec: WatermarkSpec,
    photoAspect: Float,
    frame: StagePhotoFrame,
): FloatArray {
    val r = spec.rect(photoAspect)
    return floatArrayOf(
        frame.left + r[0] * frame.width,
        frame.top + r[1] * frame.height,
        r[2] * frame.width,
        r[3] * frame.height,
    )
}

/**
 * D1-3: map the engine's photo draw box (in bitmap pixels) onto the same
 * `ContentScale.Fit` rectangle as the rendered bitmap. The watermark UI (drag
 * handle + dashed zone) and the engine both use this box, so a card with a
 * bottom border band no longer draws the frame one band lower than the mark.
 *
 * `photoRect == null` (old cached frame or a platform that does not report it)
 * keeps the historical full-canvas frame so nothing regresses.
 */
internal fun photoFrameFor(
    boxW: Float,
    boxH: Float,
    bitmapW: Float,
    bitmapH: Float,
    photoRect: ImageRect?,
): StagePhotoFrame {
    val canvas = fitPhotoFrame(boxW, boxH, bitmapW, bitmapH)
    if (photoRect == null || photoRect.w <= 0f || photoRect.h <= 0f ||
        bitmapW <= 0f || bitmapH <= 0f
    ) {
        return canvas
    }
    val sx = canvas.width / bitmapW
    val sy = canvas.height / bitmapH
    return StagePhotoFrame(
        left = canvas.left + photoRect.x * sx,
        top = canvas.top + photoRect.y * sy,
        width = photoRect.w * sx,
        height = photoRect.h * sy,
    )
}
