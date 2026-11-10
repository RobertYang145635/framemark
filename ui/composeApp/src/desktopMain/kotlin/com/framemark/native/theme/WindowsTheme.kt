package com.framemark.native.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import java.io.File
import com.framemark.native.product.FmLog

/**
 * Windows appearance wiring (desktop only).
 *
 * Two independent jobs:
 *  1. [WindowsSystemTheme] reads "跟随系统" from
 *     `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`
 *     (`0` = dark, `1` = light). The read layer is a [WindowsThemeReader] so the
 *     tests never touch the real registry, and `FM_FAKE_SYSTEM_DARK_FILE` swaps
 *     the registry for a file during a sandboxed "follow the system" smoke run.
 *  2. [WindowsTitleBar] paints the caption of the `ComposeWindow` dark by calling
 *     `dwmapi.dll!DwmSetWindowAttribute` and then forces a non-client redraw with
 *     `SetWindowPos(SWP_FRAMECHANGED)`.
 *
 * Everything is fail-soft: a missing JNA, a missing registry value, a
 * non-Windows host or an unsupported DWM attribute only produces one log line,
 * never an exception and never a reason to stop launching.
 */

/** Replaceable `AppsUseLightTheme` read layer; `null` means "could not read". */
fun interface WindowsThemeReader {
    fun readAppsUseLightTheme(): Int?
}

/** `true` when this JVM runs on Windows. Replaceable so a test can pin it. */
fun osIsWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Resolves the Windows light/dark setting.
 *
 * Order: the `FM_FAKE_SYSTEM_DARK_FILE` override (sandbox QA) wins; otherwise the
 * registry, and only on Windows. Anything unreadable resolves to **light**, which
 * is the pre-existing appearance, so a broken read can never accidentally put the
 * app in a mode the user did not ask for.
 */
class WindowsSystemTheme(
    private val reader: WindowsThemeReader = registryThemeReader(),
    private val fakeSystemDarkFile: File? = fakeSystemDarkFileFromEnv(),
    private val windows: Boolean = osIsWindows(),
) {
    /** Where the last [isDark] answer came from, for the launch log. */
    var lastSource: String = "none"
        private set

    private var warned = false

    fun isDark(): Boolean {
        val fake = fakeSystemDarkFile
        if (fake != null) {
            val text = try {
                if (fake.isFile) fake.readText().trim() else null
            } catch (t: Throwable) {
                warnOnce("FM_APPEARANCE_FAKE_READ_FAIL ${fake.absolutePath} ${t.message}")
                null
            }
            return when (text) {
                "1" -> {
                    lastSource = "fake:${fake.name}=1"
                    true
                }
                "0" -> {
                    lastSource = "fake:${fake.name}=0"
                    false
                }
                else -> {
                    warnOnce("FM_APPEARANCE_FAKE_BAD_VALUE ${fake.absolutePath} value=${text ?: "-"} -> light")
                    lastSource = "fake:invalid"
                    false
                }
            }
        }
        if (!windows) {
            lastSource = "non-windows"
            return false
        }
        val value = try {
            reader.readAppsUseLightTheme()
        } catch (t: Throwable) {
            warnOnce("FM_APPEARANCE_REGISTRY_FAIL ${t::class.simpleName}: ${t.message}")
            null
        }
        return when (value) {
            0 -> {
                lastSource = "registry:0"
                true
            }
            1 -> {
                lastSource = "registry:1"
                false
            }
            else -> {
                warnOnce("FM_APPEARANCE_REGISTRY_UNAVAILABLE value=${value ?: "-"} -> light")
                lastSource = "registry:none"
                false
            }
        }
    }

    private fun warnOnce(message: String) {
        if (warned) return
        warned = true
        println(message)
    }

    companion object {
        const val REGISTRY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
        const val VALUE_NAME = "AppsUseLightTheme"

        // WP-X3 §7: the sandbox override is a QA hook, so it now comes through the
        // QA-only surface; a packaged product (no qaenv source) always reads the
        // registry because QaSurface.fakeSystemDarkFile() is null there.
        fun fakeSystemDarkFileFromEnv(): File? =
            com.framemark.native.qa.QaSurface.fakeSystemDarkFile()?.let { File(it) }
    }
}

/**
 * The real registry reader. `jna-platform` is only on the desktop classpath, so a
 * JNA link failure surfaces as a `Throwable` here and is handled by
 * [WindowsSystemTheme] as "unavailable".
 */
fun registryThemeReader(): WindowsThemeReader = WindowsThemeReader {
    Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        WindowsSystemTheme.REGISTRY_PATH,
        WindowsSystemTheme.VALUE_NAME,
    )
}

/**
 * Polls the system dark flag on a daemon thread.
 *
 * The task brief requires an update within 5 s of the OS switching, and the app
 * may not be focused while that happens, so the poll runs regardless of focus and
 * [poke] (a window focus event) simply forces an extra read between ticks.
 */
