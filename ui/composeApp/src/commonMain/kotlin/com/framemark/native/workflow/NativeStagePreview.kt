package com.framemark.native.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import com.framemark.native.LaunchOptions
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.library.PreviewDiagnostics
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

private const val PREVIEW_MAX_EDGE = 1280
private const val PREVIEW_MIN_EDGE = 640
private const val PREVIEW_SUPERSAMPLE = 1.25f
private const val INTERACTIVE_MAX_EDGE = 480
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
            PREVIEW_MAX_EDGE
        }
        val maxEdge = LaunchOptions.previewMaxEdge ?: adaptivePreviewEdge(displayEdge)
        val interactiveMaxEdge = minOf(maxEdge, INTERACTIVE_MAX_EDGE)
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
        // Compose owns the immediate UI value. This side effect publishes only a
        // successfully committed render input and wakes the one scheduler; changing
        // a key no longer cancels or restarts a native render.
        SideEffect {
            requestGate.update(key)
            if (latestInput.value.key != key) {
                latestInput.value = currentInput
                latestInput.changedAt = TimeSource.Monotonic.markNow()
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

        LaunchedEffect(card.id, requestSignal) {
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
                    )
                    busy = quality == "interactive"
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=cache-hit " +
                            "quality=$quality edge=$edge cache=decoded " +
                            "wallMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
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
                    state = StageRender.Ready(requestKey, bitmap, cached.photoRect, cached.textSlots)
                    busy = quality == "interactive"
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=cache-hit " +
                            "quality=$quality edge=$edge cache=bytes " +
                            "decodeMs=${decodeStarted.elapsedNow().inWholeMilliseconds} " +
                            "wallMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
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
                            println(
                                "FM_PREVIEW_REQUEST card=${request.card.id} version=$version " +
                                    "phase=stale-drop quality=$quality edge=$edge reason=coalesced",
                            )
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
                        println(
                            "FM_PREVIEW_REQUEST card=${request.card.id} version=$version " +
                                "phase=stale-drop quality=$quality edge=$edge reason=acquired-stale",
                        )
                        return false
                    }
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=submit " +
                            "quality=$quality edge=$edge " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
                    NativeEngine.preview(
                        request.card,
                        request.design,
                        edge,
                        request.overlay,
                        request.photoPath,
                    )
                } finally {
                    if (gateHeld) previewGate.unlock()
                }
                val currentOnCompletion = isCurrentRequest(request, version)
                if (quality != "interactive" && !currentOnCompletion) {
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=superseded " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
                    return false
                }
                if (quality == "interactive" && version <= lastInteractiveVersion) {
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=already-progressed",
                    )
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
                        renderCache.put(requestKey, bytes, image.photoRect, image.textSlots, bitmap)
                        state = StageRender.Ready(requestKey, bitmap, image.photoRect, image.textSlots)
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
                            renderCache.put(requestKey, bytes, image.photoRect, image.textSlots, bitmap)
                            state = StageRender.Ready(requestKey, bitmap, image.photoRect, image.textSlots)
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
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version " +
                            "phase=${if (currentOnCompletion) "applied" else "progress"} " +
                            "quality=$quality edge=$edge size=${image.width}x${image.height} " +
                            "engineMs=${image.ms} " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
                } else {
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$version phase=stale-drop " +
                            "quality=$quality edge=$edge reason=publish-gate " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
                }
                return published
            }

            while (true) {
                coroutineContext.ensureActive()
                requestSignal.receive()
                awaitFixedSlot()
                drainSignals()

                val request = latestInput.value
                val requestVersion = requestGate.version
                val requestStarted = TimeSource.Monotonic.markNow()
                fun current(): Boolean = isCurrentRequest(request, requestVersion)

                busy = true
                println(
                    "FM_PREVIEW_REQUEST card=${request.card.id} version=$requestVersion phase=begin " +
                        "interactiveEdge=${request.interactiveMaxEdge} finalEdge=${request.maxEdge} " +
                        "displayEdge=${request.displayEdge}",
                )

                try {
                    // Exact final pixels win immediately. Otherwise the stage renders
                    // one small frame at most every fixed interval, then one final
                    // frame after the latest input has stayed quiet.
                    if (applyCached(
                            request,
                            request.key,
                            request.maxEdge,
                            "final",
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
                            request.maxEdge,
                            "final",
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
                        request.interactiveMaxEdge,
                        "interactive",
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
                            request.interactiveMaxEdge,
                            "interactive",
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
                            request.maxEdge,
                            "final",
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
                        request.maxEdge,
                        "final",
                        requestVersion,
                        requestStarted,
                    )
                    if (current()) {
                        busy = false
                        drainSignals()
                    }
                } catch (c: CancellationException) {
                    println(
                        "FM_PREVIEW_REQUEST card=${request.card.id} version=$requestVersion phase=cancelled " +
                            "requestMs=${requestStarted.elapsedNow().inWholeMilliseconds}",
                    )
                    throw c
                } catch (t: Throwable) {
                    if (!isCurrentRequest(request, requestVersion)) continue
                    val reason = t.message ?: t::class.simpleName ?: "render failed"
                    PreviewDiagnostics.record(request.card.id, reason)
                    println("FM_PREVIEW_RENDER_FAIL card=${request.card.id} reason=$reason")
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
                                println(
                                    "FM_PREVIEW_LAYOUT card=${card.id} box=${coords.size.width}x${coords.size.height} " +
                                        "bitmap=${ready.bitmap.width}x${ready.bitmap.height} " +
                                        "wallMs=${layoutStartedAt.elapsedNow().inWholeMilliseconds}",
                                )
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
                val highlightRect = ready.textSlots
                    .firstOrNull { it.key == highlightKey }
                    ?.let { slot ->
                        slotHighlightRect(
                            slot = slot,
                            bitmapW = ready.bitmap.width,
                            bitmapH = ready.bitmap.height,
                            boxW = boxPx.width.toFloat(),
                            boxH = boxPx.height.toFloat(),
                        )
                    }
                println("FM_TEXT_HIGHLIGHT key=$highlightKey located=${ready.textSlots.firstOrNull { it.key == highlightKey }?.located} rect=$highlightRect")
                if (highlightRect != null) {
                    // WP-T: read the ink during composition; the Canvas draw scope is not composable.
                    val highlightWash = FmPalette.primary.copy(alpha = 0.10f)
                    val highlightEdge = FmPalette.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.5.dp.toPx()
                        val radius = 6.dp.toPx()
                        drawRoundRect(
                            color = highlightWash,
                            topLeft = Offset(highlightRect.x, highlightRect.y),
                            size = Size(highlightRect.w, highlightRect.h),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                        val inset = stroke / 2f
                        drawRoundRect(
                            color = highlightEdge,
                            topLeft = Offset(highlightRect.x + inset, highlightRect.y + inset),
                            size = Size(
                                (highlightRect.w - stroke).coerceAtLeast(0.5f),
                                (highlightRect.h - stroke).coerceAtLeast(0.5f),
                            ),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = stroke),
                        )
                    }
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
                                println(
                                    "FM_WATERMARK_DRAG dx=${"%.4f".format(qx)} " +
                                        "dy=${"%.4f".format(qy)}",
                                )
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
                // (elapsed seconds) after 1 s. The engine cannot be cancelled from
                // the UI, so no cancel button is offered (see the WP-C report).
                val phase = renderPhase
                if (phase != StageRenderPhase.Idle || !busy) {
                    StageBusyBadge(
                        phase = phase,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            }
        }
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
private fun StageBusyBadge(phase: StageRenderPhase, modifier: Modifier = Modifier) {
    val tint = FmPalette.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(FmPalette.stage.copy(alpha = 0.86f))
            .border(1.dp, FmPalette.stageLine, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
    }
}

/** Keep preview pixels near the physical stage size, with a small zoom headroom. */
private fun adaptivePreviewEdge(displayEdge: Int): Int =
    (displayEdge.coerceAtLeast(1) * PREVIEW_SUPERSAMPLE)
        .roundToInt()
        .coerceIn(PREVIEW_MIN_EDGE, PREVIEW_MAX_EDGE)

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