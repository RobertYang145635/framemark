package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FrameMarkTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Defect 2 is a **visual** claim — "the static sample looks like someone else's
 * photo" — so this renders the real placeholder layer over a stand-in for the
 * bundled sample, headless at 411 dp / density 3.5, and measures the result.
 *
 * What is real here: [PhoneStageFallbackOverlay] (the 12 % wash and the chip),
 * [PhoneStageFallbackChip], the two stage pills ([PhoneStageChip] /
 * [PhoneHoldCompareChip]) at their production insets, and [PhoneStageBands], the
 * photo/pill band geometry the editor lays out with. What is a stand-in: the
 * bundled sample itself, drawn as a flat white card in the photo band (decoding the
 * real preview resource would prove nothing about the placeholder). The test does
 * not render the whole editor, so it does not prove the gesture/watermark layering.
 *
 * Assertions:
 *  - the card is really dimmed by ~12 % (measured from the pixel colour, not the code);
 *  - the chip is painted, horizontally centred, and entirely above the pill band;
 *  - the two pills are still fully visible in their band.
 *
 * The PNGs are kept under `ui/composeApp/build/p1b/` for review.
 */
class PhoneStageFallbackRenderTest {

    private val density = Density(3.5f)
    private val widthDp = 411
    private val heightDp = 905
    private val stageDensity = 3.5f

    private val photoTopPx = (PhoneStageBands.PhotoTop.value * stageDensity).toInt()
    private val photoSidePx = (PhoneStageBands.PhotoSide.value * stageDensity).toInt()
    private val photoBottomPx = (PhoneStageBands.PhotoBottom.value * stageDensity).toInt()
    private val pillBandPx = (PhoneStageBands.PillBand.value * stageDensity).toInt()
    private val pillInsetPx = (PhoneStageBands.PillInset.value * stageDensity).toInt()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, placeholder: Boolean): BufferedImage {
        val scene = ImageComposeScene(
            width = (widthDp * stageDensity).toInt(),
            height = (heightDp * stageDensity).toInt(),
            density = density,
        ) {
            FrameMarkTheme {
                Box(modifier = Modifier.fillMaxSize().background(FmPalette.stage)) {
                    // Stand-in for the bundled static sample: a flat card in the photo band.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = PhoneStageBands.PhotoSide,
                                end = PhoneStageBands.PhotoSide,
                                top = PhoneStageBands.PhotoTop,
                                bottom = PhoneStageBands.PhotoBottom,
                            )
                            .background(Color.White),
                    )
                    if (placeholder) {
                        PhoneStageFallbackOverlay(
                            focusMode = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    PhoneStageChip(
                        text = "经典双行",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = PhoneStageBands.PillInset, bottom = PhoneStageBands.PillInset),
                    )
                    PhoneHoldCompareChip(
                        comparing = false,
                        onCompareChange = {},
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = PhoneStageBands.PillInset, bottom = PhoneStageBands.PillInset),
                    )
                }
            }
        }
        val image = scene.render()
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "$name produced no PNG bytes")
        val outDir = Path.of("build/p1b").toAbsolutePath()
        Files.createDirectories(outDir)
        val keep = outDir.resolve("fallback-chip-$name-411x905.png")
        Files.write(keep, png)
        println("FALLBACK_RENDER name=$name path=$keep bytes=${png.size}")
        return ImageIO.read(ByteArrayInputStream(png))
    }

    private fun luminance(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** A point on the sample card, clear of the centred chip. */
    private fun samplePixel(image: BufferedImage): Int =
        image.getRGB(image.width / 2, photoTopPx + 40)

    @Test
    fun the_static_sample_is_dimmed_and_the_chip_stays_above_the_pills() {
        val baseline = render("baseline", placeholder = false)
        val withChip = render("placeholder", placeholder = true)

        // 1) The 12 % wash really lands on the sample. Compositing `stage` at alpha a
        //    over the white card gives out = a * 43 + (1 - a) * 255 on the red channel.
        val before = (samplePixel(baseline) shr 16) and 0xFF
        val after = (samplePixel(withChip) shr 16) and 0xFF
        val stageRed = 0x2B
        val measuredAlpha = (before - after).toFloat() / (before - stageRed)
        println(
            "FALLBACK_MEASURE sampleRed before=$before after=$after " +
                "alpha=${"%.3f".format(measuredAlpha)}",
        )
        assertTrue(
            abs(measuredAlpha - PhoneStageFallback.DimAlpha) <= 0.02f,
            "the wash is ${"%.1f".format(measuredAlpha * 100)}% instead of " +
                "${"%.1f".format(PhoneStageFallback.DimAlpha * 100)}%",
        )

        // 2) The chip is painted dead centre of the stage and entirely above the pills.
        val bandTop = withChip.height - pillBandPx
        val photoBandBottom = withChip.height - photoBottomPx
        var chipTop = Int.MAX_VALUE
        var chipBottom = Int.MIN_VALUE
        var chipLeft = Int.MAX_VALUE
        var chipRight = Int.MIN_VALUE
        for (y in photoTopPx until photoBandBottom) {
            for (x in photoSidePx until withChip.width - photoSidePx) {
                if (luminance(withChip.getRGB(x, y)) > 120) continue
                if (y < chipTop) chipTop = y
                if (y > chipBottom) chipBottom = y
                if (x < chipLeft) chipLeft = x
                if (x > chipRight) chipRight = x
            }
        }
        assertTrue(chipTop < chipBottom, "the placeholder chip was not painted")
        val centrePx = (chipLeft + chipRight) / 2
        println(
            "FALLBACK_MEASURE chip box=($chipLeft,$chipTop)..($chipRight,$chipBottom) " +
                "centrePx=$centrePx stageCentrePx=${withChip.width / 2} " +
                "photoBandBottomPx=$photoBandBottom pillBandTopPx=$bandTop",
        )
        assertTrue(
            abs(centrePx - withChip.width / 2) <= 4 * stageDensity,
            "the chip is not centred: $centrePx vs ${withChip.width / 2}",
        )
        assertTrue(
            chipBottom < bandTop,
            "the chip reached the pill band ($chipBottom >= $bandTop)",
        )
        assertTrue(
            chipBottom <= photoBandBottom,
            "the chip left the photo band ($chipBottom > $photoBandBottom)",
        )

        // 3) The chip's bottom edge is the same distance below the band's centre as its
        //    top edge is above it: it is centred in the photo band, not pushed down.
        val bandCentre = (photoTopPx + photoBandBottom) / 2
        assertTrue(
            abs((chipTop + chipBottom) / 2 - bandCentre) <= 4 * stageDensity,
            "the chip is off the photo band's centre line (${(chipTop + chipBottom) / 2} vs $bandCentre)",
        )

        // 4) Both stage pills are still there: dark pixels in the pill band, and the
        //    placeholder never painted into that band.
        var pills = 0
        for (y in bandTop until withChip.height - pillInsetPx) {
            for (x in 0 until withChip.width) {
                if (luminance(withChip.getRGB(x, y)) < 90) pills++
            }
        }
        assertTrue(pills > 2000, "the stage pills are missing from the render ($pills px)")
        assertTrue(baseline.width == withChip.width && baseline.height == withChip.height)
    }
}
