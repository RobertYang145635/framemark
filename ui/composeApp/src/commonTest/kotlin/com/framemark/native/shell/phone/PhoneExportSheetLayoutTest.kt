package com.framemark.native.shell.phone

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The export sheet's no-jump contract (defect 1: "the sheet must not jump between
 * sizes when a segmented option changes").
 *
 * Every row of the form has a fixed height except the resolution note: its text
 * depends on 格式 / 尺寸 (`PhoneExportOptions.resolutionNote`). The JPEG promise wraps
 * to two lines at phone width, the PNG / 无损 WebP promise fits one, and the 长边
 * steps are one line again — so the row keeps a two-line shelf and the sheet's edge
 * stays put. `PhoneExportSheetRenderTest` measures the same claim on all nine option
 * states; this pins the shelf's definition.
 */
class PhoneExportSheetLayoutTest {

    @Test
    fun the_option_dependent_note_keeps_a_two_line_shelf() {
        assertEquals(18.dp, PhoneExportSheetLayout.NoteLineHeight)
        assertEquals(2, PhoneExportSheetLayout.NoteShelfLines)
        assertEquals(36.dp, PhoneExportSheetLayout.NoteShelfHeight)
    }

    @Test
    fun the_shelf_is_at_least_one_line_and_shorter_than_the_segmented_row() {
        // A one-line shelf could not hold the JPEG promise; a shelf taller than the
        // 42 dp segmented plate would read as a deliberate gap rather than a line box.
        assertTrue(PhoneExportSheetLayout.NoteShelfHeight >= PhoneExportSheetLayout.NoteLineHeight)
        assertTrue(PhoneExportSheetLayout.NoteShelfHeight < 42.dp)
    }
}
