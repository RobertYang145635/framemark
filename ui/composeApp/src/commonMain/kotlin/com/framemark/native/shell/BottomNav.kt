package com.framemark.native.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalFmDark
import com.framemark.native.theme.fmTweenSpec

/**
 * The editor's five phone tools (V06). The names are UI routing only: no design
 * state, project field or engine binding is keyed on them.
 */
enum class EditorTool(val label: String) {
    Border("边框"),
    Params("调整"),
    Text("文字"),
    Logo("Logo"),
    Watermark("图片水印"),
}

// ---------------------------------------------------------------------------
// Floating glass capsule — geometry, paint, motion
// ---------------------------------------------------------------------------

/**
 * Geometry of the floating glass tool capsule.
 *
 * The bar keeps the height its caller already budgets (`58 dp` / `62 dp` on a
 * phone, `computePhoneEditorHeights`); the capsule **floats inside that slot**:
 * 8 dp off each side, 6 dp off the bottom, fully rounded, with a pill behind the
 * selected cell. Nothing here grows the slot, so the phone layout arithmetic and
 * `PhoneEditorHeightsTest` are untouched.
 *
 * The touch target rule is the one hard constraint: a cell is
 * `capsule − 2 × [pillInsetV]` tall, so [capsuleHeightDp] never returns a capsule
 * shorter than `48 + 2 × 2 = 52 dp` (the smallest real slot, 58 dp, yields
 * exactly 52 → 48 dp cells).
 *
 * Everything is a token or a pure function (`cellWidthDp` … `pillLeftDp`) so the
 * arithmetic is unit-tested without a composition (`FmNavCapsuleTest`).
 */
internal object FmNavCapsule {

    /** Distance from the screen edges — the capsule never touches them. */
    val horizontalInset = 8.dp

    /** Distance under the capsule; the slot's remaining 6 dp is the float's shadow room. */
    val bottomInset = 6.dp

    /** Inset of the cell track inside the capsule. */
    val innerPad = 2.dp

    /** Pill inset inside its own cell (reference app: ~8 dp horizontal, ~6 dp vertical). */
    val pillInsetH = 6.dp
    val pillInsetV = 2.dp

    /** Soft drop shadow, not a hard edge. */
    val shadowElevation = 8.dp

    /** Backdrop blur radius. The reference draws a real gaussian, not a tint. */
    val blurRadius = 24.dp

    /**
     * Wash laid *over* the blurred backdrop copy (white, light / dark).
     *
     * Why 70 % and not the 10–16 % a translucent Haze tint suggests: the capsule
     * carries 12 sp labels and near-black icons. At 10–16 % white the ink would sit
     * on an arbitrary photo and lose contrast wherever the photo is dark — the
     * reference app's own bar measures rgb(245,247,250) over rgb(240,242,245), i.e.
     * ~92–95 % opaque, which is the same conclusion from the other side. 70 % keeps
     * ~30 % of the blurred photo visible *and* an AA floor:
     *
     *  - light, photo = black  → ink #242823 on 0.70·255 = 178 → ≈ 7:1;
     *  - dark,  photo = white  → ink #E8EDE7 on 0.70·(#1C1C1E) + 0.30·255 ≈ 96 → ≈ 5:1.
     */
    const val WashAlphaLight = 0.70f
    const val WashAlphaDark = 0.70f

    /** Hairline: 1 dp specular edge, white 40 % light / 12 % dark. */
    const val HairlineAlphaLight = 0.40f
    const val HairlineAlphaDark = 0.12f

    /** Glyph and label. */
    val iconSize = 22.dp
    val iconLabelGap = 2.dp
    val labelSize = 12.sp
    val labelLineHeight = 15.sp

    /** Minimum touch target each cell must clear (accessibility floor). */
    val minTouchTarget = 48.dp

    /**
     * Capsule height for a caller's slot. `slot − [bottomInset]`, grown if that
     * would put a cell under [minTouchTarget], and never larger than the slot.
     */
    fun capsuleHeightDp(slotHeight: Dp): Dp {
        if (slotHeight <= 0.dp) return 0.dp
        val floored = (slotHeight - bottomInset).coerceAtLeast(minTouchTarget + pillInsetV * 2)
        return floored.coerceAtMost(slotHeight)
    }

