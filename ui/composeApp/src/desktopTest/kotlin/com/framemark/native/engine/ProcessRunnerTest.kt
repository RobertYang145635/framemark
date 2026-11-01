package com.framemark.native.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessRunnerTest {
    private fun command(mode: String): List<String> {
        val classpath = listOf(
            ProcessRunnerChild::class.java.protectionDomain.codeSource.location.toURI(),
            Unit::class.java.protectionDomain.codeSource.location.toURI(),
        ).joinToString(File.pathSeparator) { File(it).path }
        return listOf(File(System.getProperty("java.home"), "bin/java.exe").path,
            "-cp", classpath, ProcessRunnerChild::class.java.name, mode)
    }

    @Test
    fun a_child_holding_stdout_open_is_killed_at_the_deadline() {
        val start = System.nanoTime()
        val error = assertFailsWith<IllegalStateException> { ProcessRunner.run(command("hang"), timeoutMillis = 500) }
        assertTrue(error.message.orEmpty().contains("timed out"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000)
    }

    @Test
    fun a_child_not_reading_stdin_cannot_block_the_timeout() {
        assertFailsWith<IllegalStateException> {
            ProcessRunner.run(command("hang"), timeoutMillis = 500) { it.write(ByteArray(4 * 1024 * 1024)) }
        }
    }

    @Test
    fun large_output_is_drained_and_retains_a_bounded_tail() {
        val result = ProcessRunner.run(command("output"), timeoutMillis = 10000)
        assertEquals(0, result.code)
        assertTrue(result.output.endsWith("END"))
        assertTrue(result.output.length <= 65536)
    }
}

object ProcessRunnerChild {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "hang") Thread.sleep(30000)
        else {
            repeat(100000) { System.out.print('x') }
            System.out.print("END")
        }
    }
}
