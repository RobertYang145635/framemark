#!/usr/bin/env python3
"""UI copy audit 2026-11-13, items 4 and 5: support codes and raw exception text.

Applied against the working tree; every anchor is asserted so a drifted file fails
loudly instead of half-applying.
"""
import io, sys, os

ROOT = r"E:\FrameMark\work\ui-v2\ui\composeApp\src\commonMain\kotlin\com\framemark\native"


def patch(rel, pairs, import_line=None, import_after=None):
    path = os.path.join(ROOT, rel)
    text = io.open(path, encoding='utf-8').read()
    crlf = io.open(path, encoding='utf-8', newline='').read().count('\r\n') > 0
    for old, new in pairs:
        if new in text:
            continue
        assert old in text, f"{rel}: anchor not found: {old[:70]!r}"
        text = text.replace(old, new, 1)
    if import_line and import_line not in text:
        assert import_after in text, f"{rel}: import anchor missing"
        text = text.replace(import_after, import_after + '\n' + import_line, 1)
    io.open(path, 'w', encoding='utf-8', newline='\r\n' if crlf else '\n').write(text)
    print("patched", rel, "(crlf)" if crlf else "(lf)")


# ---------------------------------------------------------------- item 4: codes
patch("shell/desktop/BatchWorkspaceModel.kt", [(
'''    /**
     * 失败原因的人话版本。引擎/系统抛出的原文常常是英文 Java 异常（例如文件被占用、
     * 拒绝访问），直接摆给用户没有信息量；认得的归成一类，认不得的原样保留。
     */
    fun failureText(code: String?, message: String?): String {
        val raw = message?.trim().orEmpty().ifBlank { return fallback(code) }
        val stripped = stripCode(raw)
        val reason = when {
            containsAny(stripped, "占用", "being used", "in use", "另一个程序") ->
                "文件被占用：另一个程序正在使用它。"
            containsAny(stripped, "拒绝访问", "access is denied", "access denied", "permission denied", "没有权限", "权限不足") ->
                "没有写入权限，导出目录不可写。"
            containsAny(stripped, "找不到指定的路径", "系统找不到", "no such file", "not a directory", "不是目录", "路径不存在") ->
                "输出路径不存在或不可用。"
            containsAny(stripped, "磁盘空间", "not enough space", "disk full", "空间不足") ->
                "磁盘空间不足。"
            else -> stripped.ifBlank { fallback(code) }
        }
        val suffix = code?.takeIf { it.isNotBlank() && !reason.contains(it) }?.let { "（$it）" }.orEmpty()
        return (reason + suffix).take(160)
    }

    private fun fallback(code: String?): String =
        if (code.isNullOrBlank()) "导出没有成功，原因未知。" else "导出没有成功（$code）。"
''',
'''    /**
     * 失败原因的人话版本。引擎/系统抛出的原文常常是英文 Java 异常（例如文件被占用、
     * 拒绝访问），直接摆给用户没有信息量；认得的归成一类，认不得的给一句通用文案。
     *
     * 支撑码（`FM-E-…`）不进这句话：它已经在 `BatchRunner` 的 `FM_BATCH_ITEM_FAIL`
     * 日志行和 `BatchJobError.code` 里，用户看到的只是句子（审计 item 4）。
     */
    fun failureText(code: String?, message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return "导出没有成功，原因未知。"
        val stripped = NoticeText.human(stripCode(raw)).orEmpty()
        val reason = when {
            containsAny(stripped, "占用", "being used", "in use", "另一个程序") ->
                "文件被占用：另一个程序正在使用它。"
            containsAny(stripped, "拒绝访问", "access is denied", "access denied", "permission denied", "没有权限", "权限不足") ->
                "没有写入权限，导出目录不可写。"
            containsAny(stripped, "找不到指定的路径", "系统找不到", "no such file", "not a directory", "不是目录", "路径不存在") ->
                "输出路径不存在或不可用。"
            containsAny(stripped, "磁盘空间", "not enough space", "disk full", "空间不足") ->
                "磁盘空间不足。"
            stripped.isNotBlank() && !stripped.hasCjk() && !stripped.all { it.isDigit() || it.isWhitespace() } ->
                // An untranslatable English diagnostic is still not copy.
                "导出没有成功，请重试。"
            stripped.isNotBlank() -> stripped
            else -> "导出没有成功，请重试。"
        }
        // The code is the support handle, not the sentence: keep it in the log only.
        FmLog.info("FM_BATCH_ITEM_FAIL") { "code=${code ?: "-"} reason=${reason.take(80)}" }
        return reason.take(160)
    }
''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.BatchJobStatus")

patch("shell/desktop/ExportDialog.kt", [(
'''                error = failure?.let { "${it.userMessage}（${it.code}）" } ?: "导出没有成功，请重试。"''',
'''                // The support code stays in the log line below, not in the sentence.
                error = failure?.userMessage ?: "导出没有成功，请重试。"''')])

patch("shell/phone/PhoneExportSheet.kt", [(
'''                error = engineFailure?.let { "${it.userMessage}（${it.code}）" }
                    ?: "导出没有成功，请重试。"''',
'''                // Code in the log line below; the sheet shows the sentence only.
                error = engineFailure?.userMessage ?: "导出没有成功，请重试。"''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.FmLog")

patch("workflow/BatchScreen.kt", [(
'''    BatchItemStatus.Failed -> "失败：" + (item.error ?: "未知原因")''',
'''    BatchItemStatus.Failed -> "失败：" + (NoticeText.human(item.error) ?: "未知原因")''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.shell.TitleBlock")

patch("workflow/PhotoBatch.kt", [(
'''        queue.error?.let { message ->
            Text(message, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
        }''',
'''        queue.error?.let { message ->
            NoticeText.human(message)?.let { text ->
                Text(text, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
            }
        }'''),
('''                        row.error?.let {
                            Text(it, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
                        }''',
'''                        row.error?.let { message ->
                            NoticeText.human(message)?.let { text ->
                                Text(text, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
                            }
                        }''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.FmLog")

patch("shell/PhoneShell.kt", [(
'''        watermarkError?.let { message ->
            PhoneNotice(text = message, onClose = { watermarkError = null })
        }''',
'''        watermarkError?.let { message ->
            // Store errors carry an `FM-E-…` code; the pill shows the sentence only.
            NoticeText.human(message)?.let { text ->
                PhoneNotice(text = text, onClose = { watermarkError = null })
            }
        }'''),
('''            PhonePage.Settings -> PhoneSettingsPage(
                errorText = store.saveError,''',
'''            PhonePage.Settings -> PhoneSettingsPage(
                errorText = NoticeText.human(store.saveError),''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.FmLog")

patch("shell/PhonePresetSheet.kt", [(
'''            store.presetError?.let { message ->
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = "写入失败：$message",''',
'''            store.presetError?.let { message ->
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = NoticeText.human(message)?.let { "写入失败：$it" } ?: "写入失败，请重试。",''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.FmLog")

patch("workflow/ProjectPresetBatch.kt", [(
'''            title = { Text("预设操作没有成功") },
            text = { Text(failure) },''',
'''            title = { Text("预设操作没有成功") },
            text = { Text(NoticeText.human(failure) ?: "预设操作没有成功，请重试。") },''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.ProjectPreset")

patch("workflow/ProjectStatusBanner.kt", [(
'''    val message = projects.loadError ?: projects.saveError''',
'''    // A store error may carry an `FM-E-…` code; the banner is a user sentence.
    val message = NoticeText.human(projects.loadError ?: projects.saveError)''')],
      import_line="import com.framemark.native.product.NoticeText",
      import_after="import com.framemark.native.product.FmLog")

patch("shell/DesktopShell.kt", [(
'''                    projects.presetError ?: "保存预设失败"''',
'''                    NoticeText.human(projects.presetError) ?: "保存预设失败"''')])

patch("workflow/BatchRunner.kt", [(
'''                        item.error = (t.message ?: t::class.simpleName ?: "export failed").take(300)
                        FmLog.warn("FM_BATCH_ITEM_FAIL") { "id=${item.id} card=${item.cardId} error=${item.error}" }''',
'''                        // `EngineFailure.userMessage` is already the sentence; anything else
                        // is a raw Java/engine message, so the copy is fixed and the text
                        // goes to the log (audit items 4 and 5).
                        item.error = (t as? EngineFailure)?.userMessage ?: "导出失败，请重试。"
                        FmLog.warn("FM_BATCH_ITEM_FAIL") { "id=${item.id} card=${item.cardId} raw=${t.message}" }''')])
print("done")
