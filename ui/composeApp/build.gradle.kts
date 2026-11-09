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

// WP-X3 §7: QA surface switch. Default `true` keeps every dev/QA build exactly as it
// was (that is what tools\run-desktop.ps1 and the three compile gates run). Passing
// `-PfmQaSurface=false` strips the verify/ and qaenv/ packages from the desktop
// compilation, which is how the packaged product is built — see desktopMain below.
val fmQaSurface = providers.gradleProperty("fmQaSurface").orNull?.toBoolean() ?: true

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
            // WP-X3 §7 (release hygiene): the desktop QA surface lives in two packages
            // that must not reach the packaged product —
            //   com/framemark/native/verify/**  the 9 file scripted verification classes
            //                                   (1969 lines, referenced by nothing)
            //   com/framemark/native/qaenv/**   the FM_* environment hooks (FM_WINDOW,
            //                                   FM_OPEN_*, FM_CONTROL_PROBE, …)
            // They are included by default so the documented dev/QA run
            // (tools\run-desktop.ps1 + RUNBOOK §5) keeps working unchanged, and a
            // release package is built with `-PfmQaSurface=false`:
            //   ./gradlew -p ui :composeApp:desktopJar -PfmQaSurface=false
            // The compiled output then contains no verify/ and no qaenv/ class (and
            // therefore none of the FM_* literals); QaSurface.hooks resolves to null,
            // so the hooks become no-ops rather than being present-but-idle.
            if (!fmQaSurface) {
                kotlin.exclude("**/com/framemark/native/verify/**", "**/com/framemark/native/qaenv/**")
            }
            dependencies {
                implementation(compose.desktop.currentOs)
                // WP-T: "follow the system appearance" (Advapi32Util registry read)
                // and the DWM dark caption. Both jars are already in the
                // E:\FrameMark\gradle-home cache; desktop-only, so the Android
                // target never sees them.
                implementation("net.java.dev.jna:jna:5.6.0")
                implementation("net.java.dev.jna:jna-platform:5.6.0")
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
                // ARCH1 (ARCH-MODULES.md §2): architecture rules are asserted with
                // Konsist instead of a hand-written import scanner, so there is exactly
                // one mechanism. The tests live in
                // src/desktopTest/kotlin/com/framemark/native/arch/*KonsistTest.kt and are
                // part of the normal desktopTest run (no separate parallel gate).
                implementation("com.lemonappdev:konsist:0.18.1")
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
        versionCode = 4
        versionName = "0.9.0-rc5"
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
            // WP-FIX1: no `versionNameSuffix`. The debug APK used to report
            // `0.9.0-rc3-dev`, while the version the package must carry is
            // `0.9.0-rc5` — `aapt2 dump badging` has to read exactly that, and the
            // 设置页 reads the same string through PackageManager. Isolation between
            // the QA build and a production install still comes from
            // `applicationIdSuffix` above, not from the version label.
        }
    }
}

// P5a packaging. One version label for every desktop artifact: jpackage/MSI only
// accept a numeric 3-part version, so the `-rc1` tag lives in the file names, in the
// build stamp written into the package, and in -Dframemark.build.label.
val framemarkPackageVersion = "0.9.0"
val framemarkVersionLabel = "0.9.0-rc5"

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
