package com.framemark.native.verify

import com.framemark.native.engine.DesktopRegistryProvider
import com.framemark.native.engine.RegistrySource
import com.framemark.native.inspector.RegistryInspectorFactory
import com.framemark.native.product.ControlBackend
import com.framemark.native.product.isRoutableConsumer
import java.io.File
import kotlin.system.exitProcess

/**
 * P3f static gate, run outside the Gradle test executor: proves the desktop
 * inspector's registry-driven model has no dead control and that every IR key comes
 * from the binding table's bundle paths.
 *
 *   java -cp <classpath> com.framemark.native.verify.P3fStaticVerifyKt --out _research/native/p3f
 */
private fun esc(text: String): String = text.replace("\\", "\\\\").replace("\"", "'")
    .replace("\n", "\\n").replace("\r", "")

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    val i = args.indexOf("--out")
    val out = File(if (i >= 0 && i + 1 < args.size) args[i + 1] else "_research/native/p3f")
        .let { if (it.isAbsolute) it else File(root, it.path) }
    out.mkdirs()

    val provider = DesktopRegistryProvider(RegistrySource(RegistrySource.devWorkspaceRoot()))
    val load = provider.load()
    if (load == null) {
        println("P3F_STATIC FAIL registry could not load: missing=${provider.missing()}")
        exitProcess(1)
    }
    val checks = linkedMapOf<String, Boolean>()
    val notes = linkedMapOf<String, String>()
    val stats = load.stats
    val decisions = load.auditAll()
    val shown = decisions.filter { it.shown }

    val catalogCardIds = load.registry.cards.map { it.cardId }
    checks["registryCardCountMatchesCatalog"] =
        stats.cards == catalogCardIds.size && catalogCardIds.distinct().size == catalogCardIds.size
    checks["registryValidateZero"] = load.registry.validate().isEmpty()
    checks["shownAllRoutable"] = shown.all { isRoutableConsumer(it.consumer) }
    checks["shownConsumerNonBlank"] = shown.none { it.consumer.isBlank() }
    checks["gatedNeverShown"] = decisions.none { it.reason == "gated" && it.shown }
    // Two earlier attempts to pin the gated case (`none { gated && shown }`, then a
    // reason-attribution count) both stayed green with the gated branch deleted: the
    // registry already guarantees visible ⇒ ¬gated, so that branch is redundant. The
    // load-bearing assertion is therefore the registry invariant itself.
    checks["registryInvariantVisibleImpliesNotGated"] =
        load.registry.cards.flatMap { it.controls }.none { it.visible && it.gated }
    checks["hiddenIsExactlyGatedPlusUnsupported"] =
        decisions.count { !it.shown } == stats.gatedControls + load.binding.stats.unsupported
    checks["unsupportedHidden"] = decisions.count { it.reason == "unsupported" } == load.binding.stats.unsupported &&
        decisions.none { it.reason == "unsupported" && it.shown }
    val regen = decisions.filter { it.reason == "needs-ir-regen" }
    checks["needsRegenShownButDisabled"] = regen.size == load.binding.stats.needsRegen &&
        regen.isNotEmpty() && regen.all { it.shown && it.disabled }

    // IR node keys must name a bundle node from nodes[], never the vendor spelling.
    val vendorSpelling = Regex("""/(top|bottom|left|right)\d""")
    val offenders = ArrayList<String>()
    var nodeKeys = 0
    val distinctNodes = HashSet<String>()
    for (card in load.registry.cards) {
        if (card.backend != ControlBackend.Ir) continue
        for (control in card.controls.filter { it.visible }) {
            val entry = load.binding.entry(card.styleId, control.controlId) ?: continue
            if (entry.classification != "expressible") continue
            val keys = listOfNotNull(entry.primaryConsumer) + entry.companionConsumers
            for (key in keys.filter { it.contains("slot") }) {
                nodeKeys++
                val node = key.substringAfter(':', "")
                distinctNodes += node
                if (node.isBlank()) offenders += "${card.cardId}::${control.controlId} 无节点 $key"
                if (vendorSpelling.containsMatchIn(node)) offenders += "${card.cardId}::${control.controlId} 厂商路径 $node"
                if (node.isNotBlank() && node !in entry.nodes) {
                    offenders += "${card.cardId}::${control.controlId} key=$key 不在 nodes=${entry.nodes}"
                }
            }
        }
    }
    checks["irKeysUseBundleNodes"] = nodeKeys > 0 && offenders.isEmpty()
    checks["bindingCountsMatchP3c"] = load.binding.stats.let {
        it.styles == 106 && it.entries == 526 && it.expressible == 508 && it.unsupported == 12 && it.needsRegen == 6
    }

    // slotOff must fan out over the whole node set, one key per node.
    val shared = ArrayList<Triple<String, String, Int>>()
    val byKey = decisions.associateBy { "${it.cardId}::${it.controlId}" }
    for (card in load.registry.cards) {
        if (card.backend != ControlBackend.Ir) continue
        for (control in card.controls.filter { it.visible }) {
            val entry = load.binding.entry(card.styleId, control.controlId) ?: continue
            if (entry.param != "slotOff" || entry.nodes.size < 2) continue
            val decision = byKey["${card.cardId}::${control.controlId}"] ?: continue
            val keys = (listOf(decision.consumer) + decision.companions).toSet()
            val expected = entry.nodes.map { "params.slotOff:$it" }.toSet()
            shared += Triple(card.cardId, control.controlId, if (keys == expected) 1 else 0)
        }
    }
    checks["slotOffFansOutPerNode"] = shared.isNotEmpty() && shared.all { it.third == 1 }
    notes["slotOffSharedTargets"] = shared.size.toString()
    notes["registry"] = "cards=${stats.cards} catalogUnique=${catalogCardIds.distinct().size} " +
        "visible=${stats.visibleControls} routable=${stats.routableControls} " +
        "consumed=${stats.consumedControls} gated=${stats.gatedControls} hand=${load.registry.cards.count { it.backend == ControlBackend.Hand }} " +
        "ir=${load.registry.cards.count { it.backend == ControlBackend.Ir }}"
    notes["decisions"] = "total=${decisions.size} shown=${shown.size} disabled=${shown.count { it.disabled }} hidden=${decisions.size - shown.size}"
    notes["irNodeKeys"] = "keys=$nodeKeys distinctNodes=${distinctNodes.size}"
    notes["offenders"] = offenders.take(6).joinToString(" | ").ifBlank { "none" }
    notes["bindingFile"] = RegistrySource(RegistrySource.devWorkspaceRoot()).documents().irSlotBinding?.path ?: "missing"
    notes["sampleKeys"] = shown.filter { it.consumer.contains("slot") }.take(4).joinToString(" | ") {
        "${it.controlId}->${it.consumer}"
    }

    fun render(): String = buildString {
        append("{\n  \"checks\": {")
        append(checks.entries.joinToString(",\n    ") { "\"${it.key}\": ${it.value}" })
        append("},\n  \"notes\": {")
        append(notes.entries.joinToString(",\n    ") { "\"${it.key}\": \"${esc(it.value)}\"" })
        append("}\n}\n")
    }
    // Re-parse the artifact with the project's own reader: an evidence file nobody can
    // parse is not evidence, and Windows backslashes in the notes broke exactly this.
    val probe = runCatching { com.framemark.native.library.Json.parse(render()) }
    checks["artifactReparseable"] = probe.isSuccess
    val artifact = render()
    File(out, "static-gate.json").writeText(artifact)
    checks["artifactOnDiskReparseable"] = runCatching {
        com.framemark.native.library.Json.parse(File(out, "static-gate.json").readText())
    }.isSuccess
    if (probe.isFailure) println("P3F_ARTIFACT_BAD ${probe.exceptionOrNull()?.message}")
    checks.forEach { (k, v) -> println("P3F_CHECK $k=$v") }
    notes.forEach { (k, v) -> println("P3F_NOTE $k=$v") }
    val ok = checks.values.all { it }
    println(if (ok) "P3F_STATIC PASS" else "P3F_STATIC FAIL")
    if (!ok) exitProcess(1)
}
