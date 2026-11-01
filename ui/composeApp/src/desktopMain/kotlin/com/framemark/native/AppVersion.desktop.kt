package com.framemark.native

actual fun appVersionLabel(): String =
    System.getProperty("jpackage.app-version")
        ?: System.getProperty("framemark.build.label")
        ?: "dev"
