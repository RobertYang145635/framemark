package com.framemark.native

import android.content.Context

private var installedLabel: String? = null

/** Called once from the activity with the real package version. */
fun installAppVersion(context: Context) {
    installedLabel = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
}

actual fun appVersionLabel(): String = installedLabel ?: "dev"
