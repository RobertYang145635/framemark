package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.theme.LocalFmColors
import com.framemark.native.theme.StageTone

/** V2：舞台底部悬浮胶囊（原图 / 适合 / 100% / 底色）占用的高度。 */
val DesktopStageCapsuleReservedHeight = 56.dp

/** 舞台底色：读 WP-T 的外观设置，三档（深灰 / 中灰 / 浅灰）。 */
@Composable
fun desktopStageBackdrop(): androidx.compose.ui.graphics.Color =
    LocalFmColors.current.stageColor(LocalAppearance.current.settings.stageTone)

private fun stageToneLabel(tone: StageTone): String = when (tone) {
    StageTone.Dark -> "深灰"
    StageTone.Mid -> "中灰"
    StageTone.Light -> "浅灰"
}

/**
 * 舞台底色的三档切换（报告 §6“浮动工具条 · 舞台底色”）。写入走 WP-T 的
 * `AppearanceController.setStageTone`，与 appearance.json 持久化同一入口。
 */
@Composable
fun DesktopStageToneTool(
    controller: AppearanceController = LocalAppearance.current,
) {
    var open by remember { mutableStateOf(false) }
    val current = controller.settings.stageTone
    Box {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.stageLine)
                .clickable { open = true }
                .semantics {
                    contentDescription = "舞台底色：${stageToneLabel(current)}"
                    role = Role.Button
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(LocalFmColors.current.stageColor(current))
                    .border(1.dp, FmPalette.stageMuted, CircleShape),
            )
            Text("底色", style = FmType.labelSmall, color = FmPalette.stageInk)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StageTone.entries.forEach { tone ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(LocalFmColors.current.stageColor(tone))
                                    .border(1.dp, FmPalette.outlineStrong, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stageToneLabel(tone), style = FmType.bodyMedium)
                        }
                    },
                    onClick = {
                        controller.setStageTone(tone)
                        open = false
                    },
                )
            }
        }
    }
}

/** 舞台右上角状态标记：实时预览 / 引擎样张 / 渲染中（报告 §6）。 */
@Composable
fun DesktopStageStatusTag(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = FmType.labelSmall,
        color = if (live) FmPalette.success else FmPalette.stageMuted,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.stageLine)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "舞台状态：$text" },
    )
}
