package com.framemark.native.product.update

/**
 * 更新清单（`updates/latest.json`，随 Release 更新）。
 *
 * 用整数 [versionCode] 比较版本，避免 `0.9.0-rc13` 这种字符串在客户端各写一套解析；
 * 资产按平台拆开，镜像只需要代理同一个 JSON。
 */
data class UpdateAsset(
    val url: String,
    val sha256: String,
    val size: Long,
    /** 清单里的资产镜像（完整 URL）；下载时优先于内置加速前缀。 */
    val mirrors: List<String> = emptyList(),
)

data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val channel: String,
    val minSupportedVersionCode: Long,
    val notes: String,
    val android: UpdateAsset?,
    val windows: UpdateAsset?,
    /**
     * 可选：资产镜像（完整 URL，优先于内置加速前缀）。
     *
     * 有了它，换下载渠道只要改这份 JSON（例如以后挂到国内对象存储），不用再发版去
     * 改客户端的镜像表。
     */
    val mirrors: List<String> = emptyList(),
) {
    val isPrerelease: Boolean get() = channel.equals("rc", ignoreCase = true) ||
        channel.equals("beta", ignoreCase = true)

    fun assetFor(platform: UpdatePlatform): UpdateAsset? = when (platform) {
        UpdatePlatform.Android -> android
        UpdatePlatform.Windows -> windows
    }
}

enum class UpdatePlatform { Android, Windows }

/** 解析 `latest.json`；缺失必填字段或 JSON 损坏时返回 null（调用方回退 GitHub API）。 */
object UpdateManifestJson {
    fun parse(text: String): UpdateManifest? {
        val versionName = string(text, "versionName") ?: return null
        val versionCode = number(text, "versionCode") ?: return null
        val mirrors = stringArray(text, "mirrors")
        return UpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            channel = string(text, "channel") ?: "stable",
            minSupportedVersionCode = number(text, "minSupportedVersionCode") ?: 0L,
            notes = string(text, "notes").orEmpty(),
            android = asset(text, "android", mirrors),
            windows = asset(text, "windows", mirrors),
            mirrors = mirrors,
        )
    }

    /**
     * 顶层的字符串数组（`"mirrors": ["https://…", …]`）；没有就是空表。
     *
     * 手写扫描而不是正则：这个文件里的 JSON 都是自己写出来的稳定形状，按引号切分足够，
     * 也避免正则里的字符类在 Kotlin 字符串里再转义一层（出过错）。
     */
    private fun stringArray(text: String, key: String): List<String> {
        val at = text.indexOf("\"$key\"")
        if (at < 0) return emptyList()
        val open = text.indexOf('[', at)
        if (open < 0) return emptyList()
        val close = text.indexOf(']', open)
        if (close < 0) return emptyList()
        val out = mutableListOf<String>()
        var i = open + 1
        while (i < close) {
            val quote = text.indexOf('"', i)
            if (quote < 0 || quote >= close) break
            val value = StringBuilder()
            i = quote + 1
            while (i < close) {
                val c = text[i]
                if (c == '\\' && i + 1 < close) {
                    value.append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    break
                }
                value.append(c)
                i++
            }
            val item = value.toString().trim()
            if (item.isNotEmpty()) out += item
        }
        return out
    }

    private fun asset(text: String, key: String, mirrors: List<String>): UpdateAsset? {
        val at = text.indexOf("\"$key\"")
        if (at < 0) return null
        val start = text.indexOf('{', at)
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i + 1; break }
                }
            }
        }
        if (end < 0) return null
        val chunk = text.substring(start, end)
        val url = string(chunk, "url") ?: return null
        val sha = string(chunk, "sha256") ?: return null
        return UpdateAsset(
            url = url,
            sha256 = sha,
            size = number(chunk, "size") ?: 0L,
            mirrors = mirrors,
        )
    }

    private fun string(text: String, key: String): String? {
        val marker = "\"$key\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val colon = text.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        val hex = text.substring(i + 2, minOf(i + 6, text.length))
                        hex.toIntOrNull(16)?.let(out::append)
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            } else if (c == '"') {
                return out.toString()
            } else {
                out.append(c); i++
            }
        }
        return null
    }

    private fun number(text: String, key: String): Long? {
        val marker = "\"$key\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val colon = text.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] == '-')) i++
        return text.substring(start, i).toLongOrNull()
    }
}

/** 当前平台，用于选择清单里的资产。 */
expect fun updatePlatform(): UpdatePlatform

/**
 * 下载并安装更新。返回 null 表示已交给系统/安装器；否则返回一句给用户看的原因。
 * Android 用 PackageInstaller，Windows 调 MSI；两者都在 IO 线程调用。
 */
expect fun installUpdate(
    asset: UpdateAsset,
    versionName: String,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
): String?
