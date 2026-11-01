package com.framemark.native.engine

import com.framemark.native.inspector.RegistryInspectorLoad
import com.framemark.native.inspector.RegistryProvider
import com.framemark.native.product.ControlRegistryLoader

/**
 * Desktop registry provider: resolves the six registry documents plus the IR slot
 * binding (bundle layout first, workspace layout second) and parses them once.
 */
class DesktopRegistryProvider(
    private val source: RegistrySource = RegistrySource(),
) : RegistryProvider {
    private var cached: RegistryInspectorLoad? = null

    override fun load(): RegistryInspectorLoad? {
        cached?.let { return it }
        val registry = source.registry() ?: return null
        val load = RegistryInspectorLoad(registry, source.binding())
        cached = load
        return load
    }

    override fun missing(): List<String> = source.documents().missing
}
