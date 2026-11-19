package com.framemark.native.workflow

import com.framemark.native.product.FmLog

/**
 * 新手教程唯一需要记住的东西：一个布尔值。
 *
 * 为什么不是一个新数据库、也不是新的平台接口：仓库里已经有「本机状态写一个
 * 小文本文件」这条路（`PresetStore` 走 `WorkflowPlatform.readTextFile /
 * writeTextFile`，外观走 `theme/AppearanceStore`），教程标记比它们都小，
 * 只需要 `onboarding.done=true` 一行。这里把存储抽成接口，逻辑就能脱离平台单测。
 */
interface OnboardingStore {
    /** [fallback] 是「文件里没有这一行」时的答案，不是「读取失败」的答案。 */
    fun getBoolean(key: String, fallback: Boolean): Boolean

    /** 写入一位布尔值。失败只能是暂时的：下次启动会再问一次，不会丢用户数据。 */
    fun putBoolean(key: String, value: Boolean)
}

/**
 * 教程状态的纯逻辑：**没看过就显示，看过就不再显示**。
 *
 * 三件事都在这里，壳里不许各写一份：
 *  - 首次判定 [shouldShow]：`onboarding.done` 缺失 / false 就是首次；
 *  - 标记 [markDone]：跳过与完成走**同一个**入口，所以「跳过」之后也不会每次启动都弹；
 *  - 幂等：已经是 done 时再调一次不写盘（再次打开教程不会改变这个状态）。
 */
object OnboardingState {
    /** 持久化键。写成带点的名字，和 `appearance.json` 的键风格一致。 */
    const val KEY_DONE: String = "onboarding.done"

    /** 用户已经看过教程（完成或跳过都算）。 */
    fun isDone(store: OnboardingStore): Boolean = store.getBoolean(KEY_DONE, false)

    /** 首次启动才自动出现；之后只能从设置 / 齿轮里手动打开。 */
    fun shouldShow(store: OnboardingStore): Boolean = !isDone(store)

    /**
     * 标记「看过了」。完成和跳过都调它 —— 否则选了跳过的人每次启动都会再被拦一次。
     * 已经是 done 时直接返回：重复标记不该产生第二次写入。
     */
    fun markDone(store: OnboardingStore) {
        if (isDone(store)) return
        store.putBoolean(KEY_DONE, true)
    }
}

/**
 * 内存里的 [OnboardingStore]：单元测试、预览、以及没有数据目录的进程用。
 * [writes] 让测试能钉住「重复标记不写第二次」。
 */
class MemoryOnboardingStore(initial: Map<String, Boolean> = emptyMap()) : OnboardingStore {
    private val values = LinkedHashMap(initial)

    /** 写次数（一次 [putBoolean] 算一次）。 */
    var writes: Int = 0
        private set

    /** 当前全部键值；测试与证据用，不参与 UI 逻辑。 */
    val snapshot: Map<String, Boolean> get() = values.toMap()

    override fun getBoolean(key: String, fallback: Boolean): Boolean = values[key] ?: fallback

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
        writes++
    }
}

/**
 * 落盘实现：`<数据目录>/settings/onboarding.txt`，一行一个键值。
 *
 * 复用 [WorkflowPlatform] 的文本读写（与 `presets.json` 同一条路），所以手机
 * （`context.filesDir`）与桌面（`DesktopWorkflowPlatform.defaultDataDir()`，可用环境
 * 变量覆盖）不需要各自的实现，也不新增依赖。文件只有布尔值，因此用 `key=true` 的
 * 键值行而不是 JSON：读回来不需要 JSON 解析器，也不会因为一个引号把状态读丢。
 *
 * 读：首次访问才读一次，文件缺失 / 内容损坏都退回空表（等价于「没看过」）。
 * 写：整个文件重写（几十字节），失败只记一行日志 —— 教程标记丢了最坏的结果是
 * 下次启动再看一遍教程，不值得拦住任何操作。
 */
class WorkflowOnboardingStore(
    private val platform: WorkflowPlatform? = WorkflowEnv.platform,
) : OnboardingStore {

    /** 绝对路径，给日志与证据用；没有平台时是 `<none>`。 */
    val settingsPath: String
        get() {
            val dir = platform?.dataDir?.trimEnd('/', '\\') ?: return NO_PATH
            return "$dir/$FILE"
        }

    private var loaded = false
    private val values = LinkedHashMap<String, Boolean>()

    /** 写次数（测试用）。 */
    var writes: Int = 0
        private set

    /** 读进来的全部键值；测试与证据用，不参与 UI 逻辑。 */
    val snapshot: Map<String, Boolean>
        get() {
            ensureLoaded()
            return values.toMap()
        }

    override fun getBoolean(key: String, fallback: Boolean): Boolean {
        ensureLoaded()
        return values[key] ?: fallback
    }

    override fun putBoolean(key: String, value: Boolean) {
        ensureLoaded()
        values[key] = value
        writes++
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val store = platform ?: return
        val text = runCatching { store.readTextFile(settingsPath) }.getOrNull() ?: return
        decode(text).forEach { (key, value) -> values[key] = value }
    }

    private fun persist() {
        val store = platform ?: return
        runCatching { store.writeTextFile(settingsPath, encode(values)) }
            .onFailure { FmLog.warn(WRITE_FAIL_TAG) { "path=$settingsPath error=${it.message}" } }
    }

    companion object {
        /** `settings/` 与 `appearance.json` 同一个目录，迁移 / 备份时只有一处要带。 */
        const val FILE: String = "settings/onboarding.txt"

        const val NO_PATH: String = "<none>"

        /** 非 `FM_` 前缀：这不是证据协议的一部分，只是一行本地写失败。 */
        const val WRITE_FAIL_TAG: String = "ONBOARDING_WRITE_FAIL"

        /** 第一行是说明；`#` 开头的行在读回来时跳过。 */
        fun encode(values: Map<String, Boolean>): String = buildString {
            append("# FrameMark 本机状态 · 只有 true / false\n")
            values.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
        }

        /** 认得 `true` / `false`（忽略大小写），其它值一律忽略，不猜。 */
        fun decode(text: String): Map<String, Boolean> {
            val out = LinkedHashMap<String, Boolean>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val cut = line.indexOf('=')
                if (cut <= 0) return@forEach
                val key = line.substring(0, cut).trim()
                when (line.substring(cut + 1).trim().lowercase()) {
                    "true" -> out[key] = true
                    "false" -> out[key] = false
                }
            }
            return out
        }
    }
}
