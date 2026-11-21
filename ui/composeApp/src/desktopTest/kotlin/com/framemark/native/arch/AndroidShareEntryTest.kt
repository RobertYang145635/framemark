package com.framemark.native.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「从相册分享进来」和「长按图标 → 从相册导入」这两条入口，靠的是 Android 侧的声明：
 * 少了 intent-filter 或者少了 shortcuts 资源，代码再对也不会出现在系统的分享面板/长按菜单里。
 * 这类声明的错误在 JVM 测试里看不出来，所以这里直接读仓库里的 manifest / 资源文件来钉住。
 */
class AndroidShareEntryTest {

    private val root: File = run {
        // 测试的工作目录是 `<repo>/ui`（Gradle 在 composeApp 的上级目录跑 desktopTest）。
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "ui/composeApp/src/androidMain/AndroidManifest.xml").isFile) {
            dir = dir.parentFile
        }
        dir
    }

    private fun read(path: String): String {
        val file = File(root, path)
        assertTrue(file.isFile, "missing $path (searched from ${root.absolutePath})")
        return file.readText()
    }

    @Test
    fun theManifestDeclaresTheShareTargets() {
        val manifest = read("ui/composeApp/src/androidMain/AndroidManifest.xml")
        assertTrue(
            manifest.contains("android.intent.action.SEND") &&
                manifest.contains("android.intent.action.SEND_MULTIPLE"),
            "SEND / SEND_MULTIPLE 两个 action 都要声明，否则单张或多张分享会缺一个入口",
        )
        assertTrue(manifest.contains("""android:mimeType="image/*""""), "只声明 image/*")
        assertTrue(
            manifest.contains("""android:launchMode="singleTop""""),
            "singleTop + onNewIntent 才能复用已在后台的实例，不会开第二个窗口",
        )
    }

    @Test
    fun theLongPressShortcutIsRegisteredAndMatchesItsHandler() {
        val manifest = read("ui/composeApp/src/androidMain/AndroidManifest.xml")
        assertTrue(
            manifest.contains("android.app.shortcuts") && manifest.contains("@xml/fm_shortcuts"),
            "manifest 要挂 android.app.shortcuts meta-data",
        )
        val shortcuts = read("ui/composeApp/src/androidMain/res/xml/fm_shortcuts.xml")
        assertTrue(shortcuts.contains("""android:shortcutId="import-from-gallery""""), "快捷方式 id")
        assertTrue(
            shortcuts.contains("""android:name="fmShortcut"""") &&
                shortcuts.contains("""android:value="import""""),
            "快捷方式的 extra 要与 MainActivity.ShareTarget 约定一致",
        )
        val handler = read("ui/composeApp/src/androidMain/kotlin/com/framemark/native/ShareTarget.kt")
        assertTrue(handler.contains("""SHORTCUT_EXTRA = "fmShortcut""""), "处理端读同一个 extra 名")
        assertTrue(handler.contains("""SHORTCUT_IMPORT = "import"""") , "处理端认同一个值")
        assertTrue(
            shortcuts.contains("@string/shortcut_import_short") &&
                shortcuts.contains("@string/shortcut_import_long"),
            "shortcut 的 label 必须是字符串资源（写中文字面量会 AAPT 报错）",
        )
    }
}
