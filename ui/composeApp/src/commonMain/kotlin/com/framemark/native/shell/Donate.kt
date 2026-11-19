package com.framemark.native.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.theme.FmPalette
import com.framemark.native.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 打赏页：微信 / 支付宝两张收款码，只读图片，不做任何支付集成 —— 用户自己扫码。
 *
 * 图片放在 `composeResources/files/donate/`，用 [Res.readBytes] 读（两端同一份）。
 * 二维码是静态资源，不需要联网；解码失败时只显示文字说明，不抛异常（打赏不是核心路径，
 * 一个坏图不该让「关于」页崩掉）。
 */
private val CHANNELS: List<DonateChannel> = listOf(
    DonateChannel("微信支付", "wechat.png", "打开微信 → 扫一扫"),
    DonateChannel("支付宝", "alipay.png", "打开支付宝 → 扫一扫"),
)

data class DonateChannel(val label: String, val file: String, val hint: String)

object DonateText {
    const val TITLE: String = "支持 FrameMark"
    const val BODY: String =
        "FrameMark 是免费开源的。如果它帮到了你，可以扫码请我喝杯咖啡 —— 完全自愿，打赏不影响任何功能。"
    const val THANKS: String = "感谢每一位使用、反馈和分享的朋友。"
    const val UNAVAILABLE: String = "二维码读取失败；请在发布页联系作者。"
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun DonateSection(modifier: Modifier = Modifier) {
    AboutSection(title = DonateText.TITLE, modifier = modifier) {
        Text(
            text = DonateText.BODY,
            style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CHANNELS.forEach { channel ->
                DonateCodeCard(channel, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = DonateText.THANKS,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun DonateCodeCard(channel: DonateChannel, modifier: Modifier = Modifier) {
    var bitmap by remember(channel.file) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(channel.file) { mutableStateOf(false) }
    LaunchedEffect(channel.file) {
        runCatching {
            val bytes = Res.readBytes("files/donate/${channel.file}")
            LibraryPreviewLoader.decode(bytes)
        }.onSuccess { bitmap = it }.onFailure { failed = true }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FmPalette.surfaceMuted)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = channel.label,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            color = FmPalette.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = "${channel.label}收款码",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(FmPalette.canvas),
                contentScale = ContentScale.Fit,
            )
            failed -> Text(
                text = DonateText.UNAVAILABLE,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = FmPalette.onSurfaceMuted,
            )
            else -> Spacer(Modifier.fillMaxWidth().height(120.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = channel.hint,
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}
