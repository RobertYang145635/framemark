package com.framemark.native.shell.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.shell.phone.PhoneHoldCompareChip
import com.framemark.native.shell.phone.PhoneStageBands
import com.framemark.native.shell.phone.PhoneStageChip
import com.framemark.native.shell.phone.PhoneStageFallback
import com.framemark.native.shell.phone.PhoneStageFallbackOverlay
import com.framemark.native.shell.phone.PhoneSwipeFeedback
import com.framemark.native.shell.phone.phoneStageGestures
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowEnv
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Last native frame's pixel size plus the engine photo box (overlay anchor). */
internal data class PreviewGeo(val w: Int, val h: Int, val photoRect: ImageRect?)

// ---------------------------------------------------------------------------
// Stage (photo fills the remaining height, report §7.2)
// ---------------------------------------------------------------------------

// The stage's photo band and pill band live in `PhoneStageBands` (phone package):
// the failed-preview placeholder draws inside the same photo band, so both places
// must read one definition.

/**
 * The dark photo stage. The photo takes every dp the chrome leaves (report §7.2);
 * the two V2 hints — the current border name (bottom-left) and 按住看原图
 * (bottom-right) — sit in the band under it (demo `.pchip` / `.pcmp`).
 *
 * Gestures: pinch / drag while zoomed / double-tap reset, the live watermark
 * overlay, and (border tool, unzoomed) the left/right swipe that switches frames
 * in WP-L's traversal order.
 */
