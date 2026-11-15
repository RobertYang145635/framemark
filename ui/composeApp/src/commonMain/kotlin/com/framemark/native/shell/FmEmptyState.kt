package com.framemark.native.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmType

/**
 * 统一的空状态：一行标题 + 一句解释 + 可选动作（2026-11-15）。
 *
 * 之前每个空列表各写一段：桌面左栏是 `titleSmall` + `labelSmall`，手机预设是
 * `SheetNote`，水印/文字页又是另一套字号与间距。这里收成一处，横向居中、标题与
 * 说明 6 dp、正文最多两行——放得进左栏 280 dp、手机底板和弹窗三种宽度。
 *
 * 调用方负责容器（`fillMaxSize` 居中或 `fillMaxWidth` 内嵌）；这里只画内容，
 * 所以同一个空态在整页、列表和弹层里看起来一致。
 */
@Composable
fun FmEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = FmType.titleSmall,
            color = FmPalette.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = FmPalette.onSurfaceMuted,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}
