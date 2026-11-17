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
actual fun installUpdate(asset: UpdateAsset, versionName: String, onProgress: (Long, Long) -> Unit): String? {
    val context = updateContext ?: return "当前无法获取应用上下文"
    if (!context.packageManager.canRequestPackageInstalls()) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return "已打开「安装未知应用」设置，允许后回来再点一次「下载并安装」"
    }
    val name = "FrameMark-$versionName.apk"
    val apk = File(context.cacheDir, "updates/$name")
    val part = File(apk.path + ".part")
    part.parentFile?.mkdirs()
    part.delete()
    apk.delete()

    // 直接下载：OPPO 的 DownloadManager 会把文件写成 .oplusdownload 且不更新系统
    // 状态列，轮询永远等不到完成；自己下载 + 按清单大小判断更可控。
    return runCatching {
        var downloaded = false
        var lastError = "下载失败"
        for (candidate in UpdateUrls.assetCandidates(asset.url)) {
            downloaded = runCatching {
                val existing = if (part.isFile) part.length() else 0L
                val connection = java.net.URL(candidate).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "FrameMark-Update")
                if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
                try {
                    if (connection.responseCode !in 200..299) return@runCatching false
                    val total = asset.size.takeIf { it > 0 } ?: connection.contentLengthLong
                    var done = existing
                    connection.inputStream.use { input ->
                        java.io.FileOutputStream(part, existing > 0).use { output ->
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
                    true
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { lastError = it.message ?: "下载失败"; false }
            if (downloaded) break
        }
        if (!downloaded) return lastError
        if (asset.size > 0 && part.length() != asset.size) {
            part.delete()
            return "下载文件大小不符（${part.length()} != ${asset.size}）"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(asset.sha256, ignoreCase = true)) {
            part.delete()
            return "更新包校验失败（SHA-256 不符）"
        }
        if (!part.renameTo(apk)) {
            part.copyTo(apk, overwrite = true)
            part.delete()
        }

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

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output) }
            }
            val action = "${context.packageName}.INSTALL_COMMIT"
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != action) return
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            // 系统要求用户确认：把安装器 UI 拉起来。
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx?.startActivity(confirm) }
                        }
                        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE,
                        PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED,
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                        PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE -> {
                            runCatching { ctx?.unregisterReceiver(this) }
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter(action)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context, sessionId, Intent(action).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        null
    }.getOrElse { "下载或安装失败：${it.message ?: it::class.simpleName ?: "未知错误"}" }
}

