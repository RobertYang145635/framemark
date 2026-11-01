package com.framemark.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * G5 timing probe (evidence only, `FM_G5_PROBE=1`).
 *
 * [begin] runs inside the real input handler; [Host] logs the latency to the
 * second frame after it, so an async native preview render (seconds) is never
 * part of the interaction number. Cold start is process creation → the second
 * frame after the library reports ready.
 */
object FeelProbe {
    var enabled: Boolean = false
    private var nextId = 0
    private var coldLogged = false
    private val events = Channel<Event>(Channel.UNLIMITED)

    private class Event(val id: Int, val action: String, val detail: String, val mark: TimeMark)

    fun begin(action: String, detail: String = "") {
        if (!enabled) return
        nextId += 1
        events.trySend(Event(nextId, action, detail, TimeSource.Monotonic.markNow()))
    }

    @Composable
    fun Host() {
        if (!enabled) return
        LaunchedEffect(Unit) {
            kotlinx.coroutines.coroutineScope {
                for (event in events) {
                    launch {
                        withFrameNanos { }
                        withFrameNanos { }
                        val ms = event.mark.elapsedNow().inWholeMicroseconds / 1000.0
                        println("FM_FEEL_UI action=${event.action} id=${event.id} latencyMs=${fmt(ms)} detail=${event.detail}")
                    }
                }
            }
        }
    }

    @Composable
    fun ColdStart(ready: Boolean) {
        if (!enabled || !ready || coldLogged) return
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            val start = WorkflowEnv.platform?.processStartMillis() ?: return@LaunchedEffect
            val now = WorkflowEnv.platform?.nowMillis() ?: return@LaunchedEffect
            coldLogged = true
            println("FM_COLD_START totalMs=${now - start}")
        }
    }

    private fun fmt(v: Double): String {
        val n = (v * 100).toLong()
        return "${n / 100}.${(n % 100).toString().padStart(2, '0')}"
    }
}
