#!/usr/bin/env python3
"""UI copy audit 2026-11-13, item 5: no raw exception text on a user surface.

Every anchor is asserted, and a patch that is already applied is skipped, so the
script is safe to re-run.
"""
import io, os

ROOT = r"E:\FrameMark\work\ui-v2\ui\composeApp\src\commonMain\kotlin\com\framemark\native"


def patch(rel, pairs):
    path = os.path.join(ROOT, rel)
    text = io.open(path, encoding='utf-8').read()
    crlf = io.open(path, encoding='utf-8', newline='').read().count('\r\n') > 0
    changed = False
    for old, new in pairs:
        if new in text:
            continue
        assert old in text, f"{rel}: anchor not found: {old[:70]!r}"
        text = text.replace(old, new, 1)
        changed = True
    if changed:
        io.open(path, 'w', encoding='utf-8', newline='\r\n' if crlf else '\n').write(text)
        print("patched", rel)


# ------------------------------------------------------------------ presets
patch("shell/PhonePresetSheet.kt", [
    ('''            transferMessage = "无法导入预设：${error.message ?: "本机写入失败"}"''',
     '''            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", error, "无法导入预设，请确认文件没有损坏。")'''),
    ('''                                onFailure = { transferMessage = "无法导入预设：${it.message ?: "文件格式无效"}" },''',
     '''                                onFailure = {
                                    transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "无法导入预设，文件格式不正确。")
                                },'''),
    ('''                        transferMessage = "读取预设文件失败：${it.message ?: "未知错误"}"''',
     '''                        transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "读取预设文件失败，请重试。")'''),
    ('''            transferMessage = "无法打开预设文件选择器：${it.message ?: "未知错误"}"''',
     '''            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "无法打开文件选择器，请重试。")'''),
    ('''                transferMessage = "无法导出预设：${encoded.exceptionOrNull()?.message ?: "文档无效"}"''',
     '''                transferMessage = NoticeText.failure(
                    "FM_PRESET_SAVE_FAIL",
                    encoded.exceptionOrNull(),
                    "无法导出预设，请重试。",
                )'''),
    ('''                        onFailure = { transferMessage = "保存预设文件失败：${it.message ?: "未知错误"}" },''',
     '''                        onFailure = {
                            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "保存预设文件失败，请重试。")
                        },'''),
])

# ------------------------------------------------------------- export sheet
patch("shell/phone/PhoneExportSheet.kt", [
    ('''                note = "分享没有成功：${failure.message ?: "请重试"}"''',
     '''                note = NoticeText.failure("FM_EXPORT_FAIL", failure, "分享没有成功，请重试。")'''),
    ('''                note = "相册写入没有成功：${failure.message ?: "请重试"}"''',
     '''                note = NoticeText.failure("FM_EXPORT_FAIL", failure, "相册写入没有成功，请重试。")'''),
])

# ---------------------------------------------------------- desktop export
patch("shell/desktop/ExportDialog.kt", [
    ('''                                    .onFailure { error = "选择输出目录失败：${it.message}" }''',
     '''                                    .onFailure {
                                        error = NoticeText.failure(
                                            "FM_DESKTOP_EXPORT_FAIL",
                                            it,
                                            "选择输出目录失败，请重试。",
                                        )
                                    }'''),
])

# ------------------------------------------------------------- user logos
# No `FM_` tag exists in this file, and the evidence-hook ratchet is file+symbol
# keyed: a new tag here would be new debt. The raw text therefore leaves the UI
# entirely and the status line carries the fixed sentence.
patch("inspector/LogoBrandPanel.kt", [
    ('''            .onFailure { loadError = it.message ?: "用户 Logo 库读取失败" }''',
     '''            // Raw `it.message` was shown verbatim; the library is user copy only.
            .onFailure { loadError = "用户 Logo 库读取失败，请重试。" }'''),
    ('''                                    UserLogoImportStatus.fail("删除失败：${it.message ?: "用户 Logo 库写入失败"}")''',
     '''                                    UserLogoImportStatus.fail("删除失败，请重试。")'''),
])

# ------------------------------------------------------------- data loads
patch("library/LibraryData.kt", [
    ('''data class LibraryLoadResult(
    val snapshot: LibrarySnapshot,
    /** `null` when the bundle loaded; otherwise the exception text for the error screen. */
    val error: String? = null,
)''',
     '''data class LibraryLoadResult(
    val snapshot: LibrarySnapshot,
    /**
     * `null` when the bundle loaded; otherwise the sentence the error screen shows.
     * The exception text is not copy — it lives in [diagnostic] (audit item 5).
     */
    val error: String? = null,
    /** Raw exception text for support, never rendered. */
    val diagnostic: String? = null,
)'''),
    ('''            LibraryLoadResult(
                snapshot = emptySnapshot(),
                error = t.message ?: t::class.simpleName ?: "unknown error",
            )''',
     '''            LibraryLoadResult(
                snapshot = emptySnapshot(),
                error = "边框库数据读取失败，请重试。",
                diagnostic = t.message ?: t::class.simpleName ?: "unknown error",
            )'''),
])

patch("inspector/InspectorData.kt", [
    ('''data class InspectorLoadResult(
    val snapshot: InspectorSnapshot,
    val error: String? = null,
)''',
     '''data class InspectorLoadResult(
    val snapshot: InspectorSnapshot,
    /** The sentence the panel shows; null when the bundle loaded. */
    val error: String? = null,
    /** Raw exception text for support, never rendered (audit item 5). */
    val diagnostic: String? = null,
)'''),
    ('''            InspectorLoadResult(
                snapshot = emptySnapshot(),
                error = t.message ?: t::class.simpleName ?: "unknown error",
            )''',
     '''            InspectorLoadResult(
                snapshot = emptySnapshot(),
                error = "可调参数数据读取失败，请重试。",
                diagnostic = t.message ?: t::class.simpleName ?: "unknown error",
            )'''),
])
print("done")
