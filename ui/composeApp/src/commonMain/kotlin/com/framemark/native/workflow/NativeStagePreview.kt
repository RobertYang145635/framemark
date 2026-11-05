package com.framemark.native.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
            if (busy || (ready != null && !readyIsCurrent)) {
                // Busy badge instead of a blank stage: the previous frame stays visible
                // while the engine runs (~1.3 s), so an edit never looks like a freeze.
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF2F75E5),
                )
            }
        }
    }
}

/** Keep preview pixels near the physical stage size, with a small zoom headroom. */
private fun adaptivePreviewEdge(displayEdge: Int): Int =
    (displayEdge.coerceAtLeast(1) * PREVIEW_SUPERSAMPLE)
        .roundToInt()
        .coerceIn(PREVIEW_MIN_EDGE, PREVIEW_MAX_EDGE)