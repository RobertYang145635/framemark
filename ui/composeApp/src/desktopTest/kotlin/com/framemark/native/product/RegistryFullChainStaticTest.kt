package com.framemark.native.product

import com.framemark.native.engine.DesktopRegistryProvider
import com.framemark.native.engine.RegistrySource
import com.framemark.native.inspector.IrSlotBinding
import com.framemark.native.inspector.RegistryInspectorFactory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3f static gate: the desktop inspector is built from `ControlRegistry` + the IR
 * slot binding, and nothing it shows can be a dead control.
 *
 * Every assertion here reads the same documents the running app resolves, so a
 * green test means the panel a user sees has no key that the engine ignores.
 */
class RegistryFullChainStaticTest {
    private val provider get() = sharedProvider
    private val load get() = sharedLoad

    @Test
    fun the_registry_and_binding_resolve_in_the_workspace_layout() {
        val target = load
        assertTrue(target != null, "注册表必须能加载：missing=${provider.missing()}")
        val stats = target!!.stats
        assertEquals(183, stats.cards)
        assertEquals(0, ControlRegistryValidator.issues(target.registry).size, "静态校验必须 0 错")
        println(
            "P3F_REGISTRY cards=${stats.cards} visible=${stats.visibleControls} " +
                "routable=${stats.routableControls} consumed=${stats.consumedControls} gated=${stats.gatedControls}",
        )
    }

