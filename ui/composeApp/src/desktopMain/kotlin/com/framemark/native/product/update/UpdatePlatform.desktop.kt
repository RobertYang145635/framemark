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
