package com.framemark.native.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * 打赏：**二级页面**，入口只是「关于」里的一行，点开才看到二维码。
 *
 * 之前把二维码直接铺在关于页里太直白了 —— 那一页是讲版本、隐私、许可的地方。
 * 现在关于页只有一行「支持 FrameMark」，点进去是一页只有两张码的小页面，
 * 除了「扫一扫」之外不再堆文案。
 */
private val CHANNELS: List<DonateChannel> = listOf(
    DonateChannel("微信", "wechat.png"),
    DonateChannel("支付宝", "alipay.png"),
)

data class DonateChannel(val label: String, val file: String)

object DonateText {
    const val ENTRY: String = "支持 FrameMark"
    const val TITLE: String = "支持 FrameMark"
    const val HINT: String = "扫码即可，完全自愿"
    const val BACK: String = "返回"
    const val UNAVAILABLE: String = "二维码读取失败"
}

/** 关于页里的那一行入口。 */
@Composable
fun DonateEntryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DonateText.ENTRY,
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

/** 二级页面本体：只有两张码，其它什么都不放。 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun DonatePage(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ ${DonateText.BACK}",
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = DonateText.HINT,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CHANNELS.forEach { channel ->
                DonateCodeCard(channel, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
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
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = channel.label,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            color = FmPalette.onSurfaceMuted,
        )
    }
}
