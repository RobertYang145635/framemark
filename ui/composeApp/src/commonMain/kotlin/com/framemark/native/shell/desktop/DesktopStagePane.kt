package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.inspector.textSlotNavEntries
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.product.FmLog
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.shell.displayPixels
import com.framemark.native.shell.stageExportPixels
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.ProjectPhotoStage
import com.framemark.native.workflow.TextSlotRegistry
import com.framemark.native.workflow.withNativeTextSlots
import kotlin.math.roundToInt

/**
 * Centre stage of the desktop workspace: the zoomable/pannable photo canvas, the
 * floating stage chrome (title meta / status tag / compare flag / zoom tool bar) and
 * the empty states that stand in for a photo or a card.
 *
 * Owns
 *  - [EditStage] — the pane itself: scroll/drag/double-tap zoom & pan, the preview
 *    branch (hover preview, import placeholder, or the photo stage) and the stage
 *    overlays. State (`zoom`, `pan`, `stagePx`, `comparing`) is local to the stage and
 *    stays local: the shell only passes the selected card, the session and the
 *    project down.
 *  - [DesktopNativeStage] — the native-render branch of `workflow.ProjectPhotoStage`,
 *    which also needs the renderer's text rows and the watermark guides.
 *  - [ImportPlaceholder] — the stage's own "no photos yet" card.
 *
 * The chrome components themselves live in `DesktopStageChrome.kt`; the filmstrip the
 * stage sits on lives in [DesktopFilmstrip]. Nothing here reads shell state beyond
 * its parameters.
 */

