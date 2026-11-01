package com.framemark.native

/**
 * The version this build reports in its own UI. Read from the package manager on
 * Android and from the jpackage stamp on desktop, so a rebuilt release never
 * shows a stale label (the 0.1.0-u6 row survived two version bumps).
 */
expect fun appVersionLabel(): String
