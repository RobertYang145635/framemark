package com.framemark.native.shell

import com.framemark.native.product.update.UpdateSkipStorage
import com.framemark.native.workflow.WorkflowEnv

/**
 * 「跳过这个版本」的落盘实现：`<dataDir>/updates/skipped.txt`，一行版本名。
 *
 * 放在 shell 层是因为它要读 [WorkflowEnv.platform] 的文件 API，而 `product` 层
 * 不允许依赖 `workflow`（层方向只能向下）。写不进去不影响启动，只是这台机器下次还会提示。
 */
class PlatformUpdateSkipStorage : UpdateSkipStorage {

    private fun path(): String? {
        val dir = WorkflowEnv.platform?.dataDir?.trimEnd('/', '\\') ?: return null
        return "$dir/updates/skipped.txt"
    }

    override fun read(): String? = path()?.let { runCatching { WorkflowEnv.platform?.readTextFile(it) }.getOrNull() }

    override fun write(versionName: String) {
        val file = path() ?: return
        runCatching { WorkflowEnv.platform?.writeTextFile(file, versionName.trim() + "\n") }
    }

    override fun clear() {
        val file = path() ?: return
        runCatching { WorkflowEnv.platform?.deleteExportTemporary(file) }
    }
}
