package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framemark.native.appVersionLabel
import com.framemark.native.shell.AboutContent
import com.framemark.native.shell.AboutText
import com.framemark.native.theme.FmPalette

/**
 * 设置 → 关于 FrameMark：版本、隐私、权限、许可与版权（[AboutContent] 与桌面齿轮
 * 弹层共用同一支内容）。返回键走 [PhonePagePolicy.back] 回到设置页，不是首页。
 */
@Composable
fun PhoneAboutPage(onClose: () -> Unit) {
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
        }
    }
}