class WindowsSystemThemeWatcher(
    private val source: WindowsSystemTheme,
    private val onDark: (Boolean) -> Unit,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    @Volatile
    private var running = false

    private val thread = Thread({
        while (running) {
            try {
                onDark(source.isDark())
            } catch (_: Throwable) {
                // Never let a poll kill the watcher.
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }, "fm-appearance-watch").apply { isDaemon = true }

    fun start() {
        if (running) return
        running = true
        thread.start()
    }

    /** Immediate read, e.g. on window focus. */
    fun poke() {
        try {
            onDark(source.isDark())
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    companion object {
        /** 5 s, per the task brief (§4.3.1). */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}

/**
 * Dark caption for the native Windows frame.
 *
 * `DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1)` on Windows 10
 * 2004+, attribute 19 on the builds before that (the value moved from 19 to 20);
 * both failing leaves the system default caption in place with one log line.
 * `SetWindowPos(SWP_FRAMECHANGED)` is required because DWM caches the caption and
 * the non-client area would otherwise repaint only on the next resize.
 */
object WindowsTitleBar {
    private const val ATTR_USE_IMMERSIVE_DARK_MODE = 20
    private const val ATTR_USE_IMMERSIVE_DARK_MODE_LEGACY = 19

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Long, attribute: Int, value: IntByReference, size: Int): Int
    }

    private var warned = false
    private var lastApplied: Boolean? = null

    private val dwm: DwmApi? by lazy {
        runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    /**
     * Applies [dark] to [window]'s caption. Returns true when the DWM call
     * succeeded. Repeat calls with the same value are skipped.
     */
    fun apply(window: ComposeWindow, dark: Boolean): Boolean {
        if (!osIsWindows()) return false
        if (lastApplied == dark) return true
        val hwnd = runCatching { window.windowHandle }.getOrNull() ?: return fail("no window handle")
        if (hwnd == 0L) return fail("hwnd=0")
        val api = dwm ?: return fail("dwmapi unavailable")
        val value = IntByReference(if (dark) 1 else 0)
        val first = runCatching { api.DwmSetWindowAttribute(hwnd, ATTR_USE_IMMERSIVE_DARK_MODE, value, 4) }
            .getOrNull()
        val ok = if (first == 0) {
            true
        } else {
            val second = runCatching {
                api.DwmSetWindowAttribute(hwnd, ATTR_USE_IMMERSIVE_DARK_MODE_LEGACY, value, 4)
            }.getOrNull()
            second == 0
        }
        if (!ok) return fail("DwmSetWindowAttribute failed (20 then 19)")
        runCatching {
            User32.INSTANCE.SetWindowPos(
                HWND(Pointer(hwnd)),
                HWND(Pointer.NULL),
                0,
                0,
                0,
                0,
                SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER,
            )
        }
        lastApplied = dark
        FmLog.info("FM_APPEARANCE_TITLEBAR") { "dark=$dark" }
        return true
    }

    private fun fail(reason: String): Boolean {
        if (!warned) {
            warned = true
            FmLog.info("FM_APPEARANCE_TITLEBAR_UNSUPPORTED") { "$reason -> system default caption" }
        }
        return false
    }
}

/**
 * Glue between the window and [AppearanceController]: the whole desktop
 * appearance wiring in one composable, so `main.kt` only adds one call.
 *
 *  - reads the system flag once on start, on every window focus, and every 5 s
 *    regardless of focus (the brief: the OS switch must land within 5 s even when
 *    the app is not in front);
 *  - re-paints the Windows caption whenever the resolved appearance flips;
 *  - logs one line per resolved change, which is the evidence for the
 *    `FM_FAKE_SYSTEM_DARK_FILE` smoke run.
 *
 * [systemTheme] is passed in rather than created here (T-1): `main.kt` has to read
 * the very same reader synchronously before the first composition, so the
 * `initialSystemDark` it seeds the controller with and the value this effect keeps
 * watching can never come from two different sources.
 */
@Composable
fun DesktopAppearanceEffects(
    controller: AppearanceController,
    window: ComposeWindow,
    systemTheme: WindowsSystemTheme = remember { WindowsSystemTheme() },
) {
    val watcher = remember(systemTheme) { WindowsSystemThemeWatcher(systemTheme, controller::updateSystemDark) }

    DisposableEffect(watcher, window) {
        controller.updateSystemDark(systemTheme.isDark())
        watcher.start()
        val focus = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = watcher.poke()
        }
        window.addWindowFocusListener(focus)
        FmLog.info("FM_APPEARANCE_INIT") { "${controller.describe()} source=${systemTheme.lastSource}" }
        onDispose {
            window.removeWindowFocusListener(focus)
            watcher.stop()
        }
    }

    val dark = resolveDark(controller.settings.mode, controller.systemDark)
    LaunchedEffect(window, dark) {
        WindowsTitleBar.apply(window, dark)
    }
    LaunchedEffect(dark) {
        FmLog.info("FM_APPEARANCE_APPLIED") { "${controller.describe()} source=${systemTheme.lastSource}" }
    }
}
