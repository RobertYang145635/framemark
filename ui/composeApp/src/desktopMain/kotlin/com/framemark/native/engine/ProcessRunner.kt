package com.framemark.native.engine

import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ProcessResult(val code: Int, val output: String)

/** The deadline starts before either pipe is serviced, including a blocked stdin. */
internal object ProcessRunner {
    fun run(
        argv: List<String>,
        directory: File? = null,
        timeoutMillis: Long = 240_000,
        writeInput: ((OutputStream) -> Unit)? = null,
    ): ProcessResult {
        val process = ProcessBuilder(argv).directory(directory).redirectErrorStream(true).start()
        val pipes = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "framemark-process-pipe").apply { isDaemon = true }
        }
        try {
            val reader = pipes.submit<String> {
                process.inputStream.reader().use { input ->
                    val tail = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        tail.append(buffer, 0, count)
                        if (tail.length > 65536) tail.delete(0, tail.length - 65536)
                    }
                    tail.toString()
                }
            }
            val writer = pipes.submit {
                process.outputStream.use { output -> writeInput?.invoke(output) }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Engine timed out after ${timeoutMillis}ms")
            }
            fun remaining(): Long = (deadline - System.nanoTime()).coerceAtLeast(1)
            writer.get(remaining(), TimeUnit.NANOSECONDS)
            return ProcessResult(process.exitValue(), reader.get(remaining(), TimeUnit.NANOSECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            pipes.shutdownNow()
        }
    }
}
