package com.framemark.native

/**
 * The version this build reports in its own UI. Android reads the installed
 * package's versionName; desktop prefers the launcher's
 * -Dframemark.build.label (the precise label from version.properties) over
 * jpackage.app-version (the numeric package version), so a rebuilt release never
 * shows a stale or truncated label.
 */
expect fun appVersionLabel(): String
