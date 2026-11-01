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
}