@Composable
internal fun EditStage(
    card: LibraryCard?,
    session: DesignSession,
    model: InspectorCardModel?,
    projects: ProjectHolder,
    /** Filled by the stage render; read by the 文字 tab. */
    textSlots: TextSlotRegistry,
    /** §3.3: row being edited in the 文字 tab; null draws no frame. */
    highlightedTextSlotKey: String?,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    /** 胶片条右侧「批量导出」链接（`styles.css` 430 行 `.sinfo` 里的 link）。 */
    onOpenBatch: () -> Unit,
    hoveredCard: LibraryCard? = null,
    holdOriginal: Boolean = false,
    onReuseDesign: (String) -> Unit = {},
    onPickBorder: () -> Unit = {},
    /** 右栏当前页签；水印引导线只在 水印 页显示（WP-R D8）。 */
    inspectorPage: InspectorPage? = null,
) {
    val native = card != null && NativeEngine.supports(card.id)
    val activeDesign = projects.activeDesign
    // The sample preview (no imported photo) is rendered straight from the
    // session, so it has to see the renderer-backed text rows as well.
    val panelModel = remember(model, textSlots.slots) { model?.withNativeTextSlots(textSlots.slots) }
    val design = when {
        projects.activePhoto != null -> activeDesign?.takeIf { it.cardId == card?.id }?.toDesignSnapshot()
        activeDesign != null && activeDesign.cardId == card?.id -> activeDesign.toDesignSnapshot()
        else -> card?.let { NativeEngine.designOf(it, panelModel ?: model, session) }
    }
    // §3.3: the stage's chip names the highlighted row (`左上 · 品牌`). The 文字 tab
    // already derives that name from `textSlotNavEntries`; the stage only receives the
    // finished string, so `workflow/` never re-implements the nav naming.
    val highlightedTextSlotLabel = remember(
        highlightedTextSlotKey,
        textSlots.slots,
        textSlots.width,
        textSlots.height,
    ) {
        textSlotNavEntries(textSlots.slots, textSlots.width, textSlots.height)
            .firstOrNull { it.navKey == highlightedTextSlotKey }
            ?.let { entry ->
                entry.role?.takeIf { it.isNotBlank() }?.let { "${entry.positionLabel} · $it" }
                    ?: entry.positionLabel
            }
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var stagePx by remember { mutableStateOf(IntSize.Zero) }
    // 渲染帧的像素尺寸：只在参数族未知的卡片上给「→ 导出 W × H」兜底（见
    // stageExportPixels）。已知参数族的尺寸不再依赖它，所以窗口宽度不会改数字。
    var renderedPx by remember { mutableStateOf(IntSize.Zero) }
    var comparing by remember { mutableStateOf(false) }

    // AU2 A-06：顶栏的导出尺寸与右栏「导出尺寸」、手机样式页共用 [OutputSize]。
    val exportPixels = stageExportPixels(
        model = model?.takeIf { it.cardId == card?.id },
        session = session,
        photo = projects.activePhoto,
        rendered = renderedPx,
        settings = projects.exportSettingsForUi(),
    )

    LaunchedEffect(projects.activePhotoId) {
        // A hold-compare cannot survive the photo it was comparing.
        comparing = false
    }

    fun fitToWindow() {
        zoom = 1f
        pan = Offset.Zero
        FmLog.info("FM_WHEEL") { "fit zoom=1.000" }
    }

    fun clampPan(next: Offset, z: Float = zoom): Offset {
        if (z <= 1f) return Offset.Zero
        val maxX = stagePx.width * (z - 1f) / 2f
        val maxY = stagePx.height * (z - 1f) / 2f
        return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }

    /** 工具条 `−` / `+`：与滚轮同一套倍率与 100 % 吸附。 */
    fun stepZoom(direction: Int) {
        val factor = if (direction > 0) 1.25f else 0.8f
        val raw = (zoom * factor).coerceIn(0.25f, 4f)
        val next = if (raw in 0.94f..1.06f) 1f else raw
        if (next == zoom) return
        if (next <= 1f) {
            zoom = next
            pan = Offset.Zero
        } else {
            zoom = next
            pan = clampPan(pan, next)
        }
        FmLog.info("FM_WHEEL") { "step=${if (direction > 0) "+" else "-"} zoom=${"%.3f".format(zoom)}" }
    }

    /** 25%..400% with a 100% detent; zoom steps are multiplicative. */
    fun wheelZoom(anchor: Offset, dy: Float) {
        val factor = if (dy < 0f) 1.1f else 1f / 1.1f
        val raw = (zoom * factor).coerceIn(0.25f, 4f)
        val next = if (raw in 0.94f..1.06f) 1f else raw
        if (next == zoom) return
        if (next <= 1f) {
            zoom = next
            pan = Offset.Zero
        } else {
            val center = Offset(stagePx.width / 2f, stagePx.height / 2f)
            val proposed = anchor - center - (anchor - center - pan) * (next / zoom)
            zoom = next
            pan = clampPan(proposed, next)
        }
        FmLog.info("FM_WHEEL") {
            "dy=${"%.0f".format(dy)} anchor=${"%.0f".format(anchor.x)},${"%.0f".format(anchor.y)} " +
                "zoom=${"%.3f".format(zoom)} pan=${"%.1f".format(pan.x)},${"%.1f".format(pan.y)}"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(desktopStageBackdrop())
                .onSizeChanged { stagePx = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            // Consume so the wheel can never scroll the shell.
                            change.consume()
                            wheelZoom(change.position, dy)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        if (zoom <= 1f) return@detectDragGestures
                        change.consume()
                        pan = clampPan(pan + drag)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoom = 1f
                            pan = Offset.Zero
                            FmLog.info("FM_WHEEL") { "reset zoom=1.000" }
                        },
                    )
                },
        ) {
            val previewed = hoveredCard
            // `.stage-area { position:absolute; inset:0; padding: 56px 64px 84px }`
            // —— 底部 84 已经容下悬浮工具条，不再单独扣一条预留高度。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = FmDesktop.stagePadSide,
                        end = FmDesktop.stagePadSide,
                        top = FmDesktop.stagePadTop,
                        bottom = FmDesktop.stagePadBottom,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (previewed != null) {
                    // 悬停 0.4 秒后的预览：直接显示这张卡的打包预览图，不切换选中项。
                    LibraryPreview(
                        card = previewed,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (projects.photos.isEmpty()) {
                    ImportPlaceholder(onAddPhoto = onAddPhoto)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    ) {
                    val activePhoto = projects.activePhoto
                    // The shared wrapper (workflow.ProjectPhotoStage) owns the
                    // native branch but exposes no `onTextSlots`, so the desktop
                    // stage mirrors that exact branch to receive the renderer's
                    // text rows. Everything else (watermark-only photo, static
                    // card, placeholder) still goes through the shared wrapper.
                    when {
                        // Hold-to-compare shows the imported photo itself, never a
                        // rendered frame and never a highlight.
                        (comparing || holdOriginal) && activePhoto != null -> PhotoThumbnail(
                            path = activePhoto.path,
                            modifier = Modifier.fillMaxSize(),
                            maxEdge = 1600,
                            cornerRadius = FmRadii.stage,
                            contentScale = ContentScale.Fit,
                        )
                        card != null && design != null && NativeEngine.supports(card.id) -> DesktopNativeStage(
                            card = card,
                            design = design,
                            projects = projects,
                            textSlots = textSlots,
                            highlightedTextSlotKey = highlightedTextSlotKey,
                            highlightedTextSlotLabel = highlightedTextSlotLabel,
                            // 渲染帧与导出画布是同一个设计，宽高比一致；有了它 `.print`
                            // 的投影才能贴着成品，而不是围着舞台上一块空白。
                            printAspect = if (renderedPx.width > 0 && renderedPx.height > 0) {
                                renderedPx.width.toFloat() / renderedPx.height
                            } else {
                                null
                            },
                            onRenderedPx = { w, h -> renderedPx = IntSize(w, h) },
                            watermarkGuides = inspectorPage == InspectorPage.Watermark,
                        )
                        else -> ProjectPhotoStage(projects = projects, card = card, design = design)
                    }
                }
            }
            }

            // ---- 舞台悬浮层（PARITY-SPEC §2C）：meta / status / 对比提示 / 工具条 ----
            if (previewed == null) {
                val activePhoto = projects.activePhoto
                if (activePhoto != null && projects.activeDesign == null) {
                    val candidates = projects.photos
                        .filter { it.id != activePhoto.id }
                        .mapNotNull { p ->
                            projects.project.designs[p.id]?.let { OtherPhotoDesign(p.id, p.displayName) }
                        }
                    DesktopStageEmptyState(
                        candidates = candidates,
                        onReuse = onReuseDesign,
                        onPickBorder = onPickBorder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val metaPhoto = projects.activePhoto
            // FIX1: 标题行的「原图」也走显示尺寸，和右栏 / 成片同一个口径。
            val metaPhotoDisplay = metaPhoto?.displayPixels()
            DesktopStageMeta(
                fileName = metaPhoto?.displayName ?: card?.name ?: "尚未添加照片",
                originalSize = metaPhotoDisplay
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { "${it.width} × ${it.height}" },
                exportSize = exportSizeLabel(exportPixels),
                modifier = Modifier.align(Alignment.TopStart),
            )
            DesktopStageStatusTag(
                text = when {
                    comparing || holdOriginal -> "原图"
                    previewed != null -> "悬停预览 · ${previewed.name}"
                    projects.activePhoto != null && activeDesign == null -> "未选边框"
                    native -> "实时预览"
                    card != null -> "示例图"
                    else -> "未选边框"
                },
                kind = when {
                    native && previewed == null && !comparing && !holdOriginal &&
                        !(projects.activePhoto != null && activeDesign == null) -> DesktopStageStatusKind.Live
                    else -> DesktopStageStatusKind.Static
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (comparing || holdOriginal) {
                DesktopStageCompareFlag(modifier = Modifier.align(Alignment.TopCenter))
            }
            val appearance = LocalAppearance.current
            DesktopStageToolbar(
                zoomLabel = if (zoom <= 1f) "适合" else "${(zoom * 100).roundToInt()}%",
                zoomDescription = "当前 ${(zoom * 100).roundToInt()}%，点击适合窗口",
                compareActive = comparing || holdOriginal,
                compareEnabled = projects.activePhoto != null,
                tone = appearance.settings.stageTone,
                onZoomOut = { stepZoom(-1) },
                onFit = ::fitToWindow,
                onZoomIn = { stepZoom(1) },
                onCompareHold = { comparing = it },
                onSelectTone = { appearance.setStageTone(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (projects.photos.isNotEmpty()) {
            DesktopFilmstrip(
                projects = projects,
                onAddPhoto = onAddPhoto,
                onRemovePhoto = onRemovePhoto,
                onOpenBatch = onOpenBatch,
            )
        }
    }

    // 设计上下文（保存预设 / 恢复当前设计）已经上移到项目卡下拉，舞台本身不再有
    // 演示稿之外的 `⋯` 菜单。
}

/**
 * Desktop stage for a card the native engine renders.
 *
 * The composition (padding, centred fill, overlay re-anchor after the first
 * frame) is the native branch of the shared `workflow.ProjectPhotoStage`, plus
 * the two things that wrapper cannot pass through:
 * `NativeStagePreview(onTextSlots = …)`, which is how the 文字 tab learns the
 * geometry of the rows the engine actually drew, and
 * `NativeStagePreview(highlightedTextSlotKey = …)`, which frames the row being
 * edited, and `NativeStagePreview(highlightedTextSlotLabel = …)`, which names that
 * row with the string the 文字 tab computed. Keep the two in sync if the wrapper's
 * native branch ever changes.
 */
@Composable
private fun DesktopNativeStage(
    card: LibraryCard,
    design: DesignSnapshot,
    projects: ProjectHolder,
    textSlots: TextSlotRegistry,
    highlightedTextSlotKey: String?,
    /** §3.3: the same row's display name (`左上 · 品牌`), computed by the 文字 tab. */
    highlightedTextSlotLabel: String?,
    /** 渲染帧宽高比：`.print` 的投影要贴着成品，不能围着整块舞台。 */
    printAspect: Float?,
    onRenderedPx: (Int, Int) -> Unit,
    /** 水印引导线是否显示（只有打开水印页时为真，见 WP-R D8）。 */
    watermarkGuides: Boolean = false,
) {
    // The watermark anchor needs the render's photo rect, which only exists after
    // the first frame; bumping the version re-keys the overlay so the mark lands
    // on the rendered image instead of the placeholder.
    var geometryVersion by remember { mutableIntStateOf(0) }
    val overlay = remember(projects.activePhotoId, projects.activeDesign?.overlay, geometryVersion) {
        projects.overlaySpec()
    }
    val shadowColor = FmPalette.scrim
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (printAspect != null && printAspect > 0f) {
                        Modifier.aspectRatio(printAspect)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                // `.print` 投影：`0 1px 2px, 0 18px 40px -12px, 0 40px 80px -30px`
                // —— Compose 只有一层 shadow，取最大那层，颜色用遮罩令牌。
                .shadow(
                    elevation = 28.dp,
                    shape = RectangleShape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                ),
        ) {
            NativeStagePreview(
                card = card,
                design = design,
                modifier = Modifier.fillMaxSize(),
                overlay = overlay,
                photoPath = projects.activePhoto?.path,
                onRendered = { w, h, _ ->
                    geometryVersion++
                    onRenderedPx(w, h)
                },
                onTextSlots = textSlots::update,
                highlightedTextSlotKey = highlightedTextSlotKey,
                highlightedTextSlotLabel = highlightedTextSlotLabel,
                // WP-C §4.5: the watermark can be dragged directly on the photo; the
                // dashed frame shows the cell it may move in, and the write-back is the
                // same absolute dx/dy the 水印 page sliders use.
                watermark = desktopWorkflowWatermark(projects.watermark()),
                onWatermarkNudge = { dx, dy -> projects.updateWatermark(dx = dx, dy = dy) },
                // WP-R D8：虚线区域框/十字辅助线只在编辑水印时出现。演示稿的普通
                // 编辑态（有水印）没有这层线，所以按当前页签开关。
                watermarkGuides = watermarkGuides,
            )
        }
    }
}

@Composable
private fun ImportPlaceholder(onAddPhoto: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(FmSpacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.panel))
                .padding(FmSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("导入照片", style = FmType.titleLarge, color = FmPalette.onSurface)
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                "为照片加上边框、文字与水印",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.xl))
            PrimaryButton(
                text = "选择照片",
                onClick = onAddPhoto,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(FmSpacing.md))
            Text(
                "支持 JPEG / PNG / WebP / TIFF，可多选；失败的图片会单独列出。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

/** 「→ 导出 W × H」。null 时不写这一行。 */
private fun exportSizeLabel(pixels: OutputPixels?): String? = pixels?.toString()
