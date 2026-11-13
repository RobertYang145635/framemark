package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.appVersionLabel
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.theme.ThemeMode
import com.framemark.native.theme.fmElevated

/**
 * 设置页，逐条对齐演示稿 `phone.js buildSettings()`（53-73 行）与
 * `07-phone-settings.png`：52 高的返回条、外观卡（外观 / 主题 / 三选一段控 /
 * 跟随系统说明）、版本卡、居中页脚。演示稿没有「状态」分组，原来的诊断行
 * 随之离场；写入错误仍然在这页最后一行露出，不吞掉失败。
 *
 * 它是一页而不是弹层：页面状态由 [com.framemark.native.shell.PhoneShell] 管，
 * 系统返回键和左上返回箭头走同一条路（[PhonePagePolicy.back]）。
 */
@Composable
fun PhoneSettingsPage(
    errorText: String? = null,
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
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // `.set-grp`（外观）：组标题在卡里，下面是 主题 → 段控 → 说明。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SettingsGroupShape)
                    .background(FmPalette.surface)
                    .fmElevated(shape = SettingsGroupShape),
            ) {
                Text(
                    text = PhoneSettingsText.GROUP_APPEARANCE,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = PhoneSettingsText.ROW_THEME,
                        style = TextStyle(
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = FmPalette.onSurface,
                    )
                    SettingsAppearanceSegment(
                        selected = controller.settings.mode,
                        onSelect = { controller.setMode(it) },
                    )
                    Text(
                        text = PhoneSettingsText.THEME_NOTE,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            // `.set-grp > .set-row.kv`（版本）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SettingsGroupShape)
                    .background(FmPalette.surface)
                    .fmElevated(shape = SettingsGroupShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = PhoneSettingsText.ROW_VERSION,
                    style = TextStyle(
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = FmPalette.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = version,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                )
            }
            Text(
                text = PhoneSettingsText.FOOT_NOTE,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = FmPalette.onSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = FmPalette.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** `.set-grp`：16 圆角 + sh-1（深色换成 1dp 描边，见 [fmElevated]）。 */
private val SettingsGroupShape = RoundedCornerShape(16.dp)

/** `.seg` 药丸的 8 圆角（`.seg.touch .thumb`）。 */
private val SegmentPillShape = RoundedCornerShape(FmRadii.chip)

/** `.set-top`：52 高、左上返回箭头（32×32）+ 17/700「设置」。 */
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = PhoneSettingsText.BACK_LABEL },
            contentAlignment = Alignment.Center,
        ) {
            PhoneBackGlyph(tint = FmPalette.onSurfaceSecondary)
        }
        Text(
            text = PhoneSettingsText.PAGE_TITLE,
            style = TextStyle(
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = FmPalette.onSurface,
        )
    }
}

/**
 * 主题三选一（演示稿 `.seg.touch`：底槽 #E9ECF0 / 10 圆角 / 内边距 3、
 * 药丸 36 高 8 圆角底 `--surface`、14 号字）。
 *
 * 这里没有直接用 `AppearancePicker`：它内部是 `inspector/FmSegmented`，选中态是
 * accent-soft 底色 + accent-strong 文字，和演示稿的白药丸不是一套（那两处文件
 * 本包不许改）。段控与 [AppearancePicker] 写的是同一个 `AppearanceController`，
 * 所以行为完全一致，只是画法照演示稿。登记见报告 §3。
 */
@Composable
private fun SettingsAppearanceSegment(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.outline)
            .padding(3.dp),
    ) {
        PhoneSettingsText.MODE_ORDER.forEach { mode ->
            val active = mode == selected
            val label = PhoneSettingsText.themeLabel(mode)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(if (active) Modifier.fmElevated(shape = SegmentPillShape) else Modifier)
                    .clip(SegmentPillShape)
                    .background(if (active) FmPalette.surface else Color.Transparent)
                    .clickable(role = Role.RadioButton) { if (!active) onSelect(mode) }
                    .semantics {
                        contentDescription = label
                        role = Role.RadioButton
                        stateDescription = if (active) "已选" else "未选"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

/**
 * 设置页的文案与格式化。纯函数，所以页面接线和外观往返都能测（见
 * `PhoneSettingsTextTest`），不用起模拟器。
 */
object PhoneSettingsText {
    const val PAGE_TITLE: String = "设置"
    const val BACK_LABEL: String = "返回首页"

    /** 第一组的组标题（`.set-h`）与行标题（`.sr-h b`）。 */
    const val GROUP_APPEARANCE: String = "外观"
    const val ROW_THEME: String = "主题"

    /** `.sr-n`：跟随系统时立即跟随、手动选择后不再跟随。 */
    const val THEME_NOTE: String =
        "跟随系统时，系统切换到深色模式后立即跟着变，不用刷新；" +
            "手动选择浅色或深色后不再跟随系统。"

    /** 第二组：`.set-row.kv`。 */
    const val ROW_VERSION: String = "版本"

    /**
     * `.set-foot`。演示稿这里写的是「演示稿的设置页：只有外观和版本号」——那是
     * 设计稿对自己的说明，产品里不能印，换成这条真实的落地说明（同一处、同字号、
     * 同样居中）。
     */
    const val FOOT_NOTE: String = "外观只保存在这台设备，不写入项目和预设。"

    /** The demo's three labels, in on-screen order. */
    val MODE_ORDER: List<ThemeMode> = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)

    fun themeLabel(mode: ThemeMode): String = when (mode) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }

    /**
     * The version row: never blank, never the build's internal placeholder.
     *
     * `appVersionLabel()` reads the installed package (`versionName`) and can come
     * back empty on a build that carries no label, or as the literal `null` /
     * `undefined` from a mis-encoded manifest. The old fallback here was the bare
     * word `dev`, which is a developer marker on a user-visible row; the row now
     * says 未知 instead. Anything else is the real label and is shown as is.
     */
    fun versionLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val unusable = trimmed.isEmpty() ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true)
        return if (unusable) UNKNOWN_VERSION else trimmed
    }

    /** Stands in for a missing build label — user copy, not a developer marker. */
    const val UNKNOWN_VERSION: String = "未知"
}
