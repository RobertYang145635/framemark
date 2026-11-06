package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.FmColorSwatch
import com.framemark.native.inspector.FmSlider
import com.framemark.native.inspector.swatchColor
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch controls of the phone parameter pages, copied from the showcase's `.touchui`
 * rules (`styles.css`): `.group`, `.ghead`, `.sub-note`, `.fs.touch`, `.seg.touch`,
 * `.sw.touch`, `.swatches`, `.outsz`.
 *
 * They live here rather than in `inspector/` on purpose: the desktop inspector's
 * `FmControls`/`FmSlider` are another package's files, and these are the phone's own
 * chassis around the shared primitives ([FmSlider], [FmColorSwatch]).
 */

/** `.touchui .group { padding: 16px 18px 14px }` + `border-bottom: 1px solid --line`. */
@Composable
fun PhoneGroup(
    title: String,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = FmPalette.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (right != null) right()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** `.touchui .sub-note { font-size: 12.5px; color: --ink-4; line-height: 1.5 }`. */
@Composable
fun PhoneSubNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = androidx.compose.material3.LocalTextStyle.current.copy(
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        ),
        color = FmPalette.onSurfaceMuted,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * `.seg.touch`: a `--seg` plate (radius 10, padding 3) with 36 dp / 14 px buttons and
 * a white thumb under the active one.
 */
@Composable
fun PhoneSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FmPalette.surfaceMuted)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, text) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) FmPalette.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        if (active) {
                            Modifier.border(1.dp, FmPalette.outline, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics {
                        contentDescription = "$label：$text"
                        stateDescription = if (active) "已选" else "未选"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Number of decimals a step needs, as the showcase's `decOf(step)`. */
internal fun phoneDecimalsOf(step: Float?): Int {
    if (step == null || step <= 0f) return 0
    val rounded = (step * 1000f).roundToInt() / 1000f
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) 0 else (text.length - dot - 1)
}

/** `11` → `"11"`, `11.1` → `"11.1"`, `0.05` → `"0.05"` (showcase `fmt`). */
internal fun phoneFormatValue(value: Float, decimals: Int): String {
    val text = if (decimals <= 0) {
        value.roundToInt().toString()
    } else {
        val factor = when (decimals) {
            1 -> 10f
            2 -> 100f
            else -> 1000f
        }
        val scaled = (value * factor).roundToInt()
        val whole = scaled / factor.toInt()
        val rest = abs(scaled % factor.toInt()).toString().padStart(decimals, '0')
        "$whole.$rest"
    }
    return text
}

/**
 * `.fs.touch`: the 15 px label (with the 6 px 已改 dot) and the 32 dp value plate on
 * one line, the 40 dp slider track under both. The ↺ reset only appears once the
 * value is off its default — the showcase hides it with `opacity: 0` otherwise.
 */
@Composable
fun PhoneSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float?,
    step: Float?,
    unit: String?,
    modified: Boolean,
    onValueChange: (Float) -> Unit,
    onReset: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    scale: Float = 1f,
    decimals: Int = phoneDecimalsOf(step?.let { it * scale }),
    sliderValue: Float = value,
    sliderRange: ClosedFloatingPointRange<Float> = valueRange,
    onSliderValueChange: (Float) -> Unit = onValueChange,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modified) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                )
            }
            Text(
                text = label,
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = hint,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (onReset != null && modified) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .semantics {
                            contentDescription = "恢复默认"
                            role = Role.Button
                        }
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneResetGlyph(tint = FmPalette.onSurfaceMuted)
                }
            }
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FmPalette.surfaceMuted)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = phoneFormatValue(value * scale, decimals),
                    style = androidx.compose.material3.LocalTextStyle.current.copy(
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        FmSlider(
            value = sliderValue,
            onValueChange = onSliderValueChange,
            valueRange = sliderRange,
            modified = modified,
            defaultValue = defaultValue,
            step = step,
            onReset = onReset,
            touch = true,
            valueText = { phoneFormatValue(it * scale, decimals) + (unit ?: "") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `.sw.touch`: a 46 × 26 track with a 22 dp knob; `.row.touch` is 52 dp tall. */
@Composable
fun PhoneSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = FmPalette.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(if (checked) FmPalette.primary else FmPalette.outlineStrong),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .offset(x = if (checked) 20.dp else 0.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(FmPalette.surface),
            )
        }
    }
}

/** `.swatches`: six columns of 38 dp circles with 12.5 px labels (`.touchui`). */
@Composable
fun PhoneSwatchRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (value, label) ->
                    val active = value == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .clickable(role = Role.RadioButton) { onSelect(value) }
                            .semantics {
                                contentDescription = "底色：$label"
                                stateDescription = if (active) "已选" else "未选"
                            }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FmColorSwatch(
                            color = swatchColor(value),
                            selected = active,
                            label = label,
                            onClick = { onSelect(value) },
                            size = 38.dp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = label,
                            style = androidx.compose.material3.LocalTextStyle.current.copy(
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** `.outsz`: a dashed-top row of 12 px supporting copy with its number on the right. */
@Composable
fun PhoneKeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label：$value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = FmPalette.onSurface,
            maxLines = 1,
        )
    }
}

/** A quiet full-width note used when a page has nothing to adjust. */
@Composable
fun PhoneEmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

/** `.kv` block: two columns of 12.5 px label/value pairs (边框规格). */
@Composable
fun PhoneSpecRows(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = value,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Convenience: the standard page body column (`--pad` 0, sections draw their own). */
@Composable
fun PhonePaneColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) { content() }
}