@Composable
internal fun StageArea(
    modifier: Modifier,
    card: LibraryCard?,
    model: InspectorCardModel?,
    activeDesign: DesignSnapshot?,
    session: DesignSession,
    tray: PhotoTray,
    compare: Boolean,
    zoom: Float,
    pan: Offset,
    previewGeo: PreviewGeo?,
    stageSize: IntSize,
    watermark: WatermarkSpec?,
    focusedTextSlotKey: String?,
    /** §3.3: the same row's display name (`左上 · 品牌`), computed by the 文字 page. */
    focusedTextSlotLabel: String?,
    renderFailure: String?,
    borderName: String?,
    swipeFeedback: PhoneSwipeFeedback?,
    undoNotice: Boolean,
    swipeEnabled: Boolean,
    focusMode: Boolean,
    density: Density,
    onStageSize: (IntSize) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onRedo: () -> Unit,
    onFrameStep: (Int) -> Unit,
    onFocusToggle: () -> Unit,
    onOpenBorder: () -> Unit,
    onOpenBatch: () -> Unit,
    onApplyAll: (() -> Unit)?,
    onOpenPhotoBatch: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onRendered: (Int, Int, ImageRect?) -> Unit,
    onRenderStarted: () -> Unit,
    onFailed: (String) -> Unit,
    onTextSlots: ((Int, Int, List<TextSlot>) -> Unit)?,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
) {
    Box(
        modifier = modifier.background(FmPalette.stage),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = PhoneStageBands.PhotoSide,
                    end = PhoneStageBands.PhotoSide,
                    top = PhoneStageBands.PhotoTop,
                    bottom = PhoneStageBands.photoBottom(focusMode),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { onStageSize(it) }
                    // WP-X3 §4: one gesture node decides between 左右滑动换边框 and
                    // 缩放/平移. Two stacked detectors could not coexist — the inner
                    // `detectTransformGestures` consumed every drag first, so the
                    // frame swipe always aborted (WP-F2 D4).
                    .phoneStageGestures(
                        swipeEnabled = swipeEnabled && !compare && zoom <= 1f,
                        onStep = onFrameStep,
                        onTransform = onTransform,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                contentAlignment = Alignment.Center,
            ) {
                val comparePath = tray.selected?.path ?: WorkflowEnv.platform?.samplePhotoPath()
                if (compare && comparePath != null) {
                    // Hold-to-compare: the raw photo, no design frame, no fake overlay.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhotoThumbnail(
                            path = comparePath,
                            maxEdge = 2048,
                            cornerRadius = 0.dp,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else when {
                    card == null -> StagePlaceholder(tray = tray, onOpenBorder = onOpenBorder)
                    NativeEngine.supports(card.id) -> NativeStagePreview(
                        // The phone stage deliberately omits `overlay`: WatermarkOverlay
                        // is the single live watermark layer. Rendering it here as well
                        // would make the throttled native frame show a second watermark.
                        card = card,
                        // The project design is the source of truth once this photo
                        // has one: it carries the flat slot overrides the text panel
                        // writes, so a restart renders the stored rows instead of a
                        // stale cached frame.
                        design = activeDesign?.takeIf { it.cardId == card.id }
                            ?: NativeEngine.designOf(card, model, session),
                        modifier = Modifier.fillMaxSize(),
                        onRendered = onRendered,
                        onRenderStarted = onRenderStarted,
                        onFailed = onFailed,
                        onTextSlots = onTextSlots,
                        highlightedTextSlotKey = focusedTextSlotKey,
                        highlightedTextSlotLabel = focusedTextSlotLabel,
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
                    }
                }
                if (!compare) {
                    watermark?.let { spec ->
                        previewGeo?.let { geo ->
                            WatermarkOverlay(
                                spec = spec,
                                geo = geo,
                                stage = stageSize,
                                density = density,
                                onChange = onWatermarkChange,
                            )
                        }
                    }
                }
            }
        }
        // 缺陷 2（"像别人家的照片"）：预览彻底失败时舞台显示的是内置静态样张，它自带
        // 白边和山景，看起来就是一张别人的照片。这里给样张压一层 12 % 的舞台底色，再
        // 正中间放一枚「示例图 · 当前照片预览暂不可用」胶囊 —— 只画，不改变回退触发条件，
        // 也不进渲染管线。覆盖层只铺照片带（PhoneStageBands），下沿在胶囊带之上 2 dp。
        if (
            PhoneStageFallback.showsPlaceholder(
                renderFailure = renderFailure,
                compare = compare,
            )
        ) {
            PhoneStageFallbackOverlay(
                focusMode = focusMode,
                modifier = Modifier.fillMaxSize(),
            )
        }
        PhoneStageOverlays(
            swipeFeedback = swipeFeedback,
            undoNotice = undoNotice,
            onRedo = onRedo,
        )
        if (!focusMode) {
            // `.pchip` says which border is applied (or 原图 while comparing) and,
            // like the showcase, appends the one hint that explains what the stage
            // can do right now. A non-native card and a failed render say so here
            // instead of stacking another floating badge over the photo.
            val hint = when {
                compare -> null
                renderFailure != null -> "预览失败 · 显示静态图"
                card == null -> null
                !NativeEngine.supports(card.id) -> "静态预览"
                swipeEnabled -> "左右滑动换边框"
                else -> null
            }
            PhoneStageChip(
                text = if (compare) "原图" else borderName ?: "未选边框",
                description = "当前边框",
                hint = hint,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = PhoneStageBands.PillInset, bottom = PhoneStageBands.PillInset),
            )
            if (card != null) {
                PhoneHoldCompareChip(
                    comparing = compare,
                    onCompareChange = onCompareChange,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = PhoneStageBands.PillInset, bottom = PhoneStageBands.PillInset),
                )
            }
        }
    }
}

@Composable
private fun StagePlaceholder(tray: PhotoTray, onOpenBorder: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PhotoThumbnail(
            path = tray.selected?.path,
            maxEdge = 1080,
            cornerRadius = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "选择边框",
            style = FmType.labelMedium,
            color = FmPalette.onPrimary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary)
                .semantics {
                    contentDescription = "选择边框"
                    role = Role.Button
                }
                .clickable(onClick = onOpenBorder)
                .padding(horizontal = FmSpacing.xl, vertical = FmSpacing.md),
        )
    }
}

