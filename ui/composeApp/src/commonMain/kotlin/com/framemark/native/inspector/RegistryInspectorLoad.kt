package com.framemark.native.inspector

import com.framemark.native.product.ControlRegistry
import com.framemark.native.product.ControlRegistryLoader

/**
 * The registry + IR slot binding pair the editor needs, behind one port so the
 * shared shell stays platform-neutral about where the documents live.
 */
class RegistryInspectorLoad(
    val registry: ControlRegistry,
    val binding: IrSlotBinding,
) {
    val stats get() = registry.stats

    /** Every card the registry knows, with the decisions taken per control. */
    fun auditAll(): List<RegistryInspectorFactory.Decision> = registry.cards.flatMap { card ->
        RegistryInspectorFactory.build(card.cardId, card.name, card.category, card.categoryName, card, binding).decisions
    }

    fun modelFor(cardId: String, cardName: String, category: String, categoryName: String): InspectorCardModel? {
        val spec = registry.card(cardId) ?: return null
        return RegistryInspectorFactory.build(cardId, cardName, category, categoryName, spec, binding).model
    }

    fun specFor(cardId: String) = registry.card(cardId)
}

/** Installed by the platform entry point; null means this target has no registry. */
interface RegistryProvider {
    fun load(): RegistryInspectorLoad?

    /** File names the platform could not resolve, for the "why is the panel empty" message. */
    fun missing(): List<String>
}

object RegistryPorts {
    var provider: RegistryProvider? = null
}

/** Loads the registry from injected document text (tests, and any platform without files). */
fun buildRegistryLoad(sources: ControlRegistryLoader.Sources, bindingJson: String?): RegistryInspectorLoad? =
    runCatching { ControlRegistryLoader.load(sources) }
        .getOrNull()
        ?.let { RegistryInspectorLoad(it, bindingJson?.let(IrSlotBinding::load) ?: IrSlotBinding.EMPTY) }
