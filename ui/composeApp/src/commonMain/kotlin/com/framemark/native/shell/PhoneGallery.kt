package com.framemark.native.shell

import androidx.compose.runtime.Composable

/**
 * 自建相册（2026-11-21）：应用内直接浏览手机照片并多选，不经过系统选择器。
 *
 * 为什么要有：系统选择器在 ColorOS 上把多选上限压到 9 张、外观也不受控；自建相册可以
 * 让用户按相册分组浏览、按点选顺序导入，并且和「分享到 FrameMark」互为补充。
 *
 * 分布：
 *  - Android 有真实实现（`PhoneGallery.android.kt`：MediaStore 查询 + loadThumbnail +
 *    READ_MEDIA_IMAGES 权限 + 分页）；
 *  - 桌面端没有（真正的桌面入口走文件选择器），actual 是空实现，靠
 *    [phoneGallerySupported] 决定是否把它当作入口。
 */
@Composable
expect fun PhoneGalleryOverlay(
    visible: Boolean,
    onCancel: () -> Unit,
    onImport: (List<String>) -> Unit,
)

/** 这个平台有没有自建相册；没有时按钮回退到系统选择器。 */
expect fun phoneGallerySupported(): Boolean
