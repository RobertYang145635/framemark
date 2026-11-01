package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * Secondary preset sheet: save the current design, list, load, rename, delete.
 * Reached from the editor's 预设 button only — presets are not a navigation tab.
 */
@Composable
fun PresetSheet(
    session: DesignSession,
    card: LibraryCard?,
    cardLookup: (String) -> LibraryCard?,
    onApply: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftName by remember(card?.id, PresetStore.items.size) {
        mutableStateOf(PresetStore.defaultName())
    }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    FmSheet(onDismiss = onDismiss) {
        SheetTitle(
            title = "预设",
            subtitle = "保存整套边框与参数；套用时替换当前设计。",
            trailing = { GhostButton("完成", onClick = onDismiss) },
        )
        Spacer(Modifier.height(FmSpacing.lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surfaceMuted)
                .padding(FmSpacing.lg),
        ) {
            SheetFieldLabel("预设名称")
            SheetTextField(
                value = draftName,
                onValueChange = { draftName = it },
                placeholder = "例如：白边 + 墨色",
            )
            Spacer(Modifier.height(FmSpacing.md))
            PrimaryButton(
                text = "保存当前设计",
                enabled = card != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = card ?: return@PrimaryButton
                    PresetStore.save(
                        name = draftName,
                        cardId = target.id,
                        cardName = target.name,
                        adjustedCount = session.adjustedCount(target.id),
                        values = session.snapshotValues(),
                        export = session.export,
                    )
                    draftName = PresetStore.defaultName()
                },
            )
            if (card == null) {
                Spacer(Modifier.height(FmSpacing.sm))
                SheetNote("先在边框库选择一套边框，再保存预设。")
            }
        }

        Spacer(Modifier.height(FmSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已保存 ${PresetStore.items.size} 套",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.weight(1f))
            Text("新保存的排在最前", style = FmType.labelSmall, color = FmPalette.onSurfaceFaint)
        }
        Spacer(Modifier.height(FmSpacing.sm))

        if (PresetStore.items.isEmpty()) {
            Text(
                text = "还没有保存的预设。保存当前设计后会出现在这里。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.card))
                    .background(FmPalette.surfaceMuted)
                    .padding(FmSpacing.lg),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                items(PresetStore.items, key = { it.id }) { preset ->
                    PresetRow(
                        preset = preset,
                        card = cardLookup(preset.cardId),
                        renaming = renamingId == preset.id,
                        renameDraft = renameDraft,
                        onRenameDraft = { renameDraft = it },
                        onStartRename = {
                            renamingId = preset.id
                            renameDraft = preset.name
                        },
                        onCancelRename = { renamingId = null },
                        onCommitRename = {
                            if (PresetStore.rename(preset.id, renameDraft)) renamingId = null
                        },
                        onApply = { onApply(preset) },
                        onDelete = {
                            if (renamingId == preset.id) renamingId = null
                            PresetStore.delete(preset.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: Preset,
    card: LibraryCard?,
    renaming: Boolean,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onStartRename: () -> Unit,
    onCancelRename: () -> Unit,
    onCommitRename: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted),
            ) {
                if (card != null) {
                    LibraryPreview(
                        card = card,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                    )
                }
            }
            Spacer(Modifier.width(FmSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                if (renaming) {
                    SheetTextField(
                        value = renameDraft,
                        onValueChange = onRenameDraft,
                        placeholder = "预设名称",
                    )
                } else {
                    Text(
                        text = preset.name,
                        style = FmType.titleSmall,
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FmSpacing.xxs))
                    Text(
                        text = presetMeta(preset),
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (renaming) {
                PrimaryButton(text = "保存名称", onClick = onCommitRename)
                Spacer(Modifier.width(FmSpacing.sm))
                GhostButton(text = "取消", onClick = onCancelRename)
            } else {
                PrimaryButton(text = "套用", onClick = onApply)
                Spacer(Modifier.width(FmSpacing.sm))
                GhostButton(text = "重命名", onClick = onStartRename)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "删除",
                    style = FmType.labelMedium,
                    color = FmPalette.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable(onClick = onDelete)
                        .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
                )
            }
        }
    }
}

private fun presetMeta(preset: Preset): String {
    val parts = mutableListOf<String>()
    if (preset.cardName.isNotBlank()) parts.add(preset.cardName)
    parts.add(if (preset.adjustedCount > 0) "${preset.adjustedCount} 项调整" else "默认参数")
    val date = WorkflowEnv.platform?.formatDateTime(preset.updatedAt).orEmpty()
    if (date.isNotBlank()) parts.add(date)
    return parts.joinToString(" · ")
}
