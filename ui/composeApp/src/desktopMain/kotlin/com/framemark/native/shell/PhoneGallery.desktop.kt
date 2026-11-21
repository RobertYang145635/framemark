package com.framemark.native.shell

import androidx.compose.runtime.Composable

/** 桌面端没有自建相册：桌面入口本来就有一整块工作区与文件选择器。 */
@Composable
actual fun PhoneGalleryOverlay(
    visible: Boolean,
    onCancel: () -> Unit,
    onImport: (List<String>) -> Unit,
) = Unit

actual fun phoneGallerySupported(): Boolean = false
