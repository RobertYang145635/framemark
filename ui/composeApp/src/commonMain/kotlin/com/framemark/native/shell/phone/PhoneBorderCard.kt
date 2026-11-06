package com.framemark.native.shell.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.shell.PHONE_FRAME_CARD_SIZE_DP
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalFmDark

/**
 * Strip card width (report §7.5: 104 → 128 dp). The number itself lives in
 * [PHONE_FRAME_CARD_SIZE_DP] because the 收起 sheet height is budgeted from it
 * (WP-P1-2): the card is a spec, the chrome around it is what gives way.
 */
internal val PhoneFrameCardSize = PHONE_FRAME_CARD_SIZE_DP.dp

/**
 * One border card: a square dark tile that shows the **whole** frame, like OPPO's
 * watermark picker. The tile keeps the stage colour `#2B2C2F` in both appearances
 * (hand-off part 2 §3 decision 2) and gets a 1 dp `line-2` hairline in the dark
 * appearance only; the selected card is outlined with the accent.
 *
 * Text and Logo details are read on the big preview above — the card exists to
 * compare layouts, so the preview is never cropped.
 */
@Composable
fun PhoneBorderCard(
    card: LibraryCard,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    nameGap: Dp = 3.dp,
    nameLineSp: Float = 14f,
) {
    val dark = LocalFmDark.current
    val shape = RoundedCornerShape(FmRadii.tile)
    val outline = when {
        selected -> FmPalette.primary
        dark -> FmPalette.outlineStrong
        else -> FmPalette.stage
    }
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = if (card.badge.isNullOrBlank()) card.name else "${card.name}，${card.badge}"
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "当前边框" else "未选中"
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(FmPalette.stage)
                .border(if (selected) 2.dp else 1.dp, outline, shape)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            PhoneFramePreview(
                card = card,
                modifier = Modifier.fillMaxSize(),
            )
            if (!card.badge.isNullOrBlank()) {
                Text(
                    text = card.badge,
                    style = FmType.labelSmall.copy(fontSize = 9.sp),
                    color = FmPalette.stageInk,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(FmPalette.scrim)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(nameGap))
        Text(
            text = card.name,
            style = FmType.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = nameLineSp.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The frame's own thumbnail, fitted (never cropped) onto the dark tile. */
@Composable
fun PhoneFramePreview(card: LibraryCard, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, card.id, card.previewFile) {
        value = if (card.previewFile.isBlank()) {
            null
        } else {
            runCatching { LibraryPreviewLoader.load(card) }.getOrNull()
        }
    }
    val ready = bitmap
    if (ready == null) {
        Text(
            text = "预览缺失",
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = FmPalette.stageMuted,
            maxLines = 1,
            modifier = modifier.padding(FmSpacing.xs),
        )
        return
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = ready,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
