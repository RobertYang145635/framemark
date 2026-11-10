package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.framemark.native.theme.FmPalette
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The text-row highlight is a **visual** deliverable, so this renders it headless at
 * the device's 3.5 density (OPPO PGEM10, 560 dpi) instead of trusting the code shape.
 * The canvas is the white card background the user reported ("empty slot on white is
 * nearly invisible"), which is the hardest case for a thin line.
 *
 * It asserts what a screenshot would show:
 *  - a chip is really painted: the label's ink pixels exist only in the render *with*
 *    a label (a baseline render without one has none), so the assertion cannot pass on
 *    the frame, the ring or an anti-aliased edge;
 *  - the ink stays inside the canvas and, for a normal row, above the row (the chip's
 *    default slot), never on top of the glyphs;
 *  - a row sitting inside the bottom pill band keeps its chip above the band;
 *  - an empty row appends `（空）`: strictly more ink than the same label without it.
 *
 * The PNGs are kept under `ui/composeApp/build/p1b/` for review.
 */
class TextSlotHighlightRenderTest {

    private val density = Density(3.5f)
    private val widthPx = 1080
    private val heightPx = 1400

    /** The composable's own `StagePillBand`, in device pixels (56 dp at 3.5). */
    private val bandPx = (56f * density.density).toInt()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String,
        rect: FitRectPx,
        label: String?,
        blankText: Boolean = false,
    ): BufferedImage {
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            Box(modifier = Modifier.fillMaxSize().background(FmPalette.surface)) {
                TextSlotHighlightOverlay(rect = rect, label = label, blankText = blankText)
            }
        }
        val image = scene.render()
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "$name produced no PNG bytes")
        val outDir = Path.of("build/p1b").toAbsolutePath()
        Files.createDirectories(outDir)
        val keep = outDir.resolve("text-highlight-$name.png")
        Files.write(keep, png)
        println("TEXT_HIGHLIGHT_RENDER path=$keep bytes=${png.size} px=${image.width}x${image.height}")
        return ImageIO.read(ByteArrayInputStream(png))
    }

    private data class Ink(
        val count: Int,
        val minX: Int = Int.MAX_VALUE,
        val minY: Int = Int.MAX_VALUE,
        val maxX: Int = Int.MIN_VALUE,
        val maxY: Int = Int.MIN_VALUE,
    )

    /** Pixels with the label ink's light neutral colour, ignoring [baseline]'s own pixels. */
    private fun labelInk(image: BufferedImage, baseline: BufferedImage): Ink {
        var count = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                if (rgb == (baseline.getRGB(x, y) and 0xFFFFFF)) continue
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val light = minOf(r, g, b) >= 140
                val neutral = maxOf(r, g, b) - minOf(r, g, b) <= 24
                if (!light || !neutral) continue
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return Ink(count, minX, minY, maxX, maxY)
    }

    @Test
    fun the_chip_ink_is_drawn_inside_the_canvas_above_the_row() {
        val rect = FitRectPx(x = 250f, y = 700f, w = 500f, h = 70f)
        val withLabel = render("middle", rect, label = "左上 · 品牌")
        val withoutLabel = render("middle-no-label", rect, label = null)
        val ink = labelInk(withLabel, withoutLabel)
        assertTrue(ink.count > 200, "the chip label was not painted (${ink.count} ink pixels)")
        assertTrue(ink.minX >= 0 && ink.maxX < widthPx, "label ink left the canvas: x ${ink.minX}..${ink.maxX}")
        assertTrue(ink.minY >= 0 && ink.maxY < heightPx, "label ink left the canvas: y ${ink.minY}..${ink.maxY}")
        // Default slot: the chip sits above the row, so no ink may be drawn over the glyphs.
        assertTrue(ink.maxY < rect.y, "label ink reached the row (${ink.maxY} >= ${rect.y})")
    }

    @Test
    fun a_row_in_the_pill_band_keeps_the_chip_above_the_band() {
        val bandTop = heightPx - bandPx
        val rect = FitRectPx(x = 250f, y = bandTop + 40f, w = 500f, h = 100f)
        val withLabel = render("band", rect, label = "左下 · 机型")
        val withoutLabel = render("band-no-label", rect, label = null)
        val ink = labelInk(withLabel, withoutLabel)
        assertTrue(ink.count > 200, "the chip label was not painted (${ink.count} ink pixels)")
        assertTrue(
            ink.maxY < bandTop,
            "label ink entered the pill band (${ink.maxY} >= $bandTop)",
        )
    }

    @Test
    fun an_empty_row_appends_the_empty_marker() {
        val rect = FitRectPx(x = 200f, y = 900f, w = 600f, h = 80f)
        val baseline = render("empty-baseline", rect, label = null)
        val plain = labelInk(render("empty-plain", rect, label = "左上 · 品牌", blankText = false), baseline)
        val blank = labelInk(render("empty-suffix", rect, label = "左上 · 品牌", blankText = true), baseline)
        // More glyphs, more ink: `（空）` is really appended to the name, not just held in state.
        assertTrue(
            blank.count > plain.count + 100,
            "（空） was not drawn (${blank.count} vs ${plain.count} ink pixels)",
        )
    }
}
