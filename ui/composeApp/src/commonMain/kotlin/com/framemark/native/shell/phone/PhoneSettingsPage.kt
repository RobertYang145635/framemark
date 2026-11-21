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
import com.framemark.native.shell.OnboardingText
import com.framemark.native.theme.AppearancePanel
import com.framemark.native.theme.AppearancePanelText
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.ThemeMode
import com.framemark.native.theme.fmElevated

/**
 * 设置页，逐条对齐演示稿 `phone.js buildSettings()`（53-73 行）与
 * `07-phone-settings.png`：52 高的返回条、外观卡（配色方案 §8 的配色 / 明暗 /
 * 画布三段）、版本卡、居中页脚。演示稿没有「状态」分组，原来的诊断行
 * 随之离场；写入错误仍然在这页最后一行露出，不吞掉失败。
 *
 * 它是一页而不是弹层：页面状态由 [com.framemark.native.shell.PhoneShell] 管，
 * 系统返回键和左上返回箭头走同一条路（[PhonePagePolicy.back]）。
 */
@Composable
fun PhoneSettingsPage(
    errorText: String? = null,
    onClose: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
) {
    val version = PhoneSettingsText.versionLabel(appVersionLabel())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas),
    ) {
        PhoneSubPageTopBar(title = PhoneSettingsText.PAGE_TITLE, onBack = onClose)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // `.set-grp`（外观）：组标题在卡里，下面是配色 / 明暗 / 画布三段。
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
                // 配色方案 §8：界面配色（四张预览卡）→ 明暗模式 → 画布背景 → 恢复默认。
                // 面板与桌面齿轮弹层是同一个组合件，所以两端文案、顺序和交互一致。
                AppearancePanel(
                    columns = 2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            // `.set-grp > .set-row.kv`（关于）：版本 + 隐私 / 权限 / 许可入口。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SettingsGroupShape)
                    .background(FmPalette.surface)
                    .fmElevated(shape = SettingsGroupShape),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(FmPalette.outline),
                )
                // 隐私与权限：独立一行。以前它藏在「隐私、权限与许可」那一页的正文里，
                // 用户找不到（WP-Q2⑥ 的同类反馈）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenPrivacy)
                        .semantics { contentDescription = PhoneSettingsText.PRIVACY_ROW }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = PhoneSettingsText.PRIVACY_ROW,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "›",
                        style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(FmPalette.outline),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenAbout)
                        .semantics { contentDescription = PhoneSettingsText.ABOUT_ROW }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = PhoneSettingsText.ABOUT_ROW,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "›",
                        style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                // 新手教程：与桌面齿轮弹层同一行入口。它是一层浮层，不改页面栈，
                // 所以这里只是一个回调，页面本身不新增 PhonePage。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(FmPalette.outline),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenOnboarding)
                        .semantics { contentDescription = PhoneSettingsText.ONBOARDING_ROW }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = PhoneSettingsText.ONBOARDING_ROW,
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                            color = FmPalette.onSurface,
                        )
                        Text(
                            text = PhoneSettingsText.ONBOARDING_HINT,
                            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                            color = FmPalette.onSurfaceMuted,
                        )
                    }
                    Text(
                        text = "›",
                        style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
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

/** `.set-top`：52 高、左上返回箭头（32×32）+ 17/700 标题；设置页与关于页共用。 */
@Composable
internal fun PhoneSubPageTopBar(
    title: String,
    onBack: () -> Unit,
    backLabel: String = PhoneSettingsText.BACK_LABEL,
) {
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
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            PhoneBackGlyph(tint = FmPalette.onSurfaceSecondary)
        }
        Text(
            text = title,
            style = TextStyle(
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = FmPalette.onSurface,
        )
    }
}

object PhoneSettingsText {
    const val PAGE_TITLE: String = "设置"
    const val BACK_LABEL: String = "返回首页"
    const val BACK_TO_SETTINGS: String = "返回设置"
    const val ABOUT_ROW: String = "关于与许可"
    const val PRIVACY_ROW: String = "隐私与权限"
    const val PRIVACY_PAGE_TITLE: String = "隐私与权限"

    /**
     * 关于那一组里的第三行：新手教程。名字与提示都直接取教程自己的文案表
     * （[OnboardingText]），设置页不再存一份字面量。
     */
    const val ONBOARDING_ROW: String = OnboardingText.ENTRY
    const val ONBOARDING_HINT: String = OnboardingText.ENTRY_HINT

    /** 第一组的组标题（`.set-h`）。 */
    const val GROUP_APPEARANCE: String = "外观"

    /** 第二组：`.set-row.kv`。 */
    const val ROW_VERSION: String = "版本"

    /**
     * `.set-foot`。演示稿这里写的是「演示稿的设置页：只有外观和版本号」——那是
     * 设计稿对自己的说明，产品里不能印，换成这条真实的落地说明（同一处、同字号、
     * 同样居中）。
     */
    const val FOOT_NOTE: String = "外观只保存在这台设备，不写入项目和预设。"

    /**
     * 明暗三档的顺序与用词：设置页不再自己维护一份，直接读外观面板的文案表，
     * 免得两处标签慢慢分叉（配色方案 §8.1 的顺序是 跟随系统 / 浅色 / 深色）。
     */
    val MODE_ORDER: List<ThemeMode> get() = AppearancePanelText.MODE_ORDER

    fun themeLabel(mode: ThemeMode): String = AppearancePanelText.modeLabel(mode)

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
