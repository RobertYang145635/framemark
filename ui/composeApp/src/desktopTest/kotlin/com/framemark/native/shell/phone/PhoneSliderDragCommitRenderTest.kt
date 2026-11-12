package com.framemark.native.shell.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.framemark.native.theme.FrameMarkTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拖动气泡在**真的两行参数行**上的行为（2026-11-13）：40 个指针样本只写 2 次。
 *
 * 这一份不是状态机单测（那在 `DragCommitGateTest`），而是把 `PhoneSliderParamRow`
 * 真渲染在 `ImageComposeScene` 里、用真的指针事件拖 40 个样本，数
 * `onValueChange` 被调用了几次：
 *
 *  - 改动前：一个样本一次 `session.set` → 40 次（每次都让整个编辑器重组 + 让预览
 *    渲染器排一帧 550 ms 的引擎渲染）；
 *  - 改动后：第一次样本立刻写、松手补最后一笔 → 2 次，中间样本只在行内显示。
 */
class PhoneSliderDragCommitRenderTest {

    private val writes = mutableListOf<Float>()

    private val width = 400
    private val densityValue = 1f

    /** 行高 26（标签行）+ 26（轨道行）：轨道中线在这一行的 y = 39 处。 */
    private val trackY = 39f

    @OptIn(ExperimentalComposeUiApi::class)
    private fun newScene(): ImageComposeScene {
        writes.clear()
        return ImageComposeScene(width = width, height = 120, density = Density(densityValue)) {
            FrameMarkTheme {
                Column {
                    PhoneSliderParamRow(
                        label = "水平微调",
                        value = 0f,
                        range = -25f..25f,
                        defaultValue = 0f,
                        step = 0.5f,
                        unit = "%",
                        decimals = 1,
                        onValueChange = { writes += it },
                    )
                }
            }
        }
    }

    @Test
    fun fortyPointerSamplesCollapseIntoTheFirstAndTheReleaseWrite() {
        val scene = newScene()
        scene.render()
        scene.sendPointerEvent(PointerEventType.Press, Offset(100f, trackY))
        // 40 samples, 4 px apart — the density of a real 60–120 Hz drag on a 379 dp track.
        for (index in 0 until 40) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f + index * 4f, trackY))
            scene.render()
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(256f, trackY))
        scene.render()
        scene.close()

        assertTrue(writes.isNotEmpty(), "the drag never wrote a value")
        // One write per 100 ms plus the immediate first sample and the release flush:
        // a 40-sample drag that cannot produce 40 engine preview requests.
        assertTrue(
            writes.size in 2..6,
            "40 samples must collapse into the gate's cadence, not 40 writes: $writes",
        )
        assertTrue(writes.size < 40, "every sample was still written: $writes")
        // Value at x = 100 (press) → -13.5, at x = 256 (release) → 7.5 on the −25..25 track.
        assertEquals(-13.5f, writes.first(), 0.6f, "the first sample must land immediately: $writes")
        assertEquals(7.5f, writes.last(), 0.6f, "the release must carry the value the finger stopped on: $writes")
        assertEquals(writes.distinct(), writes, "an intermediate sample was written twice: $writes")
    }

    @Test
    fun aSingleTapStillWritesOnceImmediately() {
        val scene = newScene()
        scene.render()
        scene.sendPointerEvent(PointerEventType.Press, Offset(120f, trackY))
        scene.sendPointerEvent(PointerEventType.Release, Offset(120f, trackY))
        scene.render()
        scene.close()
        assertEquals(1, writes.size, "a tap must write exactly once: $writes")
    }
}
