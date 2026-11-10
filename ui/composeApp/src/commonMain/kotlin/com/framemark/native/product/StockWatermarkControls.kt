package com.framemark.native.product

/**
 * 「这种是需要放开的，有的边框需要定死，有的不需要」(用户口径)：厂商规格（IR）边框
 * **不是**一律只读。
 *
 * 一张卡只要声明了 [ANCHOR_PARAM] 或 [OFFSET_X_PARAM] / [OFFSET_Y_PARAM] 中的任意一个，
 * 它的水印就是可移动的，样式页必须把这三个控件露出来；一个都没声明的卡仍然只给只读
 * 「边框规格」块。这条判定**只在这里定义一次**，手机（`PhoneParameterPages` 样式页）与
 * 桌面（`CuratedPage` 样式页 / `ControlItem`）都读它。
 *
 * 三处引擎/桥接证据（不是猜测）：
 *  - `engine/e2b/src/ir.rs` 的 `solve()` 在 `env.ov.has_box_move()` 时按 `stockAnchor`
 *    （九宫格 `anchor_axes`，`vendor` 表示不动）加 `stockOffsetXPct|YPct` 刚体平移
 *    `is_movable_band()` 的波段；
 *  - `bridge/android/src/paint.rs` 从 `params.stockAnchor` / `params.stockOffsetXPct`
 *    / `params.stockOffsetYPct` 读这三个值；
 *  - `data`（`controls.json`）里 `anchorGrid` / `xyOffset` 行声明的正是这三个 param。
 *
 * 设计键有两种写法都要认：`ControlSpec.consumer` 与 `InspectorControl.param` 是
 * `params.stockAnchor`，`ControlSpec.sourceParam` 是裸的 `stockAnchor`。
 */
object StockWatermarkControls {

    const val ANCHOR_PARAM = "stockAnchor"
    const val OFFSET_X_PARAM = "stockOffsetXPct"
    const val OFFSET_Y_PARAM = "stockOffsetYPct"

    /** `stockAnchor` 的「按规格」值：保持厂商自己摆的位置（引擎侧等于不搬）。 */
    const val VENDOR_ANCHOR = "vendor"

    /** The three design keys a movable vendor watermark rides on. */
    val MOVABLE_PARAMS: Set<String> = setOf(ANCHOR_PARAM, OFFSET_X_PARAM, OFFSET_Y_PARAM)

    /**
     * `params.stockAnchor` → `stockAnchor`, `stockAnchor` → `stockAnchor`;
     * anything else (including blank/null) → null. This is the normalisation every
     * other predicate reads, so the two spellings can never drift.
     */
    fun keyOf(param: String?): String? {
        val raw = param?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val key = raw.removePrefix("params.")
        return key.takeIf { it in MOVABLE_PARAMS }
    }

    /** True for any of the three keys — i.e. "this card declares a movable watermark". */
    fun isMovableParam(param: String?): Boolean = keyOf(param) != null

    fun isAnchorParam(param: String?): Boolean = keyOf(param) == ANCHOR_PARAM

    fun isOffsetParam(param: String?): Boolean =
        keyOf(param) == OFFSET_X_PARAM || keyOf(param) == OFFSET_Y_PARAM
}

/**
 * Where a card's movable-watermark controls sit in its declared control list.
 *
 * Generic on purpose: the phone/desktop model is [com.framemark.native.inspector.InspectorControl]
 * and the registry model is [ControlSpec]; both answer with `param` through
 * [stockWatermarkPlan]'s `paramOf`.
 *
 * Exactly one control per role is exposed. A card can repeat an `anchorGrid` row
 * (the audit emits one row per style group), but `params.stockAnchor` is a single
 * design field, so only the first one is rendered and the rest are counted in
 * [duplicateAnchors] — never shown twice.
 */
data class StockWatermarkPlan<T>(
    /** The one 水印位置 control the 3×3 grid owns; null when the card declares none. */
    val anchor: T?,
    val offsetX: T?,
    val offsetY: T?,
    /** Declared controls the plan does not own (文字行、开关、底色…). */
    val others: List<T>,
    val duplicateAnchors: Int,
    val duplicateOffsets: Int,
) {
    /** True when the card declares a movable watermark at all (the read-only gate). */
    val adjustable: Boolean get() = anchor != null || offsetX != null || offsetY != null

    val offsets: List<T> get() = listOfNotNull(offsetX, offsetY)

    /** Controls the plan renders itself, grid first then the two 微调 sliders. */
    val owned: List<T> get() = listOfNotNull(anchor) + offsets
}

/** Splits [controls] into the movable-watermark roles and everything else. */
fun <T> stockWatermarkPlan(controls: List<T>, paramOf: (T) -> String?): StockWatermarkPlan<T> {
    var anchor: T? = null
    var offsetX: T? = null
    var offsetY: T? = null
    var duplicateAnchors = 0
    var duplicateOffsets = 0
    val others = ArrayList<T>(controls.size)
    controls.forEach { control ->
        when (StockWatermarkControls.keyOf(paramOf(control))) {
            StockWatermarkControls.ANCHOR_PARAM ->
                if (anchor == null) anchor = control else duplicateAnchors++
            StockWatermarkControls.OFFSET_X_PARAM ->
                if (offsetX == null) offsetX = control else duplicateOffsets++
            StockWatermarkControls.OFFSET_Y_PARAM ->
                if (offsetY == null) offsetY = control else duplicateOffsets++
            else -> others += control
        }
    }
    return StockWatermarkPlan(
        anchor = anchor,
        offsetX = offsetX,
        offsetY = offsetY,
        others = others,
        duplicateAnchors = duplicateAnchors,
        duplicateOffsets = duplicateOffsets,
    )
}

/**
 * The card-level predicate: true when one of the design keys in [params] is a
 * movable vendor-watermark key. This is the one call the read-only gate makes.
 */
fun declaresMovableStockWatermark(params: Iterable<String?>): Boolean =
    params.any(StockWatermarkControls::isMovableParam)
