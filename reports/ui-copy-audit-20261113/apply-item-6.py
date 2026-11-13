#!/usr/bin/env python3
"""UI copy audit 2026-11-13, item 6: engine vocabulary out of user copy."""
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


patch("workflow/NativeRender.kt", [
    # `supportsExport` is a capability, not something the user configured.
    ('''            throw EngineFailure("FM-E-ENGINE-002", "这张边框未接入桌面引擎，无法导出")''',
     '''            throw EngineFailure("FM-E-ENGINE-002", "这个边框暂时还不支持导出，请换一个边框试试")'''),
    ('''            throw EngineFailure("FM-E-ENGINE-002", "这张边框未接入桌面引擎，无法导出")''',
     '''            throw EngineFailure("FM-E-ENGINE-002", "这个边框暂时还不支持导出，请换一个边框试试")'''),
    ('''            throw EngineFailure("FM-E-EXPORT-001", "当前引擎不支持 WebP 导出，请改用 JPEG 或 PNG")''',
     '''            throw EngineFailure("FM-E-EXPORT-001", "当前版本不支持 WebP 导出，请改用 JPEG 或 PNG")'''),
])

patch("workflow/ProjectHolder.kt", [
    ('''        throw EngineFailure("FM-E-ENGINE-001", "当前平台没有桌面引擎")''',
     '''        throw EngineFailure("FM-E-ENGINE-001", "桌面渲染组件不可用，请检查安装是否完整")'''),
])

patch("inspector/RegistryInspector.kt", [
    ('''    const val DISABLED_REASON = "引擎暂不支持"''',
     '''    const val DISABLED_REASON = "当前版本暂不支持"'''),
])

patch("inspector/CuratedPanes.kt", [
    ('''                            CuratedSubNote("低于 ${FmUnits.formatNumber(floor, 2)}% 与地板等价：引擎按短边 8.5% 兜底，画布不再变矮。")''',
     '''                            CuratedSubNote("低于 ${FmUnits.formatNumber(floor, 2)}% 与地板等价：渲染按短边 8.5% 兜底，画布不再变矮。")'''),
])

patch("inspector/LogoPanes.kt", [
    ('''        "居左时左侧${rows.joinToString("、")}两行将不显示（引擎按版式实现，不是挪位）。"''',
     '''        "居左时左侧${rows.joinToString("、")}两行将不显示（按版式排，不是挪位）。"'''),
])

patch("workflow/BatchScreen.kt", [
    ('''                        subtitle = "原生导出 · 队列保存在本机，重启后可从停下的位置继续",''',
     '''                        subtitle = "本机导出 · 队列保存在本机，重启后可从停下的位置继续",'''),
    ('''                    text = "队列为空。点「添加一张」按当前照片与库内已接引擎的卡片建一条任务。",''',
     '''                    text = "队列为空。点「添加一张」，按当前照片和库里可导出的边框建一条任务。",'''),
])

patch("workflow/PhotoBatch.kt", [
    ('''        get() = undesigned.joinToString("、") { "「${it.displayName}」(第${it.nameIndex}张)" }''',
     '''        get() = undesigned.joinToString("、") { "「${it.displayName}」(第${it.nameIndex}张)" }'''),
])

patch("shell/desktop/DesktopShellDialogs.kt", [
    ('''                "预览仍是静态边框图，参数只改本地值；导出与批量导出均走原生引擎，文件写入导出目录。",''',
     '''                "预览仍是静态边框图，参数只改本地值；导出与批量导出都用本机渲染，文件写入导出目录。",'''),
])

patch("shell/desktop/DesktopColumnLayout.kt", [
    ('''                else -> "引擎样张"''',
     '''                else -> "示例图"'''),
])

patch("shell/desktop/DesktopStagePane.kt", [
    ('''                    card != null -> "引擎样张"''',
     '''                    card != null -> "示例图"'''),
])

patch("shell/desktop/ExportDialog.kt", [
    ('''                "示例照片（引擎样张）"''',
     '''                "示例照片"'''),
])
print("done")
