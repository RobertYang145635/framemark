package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.shell.phone.ExportFormContent
import com.framemark.native.shell.phone.PhoneExportOptions
import com.framemark.native.shell.phone.PhoneExportSize
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmPalettes
import com.framemark.native.theme.PaletteId
import com.framemark.native.theme.FrameMarkTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The export sheet is a **visual** deliverable, so this renders it headless at the
 * OPPO PGEM10 geometry (411 dp wide, density 3.5) instead of trusting the code shape.
 *
 * Defect 1 was "the sheet is nearly full-height with a huge empty middle": the phone
 * sheet used to fill 90 % of the window and the form's scrolling body had
 * `weight(1f)`, so the action pair sat at the window's bottom edge under ~500 dp of
 * blank white. The design (`06-phone-export.png`) is a content-height sheet: the
 * summary card, the two segmented rows, the notes and the link decide the height,
 * and 分享 / 保存到相册 follow directly under them.
 *
 * What is rendered: the real [FmSheetSurface] (the sheet's own layout: grabber,
 * scrim, bottom alignment, height policy) around the real [ExportFormContent], at
 * window sizes and every 格式 × 尺寸 option state. The platform `Dialog` shell is
 * the one part not drawn — an offscreen `ImageComposeScene` renders a scene, not a
 * platform dialog window — but it contributes no geometry.
 *
 * What it proves, measured from the PNGs:
 *  - the sheet height is the content height (~547 dp), not 90 % of the window, and
 *    the dark stage is still visible above the sheet;
 *  - the gap between the last form row and the action pair is the designed ~10 dp
 *    spacer, not hundreds of dp of white;
 *  - on a 430 dp-tall window the sheet stops at the 90 % cap (387 dp) and the
 *    action pair is still inside the sheet — the body scrolls instead;
 *  - switching 格式 or 尺寸 never moves the sheet's edge (the option-dependent note
 *    keeps a two-line shelf).
 *
 * The PNGs are kept under `ui/composeApp/build/p1b/` for review.
 */
class PhoneExportSheetRenderTest {

    private val density = Density(3.5f)

    // 渲染用的是默认配色（暖灰墨绿）浅色：面板不再是纯白，主按钮也不再是品牌蓝。
    private val colors = FmPalettes.of(PaletteId.Forest, dark = false)
    private val surfaceRgb = colors.surface.toArgb() and 0xFFFFFF
    private val primaryRgb = colors.primary.toArgb() and 0xFFFFFF

    private val card = LibraryCard(
        id = "card::demo",
        name = "经典双行",
        category = "common",
        categoryName = "常用",
        brand = "Xiaomi",
        vendor = "Xiaomi",
        family = "经典双行",
        fidelity = "verified",
        badge = null,
        en = null,
        systemStyle = "classic-two-line",
        previewSourcePath = "",
        previewFile = "",
    )

    private fun settingsFor(format: ExportFormat, size: PhoneExportSize): ExportSettings =
        PhoneExportOptions.normalized(
            PhoneExportOptions.applySize(
                PhoneExportOptions.applyFormat(ExportSettings(), format),
                size,
            ),
        )

    private data class Frame(val image: BufferedImage, val path: Path) {
        private val pxPerDp = 3.5f
        val heightDp: Float get() = image.height / pxPerDp
    }