    /** Corner radius of the capsule: half its height, i.e. a true stadium. */
    fun radiusDp(capsuleHeight: Dp): Dp = capsuleHeight / 2

    /**
     * Width of one equal-weight cell: the capsule, minus its insets, minus any
     * trailing action (the 预设 cell in `PhoneToolBar`), split evenly.
     */
    fun cellWidthDp(capsuleWidth: Dp, count: Int, trailing: Dp = 0.dp): Dp {
        if (count <= 0) return 0.dp
        val track = (capsuleWidth - innerPad * 2 - trailing).coerceAtLeast(0.dp)
        return track / count
    }

    /** Left edge of cell [index] inside the capsule. Out-of-range indices clamp. */
    fun cellLeftDp(index: Int, capsuleWidth: Dp, count: Int, trailing: Dp = 0.dp): Dp {
        if (count <= 0) return 0.dp
        val i = index.coerceIn(0, count - 1)
        return innerPad + cellWidthDp(capsuleWidth, count, trailing) * i
    }

    /** Width of the selected pill: one cell minus its horizontal inset pair. */
    fun pillWidthDp(capsuleWidth: Dp, count: Int, trailing: Dp = 0.dp): Dp =
        (cellWidthDp(capsuleWidth, count, trailing) - pillInsetH * 2).coerceAtLeast(0.dp)

    /** Left edge of the pill for [index], centred in that cell. */
    fun pillLeftDp(index: Int, capsuleWidth: Dp, count: Int, trailing: Dp = 0.dp): Dp {
        if (count <= 0) return 0.dp
        return cellLeftDp(index, capsuleWidth, count, trailing) + pillInsetH
    }

    /** Height of the pill (and of a cell's touch target). */
    fun pillHeightDp(capsuleHeight: Dp): Dp =
        (capsuleHeight - pillInsetV * 2).coerceAtLeast(0.dp)

    /** Index of [tool] in [tools], or `-1` when the bar does not show it (e.g. no Logo). */
    fun selectedIndex(tools: List<EditorTool>, tool: EditorTool): Int = tools.indexOf(tool)
}

/**
 * Which glass a capsule can draw.
 *
 * [Backdrop] draws the **blurred copy of what is behind the capsule**; [TintOnly]
 * is the translucent fill used when there is nothing to blur, or when the platform
 * cannot blur.
 */
internal enum class FmNavGlassLevel { Backdrop, TintOnly }

/** The pure half of the glass decision, so it can be tested off-device. */
internal object FmNavGlass {

    /**
     * `Backdrop` only when all three hold:
     *  - a backdrop source is actually wired (`hasBackdrop`);
     *  - the blur radius is positive;
     *  - the platform can blur (`RenderEffect` = API 31+ on Android).
     *
     * The last one is **not** cosmetic. Drawing the copy without a blur leaves a
     * sharp duplicate of the photo/panel inside the capsule — a mis-aligned ghost,
     * worse than no glass at all. So an unsupported platform gets the plain tint.
     */
    fun decide(hasBackdrop: Boolean, blurRadius: Dp, blurSupported: Boolean): FmNavGlassLevel =
        if (hasBackdrop && blurSupported && blurRadius > 0.dp) {
            FmNavGlassLevel.Backdrop
        } else {
            FmNavGlassLevel.TintOnly
        }
}

/**
 * A captured backdrop for the capsule — a deliberately tiny, dependency-free
 * stand-in for the `dev.chrisbanes.haze` library the reference app uses.
 *
 * Attach [source] to the content that should show *through* the capsule (the photo
 * stage, in the editor), then pass this object to
 * [FmNavGlassCapsule]. The source records itself into a [GraphicsLayer] and draws
 * that layer back unchanged, so it looks the same; the capsule draws the matching
 * slice of it, blurred, under its tint.
 *
 * Nothing is captured until a capsule consumes it, and the layer is only *drawn* by
 * the capsule — capture is one extra layer per frame, not a bitmap round-trip.
 */
