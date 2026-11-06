package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.appVersionLabel
import com.framemark.native.theme.AppearancePicker
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.theme.ThemeMode

/**
 * 设置 (hand-off part 2 §3 decision 3, demo `07-phone-settings.png`).
 *
 * Two groups, in the demo's order: 外观 (WP-T's [AppearancePicker], called as is —
 * this page never re-implements or edits it) and 版本 (read from the app's own
 * version source, never a literal). The 状态 group below keeps the diagnostics the
 * old overflow-menu page showed; nothing that used to be visible was dropped.
 *
 * It is a page, not a dialog: the shell owns the phone's page state (首页 / 编辑页 /
 * 设置页), so the system Back key and the top bar's 返回 leave here the same way.
 */
@Composable
fun PhoneSettingsPage(
    photoCount: Int,
    designCount: Int,
    presetCount: Int,
    saveCount: Int,
    lastExportName: String?,
    errorText: String?,
    onClose: () -> Unit,
) {
    val controller = LocalAppearance.current
    val version = PhoneSettingsText.versionLabel(appVersionLabel())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas),
    ) {
        SettingsTopBar(onBack = onClose)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FmSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
        ) {
            // Demo card 1: the 外观 label sits *inside* the card, then 主题, the
            // switch and the follow-the-system explanation.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.card))
                    .background(FmPalette.surface)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                    .padding(FmSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
            ) {
                Text(
                    text = "外观",
                    style = FmType.labelSmall.copy(fontSize = 12.sp),
                    color = FmPalette.onSurfaceMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "主题",
                        style = FmType.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = PhoneSettingsText.themeLabel(controller.settings.mode),
                        style = FmType.labelSmall.copy(fontSize = 12.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                // WP-T §3 decision: P2 only *places* the picker, it does not own it.
                AppearancePicker(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "跟随系统时，系统切换到深色模式后立即跟着变，不用刷新；" +
                        "手动选择浅色或深色后不再跟随系统。",
                    style = FmType.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                )
            }

            // Demo card 2: the version, read from the app's own source.
            SettingsValueRow(label = "版本", value = version)
            Text(
                text = "外观只保存在这台设备，不写入项目和预设。",
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.onSurfaceFaint,
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsGroupLabel("状态")
            SettingsValueRow(label = "照片", value = "$photoCount 张 · 设计 $designCount 套")
            SettingsValueRow(label = "预设", value = "$presetCount 套")
            SettingsValueRow(
                label = "工程落盘",
                value = if (saveCount > 0) "已写 $saveCount 次" else "未写入",
            )
            SettingsValueRow(label = "最近导出", value = lastExportName ?: "无")
            if (errorText != null) SettingsValueRow(label = "写入错误", value = errorText)
            Spacer(Modifier.height(FmSpacing.xxl))
        }
    }
}

/**
 * Copy and formatting for the 设置 page. Pure, so the phone settings wiring can be
 * tested without a composition (the appearance round-trip test drives the same
 * labels this page prints).
 */
object PhoneSettingsText {
    /** The demo's three labels, in on-screen order. */
    val MODE_ORDER: List<ThemeMode> = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)

    fun themeLabel(mode: ThemeMode): String = when (mode) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }

    /** The version row: never blank, never the build's internal placeholder. */
    fun versionLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == "null") "dev" else trimmed
    }
}

/** Demo header: a back chevron on the left and the page title, no close button. */
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FmSpacing.sm, end = FmSpacing.xxl, top = FmSpacing.sm, bottom = FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            PhoneBackGlyph(tint = FmPalette.onSurfaceSecondary)
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = "设置",
            style = FmType.titleLarge.copy(fontSize = 20.sp),
            color = FmPalette.onSurface,
        )
    }
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text = text,
        style = FmType.labelMedium.copy(fontSize = 12.5.sp),
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(top = FmSpacing.md, bottom = FmSpacing.xxs),
    )
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FmType.bodyLarge, color = FmPalette.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
        )
    }
}
