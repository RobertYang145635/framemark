package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.appVersionLabel
import com.framemark.native.shell.AboutContent
import com.framemark.native.shell.AboutText
import com.framemark.native.shell.OnboardingText
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.fmElevated

/**
 * 设置 → 关于 FrameMark：版本、隐私、权限、许可与版权（[AboutContent] 与桌面齿轮
 * 弹层共用同一支内容）。返回键走 [PhonePagePolicy.back] 回到设置页，不是首页。
 *
 * 页面最后的「新手教程」行是第二个入口（设置页那一行是第一个）：关于页是用户
 * 找不到某个功能时会来的地方，教程放在这里，位置与「隐私 / 权限 / 许可」同一支
 * 纵向流里，不新增 [PhonePage]，点开仍是一层覆盖。
 */
@Composable
fun PhoneAboutPage(
    onClose: () -> Unit,
    onOpenOnboarding: (() -> Unit)? = null,
) {
    val version = PhoneSettingsText.versionLabel(appVersionLabel())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas),
    ) {
        PhoneSubPageTopBar(
            title = AboutText.TITLE,
            onBack = onClose,
            backLabel = PhoneSettingsText.BACK_TO_SETTINGS,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
        ) {
            AboutContent(version = version)
            if (onOpenOnboarding != null) {
                Spacer(Modifier.height(14.dp))
                OnboardingRow(onOpen = onOpenOnboarding)
            }
        }
    }
}

/** 与设置页「关于」组同形的行：标题 + 一句提示 + `›`。 */
@Composable
private fun OnboardingRow(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FmPalette.surface)
            .fmElevated(shape = shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = OnboardingText.ENTRY }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = OnboardingText.ENTRY,
                style = TextStyle(
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = FmPalette.onSurface,
            )
            Text(
                text = OnboardingText.REOPEN_HINT,
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
