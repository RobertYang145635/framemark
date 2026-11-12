package com.framemark.native.shell.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 拖动气泡（2026-11-13）：**一个**拖动期间的值闸门，滑杆行与画布水印共用。
 *
 * 为什么要有它：`FmSlider` 每收到一个 `positionChanged` 就回调一次（一次 60–120 Hz
 * 的拖动 = 每秒 60–120 个样本）。照原样把每个样本写成 `session.set(...)`，每一笔都会
 * 搬一整张值表、让整个编辑器重组一次、重算一次 `PreviewRequestKey`，并让预览渲染器
 * 排一帧引擎渲染 —— 手指在拖、引擎在追，样式页的实时预览就明显跟不上。
 *
 * 所以拖动期间只更新**本地显示值**（数值文本与轨道 60 fps 跟手），真正写回按
 * [DRAG_COMMIT_INTERVAL_MS] 封顶（最多 10 次/秒），松手立即补写最后一笔。这正是
 * 画布水印直拖原本就有的节奏（原来写死在 `PhoneStagePane.WatermarkOverlay` 里），
 * 现在抽成这一份，两处共用同一套闸门，不再各写一份。
 *
 * 持久化语义不变：写回的还是同一个 `WatermarkSpec` / 控件值，只在时间上合并了几笔；
 * `DesignHistory` 的 700 ms 合并窗口本来就把一次拖动算成一步撤销。
 */
internal const val DRAG_COMMIT_INTERVAL_MS = 100L

/** 进程内单调毫秒；只有相对差值有意义（闸门的 100 ms 判据）。 */
private val dragClockStart: TimeMark = TimeSource.Monotonic.markNow()

internal fun dragNowMs(): Long = dragClockStart.elapsedNow().inWholeMilliseconds

/**
 * 拖动闸门的状态机（无协程、无 Compose 作用域）：样本只落 [live]，[publishLatest]
 * 由定时器调用，[release] 是松手那一笔。
 *
 * [clock] 可注入，测试用假时钟直接验证「40 个样本只写 4 次」这类判据。
 */
internal class DragCommitGate<T>(
    initial: T,
    private val publish: (T) -> Unit,
    private val intervalMs: Long = DRAG_COMMIT_INTERVAL_MS,
    private val clock: () -> Long = ::dragNowMs,
) {
    /** 拖动中给界面显示的值：跟手，不等引擎。 */
    var live by mutableStateOf(initial)
        private set

    /** 这个闸门当前是否在一次拖动里（数值文本据此决定用实时值还是调用方的值）。 */
    var dragging by mutableStateOf(false)
        private set

    /** 定时器要等的唤醒；并入 channel 的合并语义，中间样本不会排队。 */
    internal val signal = Channel<Unit>(Channel.CONFLATED)

    private var lastPublishAtMs: Long? = null
    private var lastPublished: T? = null

    /** 样本：只改本地值 + 唤醒定时器，不写回。 */
    fun sample(next: T) {
        live = next
        dragging = true
        signal.trySend(Unit)
    }

    /** 不是拖动、但本地值必须变（例如画布水印学到照片画幅）：同样并入定时发布。 */
    fun nudge(next: T) {
        live = next
        signal.trySend(Unit)
    }

    /** 松手：补写最后一笔（和上一笔相同就不写，避免重复提交）。 */
    fun release() {
        dragging = false
        if (live != lastPublished) publishLatest()
    }

    /** 定时器到点：把当前 [live] 写回。 */
    fun publishLatest() {
        lastPublishAtMs = clock()
        lastPublished = live
        publish(live)
    }

    /** 距离下一次允许写回还有多少毫秒（0 = 立刻可以写）。 */
    fun pendingWaitMs(): Long {
        val last = lastPublishAtMs ?: return 0L
        return (intervalMs - (clock() - last)).coerceAtLeast(0L)
    }

    /** 定时器的一步：到点就写回当前 [live] 并返回 true（未到点返回 false）。 */
    fun publishIfDue(): Boolean {
        if (pendingWaitMs() > 0L) return false
        publishLatest()
        return true
    }

    /** 外部（撤销 / 预设 / 输入框）改了值：不在拖动时才跟随，避免打断手指。 */
    fun syncExternal(next: T) {
        if (!dragging && live != next) live = next
    }
}

/**
 * 记住一个 [DragCommitGate]，并跑它的发布定时器：收到唤醒后按
 * `intervalMs` 的节奏写回**最新**值（不是每个样本都写），中间样本合并掉。
 *
 * [publish] 用 `rememberUpdatedState` 跟随重组，所以合并器不会因为回调换了身份而重建。
 */
@Composable
internal fun <T> rememberDragCommit(
    value: T,
    intervalMs: Long = DRAG_COMMIT_INTERVAL_MS,
    publish: (T) -> Unit,
): DragCommitGate<T> {
    val latestPublish by rememberUpdatedState(publish)
    val gate = remember { DragCommitGate(value, { next -> latestPublish(next) }, intervalMs) }
    LaunchedEffect(gate) {
        while (true) {
            gate.signal.receive()
            // 到点才写；`delay` 偶尔短 1 ms 也没关系，写在 `publishIfDue` 里判。
            while (!gate.publishIfDue()) {
                delay(gate.pendingWaitMs().coerceAtLeast(1L))
            }
            // 每次唤醒只写一笔：等待期间到达的样本已经落在 live 里，合并进这一笔。
            while (gate.signal.tryReceive().isSuccess) {
                // 合并掉
            }
        }
    }
    LaunchedEffect(value) { gate.syncExternal(value) }
    return gate
}
