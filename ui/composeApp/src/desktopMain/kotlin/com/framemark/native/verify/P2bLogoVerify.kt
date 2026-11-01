package com.framemark.native.verify

import com.framemark.native.engine.DesktopNativeRender
import com.framemark.native.engine.LogoLibrary
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * #78 (Kotlin half): the desktop hand path must carry the brand picker's choice
 * onto `design.logo`, and changing the brand must change the render.
 *
 * Run it twice — once with the runtime's own e4hand (which parses `design.logo`
 * but ignores it) and once with `FM_E4HAND_EXE` pointed at the fixed engine — to
 * get the before/after pair.
 *
 *   java -cp <classpath> com.framemark.native.verify.P2bLogoVerifyKt \
 *        --out _research/native/p2b --card card-1
 */
private fun logoSha(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

private fun brandModel(cardId: String): InspectorCardModel {
    val control = InspectorControl(
        key = "$cardId::brand",
        controlId = "brand.picker",
        param = "design.logo",
        label = "品牌",
        kind = "brandPicker",
        section = InspectorSection.Logo,
        type = ControlType.Select,
        initial = ControlValue.Str("auto"),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )
    return InspectorCardModel(
        cardId = cardId,
        cardName = cardId,
        audited = true,
        effectiveCount = 1,
        gatedCount = 0,
        declaredCount = 1,
        effectiveIds = listOf(control.controlId),
        gatedIds = emptyList(),
        visibleIds = listOf(control.controlId),
        hiddenIds = emptyList(),
        controls = listOf(control),
        sections = emptyList(),
    )
}

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    fun arg(name: String, fallback: String): String {
        val i = args.indexOf("--$name")
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else fallback
    }
    val out = File(arg("out", "_research/native/p2b")).let { if (it.isAbsolute) it else File(root, it.path) }
    val cardId = arg("card", "card-1")
    out.mkdirs()

    val data = File(out, "tmp/logo-data").apply { deleteRecursively(); mkdirs() }
    val exports = File(out, "exports-logo").apply { deleteRecursively(); mkdirs() }
    WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = data.path, exportDir = exports.path)
    val engineExe = System.getenv("FM_E4HAND_EXE")
    println("FM_LOGO_VERIFY engine=${engineExe ?: "runtime-app default"} card=$cardId")

    val render = DesktopNativeRender()
    WorkflowEnv.nativeRender = render
    val logos = LogoLibrary()
    val brands = logos.availableBrands()
    require(brands.size >= 3) { "runtime logo set too small: ${logos.dir}" }
    val brandA = listOf("canon", "nikon", "sony", "leica").firstOrNull { brands.contains(it) }
        ?: brands[0]
    val brandB = brands.first { it != brandA && logos.resolve(it) != null }
    println("FM_LOGO_VERIFY brands a=$brandA b=$brandB available=${brands.size}")

    val photo = File(root, "_research/test-assets/ratio-study/4x3.jpg")
    require(photo.isFile) { "photo fixture missing" }

    val controller = DesktopProjectController(data.path)
    controller.load()
    runBlocking { controller.importPhotos(listOf(photo.path)) }
    controller.applyCard(cardId, cardId)
    controller.setExportSettings(
        ExportSettings(
            format = SampleFormat.Png,
            sizeMode = ExportSizeMode.LongEdge,
            longEdge = 700,
            filenamePattern = "logo-{index}",
        ),
    )

    fun export(brand: String?): String {
        val overrides = if (brand == null) emptyMap() else mapOf("$cardId::brand" to brand)
        controller.applyControlOverrides(brandModel(cardId), overrides)
        val resolved = logos.resolve(controller.activeDesign?.logo)
        println(
            "FM_LOGO_STATE brand=${brand ?: "-"} stored=${controller.activeDesign?.logo ?: "-"} " +
                "asset=${resolved?.name ?: "-"} ${resolved?.width ?: 0}x${resolved?.height ?: 0}",
        )
        val result = runBlocking { controller.exportActive(cardId, controller.exportSettingsForUi()) }
        return logoSha(File(result.path))
    }

    val auto = export(null)
    val a = export(brandA)
    val b = export(brandB)
    val backToAuto = export(null)

    val changed = a != b
    val autoUntouched = auto == backToAuto
    val differsFromDefault = a != auto && b != auto
    val ok = changed && autoUntouched && differsFromDefault
    println("FM_LOGO_SHA auto=$auto")
    println("FM_LOGO_SHA a=$a")
    println("FM_LOGO_SHA b=$b")
    println("FM_LOGO_RESULT changed=$changed autoStable=$autoUntouched differsFromDefault=$differsFromDefault")
    File(out, "logo-$cardId.json").writeText(
        "{\n  \"engine\": ${jsonStr(engineExe ?: "runtime-app default")},\n" +
            "  \"card\": ${jsonStr(cardId)},\n" +
            "  \"brands\": [${jsonStr(brandA)}, ${jsonStr(brandB)}],\n" +
            "  \"shaAuto\": ${jsonStr(auto)},\n  \"shaBrandA\": ${jsonStr(a)},\n  \"shaBrandB\": ${jsonStr(b)},\n" +
            "  \"shaAutoAgain\": ${jsonStr(backToAuto)},\n" +
            "  \"logoChangesPixels\": $changed,\n  \"autoIsStable\": $autoUntouched,\n" +
            "  \"differsFromCardDefault\": $differsFromDefault\n}\n",
    )
    controller.dispose()
    println(if (ok) "P2B_LOGO PASS" else "P2B_LOGO FAIL")
    if (!ok) exitProcess(1)
}

private fun jsonStr(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
