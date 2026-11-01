package com.framemark.native.verify

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * P3f window evidence: compare a before/after render and report the change two ways
 * — an absolute judgement (did any pixel change, and by how much) and a severity
 * normalised by canvas width, so a 1 px wobble on a 4000 px canvas is not read the
 * same as a 1 px wobble on a 600 px one.
 *
 *   java -cp <classpath> com.framemark.native.verify.P3fDiffKt <before.png> <after.png>
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: P3fDiffKt <before> <after> [label]")
        return
    }
    val label = args.getOrNull(2) ?: "pair"
    val a = ImageIO.read(File(args[0])) ?: error("cannot decode ${args[0]}")
    val b = ImageIO.read(File(args[1])) ?: error("cannot decode ${args[1]}")
    if (a.width != b.width || a.height != b.height) {
        println("P3F_DIFF label=$label dimsDiffer=${a.width}x${a.height} vs ${b.width}x${b.height}")
        return
    }
    var minX = a.width
    var minY = a.height
    var maxX = -1
    var maxY = -1
    var changed = 0
    var maxDelta = 0
    var sumDelta = 0L
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val pa = a.getRGB(x, y)
            val pb = b.getRGB(x, y)
            if (pa == pb) continue
            changed++
            val d = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                abs((pa and 0xFF) - (pb and 0xFF))
            if (d > maxDelta) maxDelta = d
            sumDelta += d
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
    }
    val total = a.width.toLong() * a.height
    val bboxW = if (maxX < 0) 0 else maxX - minX + 1
    val bboxH = if (maxY < 0) 0 else maxY - minY + 1
    // Severity: worst single-pixel channel-sum delta, scaled to a 1000 px canvas so
    // it is comparable across cards; `changed`/`maxDelta` stay the absolute track.
    val severity = if (changed == 0) 0.0 else maxDelta * (1000.0 / maxOf(a.width, 1))
    println(
        "P3F_DIFF label=$label same=${changed == 0} canvas=${a.width}x${a.height} " +
            "changed=$changed changedPct=" + "%.6f".format(changed * 100.0 / total) +
            " bbox=$minX,$minY,${bboxW}x${bboxH} bboxPct=" +
            "%.4f".format(if (changed == 0) 0.0 else bboxW.toLong() * bboxH * 100.0 / total) +
            " maxDelta=$maxDelta meanDeltaInBbox=" +
            "%.2f".format(if (bboxW * bboxH == 0) 0.0 else sumDelta.toDouble() / (bboxW * bboxH)) +
            " severity1000=" + "%.1f".format(severity),
    )
}
