package com.framemark.native.product.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File
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


private var updateContext: Context? = null

/** MainActivity 启动时注入 applicationContext（安装 APK 需要 Context）。 */
fun installUpdateContext(context: Context) {
    updateContext = context.applicationContext
}

actual fun updatePlatform(): UpdatePlatform = UpdatePlatform.Android

/**
 * 下载 -> SHA-256 -> 签名比对 -> PackageInstaller。返回 null 表示系统安装确认页已拉起；
 * 否则返回一条可读原因，由 UI 显示并保留「打开发布页」兜底。
 */
actual fun installUpdate(asset: UpdateAsset, versionName: String): String? {
    val context = updateContext ?: return "当前无法获取应用上下文"
    if (!context.packageManager.canRequestPackageInstalls()) {
        return "请先允许 FrameMark 安装未知应用（设置 → 安装未知应用）"
    }
    val name = "FrameMark-$versionName.apk"
    val file = File(context.externalCacheDir ?: context.cacheDir, "updates/$name")
    file.parentFile?.mkdirs()
    if (file.exists() && !file.delete()) return "无法清理旧的下载文件"

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(asset.url))
        .setTitle("FrameMark $versionName")
        .setDescription("正在下载更新")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$name")
    val id = runCatching { manager.enqueue(request) }.getOrNull() ?: return "无法开始下载"
    val deadline = System.currentTimeMillis() + 5 * 60_000
    var local: File? = null
    while (System.currentTimeMillis() < deadline) {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
        cursor.use { c ->
            if (c != null && c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_FAILED -> return "下载失败"
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        local = uri?.let { File(Uri.parse(it).path ?: return@let null) }
                    }
                }
            }
        }
        if (local != null) break
        Thread.sleep(400)
    }
    val apk = local ?: return "下载超时，请稍后重试"
    if (!apk.isFile) return "下载文件不存在"
    if (asset.size > 0 && apk.length() != asset.size) return "下载文件大小不符"
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(asset.sha256, ignoreCase = true)) return "更新包校验失败（SHA-256 不符）"

    // 签名必须与当前安装包一致；镜像可以换文件，但换不了签名。
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    @Suppress("DEPRECATION")
    val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
    if (!installed.isNullOrEmpty() && !archive.isNullOrEmpty()) {
        val current = installed.map { it.toCharsString() }.toSet()
        val next = archive.map { it.toCharsString() }.toSet()
        if (current != next) return "更新包签名与当前版本不一致，已拒绝安装"
    }

    return runCatching {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output) }
            }
            val intent = Intent(context, com.framemark.native.MainActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        null
    }.getOrElse { "无法启动安装器：${it.message ?: "未知错误"}" }
}
