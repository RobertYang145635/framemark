package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framemark.native.shell.PrivacyContent
import com.framemark.native.theme.FmPalette

/**
 * 设置 → 隐私与权限（独立一页）。
 *
 * 这一段正文过去藏在「隐私、权限与许可」页里，和版本号、更新、打赏、许可混在一条
 * 长滚动里，用户找「隐私」时看不到入口。现在设置页有一行显式的「隐私与权限」，
 * 内容与桌面「关于」里的同一层二级页共用 [PrivacyContent]，不各写一份文案。
 *
 * 返回：左上箭头与系统返回键都走 [PhonePagePolicy.back]（[PhonePage.Privacy] →
 * [PhonePage.Settings]）。
 */
@Composable
fun PhonePrivacyPage(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas),
    ) {
        PhoneSubPageTopBar(
            title = PhoneSettingsText.PRIVACY_PAGE_TITLE,
            onBack = onClose,
            backLabel = PhoneSettingsText.BACK_TO_SETTINGS,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrivacyContent()
        }
    }
}