@Stable
class FmNavBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    private val origin: MutableState<Offset>,
) {

    /** Where the captured content's top-left sits, in window coordinates. */
    internal val originInWindow: Offset get() = origin.value

    /**
     * The capture half: put this on the content behind the capsule. It records the
     * node into [layer] and then draws it normally, so the source is unchanged on
     * screen (and remains the one the user interacts with).
     */
    fun source(modifier: Modifier = Modifier): Modifier = modifier
        .onGloballyPositioned { origin.value = it.positionInWindow() }
        .drawWithContent {
            layer.record(
                density = Density(this.density, this.fontScale),
                layoutDirection = this.layoutDirection,
                size = IntSize(size.width.toInt(), size.height.toInt()),
            ) {
                this@drawWithContent.drawContent()
            }
            drawLayer(layer)
        }
}

/** Remembers a backdrop sink for one editor. */
@Composable
internal fun rememberFmNavBackdrop(): FmNavBackdrop {
    val layer = rememberGraphicsLayer()
    val origin = remember { mutableStateOf(Offset.Zero) }
    return remember(layer) { FmNavBackdrop(layer, origin) }
}

// ---------------------------------------------------------------------------
// The capsule
// ---------------------------------------------------------------------------

/**
 * The floating glass capsule. It fills the caller's [slotHeight] slot with a
 * rounded, translucent, shadowed surface inset by [FmNavCapsule.horizontalInset] /
 * [FmNavCapsule.bottomInset], and hands its measuring scope to [content] so the
 * caller can place the selected pill with [FmNavCapsule.pillLeftDp].
 *
 * Glass layers, bottom to top:
 *  1. backdrop copy (blurred) + white tint — or, without a usable backdrop, the
 *     palette's inset tone at 94 %;
 *  2. [content] (the glyph/label row — never blurred, never tinted);
 *  3. the 1 dp specular hairline, drawn last so a busy backdrop cannot wash it out.
 */
@Composable
internal fun FmNavGlassCapsule(
    slotHeight: Dp,
    modifier: Modifier = Modifier,
    backdrop: FmNavBackdrop? = null,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val capsuleHeight = FmNavCapsule.capsuleHeightDp(slotHeight)
    val shape = RoundedCornerShape(FmNavCapsule.radiusDp(capsuleHeight))
    val level = FmNavGlass.decide(
        hasBackdrop = backdrop != null,
        blurRadius = FmNavCapsule.blurRadius,
        blurSupported = fmBackdropBlurSupported(),
    )
    val capsulePosition = remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight)
            .padding(
                start = FmNavCapsule.horizontalInset,
                end = FmNavCapsule.horizontalInset,
                bottom = FmNavCapsule.bottomInset,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(capsuleHeight)
                .onGloballyPositioned { capsulePosition.value = it.positionInWindow() }
                .shadow(
                    elevation = FmNavCapsule.shadowElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = FmElevation.navAmbient,
                    spotColor = FmElevation.navSpot,
                )
                .clip(shape),
        ) {
            when (level) {
                FmNavGlassLevel.Backdrop -> {
                    val source = backdrop
                    if (source != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(FmNavCapsule.blurRadius, BlurredEdgeTreatment(shape))
                                .drawWithContent {
                                    val origin = source.originInWindow
                                    val here = capsulePosition.value
                                    translate(left = origin.x - here.x, top = origin.y - here.y) {
                                        drawLayer(source.layer)
                                    }
                                },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NavCapsulePaint.wash()),
                    )
                }
                FmNavGlassLevel.TintOnly -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NavCapsulePaint.fallbackFill()),
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), content = content)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, NavCapsulePaint.hairline(), shape),
            )
        }
    }
}

/**
 * The selected pill. Must be called inside [FmNavGlassCapsule]'s content scope, so
 * the capsule's measured width is available; it animates between cells with a
 * ~180 ms FastOutSlowIn tween (`fmTweenSpec` collapses it to `snap()` when motion
 * is off), never with a spring — the pill is a position indicator, not a bounce.
 *
 * @param trailing width taken by a trailing action cell (the bar's 预设 button), so
 *   the pill stays centred under a tool even when that action is present.
 */
