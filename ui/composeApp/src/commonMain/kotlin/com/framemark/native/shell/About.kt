package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.fmElevated

/** 一条开源许可：库名 + 许可名（+ 一句用途，可选）。 */
data class LicenseEntry(val name: String, val license: String, val purpose: String)

/**
 * 「关于 FrameMark」的内容：版本、隐私、权限、许可与版权（InkFrame 复盘第 9–10 条）。
 *
 * 手机设置页与桌面齿轮弹层共用这一支；两端只在**入口**和滚动容器上不同
 * （手机是一整页，桌面是 420 dp 宽的对话框）。文案集中在 [AboutText]，测试不用
 * 起模拟器就能核对。
 *
 * 这里刻意不写「我们承诺……」之类的空话，只写这个应用**实际**做了/没做什么：
 * Android 清单里没有任何相册、存储或网络权限；选照片走系统照片选择器；导出通过
 * 系统媒体库写入 —— 这几条都能在安装包和代码里核对。
 */
@Composable
fun AboutContent(version: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AboutCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AboutText.VERSION_ROW,
                    style = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = version,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        AboutSection(title = AboutText.PRIVACY_TITLE) {
            AboutParagraph(AboutText.PRIVACY)
        }
        AboutSection(title = AboutText.PERMISSIONS_TITLE) {
            AboutParagraph(AboutText.PERMISSIONS)
        }
        AboutSection(title = AboutText.LICENSE_TITLE) {
            AboutText.LICENSES.forEachIndexed { index, entry ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.name,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.onSurface,
                )
                Text(
                    text = entry.license,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                )
                Text(
                    text = entry.purpose,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                )
                if (index < AboutText.LICENSES.lastIndex) Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            AboutParagraph(AboutText.LICENSE_NOTE)
        }
    }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FmPalette.surface)
            .fmElevated(shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        AboutCard(content = content)
    }
}

@Composable
private fun AboutParagraph(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp),
        color = FmPalette.onSurfaceSecondary,
    )
}

/** 关于页的全部文案；纯常量，测试可直接断言。 */
object AboutText {
    const val TITLE: String = "关于 FrameMark"
    const val VERSION_ROW: String = "版本"
    const val PRIVACY_TITLE: String = "隐私"
    const val PERMISSIONS_TITLE: String = "权限"
    const val LICENSE_TITLE: String = "许可与版权"

    /**
     * 只陈述能在安装包 / 代码里核对的事实：没有网络权限、不申请相册权限、
     * 项目与外观只存本机。
     */
    const val PRIVACY: String =
        "照片、边框参数、文字、水印与 Logo 都只在这台设备上处理，应用没有联网权限，" +
            "不会上传任何内容。工程、预设和外观设置保存在本机应用目录；导出的照片写入系统相册" +
            "（桌面端写入你选择的目录）。卸载应用会删除本机工程与预设，已导出的照片不受影响。"

    const val PERMISSIONS: String =
        "FrameMark 不申请相册、存储或相机权限：选照片使用系统照片选择器，只读取你选中的文件；" +
            "导出通过系统媒体库写入「相册 / FrameMark」。桌面端只读写你明确选择的工程、预设与导出目录。"

    /** 桌面便携包 `runtime/legal` 目录里有完整的第三方许可原文。 */
    const val LICENSE_NOTE: String =
        "Windows 便携包的 runtime/legal 目录含上述组件的完整许可原文；以上为摘要，遇到冲突以原文为准。"

    /**
     * 实际依赖（`composeApp/build.gradle.kts` + Compose Multiplatform 运行时）。
     * 只列确定随应用分发的组件；测试期依赖（Konsist、kotlin-test）不面向用户，不列。
     */
    val LICENSES: List<LicenseEntry> = listOf(
        LicenseEntry("Kotlin 标准库 / kotlinx.coroutines", "Apache License 2.0", "应用逻辑、协程与并发任务"),
        LicenseEntry("Compose Multiplatform（JetBrains）", "Apache License 2.0", "Android 与 Windows 的全部界面"),
        LicenseEntry("Skiko / Skia", "Apache License 2.0 / BSD 3-Clause", "Compose 的 2D 绘制与图像编解码"),
        LicenseEntry("AndroidX Activity", "Apache License 2.0", "Android 的 Activity / 返回键与照片选择器接入"),
        LicenseEntry("JNA（Java Native Access）", "LGPL 2.1 或 Apache License 2.0（双许可）", "Windows 桌面端调用系统接口"),
        LicenseEntry("FrameMark 原生渲染引擎", "随应用分发", "Rust 实现的边框排版与导出，无第三方界面代码"),
    )
}
