package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.framemark.native.LaunchOptions
import com.framemark.native.appVersionLabel
import com.framemark.native.shell.AboutContent
import com.framemark.native.shell.AboutText
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.shell.stageCanvasPixels
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.trackTextEditing

/**
 * The modals whose content the desktop shell authors itself, and the argument
 * plumbing for the export sheet.
 *
 * Owns
 *  - [DesktopSavePresetDialog] — names and saves the current design as a preset;
 *  - [DesktopResetDesignDialog] — the confirmed「恢复当前设计」;
 *  - [DesktopRemovePhotoDialog] — removing one photo (and its design) from the project;
 *  - [DesktopHelpOverlay] — the F1 shortcut sheet, with its section/row rows;
 *  - [DesktopExportSheet] — the export dialog plus the card / design / canvas it shows.
 *
 * Every entry point takes `open` and returns immediately when it is false, so the
 * work a closed sheet used to skip inline in the shell (the export card lookup, the
 * `NativeEngine.designOf` call, the canvas math) stays skipped here too. Selection
 * state (`savePresetOpen`, `exportSheetOpen`, …) lives in `DesktopShell`; nothing in
 * this file remembers anything.
 */

@Composable
internal fun DesktopSavePresetDialog(
    open: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存预设") },
        text = {
            Column {
                Text(
                    "把当前边框的参数与文字保存为预设，之后可以一键套用到其他照片。",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                )
                Spacer(Modifier.height(FmSpacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text("预设名称") },
                    modifier = Modifier.fillMaxWidth().trackTextEditing(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val clean = name.trim()
                    onDismiss()
                    if (clean.isNotEmpty()) onSave(clean)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DesktopResetDesignDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复当前设计？") },
        text = {
            Text("只清空当前边框的参数修改，回到刚应用的设计；照片和水印保留，可以撤销。")
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DesktopRemovePhotoDialog(
    target: PhotoRef?,
    onDismiss: () -> Unit,
    onRemove: (PhotoRef) -> Unit,
) {
    if (target == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除照片") },
        text = {
            Text("从工程中移除「${target.displayName}」？对应设计和水印会一起删除，不会删除原文件。")
        },
        confirmButton = {
            TextButton(onClick = {
                onRemove(target)
                onDismiss()
            }) { Text("移除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DesktopExportSheet(
    open: Boolean,
    session: DesignSession,
    model: InspectorCardModel?,
    panelModel: InspectorCardModel?,
    selectedCard: LibraryCard?,
    projects: ProjectHolder,
    cards: List<LibraryCard>?,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val activeDesign = projects.activeDesign
    val activePhoto = projects.activePhoto
    val exportCard = if (activePhoto != null) {
        cards?.firstOrNull { it.id == activeDesign?.cardId }
    } else {
        selectedCard
    }
    // WP-D2: Windows 用 V2 导出对话框（预览 / 格式 / 尺寸 / 质量 / 文件名
    // 变量 / 保存位置 / 结果页）。手机导出用 shell/phone/PhoneExportSheet；
    // workflow/ExportSheet.kt 已在 WP-FIX2 删除（全仓零调用点）。
    DesktopExportDialog(
        session = session,
        card = exportCard,
        design = if (activePhoto != null) {
            activeDesign?.takeIf { it.cardId == exportCard?.id }?.toDesignSnapshot()
        } else {
            exportCard?.let { NativeEngine.designOf(it, panelModel ?: model, session) }
        },
        photo = activePhoto,
        photoIndex = projects.photoIndex(projects.activePhotoId),
        overlay = { projects.overlaySpec() },
        // AU2 A-06/A-19：弹层的「约 W × H」也用同一份画布公式
        // （右栏 / 顶栏 / 手机 / 弹层 / 桥接），不再用预览帧的照片框占比
        // 反推——固定比例模板那里会多算一截（1664 × 1283 vs 真值 1131）。
        canonicalCanvas = stageCanvasPixels(
            model = (panelModel ?: model)?.takeIf { it.cardId == exportCard?.id },
            session = session,
            photo = activePhoto,
            rendered = IntSize.Zero,
        ),
        autoRun = LaunchOptions.runSampleExport,
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------------------
// 关于（版本 / 隐私 / 权限 / 许可）
// ---------------------------------------------------------------------------

/**
 * 桌面「关于 FrameMark」：与手机设置页的 `PhoneAboutPage` 共用 [AboutContent]。
 *
 * 做成整窗遮罩而不是 Material `AlertDialog`：桌面弹窗是独立窗口，在自动化/截图里
 * 不好定位，而帮助面板本来就是这个形状 —— 两处浮层保持一致。
 */
@Composable
internal fun DesktopAboutOverlay(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(FmSpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(AboutText.TITLE, style = FmType.titleLarge, color = FmPalette.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
                ) {
                    Text("关闭", style = FmType.labelMedium, color = FmPalette.primaryStrong)
                }
            }
            Spacer(Modifier.height(FmSpacing.md))
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AboutContent(version = appVersionLabel())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Help (plain language)
// ---------------------------------------------------------------------------

@Composable
internal fun DesktopHelpOverlay(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(FmSpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("快捷键", style = FmType.titleLarge, color = FmPalette.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
                ) {
                    Text("关闭", style = FmType.labelMedium, color = FmPalette.primaryStrong)
                }
            }
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                "桌面版常用操作。手机尺寸下没有键盘快捷键。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.lg))
            HelpSection("编辑")
            HelpRow("Ctrl + Z", "撤销（连续切边框只算一步）")
            HelpRow("Ctrl + Shift + Z / Ctrl + Y", "重做")
            HelpSection("边框库")
            HelpRow("← / →", "按库里的顺序逐个试上一个 / 下一个")
            HelpRow("鼠标悬停 0.4 秒", "在舞台预览这张边框")
            HelpRow("Ctrl + B", "折叠或展开左侧边框库（52 px 窄条）")
            HelpRow("Ctrl + Shift + B", "折叠或展开右侧参数面板")
            HelpSection("舞台")
            HelpRow("按住 \\", "查看原图，松开恢复")
            HelpRow("Ctrl + 滚轮", "缩放（25%–400%）")
            HelpRow("双击", "适合窗口")
            HelpSection("工作流")
            HelpRow("Ctrl + E", "打开导出")
            HelpRow("Ctrl + Shift + P", "打开预设")
            HelpSection("帮助")
            HelpRow("F1", "打开这份快捷键说明")
            HelpRow("Esc", "关闭弹层")
            Spacer(Modifier.height(FmSpacing.lg))
            Text(
                "预览仍是静态边框图，参数只改本地值；导出与批量导出都用本机渲染，文件写入导出目录。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun HelpSection(title: String) {
    Text(
        text = title,
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(top = FmSpacing.md, bottom = FmSpacing.xxs),
    )
}

@Composable
private fun HelpRow(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = keys,
            style = FmType.labelMedium,
            color = FmPalette.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 150.dp, max = 210.dp),
        )
        Spacer(Modifier.width(FmSpacing.md))
        Text(
            text = description,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
