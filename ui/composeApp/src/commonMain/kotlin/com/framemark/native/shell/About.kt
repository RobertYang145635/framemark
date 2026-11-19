package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.product.FmLog
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.fmElevated
import com.framemark.native.product.update.UpdateAsset
import com.framemark.native.product.update.UpdateJson
import com.framemark.native.product.update.UpdateManifest
import com.framemark.native.product.update.UpdateManifestJson
import com.framemark.native.product.update.installUpdate
import com.framemark.native.product.update.updatePlatform
import com.framemark.native.product.update.UpdateRelease
import com.framemark.native.product.update.UpdateSource
import com.framemark.native.product.update.UpdateUrls
import com.framemark.native.product.update.UpdateVersion
import com.framemark.native.product.update.fetchUrlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        UpdateCard(currentVersion = version)
        DonateSection()
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

/**
 * 「检查更新」卡片（2026-11-16）：手动检查 GitHub Releases（含预发布），
 * 可选直连或国内镜像。只读取版本元数据，不上传任何本机内容。
 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data object Failed : UpdateState
    data class Downloading(val fraction: Float = 0f) : UpdateState
    data class Result(val message: String, val error: Boolean) : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class AvailableManifest(val manifest: UpdateManifest, val asset: UpdateAsset?) : UpdateState
}

@Composable
private fun UpdateCard(currentVersion: String) {
    var source by remember { mutableStateOf(UpdateSource.Mirror) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun check() {
        if (state == UpdateState.Checking) return
        state = UpdateState.Checking
        scope.launch {
            val manifest = withContext(Dispatchers.IO) {
                UpdateUrls.manifestCandidates(source).firstNotNullOfOrNull { url ->
                    fetchUrlText(url)?.let { UpdateManifestJson.parse(it) }
                }
            }
            val platform = updatePlatform()
            val manifestNewer = manifest?.takeIf {
                UpdateVersion.isNewer(currentVersion, it.versionName)
            }
            if (manifestNewer != null) {
                state = UpdateState.AvailableManifest(manifest = manifestNewer, asset = manifestNewer.assetFor(platform))
                FmLog.info("UPDATE_CHECK") {
                    "source=${source.name} manifest=${manifestNewer.versionCode} asset=${manifestNewer.assetFor(platform) != null}"
                }
                return@launch
            }
            val candidates = UpdateUrls.apiCandidates(source)
            val releases = withContext(Dispatchers.IO) {
                candidates.firstNotNullOfOrNull { url ->
                    fetchUrlText(url)?.let { body -> UpdateJson.releases(body) }
                }
            }
            state = when {
                releases == null -> UpdateState.Failed
                else -> UpdateVersion.newestAvailable(currentVersion, releases)
                    ?.let { UpdateState.Available(it) }
                    ?: UpdateState.Latest
            }
            FmLog.info("UPDATE_CHECK") {
                "source=${source.name} candidates=${candidates.size} state=$state"
            }
        }
    }

    AboutCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AboutText.UPDATE_TITLE,
                style = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                color = FmPalette.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = AboutText.UPDATE_CHECK,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                color = if (state == UpdateState.Checking) FmPalette.onSurfaceMuted else FmPalette.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = state != UpdateState.Checking) { check() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AboutText.UPDATE_SOURCE,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.weight(1f))
            UpdateSourceChip(
                label = UpdateSource.GitHub.label,
                selected = source == UpdateSource.GitHub,
                onClick = { source = UpdateSource.GitHub },
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            UpdateSourceChip(
                label = UpdateSource.Mirror.label,
                selected = source == UpdateSource.Mirror,
                onClick = { source = UpdateSource.Mirror },
            )
        }
        when (val value = state) {
            UpdateState.Idle -> AboutUpdateLine(AboutText.UPDATE_NOTE)
            UpdateState.Checking -> AboutUpdateLine(AboutText.UPDATE_CHECKING)
            UpdateState.Latest -> AboutUpdateLine("${AboutText.UPDATE_LATEST}（$currentVersion）")
            UpdateState.Failed -> AboutUpdateLine(AboutText.UPDATE_FAILED, error = true)
            is UpdateState.Downloading -> AboutUpdateLine(
                AboutText.UPDATE_DOWNLOADING + " " + (value.fraction * 100).toInt() + "%",
            )
            is UpdateState.Result -> AboutUpdateLine(value.message, error = value.error)
            is UpdateState.AvailableManifest -> {
                Text(
                    text = "${AboutText.UPDATE_AVAILABLE} ${value.manifest.versionName}" +
                        if (value.manifest.isPrerelease) AboutText.UPDATE_PRERELEASE else "",
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.onSurface,
                )
                val notes = value.manifest.notes.lineSequence().firstOrNull { it.isNotBlank() }
                if (!notes.isNullOrBlank()) AboutUpdateLine(notes)
                if (value.asset != null) {
                    Text(
                        text = AboutText.UPDATE_INSTALL,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        color = FmPalette.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (state !is UpdateState.Downloading) {
                                    state = UpdateState.Downloading(0f)
                                    scope.launch {
                                        val error = withContext(Dispatchers.IO) {
                                            installUpdate(value.asset, value.manifest.versionName) { done, total ->
                                                if (total > 0) {
                                                    val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                                                    scope.launch { state = UpdateState.Downloading(fraction) }
                                                }
                                            }
                                        }
                                        state = UpdateState.Result(
                                            message = error ?: AboutText.UPDATE_INSTALL_STARTED,
                                            error = error != null,
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = AboutText.UPDATE_OPEN,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri(UpdateUrls.releasePage(UpdateUrls.RELEASE_PAGE, source)) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            is UpdateState.Available -> {
                val tag = value.release.tag
                val suffix = if (value.release.prerelease) AboutText.UPDATE_PRERELEASE else ""
                Text(
                    text = "${AboutText.UPDATE_AVAILABLE} $tag$suffix",
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.onSurface,
                )
                Text(
                    text = AboutText.UPDATE_OPEN,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = FmPalette.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            uriHandler.openUri(UpdateUrls.releasePage(value.release.url, source))
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateSourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        color = if (selected) FmPalette.primary else FmPalette.onSurfaceSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) FmPalette.primary.copy(alpha = 0.12f) else FmPalette.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun AboutUpdateLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
        color = if (error) FmPalette.error else FmPalette.onSurfaceMuted,
    )
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
internal fun AboutSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
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
    const val UPDATE_TITLE: String = "检查更新"
    const val UPDATE_SOURCE: String = "更新源"
    const val UPDATE_CHECK: String = "检查"
    const val UPDATE_CHECKING: String = "正在检查…"
    const val UPDATE_LATEST: String = "已是最新版本"
    const val UPDATE_AVAILABLE: String = "发现新版本"
    const val UPDATE_PRERELEASE: String = "（预发布）"
    const val UPDATE_OPEN: String = "打开发布页"
    const val UPDATE_FAILED: String = "检查失败：连不上更新源，稍后再试或切换更新源。"
    const val UPDATE_DOWNLOADING: String = "正在下载更新…"
    const val UPDATE_INSTALL: String = "下载并安装"
    const val UPDATE_INSTALL_STARTED: String = "已开始安装；完成后按系统提示重启应用。"
    const val UPDATE_NOTE: String = "联网只用于读取发布版本号，不上传照片或工程。"
    const val PRIVACY_TITLE: String = "隐私"
    const val PERMISSIONS_TITLE: String = "权限"
    const val LICENSE_TITLE: String = "许可与版权"

    /**
     * 只陈述能在安装包 / 代码里核对的事实：联网权限仅用于检查更新；不申请相册权限；
     * 项目与外观只存本机。
     */
    const val PRIVACY: String =
        "照片、边框参数、文字、水印与 Logo 都只在这台设备上处理；联网只用于「检查更新」读取 " +
            "GitHub Releases 的版本信息（可切换国内镜像），不会上传任何照片、工程或使用数据。" +
            "工程、预设和外观设置保存在本机应用目录；导出的照片写入系统相册" +
            "（桌面端写入你选择的目录）。卸载应用会删除本机工程与预设，已导出的照片不受影响。"

    const val PERMISSIONS: String =
        "FrameMark 不申请相册、存储或相机权限：选照片使用系统照片选择器，只读取你选中的文件；" +
            "导出通过系统媒体库写入「相册 / FrameMark」。唯一的网络权限用于检查更新，只请求发布版本信息，" +
            "不访问其它地址。桌面端只读写你明确选择的工程、预设与导出目录。"

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
