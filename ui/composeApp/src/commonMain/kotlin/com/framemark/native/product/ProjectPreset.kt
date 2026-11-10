package com.framemark.native.product

const val PRESET_SCHEMA_VERSION = 1

/**
 * A reusable design: border, every control value, text, the watermark **including
 * its PNG bytes**, export settings and the schema version. Applying it to another
 * photo reproduces the design without depending on any external asset file.
 */
data class ProjectPreset(
    val schemaVersion: Int = PRESET_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val design: PhotoDesign,
    val exportSettings: ExportSettings = ExportSettings(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val overlay: ProjectWatermarkSpec? get() = design.overlay

    val embeddedWatermarkBytes: Int get() = design.overlay?.asset?.byteSize ?: 0

    companion object {
        fun cleanName(name: String): String = name.trim().ifBlank { "我的预设" }

        /** Captures the active photo's design; the watermark travels inside it. */
        fun fromProject(
            project: Project,
            id: String,
            name: String,
            now: Long = 0,
        ): ProjectPreset? {
            val design = project.activeDesign ?: return null
            return ProjectPreset(
                id = id,
                name = cleanName(name),
                design = design,
                exportSettings = project.exportSettings,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