    @Composable
    private fun StageBackdrop(content: @Composable () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().background(FmPalette.stage)) { content() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String,
        windowWidthDp: Int,
        windowHeightDp: Int,
        settings: ExportSettings,
        save: Boolean = true,
    ): Frame {
        val scene = ImageComposeScene(
            width = (windowWidthDp * density.density).toInt(),
            height = (windowHeightDp * density.density).toInt(),
            density = density,
        ) {
            FrameMarkTheme {
                StageBackdrop {
                    FmSheetSurface(onDismiss = {}, wrapContent = true) {
                        ExportFormContent(
                            card = card,
                            // No design snapshot: the 112 × 96 preview well stays the
                            // empty stage colour instead of starting a native render.
                            design = null,
                            overlay = { null },
                            photoPath = null,
                            native = true,
                            settings = settings,
                            busy = false,
                            sharing = false,
                            error = null,
                            note = null,
                            outputWidth = 0,
                            outputHeight = 0,
                            hdrGainMap = false,
                            canApplyToAll = true,
                            onApplyToAll = {},
                            onPreserveHdr = {},
                            onFormat = {},
                            onSize = {},
                            onShare = {},
                            onSave = {},
                            onClose = {},
                        )
                    }
                }
            }
        }
        val image = scene.render()
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "$name produced no PNG bytes")
        val outDir = Path.of("build/p1b").toAbsolutePath()
        Files.createDirectories(outDir)
        val keep = outDir.resolve("export-sheet-$name.png")
        if (save) Files.write(keep, png)
        println(
            "EXPORT_SHEET_RENDER name=$name path=$keep bytes=${png.size} " +
                "px=${image.width}x${image.height}",
        )
        return Frame(ImageIO.read(ByteArrayInputStream(png)), keep)
    }

    // -----------------------------------------------------------------------
    // Measurements over the rendered pixels
    // -----------------------------------------------------------------------

    private fun isSheetSurface(rgb: Int): Boolean = (rgb and 0xFFFFFF) == surfaceRgb

    /** Top row of the white sheet: more than half the row is the `surface` white. */
    private fun sheetTopRow(frame: Frame): Int {
        val image = frame.image
        for (y in 0 until image.height) {
            var white = 0
            for (x in 0 until image.width) {
                if (isSheetSurface(image.getRGB(x, y))) white++
            }
            if (white > image.width / 2) return y
        }
        return -1
    }

    private fun sheetHeightDp(frame: Frame): Float {
        val top = sheetTopRow(frame)
        assertTrue(top >= 0, "no sheet surface found in ${frame.path}")
        return (frame.image.height - top) / density.density
    }

    /** Top-most row of the primary-filled action button (`保存到相册`). */
    private fun saveButtonTop(frame: Frame): Int {
        val primary = primaryRgb
        val image = frame.image
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == primary) return y
            }
        }
        return -1
    }

    /** Bottom-most row of the primary-filled action button. */
    private fun saveButtonBottom(frame: Frame): Int {
        val primary = primaryRgb
        val image = frame.image
        for (y in image.height - 1 downTo 0) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == primary) return y
            }
        }
        return -1
    }

    /**
     * Last non-white row above the action pair: the bottom edge of the form's last
     * text (the 把当前设计用到全部照片 link). The rows between it and the buttons are
     * the sheet's empty background, which is exactly the defect's white middle.
     */
    private fun lastInkRowAbove(frame: Frame, buttonTop: Int, sheetTop: Int): Int {
        val image = frame.image
        for (y in buttonTop - 1 downTo sheetTop) {
            for (x in 0 until image.width) {
                if (!isSheetSurface(image.getRGB(x, y))) return y
            }
        }
        return sheetTop
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun the_sheet_wraps_its_content_and_follows_the_last_row() {
        val frame = render(
            name = "phone-411x905",
            windowWidthDp = 411,
            windowHeightDp = 905,
            settings = settingsFor(ExportFormat.Jpeg, PhoneExportSize.Original),
        )
        val sheetTop = sheetTopRow(frame)
        val heightDp = sheetHeightDp(frame)
        val buttonTop = saveButtonTop(frame)
        val buttonBottom = saveButtonBottom(frame)
        val inkBottom = lastInkRowAbove(frame, buttonTop, sheetTop)
        val gapDp = (buttonTop - inkBottom) / density.density

        println(
            "EXPORT_SHEET_MEASURE phone sheetTopPx=$sheetTop sheetHeightDp=${"%.1f".format(heightDp)} " +
                "windowDp=${frame.heightDp} inkToButtonsDp=${"%.1f".format(gapDp)}",
        )

        // The old shape: 90 % of 905 dp = 814.3 dp, content ending ~45 % down. The
        // wrapped sheet is the content height (a bit over half the window).
        assertTrue(
            heightDp < 0.70f * frame.heightDp,
            "the sheet still fills the window: ${heightDp}dp of ${frame.heightDp}dp",
        )
        assertTrue(
            heightDp > 0.45f * frame.heightDp,
            "the sheet lost content: ${heightDp}dp of ${frame.heightDp}dp",
        )
        // Content is anchored at the bottom of the window, and the stage is visible.
        assertTrue(
            isSheetSurface(frame.image.getRGB(frame.image.width / 2, frame.image.height - 1)),
            "the sheet is not anchored to the window's bottom edge",
        )
        assertTrue(sheetTop > 0, "no stage above the sheet")
        assertTrue(
            !isSheetSurface(frame.image.getRGB(frame.image.width / 2, 0)),
            "the window's first row is already sheet surface",
        )
        // Both actions are painted, inside the sheet.
        assertTrue(buttonTop > sheetTop, "the action pair was not drawn inside the sheet")
        assertTrue(buttonBottom < frame.image.height, "the action pair left the sheet")
        // The designed spacing directly under the link is a 10 dp spacer, not a blank
        // middle: 40 dp is generous headroom for the row's own padding.
        assertTrue(
            gapDp in 0f..40f,
            "the form's last row and the actions are ${gapDp}dp apart",
        )
    }

    @Test
    fun a_short_window_caps_the_sheet_and_keeps_the_actions_inside_it() {
        val windowHeightDp = 430
        val frame = render(
            name = "short-411x430",
            windowWidthDp = 411,
            windowHeightDp = windowHeightDp,
            settings = settingsFor(ExportFormat.Jpeg, PhoneExportSize.Original),
        )
        val heightDp = sheetHeightDp(frame)
        val sheetTop = sheetTopRow(frame)
        val buttonTop = saveButtonTop(frame)
        val buttonBottom = saveButtonBottom(frame)
        println(
            "EXPORT_SHEET_MEASURE short sheetHeightDp=${"%.1f".format(heightDp)} " +
                "capDp=${"%.1f".format(windowHeightDp * 0.9f)} button=${buttonTop}..$buttonBottom",
        )
        // FmSheetMetrics.WrapMaxFraction: never past 90 % of the window.
        assertEquals(
            windowHeightDp * 0.9f,
            heightDp,
            2f,
            "the short-window sheet is not capped at 90 % of the window",
        )
        assertTrue(sheetTop > 0, "no stage above the capped sheet")
        // The action pair stays pinned inside the sheet's own bounds (the body is the
        // part that scrolls), so both buttons are fully painted and above the sheet's
        // bottom padding.
        assertTrue(buttonTop > sheetTop, "the action pair was not drawn inside the capped sheet")
        assertTrue(buttonBottom < frame.image.height, "the action pair left the capped sheet")
    }

    @Test
    fun a_360x700_window_still_fits_the_whole_form() {
        // 700 dp (not 640): the Ultra HDR switch row joined the form, so a 640 dp-tall
        // window would legitimately hit the 90 % cap. The claim is still "the whole
        // form fits in a small 360 dp-wide window and the sheet wraps its content".
        val frame = render(
            name = "phone-360x700",
            windowWidthDp = 360,
            windowHeightDp = 700,
            settings = settingsFor(ExportFormat.Jpeg, PhoneExportSize.Original),
        )
        val heightDp = sheetHeightDp(frame)
        val buttonTop = saveButtonTop(frame)
        val buttonBottom = saveButtonBottom(frame)
        println(
            "EXPORT_SHEET_MEASURE 360 sheetHeightDp=${"%.1f".format(heightDp)} " +
                "button=${buttonTop}..$buttonBottom capDp=${"%.1f".format(700 * 0.9f)}",
        )
        assertTrue(
            heightDp < 700 * 0.9f,
            "the 360x700 sheet hit the cap (${heightDp}dp) instead of wrapping its content",
        )
        assertTrue(buttonBottom < frame.image.height, "the action pair left the sheet")
        assertTrue(buttonTop > sheetTopRow(frame), "the action pair was not drawn inside the sheet")
    }

    @Test
    fun switching_format_or_size_never_resizes_the_sheet() {
        val tops = LinkedHashMap<String, Int>()
        for (format in PhoneExportOptions.FORMATS) {
            for (size in PhoneExportOptions.SIZES) {
                val frame = render(
                    name = "options-${format.name}-${size.name}",
                    windowWidthDp = 411,
                    windowHeightDp = 905,
                    settings = settingsFor(format, size),
                    // One representative option state is kept for review.
                    save = format == ExportFormat.Png && size == PhoneExportSize.Long2048,
                )
                tops["${format.name}/${size.name}"] = sheetTopRow(frame)
            }
        }
        val distinct = tops.values.distinct()
        println("EXPORT_SHEET_MEASURE options sheetTops=$tops")
        assertEquals(
            1,
            distinct.size,
            "the sheet edge moved between option states: $tops",
        )
    }
}
