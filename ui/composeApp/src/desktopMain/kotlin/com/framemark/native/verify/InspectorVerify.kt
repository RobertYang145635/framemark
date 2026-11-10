package com.framemark.native.verify

import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorDataSource
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.library.LibraryDataSource
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import com.framemark.native.product.FmLog

/**
 * U3 contract entry point: builds the inspector model for the ten sample cards
 * through the same [InspectorModelFactory] the phone UI uses and prints the
 * membership / section / label sets as stable lines for `tools/u3-compare.cjs`.
 *
 * Run with the desktop classpath from `dumpDesktopClasspath`:
 *
 *   java -cp "$(cat composeApp/build/desktop-classpath.txt)" \
 *       com.framemark.native.verify.InspectorVerifyKt
 */
private val SAMPLE_CARDS = listOf(
    // audited hand cards (capabilities effective/gated)
    "wm-classic",
    "vendor-hasselblad",
    "ratio-1",
    "glass-liquid",
    "ticket-1",
    "white-1",
    "color-walk",
    // unaudited stock cards (controls.json rows)
    "stock-oppo-camerapro-liquid_glass_style_1",
    "stock-xiaomi-standard-8",
    // unaudited stock style with zero declared rows -> neutral empty state expected
    "stock-oppo-hassel_style_4",
)

fun main(): Unit = runBlocking {
    val library = LibraryDataSource.load()
    if (library.error != null) {
        FmLog.warn("FM_U3_FAIL") { "library=${library.error}" }
        exitProcess(1)
    }
    val inspector = InspectorDataSource.load()
    if (inspector.error != null) {
        FmLog.warn("FM_U3_FAIL") { "inspector=${inspector.error}" }
        exitProcess(1)
    }
    val snapshot = inspector.snapshot
    FmLog.info("FM_U3_META") {
        "capabilities=${snapshot.capabilityCards} audited=${snapshot.auditedCards} " +
            "platformRows=${snapshot.platformRowCount} metaRecords=${snapshot.metaRecords} " +
            "metaControls=${snapshot.metaControls}"
    }
    val cards = library.snapshot.cards.associateBy { it.id }
    var missing = 0
    SAMPLE_CARDS.forEach { cardId ->
        val card = cards[cardId]
        if (card == null) {
            FmLog.info("FM_U3_CARD") { "cardId=$cardId missing=1" }
            missing++
            return@forEach
        }
        val model = InspectorModelFactory.build(card, snapshot)
        printCard(model)
    }
    FmLog.info("FM_U3_SAMPLE") { "requested=${SAMPLE_CARDS.size} missing=$missing" }
    exitProcess(if (missing == 0) 0 else 1)
}

private fun printCard(model: InspectorCardModel) {
    FmLog.info("FM_U3_CARD") {
        "cardId=${model.cardId} audited=${model.audited} " +
            "effectiveCount=${model.effectiveCount ?: -1} gatedCount=${model.gatedCount ?: -1} " +
            "declaredCount=${model.declaredCount ?: -1} visibleCount=${model.visibleCount} " +
            "hidden=${model.hiddenIds.size} empty=${if (model.empty) 1 else 0}"
    }
    FmLog.info("FM_U3_IDS") { "cardId=${model.cardId} kind=effective ids=${model.effectiveIds.joinToString(",")}" }
    FmLog.info("FM_U3_IDS") { "cardId=${model.cardId} kind=gated ids=${model.gatedIds.joinToString(",")}" }
    FmLog.info("FM_U3_IDS") { "cardId=${model.cardId} kind=visible ids=${model.visibleIds.joinToString(",")}" }
    FmLog.info("FM_U3_IDS") { "cardId=${model.cardId} kind=hidden ids=${model.hiddenIds.joinToString(",")}" }
    model.sections.forEach { section ->
        FmLog.info("FM_U3_SECTION") {
            "cardId=${model.cardId} section=${section.section.name} " +
                "ids=${section.controls.joinToString(",") { it.controlId }}"
        }
    }
    model.sections.flatMap { section ->
        section.controls.map { control -> section.section.name to control }
    }.forEach { (section, control) ->
        FmLog.info("FM_U3_LABEL") {
            "cardId=${model.cardId} section=$section " +
                "label=${jsonString(control.label)}"
        }
    }
}

private fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    value.forEach { ch ->
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
        }
    }
    return sb.append('"').toString()
}
