package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.workflow.DesignSession
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The 水印位置 grid is a **visual** deliverable, so this renders it headless instead
 * of trusting the code shape: phone sheet width (411 dp) at density 3.5, through the
 * same [StockAnchorControl] / [AnchorCellsGrid] the phone and the desktop rail call.
 *
 * It asserts what a screenshot would show:
 *  - the control draws something (many distinct colours, not a blank rectangle);
 *  - the picked cell is the one the value names: `leftTop` fills the **top-left**
 *    cell (grid index 0) and `center` fills the **middle** cell (index 4) — this is
 *    the row-major mapping the declared column-major domain must not leak through;
 *  - the other cells are not filled while one is picked.
 *
 * The PNG of the whole control is kept under `ui/composeApp/build/p1b/` for review.
 */
class StockAnchorRenderTest {

    private val DpWidth = 411

    private fun anchorControl() = InspectorControl(
        key = "card::anchorGrid.bandb0.b0",
        controlId = "anchorGrid.bandb0.b0",
        param = "params.stockAnchor",
        label = "水印位置",
        kind = "anchorGrid",
        section = InspectorSection.Layout,
        type = ControlType.Select,
        initial = ControlValue.Str("vendor"),
        min = null,
        max = null,
        step = null,
        options = StockAnchorCells.map { ControlOption(it, it) },
        multiline = false,
        declaredDefault = "vendor",
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        density: Density = Density(3.5f),
        content: @Composable () -> Unit,
    ): java.awt.image.BufferedImage {
        val scene = ImageComposeScene(
            width = (widthDp * density.density).toInt(),
            height = (heightDp * density.density).toInt(),
            density = density,
        ) { content() }
        val image = scene.render()
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "render produced no PNG bytes")
        val outDir = Path.of("build/p1b").toAbsolutePath()
        Files.createDirectories(outDir)
        val keep = outDir.resolve("stock-anchor-$name-${widthDp}dp-x${density.density}.png")
        Files.write(keep, png)
        println("STOCK_ANCHOR_RENDER path=$keep bytes=${png.size} px=${image.width}x${image.height}")
        return ImageIO.read(ByteArrayInputStream(png))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun the_stock_anchor_control_renders_at_phone_width() {
        val control = anchorControl()
        val session = DesignSession()
        session.seed(listOf(control))
        session.set(control, "leftTop")

        var primary: Color? = null
        val buffered = render("control", DpWidth, 320) {
            primary = FmPalette.primary
            Column(
                modifier = Modifier
                    .width(DpWidth.dp)
                    .fillMaxWidth()
                    .background(FmPalette.surface)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                StockAnchorControl(control = control, session = session, dense = true)
            }
        }

        val colors = HashSet<Int>()
        var px = 0
        while (px < buffered.width) {
            var py = 0
            while (py < buffered.height) {
                colors += buffered.getRGB(px, py)
                py += 3
            }
            px += 3
        }
        assertTrue(colors.size > 20, "the grid looks blank (${colors.size} sampled colours)")
        // 左上 must be filled somewhere in the left half, above the 按规格 chip.
        val fill = primary!!.toArgb() and 0xFFFFFF
        assertTrue(
            hasColor(buffered, fill, 0, (buffered.width / 2)) { y -> y < buffered.height * 3 / 5 },
            "the picked 左上 cell was not drawn with the primary fill",
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun the_stock_anchor_control_renders_at_desktop_rail_width() {
        // The desktop rail is FmDesktop.inspectorWidth (336 dp) with 18 dp side
        // padding, so the grid gets ~300 dp: three ~98 dp cells, still room for the
        // two-character labels. The desktop uses the dense=false 44 dp cells.
        val railWidth = 336
        val control = anchorControl()
        val session = DesignSession()
        session.seed(listOf(control))
        session.set(control, "rightBottom")

        var primary: Color? = null
        val buffered = render("control", railWidth, 320, Density(2f)) {
            primary = FmPalette.primary
            Column(
                modifier = Modifier
                    .width(railWidth.dp)
                    .fillMaxWidth()
                    .background(FmPalette.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                StockAnchorControl(control = control, session = session, dense = false)
            }
        }
        val colors = HashSet<Int>()
        var px = 0
        while (px < buffered.width) {
            var py = 0
            while (py < buffered.height) {
                colors += buffered.getRGB(px, py)
                py += 2
            }
            px += 2
        }
        assertTrue(colors.size > 20, "the desktop rail grid looks blank (${colors.size} colours)")
        val fill = primary!!.toArgb() and 0xFFFFFF
        // 右下 = the bottom-right cell: the fill must appear in the right half's
        // lower part of the grid, never in the top-left corner.
        assertTrue(
            hasColor(buffered, fill, buffered.width / 2, buffered.width) { it > buffered.height / 4 },
            "the picked 右下 cell was not drawn with the primary fill",
        )
        assertTrue(
            !hasColor(buffered, fill, 0, buffered.width / 3) { it < buffered.height / 4 },
            "the desktop grid filled the top-left cell for a 右下 value",
        )
    }

    @Test
    fun the_picked_cell_is_the_one_the_value_names() {
        // Grid-only render so the cell geometry is exact: 3 dp plate, 3 dp gutters,
        // 40 dp cells (dense) on a 411 dp wide sheet.
        val cell = 40f
        val plate = 3f
        val gutter = 3f
        val cellW = (DpWidth - 2 * plate - 2 * gutter) / 3f
        fun centreOf(col: Int, row: Int): Pair<Float, Float> =
            (plate + col * (cellW + gutter) + cellW / 2f) to (plate + row * (cell + gutter) + cell / 2f)

        val heightDp = (3 * cell + 4 * plate).toInt()
        var primary: Color? = null

        val topLeft = render("grid-leftTop", DpWidth, heightDp) {
            primary = FmPalette.primary
            Box(Modifier.width(DpWidth.dp).background(FmPalette.surface)) {
                AnchorCellsGrid(
                    selectedIndex = 0,
                    dense = true,
                    onPick = {},
                    glyphOf = { StockAnchorCellLabels[it] },
                    descriptionOf = { StockAnchorCellLabels[it] },
                )
            }
        }
        val centre = render("grid-center", DpWidth, heightDp) {
            Box(Modifier.width(DpWidth.dp).background(FmPalette.surface)) {
                AnchorCellsGrid(
                    selectedIndex = 4,
                    dense = true,
                    onPick = {},
                    glyphOf = { StockAnchorCellLabels[it] },
                    descriptionOf = { StockAnchorCellLabels[it] },
                )
            }
        }

        // Sample left of each cell's centre: the cell label is centred, so the
        // exact centre can land on a glyph instead of the fill.
        fun fillOf(image: java.awt.image.BufferedImage, col: Int, row: Int): Int {
            val (x, y) = centreOf(col, row)
            return pixelAt(image, x - 40f, y)
        }
        val fill = primary!!.toArgb() and 0xFFFFFF
        assertEquals(fill, fillOf(topLeft, 0, 0), "leftTop did not fill the top-left cell")
        assertNotEquals(fill, fillOf(topLeft, 1, 1), "leftTop also filled the middle cell")
        assertNotEquals(fill, fillOf(topLeft, 2, 2), "leftTop also filled the bottom-right cell")
        assertEquals(fill, fillOf(centre, 1, 1), "center did not fill the middle cell")
        assertNotEquals(fill, fillOf(centre, 0, 0), "center also filled the top-left cell")
        assertNotEquals(fill, fillOf(centre, 2, 2), "center also filled the bottom-right cell")
    }

    private fun pixelAt(image: java.awt.image.BufferedImage, xDp: Float, yDp: Float): Int {
        val x = (xDp * 3.5f).toInt().coerceIn(0, image.width - 1)
        val y = (yDp * 3.5f).toInt().coerceIn(0, image.height - 1)
        return image.getRGB(x, y) and 0xFFFFFF
    }

    private fun hasColor(
        image: java.awt.image.BufferedImage,
        rgb: Int,
        xFrom: Int,
        xTo: Int,
        yRange: (Int) -> Boolean,
    ): Boolean {
        var x = xFrom
        while (x < xTo) {
            var y = 0
            while (y < image.height) {
                if (yRange(y) && (image.getRGB(x, y) and 0xFFFFFF) == rgb) return true
                y += 2
            }
            x += 2
        }
        return false
    }
}
