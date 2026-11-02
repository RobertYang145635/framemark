package com.framemark.native.shell

import com.framemark.native.workflow.BatchRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PhoneBatchDemoInitializationTest {
    @Test
    fun normalLaunchDoesNotConstructTheLegacyBatchRunner() {
        var factoryCalls = 0

        val runner = createPhoneBatchDemoRunner(enabled = false) {
            factoryCalls++
            BatchRunner()
        }

        assertNull(runner)
        assertEquals(0, factoryCalls, "ordinary startup must not load or persist through the legacy queue reader")
    }

    @Test
    fun explicitDemoLaunchStillConstructsTheLegacyBatchRunner() {
        val expected = BatchRunner()
        var factoryCalls = 0

        val runner = createPhoneBatchDemoRunner(enabled = true) {
            factoryCalls++
            expected
        }

        assertSame(expected, runner)
        assertEquals(1, factoryCalls)
    }
}
