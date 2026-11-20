package com.framemark.native.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.resources.Res
import com.framemark.native.theme.FmPalette
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 反馈：**二级页面**，入口是「关于」里的一行，点开只有一张 QQ 群二维码。
 *
 * 与打赏同一个规矩：入口一行、页面一张码，不写说明文。群名与群号在码本身的卡片里，
 * 扫不动的人也能照着手打群号 —— 所以这里不再重复写一遍号码。
 */
object CommunityText {
    const val ENTRY: String = "反馈问题"
    const val BACK: String = "返回"
    const val GROUP_LABEL: String = "QQ 群"
    const val UNAVAILABLE: String = "二维码读取失败"
}

private const val QQ_GROUP_IMAGE = "files/community/qq-group.png"

/** 关于页里的那一行入口（与「支持 FrameMark」同一种行）。 */
@Composable
fun CommunityEntryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = CommunityText.ENTRY,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            color = FmPalette.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

/** 二级页面本体：返回 + 一张群二维码。 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun FeedbackPage(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            LibraryPreviewLoader.decode(Res.readBytes(QQ_GROUP_IMAGE))
        }.onSuccess { bitmap = it }.onFailure { failed = true }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ ${CommunityText.BACK}",
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        // 图本身就是一张卡片（群名 + 群号 + 码）：贴到卡片自己的边界再圆角，
        // 不要再套一层背景，否则图里的卡片色与底板色之间会露出一圈接缝。
        Column(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = CommunityText.GROUP_LABEL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
                failed -> Text(
                    text = CommunityText.UNAVAILABLE,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                )
                else -> Box(Modifier.fillMaxWidth().height(220.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = CommunityText.GROUP_LABEL,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
