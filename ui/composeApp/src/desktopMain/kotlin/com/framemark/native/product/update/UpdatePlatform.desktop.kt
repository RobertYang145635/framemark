package com.framemark.native.product.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌面端的更新检查 HTTP GET：JDK 的 HttpURLConnection，带超时与 UA。
 * 失败或非 2xx 一律返回 null，让调用方按镜像/直连候选继续尝试。
 */
actual fun fetchUrlText(url: String): String? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "FrameMark-UpdateCheck")
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()


actual fun updatePlatform(): UpdatePlatform = UpdatePlatform.Windows

/**
 * 下载 MSI -> SHA-256 -> msiexec /passive。返回 null 表示安装器已启动；
 * 否则返回原因（UI 显示并保留打开发布页兜底）。
 */
actual fun installUpdate(asset: UpdateAsset, versionName: String): String? {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "framemark-update")
    dir.mkdirs()
    val target = java.io.File(dir, "FrameMark-$versionName-win64.msi")
    return runCatching {
        java.net.URI(asset.url).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "FrameMark-Update")
        }.getInputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(asset.sha256, ignoreCase = true)) {
            target.delete()
            return "更新包校验失败（SHA-256 不符）"
        }
        ProcessBuilder("msiexec.exe", "/i", target.absolutePath, "/passive", "/norestart")
            .directory(dir)
            .start()
        null
    }.getOrElse { "无法启动安装器：${it.message ?: "未知错误"}" }
}