/**
 * Live watermark on the transformed stage: Compose draws it while the user drags /
 * slides, so the feedback tracks pinch/pan instantly; the engine gets the same
 * rect as an `OverlaySpec` for export.
 */
@Composable
private fun BoxScope.WatermarkOverlay(
    spec: WatermarkSpec,
    geo: PreviewGeo,
    stage: IntSize,
    density: Density,
    onChange: (WatermarkSpec?) -> Unit,
) {
    if (geo.w <= 0 || geo.h <= 0 || stage.width <= 0 || stage.height <= 0) return
    // The overlay lives inside the stage's photo box, which has no extra padding:
    // the draw box is exactly the box the preview fills with ContentScale.Fit.
    val availW = stage.width.toFloat()
    val availH = stage.height.toFloat()
    if (availW <= 1f || availH <= 1f) return
    val scale = minOf(availW / geo.w, availH / geo.h)
    val left = (availW - geo.w * scale) / 2f
    val top = (availH - geo.h * scale) / 2f
    val pr = geo.photoRect ?: run {
        // Never fall back to the full canvas: that would place the watermark on
        // the border/footer when the engine has not reported the photo box yet.
        return
    }
    val photoW = pr.w * scale
    val photoH = pr.h * scale
    if (photoW <= 1f || photoH <= 1f) return
    val aspect = photoW / photoH

    // The Compose layer is the only phone watermark layer. Draw every gesture
    // sample locally, but publish the project value on a fixed 10 Hz cadence so
    // a drag cannot force the whole editor/project to recompose per touch sample.
    var liveSpec by remember(spec.path) { mutableStateOf(spec) }
    var dragging by remember(spec.path) { mutableStateOf(false) }
    val latestOnChange by rememberUpdatedState(onChange)
    val commitSignal = remember(spec.path) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(spec) {
        if (!dragging && liveSpec != spec) liveSpec = spec
    }

    LaunchedEffect(commitSignal) {
        val intervalMs = 100L
        var lastCommitAt: TimeMark? = null
        while (true) {
            commitSignal.receive()
            val remaining = lastCommitAt?.let {
                intervalMs - it.elapsedNow().inWholeMilliseconds
            } ?: 0L
            if (remaining > 0L) delay(remaining)
            latestOnChange(liveSpec)
            lastCommitAt = TimeSource.Monotonic.markNow()
            // Every wake-up is level-triggered by liveSpec; intermediate samples
            // collapse instead of producing one project commit each.
            while (commitSignal.tryReceive().isSuccess) {
                // Coalesced into the next fixed-interval commit.
            }
        }
    }

    val r = liveSpec.rect(aspect)
    val box = liveSpec.aabb(aspect)

    // The rotated image is clipped to its AABB, not to the unrotated draw rect;
    // otherwise the four corners would be cut off at 45°/90°.
    val ow = r[2] * photoW
    val oh = r[3] * photoH
    val ox = (r[0] - box[0]) * photoW
    val oy = (r[1] - box[1]) * photoH
    val aw = box[2] * photoW
    val ah = box[3] * photoH

    LaunchedEffect(aspect, liveSpec.photoAspect) {
        if (abs(aspect - liveSpec.photoAspect) > 0.01f) {
            liveSpec = liveSpec.copy(photoAspect = aspect)
            commitSignal.trySend(Unit)
        }
    }

    Box(
        modifier = Modifier
            // The stage Box centers its children; without this the photo-box
            // offset would be added to the centered position and the watermark
            // would drift down onto the border/footer.
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    (left + (pr.x + box[0] * pr.w) * scale).roundToInt(),
                    (top + (pr.y + box[1] * pr.h) * scale).roundToInt(),
                )
            }
            .size(with(density) { aw.toDp() }, with(density) { ah.toDp() })
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .size(with(density) { ow.toDp() }, with(density) { oh.toDp() })
                .graphicsLayer { rotationZ = liveSpec.rotation }
                .pointerInput(liveSpec.path, aspect) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            commitSignal.trySend(Unit)
                        },
                        onDragCancel = {
                            dragging = false
                            commitSignal.trySend(Unit)
                        },
                    ) { change, drag ->
                        change.consume()
                        liveSpec = liveSpec.withRectNudge(
                            drag.x / photoW,
                            drag.y / photoH,
                            aspect,
                        )
                        commitSignal.trySend(Unit)
                    }
                },
        ) {
            // The tray thumbnail path re-encodes to JPEG (no alpha); the overlay
            // must keep the PNG alpha so it reads the same as the exported file.
            val wmBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                initialValue = null,
                key1 = liveSpec.path,
            ) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        WorkflowEnv.platform?.readBytes(liveSpec.path)?.let {
                            LibraryPreviewLoader.decode(it)
                        }
                    }.getOrNull()
                }
            }
            val ready = wmBitmap
            if (ready != null) {
                Image(
                    bitmap = ready,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(liveSpec.opacity),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Photo strip
// ---------------------------------------------------------------------------

@Composable
internal fun PhotoStrip(
    tray: PhotoTray,
    onSelect: (TrayPhoto) -> Unit,
    onRemove: (TrayPhoto) -> Unit,
    onAdd: () -> Unit,
    customIds: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    // Collapsed by default: the caller only composes this when the strip is open.
    Box(
        modifier = modifier
            .background(FmPalette.surface)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tray.items.forEachIndexed { index, photo ->
                PhotoTile(
                    photo = photo,
                    index = index + 1,
                    selected = photo.id == tray.selected?.id,
                    customized = photo.id in customIds,
                    onSelect = { onSelect(photo) },
                    onRemove = { onRemove(photo) },
                )
            }
            AddPhotoTile(onAdd = onAdd)
        }
    }
}

@Composable
private fun PhotoTile(
    photo: TrayPhoto,
    index: Int,
    selected: Boolean,
    customized: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    // Keep selection and removal in separate gestures. An embedded remove badge
    // receives an expanded touch target that could swallow the tile centre.
    val isSelected = selected
    var showActions by remember(photo.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FmPalette.surfaceMuted)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) FmPalette.primary else FmPalette.outline,
                shape = RoundedCornerShape(7.dp),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${photo.label}，第${index}张，长按管理照片"
                role = Role.RadioButton
                this.selected = isSelected
                stateDescription = if (isSelected) "当前照片" else "未选中"
            }
            .combinedClickable(
                onClick = onSelect,
                onLongClickLabel = "管理照片",
                onLongClick = { showActions = true },
            ),
    ) {
        PhotoThumbnail(
            path = photo.path ?: WorkflowEnv.platform?.samplePhotoPath(),
            maxEdge = 192,
            cornerRadius = 6.dp,
            modifier = Modifier.fillMaxSize(),
        )
        if (customized) {
            Text(
                text = "改",
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FmPalette.primaryStrong)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Text(
            text = index.toString().padStart(2, '0'),
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = if (isSelected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp),
        )
        DropdownMenu(
            expanded = showActions,
            onDismissRequest = { showActions = false },
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("移出项目")
                        Text("保留原文件", style = FmType.labelSmall)
                    }
                },
                onClick = { showActions = false; onRemove() },
            )
            DropdownMenuItem(
                text = { Text("取消") },
                onClick = { showActions = false },
            )
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    drawBehind {
        val width = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(width / 2f, width / 2f),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(
                width = width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            ),
        )
    }

@Composable
private fun AddPhotoTile(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FmPalette.surface)
            // WP-T report §1.6 item 9: the dashed "add photo" outline is a token now.
            .dashedBorder(FmPalette.onSurfaceFaint, 7.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "添加照片"
                role = Role.Button
            }
            .clickable(onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "＋",
            style = FmType.titleSmall.copy(fontSize = 14.sp),
            color = FmPalette.onSurfaceMuted,
        )
        Text(
            "添加",
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}
