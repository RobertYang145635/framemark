package com.framemark.native

/**
 * Evidence-only launch hooks (no effect on a normal launch).
 *
 * Desktop `main.kt` fills these from environment variables so the U3/U4 capture
 * tools can open a known card, run a few edits and open a workflow sheet without
 * coordinate guessing. Android leaves them at their defaults.
 */
object LaunchOptions {
    var openCardId: String? = null
    var editorItem: Int? = null

    /** Open the preset sheet once the editor has a loaded card. */
    var openPresetSheet: Boolean = false

    /**
     * Evidence-only channel probe: a render-v1 design document
     * (`{"design":{"params":…,"text":…}}`) merged into every render request for this
     * launch. It exists so the JNI→engine design path can be proven independently of
     * whether a particular inspector control happens to be wired; null in normal runs.
     */
    var designJsonOverride: String? = null

    /** Open the export sheet once the editor has a loaded card. */
    var openExportSheet: Boolean = false

    /** Generate the sample export right away (with the export sheet). */
    var runSampleExport: Boolean = false

    /** Switch to the batch tab, seed four sample items and start the demo queue. */
    var openBatch: Boolean = false
    /** Evidence: seed three paired photos (border A / border B / border A + watermark). */
    var pairDemo: Boolean = false

    /** Evidence: how many engine-wired cards the batch seeds (default 10). */
    var batchCount: Int = 10

    /** Evidence: clear the persisted batch queue before seeding. */
    var batchReset: Boolean = false

    /** Evidence: auto-start the seeded batch queue (foreground). */
    var batchAutoStart: Boolean = false

    /**
     * Evidence: make the next export of this card fail once, so the retry path can
     * be exercised end-to-end. Null in normal launches.
     */
    var batchFailCard: String? = null

    /** U5 desktop: start with one built-in sample photo loaded (no border yet). */
    var samplePhoto: Boolean = false

    /**
     * Evidence/API hook: absolute path of a user photo to render instead of the
     * bundled sample (Android X1 JNI `photoPath`; desktop ignores it).
     */
    var userPhotoPath: String? = null

    /**
     * Evidence hook: override the stage preview max edge. 0 renders at the
     * engine's native canvas size (used by the 5-card Android capture).
     */
    var previewMaxEdge: Int? = null

    /** U5 desktop evidence: pre-fill the library search (shows the selected card). */
    var libraryQuery: String? = null

    /** P2a evidence: open a known inspector tab ("watermark") on launch. */
    var openTab: String? = null

    /** Apply N deterministic control edits once the inspector model is ready. */
    var demoEdits: Int = 0

    /** Undo N steps right after the demo edits (for the redo-enabled shot). */
    var undoSteps: Int = 0

    /** Save a preset with this name right after the demo edits (evidence seeding). */
    var autoSavePreset: String? = null

    /** Evidence: preselect the export format (`jpg`/`png`/`webp`). */
    var exportFormat: String? = null

    /** Evidence: WebP VP8L (default) vs VP8 q100 for the preselected format. */
    var exportWebpLossless: Boolean = true

    /** Evidence: preselect the export long edge (e.g. 1200). */
    var exportLongEdge: Int? = null

    /**
     * P3f evidence: log where each inspector control actually landed on screen, so a
     * scripted click targets real bounds instead of guessed fractions.
     */
    var controlProbe: Boolean = false

    // Runtime guards so recomposition cannot repeat an evidence action.
    var demoApplied: Boolean = false
    var autoSaveApplied: Boolean = false
    var sheetsApplied: Boolean = false
    var batchSeeded: Boolean = false

    /**
     * W3 crash-window evidence (debug-only): park queue commits or preset migration
     * at the named stage so an external process kill/change can be proven to land
     * inside the intended window. Queue stages: `prereceipt` (W3-09), `receipt`
     * (W3-10), `midwrite` (W3-11), `beforeDone` (W3-12); preset stages:
     * `preset-after-backup` (W3-05) and `preset-after-replace` (W3-07).
     * Null in release builds and in all normal launches; set only inside
     * `if (BuildConfig.DEBUG)` in MainActivity's QA extras block.
     */
    var crashWindow: String? = null

    /**
     * W3 crash-window evidence (debug-only): directory under the app's external
     * files dir where crash-window marker files are written so the driver can
     * poll them before killing.  Set from androidMain inside `if (BuildConfig.DEBUG)`.
     */
    var crashMarkerDir: String? = null

    /** Debug QA only: a checkpoint may resume when its marker is removed. */
    var crashResumeOnMarkerRemoval: Boolean = false

    /**
     * W3 crash-window evidence (debug-only): auto-start the per-photo batch queue
     * on launch once `batchQueue.load()` has finished, so an existing Queued job
     * drives the commit pipeline without UI interaction.
     */
    var queueAutoStart: Boolean = false
}
