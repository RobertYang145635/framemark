package com.framemark.native.shell.phone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.framemark.native.library.LibraryLabels
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.shell.PHONE_FRAME_CARD_SIZE_DP
import com.framemark.native.shell.PhoneProjectRuntime
import com.framemark.native.shell.desktop.DesktopLibraryThumb
import com.framemark.native.shell.desktop.desktopActivePhotoPath
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmSpring
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmSpringSpec
import com.framemark.native.theme.fmTweenSpec

/**
 * Strip card width (report §7.5: 104 → 128 dp). The number itself lives in
 * [PHONE_FRAME_CARD_SIZE_DP] because the 收起 sheet height is budgeted from it
 * (WP-P1-2): the card is a spec, the chrome around it is what gives way.
 */
internal val PhoneFrameCardSize = PHONE_FRAME_CARD_SIZE_DP.dp

/** Showcase `.card .bdg`: 18 dp tall, radius 5, 10.5 px / 600. */
private val BadgeHeight = 18.dp

/** Showcase `.card .ck`: the 20 dp selected check on the thumbnail's top-right. */
private val CheckSize = 20.dp

/**
 * One border card: a square dark tile showing the **whole** frame (never cropped),
 * then the name and the one-line description — the showcase `.card` (`.th`
 * 1:1 on `--stage-dark`, `.bdg` in the tile's top-left corner, `.nm` 12 px,
 * `.ds` 11 px).
 *
 * The selected card is marked the way the demo marks it: a 2 dp accent ring with a
 * 5 dp soft ring outside it, a check dot on the tile, and the name in accent-strong
 * 600. The tile keeps the stage colour `#2B2C2F` in both appearances (hand-off
 * part 2 §3 decision 2) instead of following the surface.
 */
@Composable
fun PhoneBorderCard(
    card: LibraryCard,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    nameGap: Dp = 3.dp,
    nameLineSp: Float = 16f,
    descGap: Dp = 0.dp,
) {
    val shape = RoundedCornerShape(FmRadii.tile)
    val ring = FmPalette.primary
    val ringSoft = FmPalette.ring
    // Motion spec §3: press shrinks the card by 4 % and springs it back, the selection ring
    // grows out of the card's edge instead of popping on. Both are draw/graphicsLayer only,
    // so a row of cards never reflows while one of them is pressed.
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = fmSpringSpec(FmSpring.press),
        label = "fm.card.press",
    )
    val ringProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = fmTweenSpec(FmDuration.fast, FmEasing.standard),
        label = "fm.card.ring",
    )
    // `ringProgress` is read inside the draw lambda on purpose: the ring animates for
    // 140 ms and the card must not recompose once per frame to paint it. Painting outside
    // the tile's bounds is what the ring has always done, so nothing here adds a clip.
    val tile: Modifier = Modifier.drawBehind {
        val progress = ringProgress
        if (progress <= 0.01f) return@drawBehind
        val soft = 5.dp.toPx() * progress
        drawRoundRect(
            color = ringSoft,
            topLeft = Offset(-soft, -soft),
            size = Size(size.width + soft * 2, size.height + soft * 2),
            cornerRadius = CornerRadius(FmRadii.tile.toPx() + soft, FmRadii.tile.toPx() + soft),
            alpha = progress,
        )
        val inset = 2.dp.toPx()
        drawRoundRect(
            color = ring,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + inset * 2, size.height + inset * 2),
            cornerRadius = CornerRadius(FmRadii.tile.toPx() + inset, FmRadii.tile.toPx() + inset),
            alpha = progress,
        )
    }
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = if (card.badge.isNullOrBlank()) {
                    "边框：${card.name}"
                } else {
                    "边框：${card.name}，${card.badge}"
                }
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "当前边框" else "未选中"
            }
            // Same ripple as before (`LocalIndication`), plus the interaction source that
            // drives the press scale — one detector, not a second `pointerInput` overlay.
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(tile)
                .clip(shape)
                .background(FmPalette.stage)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            PhoneFramePreview(
                card = card,
                modifier = Modifier.fillMaxSize(),
            )
            BadgeLabel(card = card)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(CheckSize)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneCheckGlyph(tint = FmPalette.onPrimary, dim = 12.dp)
                }
            }
        }
        Spacer(Modifier.height(nameGap))
        Text(
            text = card.name,
            style = FmType.labelSmall.copy(
                fontSize = 12.sp,
                lineHeight = nameLineSp.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val desc = phoneCardDescription(card)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(descGap))
            Text(
                text = desc,
                style = FmType.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * Showcase `.card .bdg` colours: 热门 is the orange "hot" tag, 原版 / 实测 the teal
 * "original" one and 官方规格 / 官方素材 the accent "spec" one. The demo's literal
 * `#C2410C` / `#0F766E` map onto the warning / success tokens, so no colour is
 * written here.
 */
internal fun phoneCardBadgeColor(card: LibraryCard, accent: Color, warning: Color, success: Color): Color? =
    when (card.badge) {
        "热门" -> warning
        "原版", "实测" -> success
        "官方规格", "官方素材" -> accent
        else -> null
    }

/** The corner tag inside the tile, white 92 % plate like `.card .bdg` in the demo. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BadgeLabel(card: LibraryCard) {
    val badge = card.badge?.takeIf { it.isNotBlank() } ?: return
    val tint = phoneCardBadgeColor(
        card = card,
        accent = FmPalette.primaryStrong,
        warning = FmPalette.warning,
        success = FmPalette.success,
    ) ?: FmPalette.onSurfaceSecondary
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(4.dp)
            .height(BadgeHeight)
            .clip(RoundedCornerShape(FmRadii.badge))
            .background(FmPalette.surface.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge,
            style = FmType.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * Showcase `cardDesc(c)`: vendor cards read `品牌 · 系列`, every other card reads
 * `类别 · 子类`. It is the card's second line (11 px, ink-4), never a guess.
 */
internal fun phoneCardDescription(card: LibraryCard): String {
    if (card.category == "vendor") {
        // `OPPO · OPPO 相册` was the old copy: the family field already starts with
        // the brand, so the helper keeps the distinguishing part only (audit item 2).
        return LibraryLabels.vendorSource(card.brand, card.family)
    }
    return listOfNotNull(
        card.categoryName.takeIf { it.isNotBlank() },
        card.templateCategory?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** The frame's own thumbnail, fitted (never cropped) onto the dark tile. */
@Composable
fun PhoneFramePreview(card: LibraryCard, modifier: Modifier = Modifier) {
    // WP-R：卡片缩略图 = **当前照片**套该边框，和桌面左栏同一条能力
    // （`DesktopLibraryThumb` → `DesktopLibraryPhotoThumbs` → `NativeEngine.preview`）。
    // 照片来源与两端各自的项目持有者一致：桌面是 `WorkflowEnv.projectHolder`，
    // 手机是 `PhoneProjectRuntime`（Android 不设 projectHolder）。都没有时退回随包预览。
    val active = PhoneProjectRuntime.current?.activePhoto
    val photoPath = PhoneBorderPreview.photoPath(
        desktopActive = desktopActivePhotoPath(),
        activePath = active?.path,
        activeWidth = active?.widthPx ?: 0,
        activeHeight = active?.heightPx ?: 0,
        bundledSample = com.framemark.native.workflow.WorkflowEnv.platform?.bundledSamplePhotoPath(),
    )
    if (photoPath != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            DesktopLibraryThumb(
                card = card,
                photoPath = photoPath,
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
        return
    }
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
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
    }
}

