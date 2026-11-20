package com.framemark.native.product.update

/**
 * 「跳过这个版本」的持久化落点。
 *
 * 放在 product 层是为了不依赖 `workflow`（层方向只能向下：product ← workflow ← shell）。
 * 外壳用 `WorkflowEnv.platform` 的文件 API 实现一个，注入给 [UpdateLaunch]。
 */
interface UpdateSkipStorage {
    /** 上一次跳过的版本名；没有就 null。 */
    fun read(): String?

    fun write(versionName: String)

    fun clear()
}

/**
 * 启动时自动检查一次更新（2026-11-20）。
 *
 * 规则（全部是纯决策，`UpdateLaunchPolicyTest` 直接测）：
 *  1. 只有**清单里真的更新的版本**才弹窗；检查失败、没有新版都不打扰。
 *  2. 用户在弹窗里点过「跳过这个版本」的版本不再提示 —— 下次真正有新版本时照常提示。
 *  3. 已经在用更新的版本（回退安装、开发构建）时，把跳过记录清掉，别留下过期状态。
 */
object UpdateLaunch {

    /** 用户明确跳过的版本名，没有就 null。 */
    fun skipped(storage: UpdateSkipStorage?): String? =
        runCatching { storage?.read() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 记下「这个版本先不提示」。 */
    fun skip(storage: UpdateSkipStorage?, versionName: String) {
        runCatching { storage?.write(versionName.trim()) }
    }

    /** 已经在更新的版本上：清掉跳过记录（回退安装后不会一直不提示）。 */
    fun forgetIfOutdated(storage: UpdateSkipStorage?, currentVersion: String) {
        val skipped = skipped(storage) ?: return
        if (UpdateVersion.isNewer(skipped, currentVersion)) return
        runCatching { storage?.clear() }
    }

    /**
     * 启动检查：有新版本且不是已跳过的那个，返回清单；否则 null。
     * 调用方保证在 IO 线程；失败静默（启动路径上不弹任何错误）。
     */
    suspend fun check(
        currentVersion: String,
        storage: UpdateSkipStorage?,
        source: UpdateSource = UpdateSource.Mirror,
    ): UpdateManifest? {
        forgetIfOutdated(storage, currentVersion)
        // 只要清单、并且有总预算：冷启动不能为了一个弹窗等十几秒。
        val outcome = runCatching { checkManifestForLaunch(currentVersion, source) }.getOrNull()
        val manifest = (outcome as? UpdateCheckOutcome.Available)?.manifest ?: return null
        if (!shouldPrompt(currentVersion, skipped(storage), manifest.versionName)) return null
        return manifest
    }
}

/**
 * 该不该弹窗：清单版本比当前新，且不是用户跳过的那个版本。
 *
 * 单独拿出来是因为这条判断是「每次打开都弹」这句话的全部边界条件 —— 版本比较只认
 * 清单里的版本名（与关于页同一个 `UpdateVersion`），跳过只认完全相同的版本名。
 */
fun shouldPrompt(currentVersion: String, skippedVersion: String?, remoteVersion: String): Boolean {
    if (!UpdateVersion.isNewer(currentVersion, remoteVersion)) return false
    return skippedVersion.isNullOrBlank() || skippedVersion.trim() != remoteVersion.trim()
}
