package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonMap
import kotlin.system.exitProcess

/**
 * Headless entry point for the same bridge the UI uses. The X1 verifier drives
 * this so contract evidence and the app cannot drift apart:
 *
 *   java -cp <desktop classpath> com.framemark.native.engine.BridgeMainKt \
 *        --card stock-vivo-zeiss-inside --mode preview --max-edge 1024 --out preview.png
 */
private fun arg(args: Array<String>, name: String): String? {
    val i = args.indexOf("--$name")
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun argsMap(raw: String?): Map<String, Any?> =
    if (raw.isNullOrBlank()) emptyMap() else Json.parse(raw).jsonMap()

fun main(args: Array<String>) {
    val cardId = arg(args, "card") ?: run {
        System.err.println("FM_BRIDGE_FAIL missing --card")
        exitProcess(2)
    }
    val mode = arg(args, "mode") ?: "preview"
    val params = argsMap(arg(args, "params"))
    val text = argsMap(arg(args, "text"))
    val maxEdge = arg(args, "max-edge")?.toIntOrNull()
    val longEdge = arg(args, "long-edge")?.toIntOrNull()
    val quality = arg(args, "quality")?.toIntOrNull()
    val format = arg(args, "format")
    val out = arg(args, "out")
    val webpLossless = arg(args, "webp-lossless")?.let { it == "1" } ?: true

    val result = runCatching {
        DesktopEngineBridge().render(
            cardId = cardId,
            params = params,
            text = text,
            mode = mode,
            maxEdge = maxEdge,
            format = format,
            quality = quality,
            longEdge = longEdge,
            outputPath = out,
            webpLossless = webpLossless,
        )
    }
    result.onSuccess { r ->
        println(
            "FM_BRIDGE_OK card=$cardId mode=$mode path=${r.path} ${r.width}x${r.height} " +
                "raw=${r.rawWidth}x${r.rawHeight} ms=${r.ms} slots=${r.textSlots.size} warnings=${r.warnings.size}",
        )
        exitProcess(0)
    }.onFailure { t ->
        System.err.println("FM_BRIDGE_FAIL card=$cardId ${t.message}")
        t.stackTrace.take(4).forEach { System.err.println("  at $it") }
        exitProcess(1)
    }
}