package com.framemark.native.product

import com.framemark.native.workflow.JvmProductFileSystem
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentStoreTest {
    @Test
    fun separate_store_instances_serialize_the_entire_temp_file_transaction() {
        val dir = Files.createTempDirectory("fm-concurrent-store-")
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val disk = JvmProductFileSystem(dir.toString())
        val fs = object : ProductFileSystem by disk {
            override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                val count = active.incrementAndGet()
                peak.accumulateAndGet(count, ::maxOf)
                try {
                    Thread.sleep(15)
                    return disk.writeBytes(path, bytes)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val results = (1..12).map { number ->
                pool.submit<StoreResult<Unit>> {
                    check(start.await(5, TimeUnit.SECONDS))
                    SessionStore(fs).saveProject(Project(id = "project-$number", updatedAt = number.toLong()))
                }
            }
            start.countDown()
            results.forEach { assertEquals(StoreResult.Ok(Unit), it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, peak.get(), "same-path writes must never overlap")
            assertTrue(SessionStore(fs).loadProject() is StoreResult.Ok)
            assertTrue(!disk.exists("$dir/project.json.tmp"))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            dir.toFile().deleteRecursively()
        }
    }
}
