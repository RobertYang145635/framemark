package com.framemark.native.shell

import kotlin.math.abs
import kotlin.math.roundToInt
import com.framemark.native.product.FmLog

/**
 * Dev-only frame sampler left over from the U1a bottom-nav measurement. The
 * four-tab capsule is gone (the phone IA is the old WebView editor bar again), so
 * nothing emits samples today; the object stays because the desktop entry point
 * still wires `FM_MOTION_LOG=1` to it and the U1 motion table quotes the format.
 *
 * Format:
 *   FM_MOTION begin id=1 from=0 to=1
 *   FM_MOTION s id=1 t=16.7 x=0.3210 stretch=1.1900 ink=0.4000 content=0.1200
 *   FM_MOTION end id=1 t=536.4
 */
object MotionProbe {
    var enabled: Boolean = false

    fun begin(id: Int, from: Int, to: Int) {
        if (enabled) FmLog.info("FM_MOTION") { "begin id=$id from=$from to=$to" }
    }

    fun sample(id: Int, tMs: Double, x: Float, stretch: Float, ink: Float, content: Float) {
        if (!enabled) return
        FmLog.info("FM_MOTION") {
            "s id=$id t=${f1(tMs)} x=${f4(x)} stretch=${f4(stretch)} " +
                "ink=${f4(ink)} content=${f4(content)}"
        }
    }

    fun end(id: Int, tMs: Double) {
        if (enabled) FmLog.info("FM_MOTION") { "end id=$id t=${f1(tMs)}" }
    }

    private fun f4(v: Float): String {
        val n = (v * 10000f).roundToInt()
        val sign = if (n < 0) "-" else ""
        val a = abs(n)
        return "$sign${a / 10000}.${(a % 10000).toString().padStart(4, '0')}"
    }

    private fun f1(v: Double): String {
        val n = (v * 10.0).roundToInt()
        val sign = if (n < 0) "-" else ""
        val a = abs(n)
        return "$sign${a / 10}.${a % 10}"
    }
}
