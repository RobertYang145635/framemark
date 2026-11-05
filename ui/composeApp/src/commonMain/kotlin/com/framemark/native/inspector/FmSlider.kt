package com.framemark.native.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette

/**
 * The one slider used by both inspectors and the watermark panel. It follows
 * the supplied HTML reference: 44 dp hit row, 6 dp rounded track, and a 26 dp
 * white thumb with a border and soft shadow. The colour roles are the app's
 * light-theme equivalents of the dark prototype.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FmSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(FmPalette.surface)
                    .border(1.dp, FmPalette.outlineStrong, CircleShape),
            )
        },
        track = { state ->
            val fraction = if (valueRange.endInclusive > valueRange.start) {
                ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            // WP-T: colours are read during composition, the Canvas draw scope is
            // not composable.
            val idleTrack = FmPalette.outlineStrong
            val activeTrack = FmPalette.primary
            Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = idleTrack,
                    size = Size(size.width, size.height),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = activeTrack,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        ),
    )
}
