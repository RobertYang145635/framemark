package com.framemark.native.workflow

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.framemark.native.LaunchOptions
import com.framemark.native.product.FmLog

/**
 * P3f evidence probe: publish where a named UI target actually is, in window
 * pixels, so the scripted click harness aims at real geometry.
 *
 * A target is re-published every time its rectangle *moves*, not once per key:
 * the export sheet grows when its preview bitmap lands, and a once-only report
 * would leave the harness aiming at the box from before that relayout. Readers
 * must therefore take the last line for a key, never the first.
 */
object ControlProbe {
    private val geometry = LinkedHashMap<String, String>()

    fun reset() {
        geometry.clear()
    }

    fun report(key: String, coordinates: LayoutCoordinates) {
        if (!LaunchOptions.controlProbe) return
        if (!coordinates.isAttached) return
        val rect = coordinates.boundsInWindow()
        val shape = "${rect.left.toInt()}|${rect.top.toInt()}|${rect.width.toInt()}|${rect.height.toInt()}"
        if (geometry[key] == shape) return
        geometry[key] = shape
        FmLog.info("FM_CONTROL_BOUNDS") {
            "key=$key x=${rect.left.toInt()} y=${rect.top.toInt()} " +
                "w=${rect.width.toInt()} h=${rect.height.toInt()} sig=${geometry.size}"
        }
    }

    /** The value a control currently holds, so a click can be proven to have hit it. */
    fun state(key: String, value: Any?, type: String = "") {
        if (!LaunchOptions.controlProbe) return
        FmLog.info("FM_CONTROL_STATE") { "key=$key value=$value type=$type" }
    }
}

/** Attaches [ControlProbe.report] to a composable without changing its layout. */
@Composable
fun Modifier.probeBounds(key: String): Modifier =
    if (!LaunchOptions.controlProbe) this else onGloballyPositioned { ControlProbe.report(key, it) }
