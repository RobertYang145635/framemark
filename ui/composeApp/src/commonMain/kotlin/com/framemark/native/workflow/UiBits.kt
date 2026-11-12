package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmBreakpoints
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * Height policy of the phone bottom sheet (U5).
 *
 * The phone sheet used to be one fixed shape: a column filling 90 % of the window.
 * A content-height sheet — the export sheet, `06-phone-export.png` — wraps its
 * content instead and is only capped at the same 90 % ceiling on short windows,
 * where the body inside scrolls. The cap is what keeps the sheet reachable in
 * landscape: it can never grow past [WrapMaxFraction], so the action row and the
 * grabber stay inside the sheet.
 *
 * The functions are pure so the ceiling is unit-testable without a composition.
 */
object FmSheetMetrics {
    /** The fixed share of the window every pre-existing phone sheet keeps. */
    const val FillFraction: Float = 0.9f

    /** The tallest a content-wrapped phone sheet may ever get (same ceiling). */
    const val WrapMaxFraction: Float = 0.9f

    /** The height cap of a content-wrapped sheet in a [windowHeight] window. */
    fun wrapCap(windowHeight: Dp): Dp = windowHeight * WrapMaxFraction
}

/**
 * Modal secondary surface. The platform dialog owns focus and excludes the
 * background window from modal navigation; the responsive panel stays shared.
 *
 * U5: at >= 600 dp the same content becomes a centred dialog card (max 860 dp
 * wide, 88 % tall) instead of a full-width bottom sheet. The phone geometry is
 * unchanged.
 *
 * [wrapContent] opts a phone sheet into the content-height shape of [FmSheetMetrics]
 * (the export sheet). The default keeps the historical 90 % fill, so no existing
 * sheet changes size.
 */
@Composable
fun FmSheet(
    onDismiss: () -> Unit,
    wrapContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FmSheetSurface(onDismiss = onDismiss, wrapContent = wrapContent, content = content)
    }
}

/**
 * The sheet's own layout, without the platform [Dialog] shell: the scrim, the
 * phone bottom sheet (grabber included) or the wide centred card.
 *
 * Split out of [FmSheet] so the sizing can be rendered headlessly: an offscreen
 * `ImageComposeScene` renders a scene, not a platform dialog window. Tests call
 * this with the window's constraints; production reaches it only through [FmSheet].
 */
@Composable
internal fun FmSheetSurface(
    onDismiss: () -> Unit,
    wrapContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "关闭面板",
                    onClick = onDismiss,
                ),
        )
        if (maxWidth >= FmBreakpoints.compact) {
            val panelWidth = minOf(maxWidth * 0.9f, 860.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(panelWidth)
                    .heightIn(max = maxHeight * 0.88f)
                    .clip(RoundedCornerShape(FmRadii.sheet))
                    .background(FmPalette.surface)
                    .padding(start = FmSpacing.xxl, end = FmSpacing.xxl, top = FmSpacing.xl, bottom = FmSpacing.xl),
            ) {
                content()
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        if (wrapContent) {
                            // Content height, but never past the old 90 % ceiling: on a
                            // short window the body inside scrolls instead of pushing the
                            // action row out of the sheet.
                            Modifier.heightIn(max = FmSheetMetrics.wrapCap(maxHeight))
                        } else {
                            Modifier.fillMaxHeight(FmSheetMetrics.FillFraction)
                        },
                    )
                    .clip(RoundedCornerShape(topStart = FmRadii.sheet, topEnd = FmRadii.sheet))
                    .background(FmPalette.surface)
                    .padding(start = FmSpacing.screen, end = FmSpacing.screen, top = FmSpacing.sm, bottom = FmSpacing.xl),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.outlineStrong),
                )
                Spacer(Modifier.height(FmSpacing.md))
                content()
            }
        }
    }
}

@Composable
fun SheetTitle(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = FmType.titleLarge, color = FmPalette.onSurface,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(FmSpacing.xxs + FmSpacing.xxs))
            Text(subtitle, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
        }
        if (trailing != null) {
            Spacer(Modifier.width(FmSpacing.sm))
            trailing()
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * 最小高度。默认 48 dp；手机密度档（三个动作并排一行）传 44 —— 44 是触控地板，
     * 不再为了「按钮看起来更胖」多占 4 dp 的页高。桌面与其它调用点不传。
     */
    minHeight: Dp = 48.dp,
) {
    val background = if (enabled) FmPalette.primary else FmPalette.surfaceMuted
    val foreground = if (enabled) FmPalette.onPrimary else FmPalette.onSurfaceFaint
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(background)
            .border(
                1.dp,
                if (enabled) Color.Transparent else FmPalette.outline,
                RoundedCornerShape(FmRadii.pill),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FmType.labelMedium, color = foreground)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** 见 [PrimaryButton]：手机密度档传 44（触控地板），默认保持 48。 */
    minHeight: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FmType.labelMedium,
            color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
        )
    }
}

/** Compact toolbar pill used by the editor header (预设 / 导出). */
@Composable
fun ToolPill(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (primary) FmPalette.primary else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (primary) Color.Transparent else FmPalette.outline,
                shape = RoundedCornerShape(FmRadii.pill),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FmType.labelMedium,
            color = if (primary) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
        )
    }
}

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surfaceMuted)
            .border(
                1.dp,
                if (selected) FmPalette.primary else FmPalette.outline,
                RoundedCornerShape(FmRadii.pill),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
        )
    }
}

@Composable
fun SheetFieldLabel(text: String) {
    Text(
        text = text,
        style = FmType.labelMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(bottom = FmSpacing.xs + FmSpacing.xxs),
    )
}

@Composable
fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
            .padding(horizontal = FmSpacing.md, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
                }
                inner()
            }
        },
    )
}

@Composable
fun FmProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Int = 6,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary),
        )
    }
}

/** Small neutral tag (示例导出 / 已生成). */
@Composable
fun SheetTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
    ) {
        Text(text, style = FmType.labelSmall, color = FmPalette.onSurfaceMuted, maxLines = 1)
    }
}

@Composable
fun SheetNote(text: String) {
    Text(
        text = text,
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SheetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FmPalette.outline),
    )
}

@Composable
fun SheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
        Text(title, style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xxs))
        content()
    }
}
