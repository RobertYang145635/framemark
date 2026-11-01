package com.framemark.native.verify

import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryGroups
import com.framemark.native.library.LibraryPreviewLoader
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Evidence entry point: loads the bundled E1a JSON and decodes **all** preview
 * images through the same path the UI tiles use. Run with the desktop classpath
 * produced by `dumpDesktopClasspath` (it contains processedResources):
 *
 *   java -cp "$(cat composeApp/build/desktop-classpath.txt)" \
 *       com.framemark.native.verify.LibraryVerifyKt
 *
 * Exit 0 = every card has a readable, decodable preview; 1 = data load failed;
 * 2 = at least one preview failed (ids printed).
 */
fun main(): Unit = runBlocking {
    val result = LibraryDataSource.load()
    val error = result.error
    if (error != null) {
        println("LIBVERIFY FAIL load error=$error")
        exitProcess(1)
    }
    val snapshot = result.snapshot
    println(
        "LIBVERIFY cards=${snapshot.cards.size} categories=${snapshot.categories.size} " +
            "mapped=${snapshot.mappedPreviews} unmapped=${snapshot.unmappedPreviewIds.size}",
    )
    println(
        "LIBVERIFY category " + snapshot.categories.joinToString(" ") { "${it.key}=${it.count}" },
    )
    val byVendor = snapshot.cards.groupBy { LibraryGroups.keyOf(it) }
    println(
        "LIBVERIFY vendor " + LibraryGroups.vendorOrder(snapshot.cards).joinToString(" ") {
            "${LibraryGroups.labelOf(it)}=${byVendor[it]?.size ?: 0}"
        },
    )
    println("LIBVERIFY generatedAt=${snapshot.generatedAt}")

    var decoded = 0
    var bytes = 0L
    var width = 0
    var height = 0
    val failures = mutableListOf<String>()
    val started = System.nanoTime()
    snapshot.cards.forEach { card ->
        try {
            val raw = LibraryPreviewLoader.loadBytes(card)
            bytes += raw.size
            val bitmap = LibraryPreviewLoader.decode(raw)
            width = bitmap.width
            height = bitmap.height
            decoded++
        } catch (t: Throwable) {
            failures += "${card.id}: ${t.message ?: t::class.simpleName}"
        }
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    println("LIBVERIFY decoded=$decoded failed=${failures.size} bytes=$bytes bitmap=${width}x$height ms=$elapsedMs")
    failures.take(20).forEach { println("LIBVERIFY FAIL $it") }
    exitProcess(if (failures.isEmpty()) 0 else 2)
}