@Composable
internal fun FmNavSelectedPill(
    index: Int,
    capsuleWidth: Dp,
    capsuleHeight: Dp,
    count: Int,
    trailing: Dp = 0.dp,
) {
    if (index < 0 || count <= 0) return
    val targetLeft = FmNavCapsule.pillLeftDp(index, capsuleWidth, count, trailing)
    val pillLeft by animateDpAsState(
        targetValue = targetLeft,
        animationSpec = fmTweenSpec<Dp>(FmDuration.exit, FmEasing.legacyNav),
        label = "navPillLeft",
    )
    Box(
        modifier = Modifier
            .offset(x = pillLeft, y = FmNavCapsule.pillInsetV)
            .width(FmNavCapsule.pillWidthDp(capsuleWidth, count, trailing))
            .height(FmNavCapsule.pillHeightDp(capsuleHeight))
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(NavCapsulePaint.pill()),
    )
}

/** Colours of the capsule. Kept apart from the geometry so tests never need a theme. */
private object NavCapsulePaint {

    /**
     * Wash over the blurred copy — the palette's own surface, so a light capsule
     * reads near-white and a dark one near `#1C1C1E` (Classic dark surface is
     * `#1D1E22`), exactly like the reference in both appearances.
     */
    @Composable
    fun wash(): Color = FmPalette.surface.copy(
        alpha = if (LocalFmDark.current) FmNavCapsule.WashAlphaDark else FmNavCapsule.WashAlphaLight,
    )

    /**
     * Fallback fill: the palette's inset tone (one step off the panel), not pure
     * white. Over a white panel a white wash is invisible; the reference bar reads
     * because it is one tone off the page behind it, and that relationship — not
     * the literal `#F8FAFF` — is what the eye sees.
     */
    @Composable
    fun fallbackFill(): Color = FmPalette.surfaceMuted.copy(
        alpha = if (LocalFmDark.current) 0.92f else 0.94f,
    )

    /** 1 dp specular edge. */
    @Composable
    fun hairline(): Color = Color.White.copy(
        alpha = if (LocalFmDark.current) FmNavCapsule.HairlineAlphaDark else FmNavCapsule.HairlineAlphaLight,
    )
    /**
     * Selected pill: [FmPalette.indicator] is exactly the "selected pill in the
     * bottom nav" role (equal to the palette's line step by design, D-012); the
     * reference's rgb(220,222,225) is the same idea in a neutral palette.
     */
    @Composable
    fun pill(): Color = FmPalette.indicator

    /** Selected ink: brand blue. */
    @Composable
    fun inkSelected(): Color = FmPalette.primaryStrong

    /** Unselected ink: near-black / near-white, as measured on the reference. */
    @Composable
    fun inkUnselected(): Color = FmPalette.onSurface
}

// ---------------------------------------------------------------------------
// The editor tool bar
// ---------------------------------------------------------------------------

/**
 * Bottom tool bar: a floating glass capsule with equal-width cells for
 * [availableTools] (Logo only when the card has a Logo capability, so 4 or 5
 * cells). The bar only reports the tap; the caller decides which panel a tool
 * opens.
 *
 * @param height the slot the caller budgets for the bar (58 / 62 dp on a phone).
 *   The capsule floats *inside* it — see [FmNavCapsule] — so no layout arithmetic
 *   changes.
 * @param backdrop content to show blurred through the glass; without one the
 *   capsule falls back to a translucent fill (`FmNavGlass`).
 *
 * Semantics are unchanged from the docked bar: a `selectableGroup` of [EditorTool]
 * cells, each `selectable` with `Role.Tab`, carrying `contentDescription` = the
 * tool label and `stateDescription` = 已选中 / 未选中 (tests rely on those), and
 * every cell clears the 48 dp touch target.
 */
