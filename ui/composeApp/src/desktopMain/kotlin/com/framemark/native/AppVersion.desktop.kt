package com.framemark.native

actual fun appVersionLabel(): String =
    // The packaged launcher passes -Dframemark.build.label with the precise release
    // label from version.properties (e.g. 0.9.0-rc10). jpackage also sets
    // jpackage.app-version to the numeric packageVersion (0.9.0), so the label must
    // win; jpackage.app-version is only a fallback for launchers built without it.
    System.getProperty("framemark.build.label")
        ?: System.getProperty("jpackage.app-version")
        ?: "dev"
