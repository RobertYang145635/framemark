package com.framemark.native.shell.desktop

import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesStore
import com.framemark.native.workflow.WorkflowPlatform

/**
 * “最近使用”的文件口子，走 [WorkflowPlatform] 的文本读写。
 *
 * `DesktopShell` 是 commonMain，拿不到 desktopMain 的 `java.nio` 实现；桌面真正的
 * 原子实现是 `desktopMain\…\shell\desktop\DesktopRecentFramesStore.kt`（已单测）。
 * 这里保持同一个路径 `<数据目录>/settings/recent-frames.json`，解码/损坏回落统一走
 * [RecentFrames.decode]，所以两个入口看到的是同一份文件、同一套容错。
 *
 * 接口缺口（已写进报告）：`WorkflowPlatform` 没有“注入 RecentFramesStore”的钩子，
 * 否则 commonMain 可以直接用桌面的原子实现。
 */
class PlatformRecentFramesStore(
    private val platform: WorkflowPlatform,
) : RecentFramesStore {

    val path: String = platform.dataDir.trimEnd('/', '\\') + "/settings/" + RecentFrames.FILE_NAME

    override fun load(): List<String> =
        RecentFrames.decode(runCatching { platform.readTextFile(path) }.getOrNull())

    override fun save(ids: List<String>) {
        val text = RecentFrames.encode(ids)
        runCatching { platform.writeTextFile(path, text) }
    }
}
