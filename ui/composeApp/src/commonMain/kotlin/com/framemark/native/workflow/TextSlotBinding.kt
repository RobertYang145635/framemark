package com.framemark.native.workflow

/**
 * One painted text run exactly as a renderer reported it, before any platform
 * scaling. Kept as plain data so every backend (desktop IR/hand report, Android
 * geom report) can feed the same identity and geometry rules.
 */
data class ReportTextRow(
    val text: String,
    val raw: String,
    /** Flat override identity (`hand:<scope>:<id>`) when the renderer prints one. */
    val key: String?,
    /** IR node path (`b0/g0/…`) when the renderer prints one. */
    val path: String?,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val fontSize: Double? = null,
    val baseline: Double? = null,
    val lines: Int = 1,
    /** Declared row with no draw for this photo. */
    val present: Boolean = true,
    /** The row's identity is an explicit renderer identity, not content-derived. */
    val stable: Boolean = false,
    val hidden: Boolean = false,
    /** 引擎给的「正常照片下这一格会写什么」；手写模板没有。 */
    val sample: String = "",
)

/**
 * Binds reported rows to [TextSlot]s in **image pixels**.
 *
 * Only the two explicit renderer identities are accepted: the IR node path for
 * IR cards and the flat `hand:` key for hand cards. A content-derived legacy
 * spelling stays visible in the report but is never offered as an edit target,
 * because it moves when the text, EXIF or paint order changes.
 */
fun bindReportTextSlots(
    rows: List<ReportTextRow>,
    scale: Double,
    backend: String,
): List<TextSlot> = rows.map { row ->
    val key = when (backend) {
        "ir" -> row.path?.takeIf(::isWritableTextSlotKey)
            ?: row.key?.takeIf { row.stable && isWritableTextSlotKey(it) }
        else -> row.key?.takeIf { row.stable && isWritableTextSlotKey(it) }
    }
    val finite = row.x.isFinite() && row.y.isFinite() && row.w.isFinite() && row.h.isFinite()
    TextSlot(
        id = row.path ?: key ?: row.text.take(24),
        text = row.text,
        x = row.x * scale,
        y = row.y * scale,
        w = row.w * scale,
        h = row.h * scale,
        fontSize = row.fontSize?.takeIf { it.isFinite() }?.times(scale),
        baseline = row.baseline?.takeIf { it.isFinite() }?.times(scale),
        lines = row.lines.coerceAtLeast(1),
        present = row.present,
        nodePath = row.path,
        key = key,
        raw = row.raw,
        sample = row.sample,
        hidden = row.hidden,
        stable = key != null,
        located = finite && row.w > 0.0 && row.h > 0.0,
    )
}