@Composable
fun FmEditorToolBar(
    height: Dp,
    tool: EditorTool,
    availableTools: List<EditorTool>,
    onSelect: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: FmNavBackdrop? = null,
) {
    FmNavGlassCapsule(slotHeight = height, modifier = modifier, backdrop = backdrop) {
        val count = availableTools.size
        FmNavSelectedPill(
            index = FmNavCapsule.selectedIndex(availableTools, tool),
            capsuleWidth = maxWidth,
            capsuleHeight = maxHeight,
            count = count,
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = FmNavCapsule.innerPad,
                    vertical = FmNavCapsule.pillInsetV,
                )
                .selectableGroup(),
        ) {
            availableTools.forEach { entry ->
                ToolCell(
                    tool = entry,
                    selected = entry == tool,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ToolCell(
    tool: EditorTool,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) NavCapsulePaint.inkSelected() else NavCapsulePaint.inkUnselected()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(FmRadii.pill))
            .semantics(mergeDescendants = true) {
                contentDescription = tool.label
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolGlyph(tool = tool, tint = tint, modifier = Modifier.size(FmNavCapsule.iconSize))
        Box(modifier = Modifier.height(FmNavCapsule.iconLabelGap))
        Text(
            text = tool.label,
            style = FmType.labelSmall.copy(
                fontSize = FmNavCapsule.labelSize,
                lineHeight = FmNavCapsule.labelLineHeight,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolGlyph(tool: EditorTool, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tool) {
            EditorTool.Border -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.1f),
                    size = Size(w * 0.8f, w * 0.8f),
                    cornerRadius = CornerRadius(w * 0.18f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.32f, w * 0.32f),
                    size = Size(w * 0.36f, w * 0.36f),
                    cornerRadius = CornerRadius(w * 0.07f),
                    style = stroke,
                )
            }
            EditorTool.Params -> {
                // Three sliders with their knobs at different stops.
                val rows = listOf(0.24f, 0.5f, 0.76f)
                val knobs = listOf(0.68f, 0.36f, 0.58f)
                rows.forEachIndexed { index, y ->
                    drawLine(
                        tint,
                        Offset(w * 0.14f, w * y),
                        Offset(w * 0.86f, w * y),
                        stroke.width,
                        StrokeCap.Round,
                    )
                    drawCircle(tint, radius = w * 0.08f, center = Offset(w * knobs[index], w * y))
                }
            }
            EditorTool.Text -> {
                drawLine(tint, Offset(w * 0.18f, w * 0.24f), Offset(w * 0.82f, w * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.24f), Offset(w * 0.5f, w * 0.84f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, w * 0.84f), Offset(w * 0.66f, w * 0.84f), stroke.width, StrokeCap.Round)
            }
            EditorTool.Logo -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.2f),
                    size = Size(w * 0.8f, w * 0.6f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.5f, w * 0.5f), style = stroke)
                drawLine(tint, Offset(w * 0.28f, w * 0.5f), Offset(w * 0.4f, w * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.6f, w * 0.5f), Offset(w * 0.72f, w * 0.5f), stroke.width, StrokeCap.Round)
            }
            EditorTool.Watermark -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, w * 0.12f)
                    cubicTo(w * 0.82f, w * 0.5f, w * 0.82f, w * 0.62f, w * 0.82f, w * 0.68f)
                    cubicTo(w * 0.82f, w * 0.86f, w * 0.68f, w * 0.9f, w * 0.5f, w * 0.9f)
                    cubicTo(w * 0.32f, w * 0.9f, w * 0.18f, w * 0.86f, w * 0.18f, w * 0.68f)
                    cubicTo(w * 0.18f, w * 0.62f, w * 0.18f, w * 0.5f, w * 0.5f, w * 0.12f)
                    close()
                }
                drawPath(path = path, color = tint, style = stroke)
            }
        }
    }
}

/**
 * Whether this platform can blur a recorded backdrop. Android needs `RenderEffect`
 * (API 31); the desktop renderer (Skia) always can. Below the floor the capsule
 * draws its tint-only fallback — never an unblurred copy, which would read as a
 * mis-aligned ghost (see [FmNavGlass.decide]).
 */
internal expect fun fmBackdropBlurSupported(): Boolean
