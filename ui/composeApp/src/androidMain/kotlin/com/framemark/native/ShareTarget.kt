package com.framemark.native

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable

/**
 * 系统「分享」进来的图片（2026-11-21）。
 *
 * 相册/文件管理器点分享时，会发 `ACTION_SEND`（单张）或 `ACTION_SEND_MULTIPLE`（多张），
 * 图片地址放在 `Intent.EXTRA_STREAM` 里；少数相册只填 `ClipData`，所以两处都要读。读到的
 * `content://` 地址由发送方授予**一次性只读权限**，用 `openInputStream` 直接读即可 ——
 * 应用不需要申请任何相册/存储权限。
 *
 * 只做「取出并去重」，不碰 IO：导入口子仍然是 MainActivity 里现成的
 * `importPickedPhotos`（与相册选择器完全同一条路径），避免第二套导入逻辑。
 */
internal object ShareTarget {

    /** 一次最多接这么多张：多选分享可能一次几十张，全收会把内存和磁盘拖垮。 */
    const val MAX_IMAGES = 20

    /** 快捷方式（长按图标）用的额外字段值；与 `res/xml/fm_shortcuts.xml` 保持一致。 */
    const val SHORTCUT_EXTRA = "fmShortcut"
    const val SHORTCUT_IMPORT = "import"

    /** 这次启动是不是「从相册导入」的快捷方式。 */
    fun isImportShortcut(intent: Intent?): Boolean =
        intent?.getStringExtra(SHORTCUT_EXTRA) == SHORTCUT_IMPORT

    /**
     * 分享进来的图片地址；不是分享 intent 或没有图片时返回空表。
     *
     * 顺序照用户选择的顺序，重复地址只留第一次（系统分享面板偶尔会把同一张放两次）。
     */
    fun imageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> Unit
            else -> return emptyList()
        }
        val out = LinkedHashSet<Uri>()
        streamUris(intent)?.let(out::addAll)
        // 少数发送方（以及 `adb shell am start -d …` 这种测试入口）只把地址放在 data 上；
        // 系统对 data / ClipData 才会真正授予读权限，所以这一路也认。
        intent.data?.let(out::add)
        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let(out::add)
            }
        }
        return out.filter { isImageLike(it) }.take(MAX_IMAGES)
    }

    private fun streamUris(intent: Intent): List<Uri>? {
        val extra = intent.extras ?: return null
        @Suppress("DEPRECATION")
        val raw: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extra.getParcelable(Intent.EXTRA_STREAM, Parcelable::class.java)
        } else {
            extra.getParcelable(Intent.EXTRA_STREAM)
        }
        return when (raw) {
            is Uri -> listOf(raw)
            is ArrayList<*> -> raw.filterIsInstance<Uri>()
            // 有的相册把地址包成 ClipData 再塞进 EXTRA_STREAM。
            is Intent -> raw.data?.let(::listOf)
            else -> null
        }
    }

    /**
     * 是不是图片。优先问 provider 的 MIME，拿不到时按扩展名兜底：分享方偶尔把
     * `content://` 报成 `application/octet-stream`，而我们自己的导入链是按内容判断的。
     */
    private fun isImageLike(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        if (scheme != "content" && scheme != "file") return false
        val path = (uri.lastPathSegment ?: uri.path).orEmpty().lowercase()
        if (path.isEmpty()) return true
        return IMAGE_EXTENSIONS.any { path.endsWith(it) } ||
            path.isEmpty() ||
            // content://media/external/images/media/123 这类没有扩展名，交给导入链判断。
            !path.contains('.')
    }

    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".tif", ".tiff", ".dng",
    )
}