    @Test
    fun every_shown_control_carries_a_routable_engine_key() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val decisions = target.auditAll()
        val shown = decisions.filter { it.shown }
        val dead = shown.filter { !isRoutableConsumer(it.consumer) }
        assertTrue(dead.isEmpty(), "存在上了面板却落不到请求字段的控件：${dead.take(8)}")
        val empty = shown.filter { it.consumer.isBlank() }
        assertTrue(empty.isEmpty(), "可见控件 consumer 不能为空：${empty.take(8)}")
        println("P3F_SHOWN controls=${shown.size} disabled=${shown.count { it.disabled }}")
    }

    /**
     * The point of the binding table: an IR node-scoped key must name a **bundle**
     * path from `nodes[]`, never the vendor spelling (`b0/bottom0/…`).
     */
    @Test
    fun ir_node_keys_come_from_the_binding_nodes_not_the_vendor_path() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val offenders = ArrayList<String>()
        val nodeKeys = ArrayList<String>()
        for (card in target.registry.cards) {
            if (card.backend != ControlBackend.Ir) continue
            for (control in card.controls.filter { it.visible }) {
                val entry = target.binding.entry(card.styleId, control.controlId) ?: continue
                if (entry.classification != "expressible") continue
                val keys = listOf(entry.primaryConsumer) .filterNotNull() + entry.companionConsumers
                for (key in keys) {
                    val node = key.substringAfter(':', "")
                    if (!key.contains("slot")) continue
                    nodeKeys += key
                    if (node.isBlank()) {
                        offenders += "${card.cardId}::${control.controlId} 没有节点路径 $key"
                        continue
                    }
                    val vendor = entry.nodes.any { !key.endsWith(it) }
                    if (vendor && !entry.nodes.contains(node)) {
                        offenders += "${card.cardId}::${control.controlId} key=$key 不在 nodes=${entry.nodes}"
                    }
                    if (Regex("""bottom\d|top\d|left\d|right\d""").containsMatchIn(node)) {
                        offenders += "${card.cardId}::${control.controlId} 用了厂商路径域 $node"
                    }
                }
            }
        }
        assertTrue(nodeKeys.isNotEmpty(), "一条 node 键都没生成，说明绑定没接上")
        assertTrue(offenders.isEmpty(), offenders.take(10).joinToString("\n"))
        println("P3F_NODE_KEYS irNodeKeys=${nodeKeys.size} distinctNodes=${nodeKeys.map { it.substringAfter(':') }.distinct().size}")
    }

    /** `slotOff` must go out per node; a container prefix would hide the wrong rows. */
    @Test
    fun a_shared_off_switch_writes_one_key_per_node() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val multi = target.registry.cards.filter { it.backend == ControlBackend.Ir }
            .flatMap { card ->
                card.controls.filter { it.visible }.mapNotNull { control ->
                    target.binding.entry(card.styleId, control.controlId)
                        ?.takeIf { it.param == "slotOff" && it.nodes.size > 1 }
                        ?.let { card.cardId to it }
                }
            }
        val decisions = target.auditAll().associateBy { "${it.cardId}::${it.controlId}" }
        for ((cardId, entry) in multi) {
            val decision = decisions["$cardId::${entry.controlId}"] ?: continue
            assertEquals(entry.nodes.size, 1 + decision.companions.size, "$cardId::${entry.controlId} 节点数与键数不符")
            assertTrue(
                (listOf(decision.consumer) + decision.companions).toSet() == entry.nodes.map { "params.slotOff:$it" }.toSet(),
                "$cardId::${entry.controlId} 下发的不是 nodes 集合",
            )
        }
        println("P3F_SLOTOFF sharedSwitches=${multi.size}")
    }

    @Test
    fun unsupported_is_hidden_and_needs_regen_is_disabled_with_a_reason() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val decisions = target.auditAll()
        val byReason = decisions.groupingBy { it.reason.substringBefore(':') }.eachCount()
        assertEquals(target.binding.stats.unsupported, decisions.count { it.reason == "unsupported" })
        assertEquals(0, decisions.count { it.reason == "unsupported" && it.shown }, "unsupported 不能上面板")
        val regen = decisions.filter { it.reason == "needs-ir-regen" }
        assertEquals(target.binding.stats.needsRegen, regen.size)
        assertTrue(regen.all { it.shown && it.disabled }, "needs-ir-regen 必须可见但禁用")
        assertTrue(regen.all { it.disabled }, "needs-ir-regen 必须标注原因")
        assertTrue(decisions.filter { it.gatedHidden() }.none { it.shown }, "门控控件绝不可见")
        println(
            "P3F_POLICY shown=${decisions.count { it.shown }} hidden=${decisions.count { !it.shown }} " +
                "disabled=${decisions.count { it.disabled }} reasons=$byReason",
        )
    }

    @Test
    fun the_binding_table_matches_the_p3c_counts() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val stats = target.binding.stats
        assertEquals(109, stats.styles)
        assertEquals(574, stats.entries)
        assertEquals(556, stats.expressible)
        assertEquals(12, stats.unsupported)
        assertEquals(6, stats.needsRegen)
    }

    @Test
    fun the_desktop_and_phone_resolve_the_same_registry_bytes() {
        val target = requireNotNull(load) { "Registry fixtures must load: ${provider.missing()}" }
        val source = RegistrySource(RealData.root.toFile())
        val documents = source.documents()
        val file = documents.irSlotBinding
        assertTrue(file != null && file.isFile, "绑定表必须可解析：${documents.missing}")
        val reparsed = IrSlotBinding.load(file!!.readText())
        assertEquals(target.binding.stats.entries, reparsed.stats.entries)
        // Same control, same decision, whichever instance asked.
        val sample = target.registry.cards.first { it.backend == ControlBackend.Ir }
        val a = RegistryInspectorFactory.build(sample.cardId, sample.name, sample.category, sample.categoryName, sample, target.binding)
        val b = RegistryInspectorFactory.build(sample.cardId, sample.name, sample.category, sample.categoryName, sample, reparsed)
        assertEquals(a.decisions, b.decisions)
        assertEquals(a.model.controls, b.model.controls)
    }

    private fun RegistryInspectorFactory.Decision.gatedHidden(): Boolean = reason == "gated"

    /** Cross-checks the shipped table against its own declared counts. */
    private object ControlRegistryValidator {
        fun issues(registry: ControlRegistry): List<ValidationIssue> = registry.validate()
    }
}


/**
 * The registry documents are several megabytes and JUnit builds a new test instance
 * per method, so the parse is shared: one load for the whole class.
 */
private val sharedProvider = DesktopRegistryProvider(RegistrySource(RealData.root.toFile()))
private val sharedLoad by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { sharedProvider.load() }
