package com.framemark.native.product.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * Android 的更新检查 HTTP GET。只在协程的 IO 调度器上调用；任何异常都按
 * 「这个源不可用」返回 null，由 [UpdateUrls.apiCandidates] 的候选顺序继续重试。
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
