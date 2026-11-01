import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val framemarkQaSuffix = providers.gradleProperty("framemarkQaSuffix").orNull ?: ".dev"
require(Regex("\\.[A-Za-z][A-Za-z0-9_.]*").matches(framemarkQaSuffix)) {
    "framemarkQaSuffix must be a dot-prefixed Android application ID suffix"
}
val framemarkQaReleaseSuffix = providers.gradleProperty("framemarkQaReleaseSuffix").orNull
if (framemarkQaReleaseSuffix != null) {
    require(Regex("\\.[A-Za-z][A-Za-z0-9_.]*").matches(framemarkQaReleaseSuffix)) {
        "framemarkQaReleaseSuffix must be a dot-prefixed Android application ID suffix"
    }
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // U2: bundled E1a library data + 175 preview webp files live under
                // composeResources/files. The runtime is not pulled in by
                // compose.runtime, so ask for it explicitly (same plugin version).
                implementation(compose.components.resources)
                // Compose Multiplatform common BackHandler. compose.ui lists it in
                // its module metadata but Gradle does not put it on our compile
                // classpath; same version as the plugin (1.9.0), no range hacks.
                implementation("org.jetbrains.compose.ui:ui-backhandler:1.9.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
            }
        }
        // P1a product-core tests. No Compose/engine dependency on purpose: the
        // product package is plain Kotlin so both targets compile it unchanged.
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    // Kotlin package and applicationId stay com.framemark.native as specified.
    // AGP validates the R-class namespace as a *Java* package, and `native` is a
    // Java keyword, so the namespace is one segment shorter than the app id.
    namespace = "com.framemark"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.framemark.native"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.9.0-rc3"
        // The Android renderer is currently complete for arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        val nativePrebuiltRoot = providers.environmentVariable("FM_ANDROID_PREBUILT_ROOT").orNull
            ?: file("src/androidMain/cpp/prebuilt/arm64-v8a").absolutePath
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DFM_PREBUILT_ROOT=$nativePrebuiltRoot",
                )
            }
        }
    }

    buildFeatures {
        // MainActivity's evidence-only Intent hooks are guarded by BuildConfig.DEBUG.
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (framemarkQaReleaseSuffix != null) {
                // Device-only validation: isolated app id and Android debug key.
                // This artifact is not a production release and must not be published.
                applicationIdSuffix = framemarkQaReleaseSuffix
                versionNameSuffix = "-qa"
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        // Device evidence builds coexist with the installed release package
        // (com.framemark.native) instead of replacing it. Automation can choose
        // a unique suffix, for example -PframemarkQaSuffix=.qa20261102.
        getByName("debug") {
            applicationIdSuffix = framemarkQaSuffix
            versionNameSuffix = "-dev"
        }
    }
}

// P5a packaging. One version label for every desktop artifact: jpackage/MSI only
// accept a numeric 3-part version, so the `-rc1` tag lives in the file names, in the
// build stamp written into the package, and in -Dframemark.build.label.
val framemarkPackageVersion = "0.9.0"
val framemarkVersionLabel = "0.9.0-rc3"

compose.desktop {
    application {
        mainClass = "com.framemark.native.MainKt"
        nativeDistributions {
            packageName = "FrameMark"
            packageVersion = framemarkPackageVersion
            vendor = "FrameMark"
            description = "FrameMark 边框与水印工作台"
            windows {
                dirChooser = true
                perUserInstall = true
                menu = true
                shortcut = true
                console = false
            }
        }
    }
}

// The packaging script reads this instead of carrying its own copy of the version,
// so a ZIP, an MSI and the running app can never disagree.
tasks.register("writePackageVersionStamp") {
    val out = layout.buildDirectory.file("framemark-package-version.json")
    doLast {
        out.get().asFile.parentFile.mkdirs()
        out.get().asFile.writeText(
            """{"packageVersion":"$framemarkPackageVersion","label":"$framemarkVersionLabel","packageName":"FrameMark"}""" + "\n",
        )
        println("framemark version stamp: " + out.get().asFile.absolutePath)
    }
}

compose.resources {
    // Fixed package so desktopMain/androidMain and evidence tooling can import Res.
    publicResClass = true
    packageOfResClass = "com.framemark.native.resources"
}

// Local smoke helper: dump the desktop runtime classpath so the window can be
// launched with a plain `java -cp` (no jpackage/WiX needed for local runs).
// U2: composeResources (data + 175 previews) are processed by desktopProcessResources
// into build/processedResources/desktop/main, which must be on the JVM classpath
// too — otherwise Res.readBytes cannot see the bundled files.
tasks.register("dumpDesktopClasspath") {
    dependsOn("desktopProcessResources")
    val cfg = configurations.named("desktopRuntimeClasspath")
    val out = layout.projectDirectory.file("build/desktop-classpath.txt")
    doLast {
        out.asFile.parentFile.mkdirs()
        val classes = layout.buildDirectory.dir("classes/kotlin/desktop/main").get().asFile.absolutePath
        val resources = layout.buildDirectory.dir("processedResources/desktop/main").get().asFile.absolutePath
        out.asFile.writeText(classes + File.pathSeparator + resources + File.pathSeparator + cfg.get().asPath)
        println("classpath entries: " + (cfg.get().files.size + 2))
    }
}
