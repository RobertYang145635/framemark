package com.framemark.native.product

import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.WorkflowEnv
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * P2c item 2: the result page's 打开 / 分享 must tell the user when nothing happened.
 * The platform port returns a message on failure, and the sheet renders it.
 */
class DesktopResultActionsTest {
    @Test
    fun reveal_reports_a_missing_target_instead_of_claiming_success() {
        val dir = Files.createTempDirectory("fm-p2c-reveal-")
        try {
            val platform = DesktopWorkflowPlatform(dataDir = dir.toString(), exportDir = dir.toString())
            // Only the negative case is asserted: a positive one would pop an Explorer
            // window out of a unit test.
            assertNotNull(platform.revealInFolder(dir.resolve("nope.jpg").toString()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun share_says_so_when_the_platform_has_no_share_sheet() {
        val dir = Files.createTempDirectory("fm-p2c-share-")
        val old = WorkflowEnv.platform
        try {
            val platform = DesktopWorkflowPlatform(dataDir = dir.toString(), exportDir = dir.toString())
            WorkflowEnv.platform = platform
            assertNotNull(platform.shareFile(dir.toString()), "桌面没有分享入口，必须回话而不是静默")
        } finally {
            WorkflowEnv.platform = old
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun openGallery_on_desktop_reveals_the_file_and_reports_a_missing_one() {
        // 桌面没有系统相册：查看成片 = 在资源管理器里选中文件。同样只断言失败那一侧，
        // 成功那一侧会弹出一个资源管理器窗口。
        val dir = Files.createTempDirectory("fm-view-gallery-")
        try {
            val platform = DesktopWorkflowPlatform(dataDir = dir.toString(), exportDir = dir.toString())
            assertNotNull(
                platform.openGallery(dir.resolve("nope.jpg").toString()),
                "文件不存在时必须回话，不能假装打开了相册",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
