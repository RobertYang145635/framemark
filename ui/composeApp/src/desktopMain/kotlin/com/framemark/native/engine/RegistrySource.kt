package com.framemark.native.engine

import com.framemark.native.inspector.IrSlotBinding
import com.framemark.native.product.ControlRegistry
import com.framemark.native.product.ControlRegistryLoader
import java.io.File
import com.framemark.native.product.FmLog

/**
 * Where the registry documents live.
 *
 * A packaged install ships them under `<install>/data/registry/`; a dev checkout
 * keeps them spread over the source tree. Both are answered from a root that does
 * **not** come from the launch working directory: an install is located through
 * [InstallRoot] (launcher / jar folder / process command), and the workspace
 * layout is only used when the caller or `FM_RUNTIME_ROOT` supplies a checkout
 * root explicitly. The supplied root may be the repository itself or its legacy
 * parent containing a `framemark/` directory.
 *
 * This replaces the earlier "walk up from cwd" lookup, which made a shortcut or a
 * shell started in another folder boot with an empty inspector.
 */
class RegistrySource(explicitRoot: File? = null) {

    data class Documents(
        val taxonomy: File?,
        val capabilities: File?,
        val inspectorMeta: File?,
        val controls: File?,
        val vendoredControls: List<File>,
        val logos: File?,
        val irSlotBinding: File?,
    ) {
        val complete: Boolean
            get() = taxonomy != null && capabilities != null && inspectorMeta != null &&
                controls != null && logos != null && vendoredControls.isNotEmpty()
        val missing: List<String>
            get() = buildList {
                if (taxonomy == null) add("taxonomy.json")
                if (capabilities == null) add("capabilities.json")
                if (inspectorMeta == null) add("inspector-meta.json")
                if (controls == null) add("controls.json")
                if (logos == null) add("logos.json")
                if (vendoredControls.isEmpty()) add("controls.{oppo,xiaomi,vivo}.json")
                if (irSlotBinding == null) add("ir-slot-binding.json")
            }
    }

    private val explicitRoots: List<File> = buildList {
        explicitRoot?.let { add(it.absoluteFile) }
        System.getenv("FM_RUNTIME_ROOT")?.takeIf { it.isNotBlank() }?.let { add(File(it).absoluteFile) }
    }.distinct()

    private val roots: List<File> = (explicitRoots + InstallRoot.candidates()).distinct()

    /** Packaged layout: `<root>/data/registry/`. */
    private val bundleRegistry: File? =
        roots.firstNotNullOfOrNull { root ->
            File(root, "data/registry").takeIf { File(it, "taxonomy.json").isFile }
        }

    /** Dev layout, only reachable through an explicit root. */
    private val workspace: File? =
        explicitRoots.firstNotNullOfOrNull { root ->
            when {
                File(root, "engine/data/taxonomy.json").isFile -> root
                File(root, "framemark/engine/data/taxonomy.json").isFile -> File(root, "framemark")
                else -> null
            }
        }

    /** The workspace repo root, when an explicit root pointed at one. */
    val repoRoot: File? get() = workspace

    private fun bundledOr(name: String, vararg workspaceRel: String): File? {
        bundleRegistry?.let { val f = File(it, name); if (f.isFile) return f }
        val root = workspace ?: return null
        for (rel in workspaceRel) {
            val f = File(root, rel)
            if (f.isFile) return f
        }
        return null
    }

    fun documents(): Documents = Documents(
        taxonomy = bundledOr("taxonomy.json", "engine/data/taxonomy.json"),
        capabilities = bundledOr("capabilities.json", "engine/data/capabilities.json"),
        inspectorMeta = bundledOr(
            "inspector-meta.json",
            "ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json",
            "engine/data/inspector-meta.json",
        ),
        controls = bundledOr("controls.json", "engine/data/controls.json"),
        vendoredControls = VENDOR_KEYS.mapNotNull { key ->
            bundledOr(
                "controls.$key.json",
                "bridge/desktop/controls-data/controls.$key.json",
            )
        },
        logos = bundledOr("logos.json", "engine/data/logos.json"),
        irSlotBinding = bundledOr(
            "ir-slot-binding.json",
            "bridge/desktop/ir-slot-binding.json",
            "_research/native/p3c/ir-slot-binding.json",
        ),
    )

    /** Null when the documents are not reachable; the caller then keeps the audited model. */
    fun registry(): ControlRegistry? {
        val docs = documents()
        if (!docs.complete) {
            FmLog.info("FM_REGISTRY_INCOMPLETE") { "missing=${docs.missing.joinToString(",")}" }
            FmLog.info("FM_REGISTRY_ROOTS") { "searched=${roots.joinToString(",")} (FM_RUNTIME_ROOT is the only dev fallback)" }
            return null
        }
        val started = System.nanoTime()
        val registry = runCatching {
            ControlRegistryLoader.load(
                ControlRegistryLoader.Sources(
                    taxonomyJson = docs.taxonomy!!.readText(),
                    capabilitiesJson = docs.capabilities!!.readText(),
                    inspectorMetaJson = docs.inspectorMeta!!.readText(),
                    controlsJson = docs.controls!!.readText(),
                    vendoredControlsJson = docs.vendoredControls.map { it.readText() },
                    logosJson = docs.logos!!.readText(),
                ),
            )
        }.onFailure { FmLog.warn("FM_REGISTRY_FAIL") { "${it.message}"} }.getOrNull() ?: return null
        val ms = (System.nanoTime() - started) / 1_000_000L
        val stats = registry.stats
        FmLog.info("FM_REGISTRY_READY") {
            "cards=${stats.cards} visible=${stats.visibleControls} " +
                "routable=${stats.routableControls} consumed=${stats.consumedControls} " +
                "gated=${stats.gatedControls} ms=$ms root=${docs.taxonomy!!.parent}"
        }
        return registry
    }

    fun binding(): IrSlotBinding {
        val file = documents().irSlotBinding
        if (file == null) {
            FmLog.info("FM_IR_BINDING_MISSING") { "falls back to the registry consumer" }
            return IrSlotBinding.EMPTY
        }
        val binding = runCatching { IrSlotBinding.load(file.readText()) }
            .onFailure { FmLog.warn("FM_IR_BINDING_FAIL") { "${it.message}"} }
            .getOrNull() ?: return IrSlotBinding.EMPTY
        val s = binding.stats
        FmLog.info("FM_IR_BINDING_READY") { "styles=${s.styles} entries=${s.entries} expressible=${s.expressible} unsupported=${s.unsupported} needsRegen=${s.needsRegen} file=${file.path}" }
        return binding
    }

    companion object {
        private val VENDOR_KEYS = listOf("oppo", "xiaomi", "vivo")

        /**
         * Only for test/verify entry points that are known to run against the
         * checkout. The app never calls this: a normal launch must resolve from the
         * install root or an explicit `FM_RUNTIME_ROOT`, never by guessing upwards.
         */
        /** Explicit dev root for tests/verify entry points only. */
        fun devWorkspaceRoot(): File? {
            System.getenv("FM_RUNTIME_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val code = runCatching {
                RegistrySource::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
            }.getOrNull() ?: return null
            var dir: File? = if (code.isDirectory) code else code.parentFile
            var hops = 0
            while (dir != null && hops++ < 12) {
                if (File(dir, "engine/data/taxonomy.json").isFile) return dir
                if (File(dir, "framemark/engine/data/taxonomy.json").isFile) return File(dir, "framemark")
                dir = dir.parentFile
            }
            return null
        }
    }
}
