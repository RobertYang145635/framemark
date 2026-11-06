package com.framemark.native.shell.desktop

import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesStore
import com.framemark.native.workflow.DesktopWorkflowPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 桌面“最近使用”文件：`<数据目录>\settings\recent-frames.json`。
 *
 * 数据目录与 `settings\appearance.json` 同源（`DesktopWorkflowPlatform.defaultDataDir()`），
 * 写入照 `DesktopAppearanceStore`：全文写 `<name>.tmp` → 字节数校验 →
 * `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`，卷不支持时退回普通替换。
 *
 * 读取只经过 [RecentFrames.decode]：文件缺失、JSON 损坏、版本不认识、未知卡 id
 * 都由 WP-L 那一层统一处理（返回空列表，不抛）。
 */
class DesktopRecentFramesStore(
    private val file: File = defaultFile(),
) : RecentFramesStore {

    val settingsPath: String get() = file.absolutePath

    private val charset = Charsets.UTF_8

    override fun load(): List<String> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText(charset) }.getOrNull()
        return RecentFrames.decode(text)
    }

    override fun save(ids: List<String>) {
        val directory = file.absoluteFile.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val temp = File(directory, file.name + TEMP_SUFFIX)
        val bytes = RecentFrames.encode(ids).encodeToByteArray()
        try {
            temp.writeBytes(bytes)
            if (temp.length() != bytes.size.toLong()) {
                runCatching { temp.delete() }
                return
            }
            val source = temp.toPath()
            val target = file.toPath()
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Throwable) {
            runCatching { temp.delete() }
        }
    }

    companion object {
        const val TEMP_SUFFIX = ".tmp"

        fun defaultFile(): File =
            File(File(DesktopWorkflowPlatform.defaultDataDir(), "settings"), RecentFrames.FILE_NAME)
    }
}
