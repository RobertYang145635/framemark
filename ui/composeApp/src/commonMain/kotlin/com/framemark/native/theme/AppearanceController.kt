package com.framemark.native.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Owns the appearance for one process: the persisted [AppearanceSettings], the
 * platform's own dark flag, and the write-through to [store].
 *
 * Observable from a composition — [settings] and [systemDark] are snapshot state,
 * so assigning either re-runs every composable that read them and the whole tree
 * swaps palette. An instance is installed by the platform entry point
 * (`main.kt` on desktop, `MainActivity.onCreate` on Android) into
 * [AppearanceEnv] and published down the tree through [LocalAppearance].
 */
class AppearanceController(
    private val store: AppearanceStore? = NoPersistenceAppearanceStore,
    /** `FM_APPEARANCE` QA override: force this mode for this run, never persisted. */
    forcedMode: ThemeMode? = null,
    initialSystemDark: Boolean = false,
) {
    private val forced: ThemeMode? = forcedMode

    /** The persisted decision — what the file holds, without the QA override. */
    private var base: AppearanceSettings

    /** The decision the UI shows and the theme renders. */
    var settings: AppearanceSettings by mutableStateOf(AppearanceSettings.DEFAULT)
        private set

    /** What the platform says right now. Desktop polls it, Android reads `uiMode`. */
    var systemDark: Boolean by mutableStateOf(initialSystemDark)
        private set

    /** How the store read went: `loaded` / `missing` / `empty` / `error`. */
    var loadStatus: String = "unknown"
        private set

    /** Absolute settings path, or `<none>` when nothing is persisted. */
    val settingsPath: String get() = store?.settingsPath ?: NoPersistenceAppearanceStore.settingsPath

    /** Result of the last save, for the logs. Observable so a failed save can show a note. */
    var lastSaveOk: Boolean by mutableStateOf(true)
        private set

    init {
        val text = try {
            store?.load()
        } catch (_: Throwable) {
            loadStatus = "error"
            null
        }
        if (loadStatus == "unknown") {
            loadStatus = when (text) {
                null -> "missing"
                else -> if (text.isBlank()) "empty" else "loaded"
            }
        }
        base = AppearanceSettingsCodec.decode(text)
        settings = withForced(base)
    }

    private fun withForced(value: AppearanceSettings): AppearanceSettings =
        if (forced != null) value.copy(mode = forced) else value

    /** The appearance the theme should render right now. */
    val dark: Boolean get() = resolveDark(settings.mode, systemDark)

    /** True when a QA launch override is in charge for this run. */
    val isForced: Boolean get() = forced != null

    fun setMode(mode: ThemeMode) {
        if (base.mode == mode) return
        base = base.copy(mode = mode)
        settings = withForced(base)
        persist()
    }

    /**
     * 切换界面配色（配色方案 §6）。只改界面令牌：照片像素、边框参数、文字、水印和
     * Logo 都不参与，所以切换后同一设计导出的作品一模一样（文档 §7.2）。
     */
    fun setPalette(palette: PaletteId) {
        if (base.palette == palette) return
        base = base.copy(palette = palette)
        settings = withForced(base)
        persist()
    }

    fun setStageTone(tone: StageTone) {
        if (base.stageTone == tone) return
        base = base.copy(stageTone = tone)
        settings = withForced(base)
        persist()
    }

    /**
     * 「恢复默认外观」（配色方案 §8.3）：配色回到暖灰墨绿、明暗跟随系统、画布深灰。
     * 只动外观偏好，不碰当前照片、参数、工具页或编辑会话；一次写盘，不是三次。
     */
    fun resetToDefault() {
        if (base == AppearanceSettings.DEFAULT) return
        base = AppearanceSettings.DEFAULT
        settings = withForced(base)
        persist()
    }

    /** Pushed by the platform watcher; a no-op assignment does not recompose. */
    fun updateSystemDark(dark: Boolean) {
        if (systemDark == dark) return
        systemDark = dark
    }

    /**
     * Writes [base] — deliberately **not** [settings] — so a `FM_APPEARANCE`
     * override can never leak into the user's own file, even when the user
     * changes the stage tone during a forced run.
     */
    private fun persist() {
        val target = store ?: return
        lastSaveOk = try {
            target.save(AppearanceSettingsCodec.encode(base))
        } catch (_: Throwable) {
            false
        }
    }

    /** Human-readable one-liner for the launch log. */
    fun describe(): String = buildString {
        append("palette=").append(settings.palette.wire)
        append(" mode=").append(settings.mode.wire)
        append(" stageTone=").append(settings.stageTone.wire)
        append(" systemDark=").append(systemDark)
        append(" dark=").append(dark)
        append(" load=").append(loadStatus)
        if (isForced) append(" forced=").append(forced?.wire)
        append(" file=").append(settingsPath)
    }
}

/**
 * Process-wide controller. Set once by the platform entry point before the first
 * composition; `FrameMarkApp` falls back to a non-persisting controller when a
 * target forgets to install one, so the UI never crashes over appearance.
 */
object AppearanceEnv {
    var controller: AppearanceController? = null
}

/**
 * The active [AppearanceController]. Provided by `FrameMarkApp`; the default is a
 * shared non-persisting instance so a `@Preview` or a test composable that reads
 * `AppearancePanel` still renders.
 */
val LocalAppearance = staticCompositionLocalOf { DefaultAppearanceController }

private val DefaultAppearanceController: AppearanceController by lazy {
    AppearanceController(store = NoPersistenceAppearanceStore)
}
