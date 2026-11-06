package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.SystemBarsOnDarkSurface

/** 48 dp round icon target (C-07) inside the 52 dp bar, as in the showcase `.ptop .ibtn`. */
private val TopBarIconSize = 48.dp

/**
 * V2 phone editor top bar (report §7.2, demo `.ptop`): 返回 · 照片切换 · 撤销 / 重做 · 导出.
 *
 * It is **dark in both appearances** (hand-off part 2 §3 decision 2: the phone
 * editor's top bar uses the stage colour `#2B2C2F`), so the status bar icons must
 * switch to light ink while it is composed — [SystemBarsOnDarkSurface] does that
 * and restores the theme default when the editor leaves (WP-T report §6 item 5;
 * do not call `enableEdgeToEdge` from here).
 *
 * 返回 walks the same layers the system back button walks (keyboard → full
 * parameter page → focus view → expanded sheet); the caller owns that chain.
 */
@Composable
fun PhoneEditorTopBar(
    height: Dp,
    fileName: String,
    photoIndex: Int,
    photoCount: Int,
    photosOpen: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onTogglePhotos: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The bar sits under the status bar and is dark in both themes.
    SystemBarsOnDarkSurface(true)
    // The shell applies the safe-drawing inset, so the strip *above* this node is
    // still the light canvas while the icons are already white — in light mode the
    // clock was unreadable, and dark mode showed a #121316 seam over the #2B2C2F bar
    // (MID-APK-REPORT §6 item 2). Painting upward keeps the bar's own layout budget
    // untouched and puts the same stage colour behind the status bar in both modes.
    val stageColor = FmPalette.stage
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(stageColor)
            .drawBehind {
                val strip = statusBarTop.toPx()
                if (strip > 0f) {
                    drawRect(
                        color = stageColor,
                        topLeft = Offset(0f, -strip),
                        size = Size(size.width, strip),
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height - 1.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIcon(
                description = "返回",
                enabled = true,
                onClick = onBack,
            ) { tint -> PhoneBackGlyph(tint = tint) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(TopBarIconSize)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .semantics {
                        contentDescription = "切换照片，第 $photoIndex 张，共 $photoCount 张"
                        role = Role.Button
                        stateDescription = if (photosOpen) "照片条已展开" else "照片条已收起"
                    }
                    .clickable(onClick = onTogglePhotos)
                    .padding(horizontal = FmSpacing.xs),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = fileName,
                        style = FmType.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = FmPalette.stageInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$photoIndex / $photoCount",
                            style = FmType.labelSmall.copy(fontSize = 12.sp),
                            color = FmPalette.stageMuted,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(3.dp))
                        PhoneChevronGlyph(tint = FmPalette.stageMuted, up = photosOpen)
                    }
                }
            }
            TopBarIcon(
                description = "撤销",
                enabled = canUndo,
                stateText = if (canUndo) null else "没有可撤销的修改",
                onClick = onUndo,
            ) { tint -> PhoneHistoryGlyph(tint = tint, redo = false) }
            TopBarIcon(
                description = "重做",
                enabled = canRedo,
                stateText = if (canRedo) null else "没有可重做的修改",
                onClick = onRedo,
            ) { tint -> PhoneHistoryGlyph(tint = tint, redo = true) }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "导出当前照片"
                        role = Role.Button
                    }
                    .clickable(onClick = onExport),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.primary)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "导出",
                        style = FmType.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = FmPalette.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FmPalette.stageLine),
        )
    }
}

/** One round 44 dp icon; disabled icons explain why through the state description. */
@Composable
private fun TopBarIcon(
    description: String,
    enabled: Boolean,
    stateText: String? = null,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val tint = if (enabled) FmPalette.stageInk else FmPalette.stageMuted.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .size(TopBarIconSize)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                if (stateText != null) stateDescription = stateText
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint)
    }
}
