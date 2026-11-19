package com.framemark.native.arch

import com.framemark.native.product.RealData
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P0-1 的结构性一半：**产品代码永远不许把内置样张塞进工程**。
 *
 * 缺陷长这样：空工程上点「选择一个边框」→ `openCardFromBrowser` 调
 * `store.adoptSample()`，工程里多出一条 `PhotoRef("photo:sample", "", "示例照片")`；
 * 于是「导出」出来的是应用自带的山景照，用户以为那是自己的照片。修的时候只删掉
 * 调用点是不够的 —— 方法还在，任何人随时可以再接一次。所以：
 *  1. `PhoneProjectStore` 里那一个已经改名为 `adoptSampleFixture`（测试 / 证据用）；
 *  2. 这条 gate 扫 `commonMain` / `desktopMain` / `androidMain`，任何 `.adoptSample`
 *     调用都算违规。把方法重新接回 UI 会让构建变红，而不是靠 review 记得。
 *
 * 与 `UiCopySourceGuardTest` / `EvidenceHookKonsistTest` 同一套做法：扫生产源码，
 * 不扫测试（测试当然可以造一个带样张的工程）。
 */
class SamplePhotoGuardTest {

    /** `.adoptSample…` = 一次调用；声明处的 `fun adoptSampleFixture()` 没有前导点。 */
    private val injection = Regex("\\.adoptSample[A-Za-z]*\\s*\\(")

    @Test
    fun production_code_never_adopts_the_bundled_sample_photo() {
        val root = RealData.root.resolve("ui/composeApp/src")
        val findings = mutableListOf<String>()
        var scanned = 0
        for (sourceSet in Arch.productionSourceSets) {
            val dir = root.resolve(sourceSet)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                    scanned++
                    val label = root.relativize(file).toString().replace('\\', '/')
                    file.readText().lineSequence().forEachIndexed { index, line ->
                        if (injection.containsMatchIn(line)) findings += "$label:${index + 1}: ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(scanned > 100, "expected the production tree, scanned only $scanned files")
        assertTrue(
            findings.isEmpty(),
            "内置样张只能由测试注入；产品代码里不许出现这条调用（P0-1）：\n" +
                findings.joinToString("\n"),
        )
    }
}
