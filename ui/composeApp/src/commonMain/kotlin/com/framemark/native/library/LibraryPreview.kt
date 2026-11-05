package com.framemark.native.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Runtime preview failures, surfaced in the UI instead of a silent blank tile. */
object PreviewDiagnostics {
    val failures = mutableStateMapOf<String, String>()

    fun record(cardId: String, reason: String) {
        if (failures.put(cardId, reason) == null) {
            println("FM_PREVIEW_FAIL id=$cardId reason=$reason")
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val bitmap: ImageBitmap) : PreviewState
    data class Failed(val reason: String) : PreviewState
}

/**
 * Loads one bundled preview through the bounded shared cache. Tile composition
 * never touches resource IO or image decoding on the UI thread.
 */
@Composable
fun LibraryPreview(
    card: LibraryCard,
    modifier: Modifier = Modifier,
) {
    val state by produceState<PreviewState>(initialValue = PreviewState.Loading, card.id, card.previewFile) {
        value = if (card.previewFile.isBlank()) {
            val reason = "no preview row"
            PreviewDiagnostics.record(card.id, reason)
            PreviewState.Failed(reason)
        } else {
            try {
                PreviewState.Ready(LibraryPreviewLoader.load(card))
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The tile left the viewport / the tab changed: not an image failure.
                throw c
            } catch (t: Throwable) {
                val reason = t.message ?: t::class.simpleName ?: "decode failed"
                PreviewDiagnostics.record(card.id, reason)
                PreviewState.Failed(reason)
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted),
    ) {
        when (val s = state) {
            is PreviewState.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            PreviewState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FmPalette.surfaceMuted),
            )

            is PreviewState.Failed -> MissingPreview()
        }
    }
}

/** Explicit "no image" tile; the failure reason stays in the log, not on screen. */
@Composable
private fun MissingPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, FmPalette.warning.copy(alpha = 0.55f), RoundedCornerShape(FmRadii.tile))
            .padding(FmSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "预览缺失",
            style = FmType.labelMedium.copy(fontSize = 12.sp),
            color = FmPalette.warning,
            maxLines = 1,
        )
    }
}
