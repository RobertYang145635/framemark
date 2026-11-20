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
actual fun installUpdate(asset: UpdateAsset, versionName: String, onProgress: (Long, Long) -> Unit): String? {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "framemark-update")
    dir.mkdirs()
    val target = java.io.File(dir, "FrameMark-$versionName-win64.msi")
    // 逐个候选源下载（清单里的镜像 → 加速前缀 → 直连），每个都校验 SHA-256：
    // 直连 github.com 在国内实测只有 ~18 KB/s，以前这里只认 asset.url，所以「下载很慢」。
    var lastError = "更新包下载失败"
    for (candidate in UpdateUrls.assetCandidates(asset.url, asset.mirrors)) {
        val downloaded = runCatching {
            target.delete()
            java.net.URI(candidate).toURL().openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "FrameMark-Update")
            }.getInputStream().use { input ->
                val total = asset.size.takeIf { it > 0 } ?: input.available().toLong()
                var done = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
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
                error("校验失败（SHA-256 不符）")
            }
            true
        }.getOrElse {
            lastError = "${candidate.substringAfter("//").substringBefore("/")}: ${it.message ?: "下载失败"}"
            false
        }
        if (downloaded) {
            return runCatching {
                ProcessBuilder("msiexec.exe", "/i", target.absolutePath, "/passive", "/norestart")
                    .directory(dir)
                    .start()
                null
            }.getOrElse { "无法启动安装器：${it.message ?: "未知错误"}" }
        }
    }
    target.delete()
    return lastError
}
