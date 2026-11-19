package com.framemark.native.product

import com.framemark.native.library.Json
import com.framemark.native.library.jsonMap
import com.framemark.native.workflow.DesignSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locates the repository and reads the frozen data the app ships. */
internal object RealData {
    val root: Path = findRoot()

    private fun findRoot(): Path {
        val codeRoot = runCatching {
            ControlRegistryRealDataTest::class.java.protectionDomain?.codeSource?.location?.toURI()
                ?.let(Path::of)
        }.getOrNull()?.let { if (Files.isDirectory(it)) it else it.parent }
        val starts = listOfNotNull(codeRoot, Path.of(System.getProperty("user.dir")).toAbsolutePath())
        for (start in starts) {
            var dir: Path? = start
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve("engine/data/taxonomy.json"))) return dir
                val legacyProject = dir.resolve("framemark")
                if (Files.isRegularFile(legacyProject.resolve("engine/data/taxonomy.json"))) return legacyProject
                dir = dir.parent
            }
        }
        error("repository root not found from test code source or ${System.getProperty("user.dir")}")
    }

    fun text(relative: String): String = Files.readString(root.resolve(relative.removePrefix("framemark/")))

    fun sources(): ControlRegistryLoader.Sources = ControlRegistryLoader.Sources(
        taxonomyJson = text("framemark/engine/data/taxonomy.json"),
        capabilitiesJson = text("framemark/engine/data/capabilities.json"),
        inspectorMetaJson = text("framemark/ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json"),
        controlsJson = text("framemark/engine/data/controls.json"),
        vendoredControlsJson = listOf("oppo", "xiaomi", "vivo").map {
            text("framemark/bridge/desktop/controls-data/controls.$it.json")
        },
        logosJson = text("framemark/engine/data/logos.json"),
    )

    fun registry(): ControlRegistry = ControlRegistryLoader.load(sources())

    /** IR styles whose engine JSON actually draws at least one text node. */
    fun stylesWithTextNodes(): Set<String> {
        val dir = root.resolve("engine/data/ir/styles")
        val out = HashSet<String>()
        fun hasText(node: Any?): Boolean {
            val m = node as? Map<*, *> ?: return false
            if (m["kind"] == "text") return true
            val kids = m["children"] as? List<*> ?: return false
            return kids.any { hasText(it) }
        }
        Files.walk(dir).use { walk ->
            walk.filter { it.toString().endsWith(".json") }.forEach { p ->
                val doc = Json.parse(Files.readString(p)).jsonMap()
                val id = doc["id"] as? String ?: return@forEach
                val bands = doc["bands"] as? List<*> ?: return@forEach
                if (bands.any { hasText(it) }) out += id
            }
        }
        return out
    }

    fun outputDir(): Path {
        val dir = root.resolve("ui/composeApp/build/p1b")
        Files.createDirectories(dir)
        return dir
    }
}

class ControlRegistryRealDataTest {
    @Test
    fun brand_control_probe() {
        val registry = RealData.registry()
        for (id in listOf("wm-classic", "stock-xiaomi-classic-17")) {
            val card = registry.card(id) ?: continue
            val c = card.controls.firstOrNull { it.controlId.contains("brand", true) }
            println("BRAND_PROBE card=$id control=" + c?.controlId + " kind=" + c?.sourceKind + " type=" + c?.type + " options=" + (c?.options?.size ?: -1) + " visible=" + c?.visible + " consumer=" + c?.consumer)
            val built = com.framemark.native.inspector.RegistryInspectorFactory.build(
                id, card.name, card.category, card.categoryName, card,
                com.framemark.native.inspector.IrSlotBinding.EMPTY,
            ).model
            val ic = built.controls.firstOrNull { it.controlId.contains("brand", true) }
            println("BRAND_UI card=$id type=" + ic?.type + " options=" + (ic?.options?.size ?: -1) + " param=" + ic?.param + " section=" + ic?.section)
        }
    }

    @Test
    fun every_card_has_unique_visible_controls_with_labels() {
        val registry = RealData.registry()
        for (card in registry.cards) {
            val visible = card.controls.filter { it.visible }
            assertEquals(
                visible.map { it.controlId }.distinct().size,
                visible.size,
                "${card.cardId} has duplicate visible control ids",
            )
            visible.forEach { control ->
                assertTrue(control.label.isNotBlank(), "${card.cardId}::${control.controlId} has blank label")
                assertTrue(control.consumer.isNotBlank(), "${card.cardId}::${control.controlId} has blank consumer")
            }
        }
    }

    @Test
    fun representative_hand_controls_use_plain_display_labels() {
        val registry = RealData.registry()
        fun label(cardId: String, controlId: String): String =
            registry.card(cardId)!!.controls.single { it.controlId == controlId }.label

        assertEquals("模糊强度", label("blur-classic", "slider.params:blur"))
        assertEquals("照片留白", label("blur-classic", "slider.params:marginPct"))
        assertEquals("整体文字大小", label("blur-classic", "slider.text:fontScale"))
        assertEquals("照片比例", label("ratio-3", "slider.params:canvasAspect"))
        assertEquals("方形区域大小", label("ratio-3", "slider.params:squarePct"))
        assertEquals("背景透明度", label("ratio-3", "slider.params:tintAlpha"))
    }

    @Test
    fun registry_covers_all_180_cards_without_join_loss() {
        val registry = RealData.registry()
        val stats = registry.stats

        assertEquals(187, stats.cards)
        assertEquals(69, stats.auditedCards)
        assertEquals(118, stats.unauditedCards)
        assertEquals(1023, stats.capabilityRows)
        assertEquals(779, stats.effectiveRows)
        assertEquals(244, stats.gatedRows)
        assertEquals(585, stats.platformRows)
        assertEquals(113, stats.platformStyles)
        assertEquals(1023, stats.metaRowsJoined, "capabilities ↔ inspector-meta 必须 100% 命中")
        assertEquals(585, stats.vendoredJoined, "controls.json ↔ vendored controls 必须 100% 命中")
        assertEquals(45, stats.handPanelRows)
        assertEquals(61, stats.handPanelCardPairs)
        assertEquals(3, stats.handPanelRangeMismatches.size, "面板与 meta 的已知范围分歧")
        // W4：迪士尼装饰卡没有引擎文字/参数节点 ⇒ 它们（且只有它们）允许没有控件。
        assertEquals(listOf(
            "stock-vivo-disney-chip_dale", "stock-vivo-disney-donald_duck", "stock-vivo-disney-judy",
            "stock-vivo-disney-mickey", "stock-vivo-disney-pluto",
        ), stats.unmappedCards.sorted())
        assertEquals(187, stats.cardsWithVisible + stats.emptyCards)
        assertEquals(182, stats.cardsWithVisible)
        assertEquals(5, stats.emptyCards, "5 张迪士尼装饰卡没有控件")
        assertEquals(182, stats.cardsWithVisible)
        assertEquals(1364, stats.visibleControls)
        assertEquals(244, stats.hiddenControls)
        assertEquals(244, stats.gatedControls)
        assertEquals(1608, stats.routableControls)
        assertEquals(781, stats.consumedControls)
        assertEquals(585, registry.cards.filter { it.backend == ControlBackend.Ir }.sumOf { it.visibleCount })
        assertEquals(779, registry.cards.filter { it.backend == ControlBackend.Hand }.sumOf { it.visibleCount })
        assertEquals(41, registry.cards.filter { it.backend == ControlBackend.Ir }.sumOf { c -> c.controls.count { it.consumed } })
        assertEquals(740, registry.cards.filter { it.backend == ControlBackend.Hand }.sumOf { c -> c.controls.count { it.consumed } })
        val hiddenReasons = registry.cards.flatMap { it.controls }.filter { !it.visible }
            .groupingBy { it.hiddenReason ?: "null" }.eachCount()
        assertEquals(mapOf("gated" to 244), hiddenReasons, "除门控外不应有被隐藏的控件")

        val issues = registry.validate()
        assertTrue(issues.isEmpty(), "静态校验失败：\n${issues.joinToString("\n")}")
    }

    /**
     * 2026-11-19「字标大小」：非厂商手写卡的字标尺寸可调。
     *
     * 目标集合 = 模板**真的**会画 `draw_brand_logo` 的卡（渲染 diff 证明：logoSizePct=30
     * 与 100 的像素不同）。另有 30 张非厂商卡不在此列：参数 / 色卡 / 票据 / 电影 / 比例 /
     * 毕业 / FrameMark 精选里除竖排外的卡、液态玻璃调色板，模板根本没有字标；blur-date、
     * white-3、white-4、card-3 的字标行被门控（没有可选品牌），字标永远不出现。
     * 厂商水印 12 张（下面点名）是身份的一部分，不加。
     */
    @Test
    fun every_hand_card_that_draws_a_brand_mark_exposes_one_logo_size_slider() {
        val registry = RealData.registry()
        fun markControls(cardId: String) = registry.card(cardId)!!.controls
            .filter { it.consumer == "params.logoSizePct" }

        val expected = listOf(
            "blur-classic", "personal-simple",
            "wm-classic", "wm-simple", "wm-black", "wm-canon", "wm-left", "wm-wide", "wm-right",
            "white-1", "white-2",
            "card-1", "card-2", "card-4",
            "person-1", "person-2", "person-3", "person-4", "person-5",
            "glass-white-1", "glass-white-2", "glass-white-3", "glass-card-4", "glass-pill-5",
            "glass-liquid", "glass-card",
            "fm-vertical",
        ).sorted()
        val actual = registry.cards.filter { card -> card.controls.any { it.consumer == "params.logoSizePct" } }
            .map { it.cardId }.sorted()
        assertEquals(expected, actual, "字标大小滑杆的目标卡集合变了")

        for (cardId in expected) {
            val controls = markControls(cardId)
            assertEquals(1, controls.size, "$cardId 只能有一条字标大小滑杆")
            val control = controls.single()
            assertEquals("slider.params:logoSizePct", control.controlId)
            assertEquals(ControlType.Slider, control.type)
            assertEquals("字标大小", control.label)
            assertEquals(40.0, control.min)
            assertEquals(220.0, control.max)
            assertEquals(5.0, control.step)
            assertEquals(100.0, control.defaultValue, "$cardId 默认 100 = 现状不变")
            assertEquals(100.0, control.currentValue)
            assertEquals("pct", control.unit, "百分比按 pct 词表走，UI 才显示 %")
            assertTrue(control.visible && !control.gated, "$cardId 的字标大小必须可见")
            assertEquals("params.logoSizePct", control.consumer)
            assertTrue(control.consumed, "手写卡的 params.* 由引擎直接读取")
        }

        // 厂商水印是身份的一部分：字标尺寸不跟着品牌变。taxonomy 里 vendor 类还包含
        // 厂商系统相机卡，所以这里按 12 张手写厂商水印卡点名，而不是按 category 过滤。
        val vendorWatermark = listOf(
            "vendor-hasselblad", "vendor-hasselblad-dark", "vendor-hasselblad-h",
            "vendor-hasselblad-frame", "vendor-hasselblad-matte",
            "vendor-lumo", "vendor-lumo-frame", "vendor-lumo-matte",
            "vendor-zeiss", "vendor-leica", "vendor-xiaomi", "vendor-xiaomi-single",
        )
        assertEquals(12, vendorWatermark.size)
        for (cardId in vendorWatermark) {
            val card = requireNotNull(registry.card(cardId)) { "$cardId 不在注册表里" }
            assertEquals(ControlBackend.Hand, card.backend, "$cardId 应是手写卡")
            assertEquals("vendor", card.category)
            assertTrue(
                card.controls.none { it.consumer == "params.logoSizePct" },
                "$cardId 是厂商水印卡，字标尺寸是身份的一部分，不该有字标大小滑杆",
            )
        }
    }

    /** 用户真正看到的那一列：字标大小落在 Logo 标签页，显示为百分比。 */
    @Test
    fun logo_size_slider_lands_in_the_logo_tab_as_a_percentage() {
        val registry = RealData.registry()
        val card = requireNotNull(registry.card("white-1")) { "white-1 不在注册表里" }
        val model = com.framemark.native.inspector.RegistryInspectorFactory.build(
            card.cardId, card.name, card.category, card.categoryName, card,
            com.framemark.native.inspector.IrSlotBinding.EMPTY,
        ).model
        val row = model.controls.single { it.controlId == "slider.params:logoSizePct" }
        assertEquals("字标大小", row.label)
        assertEquals(com.framemark.native.inspector.ControlType.Slider, row.type)
        assertEquals(com.framemark.native.inspector.InspectorSection.Logo, row.section)
        assertEquals("params.logoSizePct", row.param)
        assertEquals(40f, row.min)
        assertEquals(220f, row.max)
        assertEquals(5f, row.step)
        assertEquals("%", row.unit)
        assertEquals(1f, row.unitScale)
        assertNull(row.disabledReason)
        assertEquals("100.0", row.declaredDefault)
        assertEquals("100.0", row.currentText)
    }

    /**
     * 2026-11-19「模糊度」：玻璃族手写卡的模糊强度可调。
     *
     * 目标集合 = 模板**真的**读 `params.blur` 的 4 张玻璃卡（variants.json 的 base
     * 为 glass / glassSideCard / glassPalettePortrait）：
     *  - glass-liquid（glass.rs）与 glass-palette（glass_palette_portrait.rs）按
     *    `blur_px = s * 0.032 * (blur / 24)` 采样；
     *  - glass-card / glass-pill-5（glass_side_card.rs）通过 `draw_blurred_backdrop`
     *    读同一个键。
     * 同属「液态玻璃」分类、但模板是 sideCard / specFooter 的 glass-white-1..3 与
     * glass-card-4 不在此列：把设计稿的 blur 从 6 改到 48，渲染结果逐字节相同
     * （负对照，见本轮报告），加了就是永不生效的死控件。
     *
     * 范围 0–48、步进 2 写在 inspector-meta.json（注册表真正读的那份）；默认值
     * 沿用每张卡自己的现状（引擎缺省 24，glass-card 26、glass-pill-5 30 是它们的
     * 声明值），不硬改成 24，否则「重置」会把卡片改样。
     */
    @Test
    fun every_glass_family_hand_card_exposes_one_blur_strength_slider() {
        val registry = RealData.registry()
        val expected = linkedMapOf(
            "glass-liquid" to 24.0,
            "glass-palette" to 24.0,
            "glass-card" to 26.0,
            "glass-pill-5" to 30.0,
        )
        for ((cardId, current) in expected) {
            val card = requireNotNull(registry.card(cardId)) { "$cardId 不在注册表里" }
            val controls = card.controls.filter { it.consumer == "params.blur" }
            assertEquals(1, controls.size, "$cardId 只能有一条模糊度滑杆")
            val control = controls.single()
            assertEquals("slider.params:blur", control.controlId)
            assertEquals(ControlType.Slider, control.type)
            assertEquals("模糊度", control.label)
            assertEquals(0.0, control.min)
            assertEquals(48.0, control.max)
            assertEquals(2.0, control.step)
            assertEquals(current, control.defaultValue, "$cardId 的默认值 = 该卡现状")
            assertEquals(current, control.currentValue)
            assertEquals("", control.unit, "模糊度没有单位，面板不显示后缀")
            assertTrue(control.visible && !control.gated, "$cardId 的模糊度必须可见")
            assertEquals("params.blur", control.consumer)
            assertTrue(control.consumed, "手写卡的 params.* 由引擎直接读取")
        }

        // 背景模糊族的滑杆保持原名「模糊强度」，这次只加玻璃族的「模糊度」。
        assertEquals(
            "模糊强度",
            registry.card("blur-classic")!!.controls.single { it.controlId == "slider.params:blur" }.label,
        )

        // 负对照：模板不读 params.blur 的 4 张「液态玻璃」卡没有这条滑杆。
        for (cardId in listOf("glass-white-1", "glass-white-2", "glass-white-3", "glass-card-4")) {
            val card = requireNotNull(registry.card(cardId)) { "$cardId 不在注册表里" }
            assertTrue(
                card.controls.none { it.consumer == "params.blur" },
                "$cardId 的模板（sideCard / specFooter）不读 params.blur，不该有模糊度滑杆",
            )
        }
    }

    /** 用户真正看到的那一列：模糊度落在「样式」页、不带单位后缀、0–48 步进 2。 */
    @Test
    fun glass_blur_slider_lands_in_the_style_tab_without_a_unit() {
        val registry = RealData.registry()
        val card = requireNotNull(registry.card("glass-liquid")) { "glass-liquid 不在注册表里" }
        val model = com.framemark.native.inspector.RegistryInspectorFactory.build(
            card.cardId, card.name, card.category, card.categoryName, card,
            com.framemark.native.inspector.IrSlotBinding.EMPTY,
        ).model
        val row = model.controls.single { it.controlId == "slider.params:blur" }
        assertEquals("模糊度", row.label)
        assertEquals(com.framemark.native.inspector.ControlType.Slider, row.type)
        // 模糊度是外观：布局分组 + 样式页（标签页由 param 推导，不需要新数据）。
        assertEquals(com.framemark.native.inspector.InspectorSection.Layout, row.section)
        assertEquals(
            com.framemark.native.inspector.InspectorPage.Style,
            com.framemark.native.inspector.inspectorPageOf(row),
        )
        assertEquals("params.blur", row.param)
        assertEquals(0f, row.min)
        assertEquals(48f, row.max)
        assertEquals(2f, row.step)
        assertNull(row.unit, "模糊度没有单位")
        assertEquals(1f, row.unitScale)
        assertNull(row.disabledReason)
        assertEquals("24.0", row.declaredDefault)
        assertEquals("24.0", row.currentText)
    }

    /**
     * W1（2026-10-31）：用户口径「厂商模板只需要能修改文字内容」。
     * 106 张 IR 卡每张至少要有一条可见的文字控件，且键必须落在 bundle 节点路径上
     * （`params.slotText:b<band>/g<n>...`），不能是厂商拼写。
     */
    @Test
    fun every_ir_card_exposes_an_editable_text_line_with_a_bundle_node() {
        val registry = RealData.registry()
        val irCards = registry.cards.filter { it.backend == ControlBackend.Ir }
        // W4（2026-10-31）：迪士尼 PAG 卡是纯装饰（模板本身没有文字节点），
        // 判据按"该卡引擎样式里确实有文字节点"放行，不用卡 id 白名单。
        val withText = RealData.stylesWithTextNodes()
        val textFree = irCards.filter { card -> card.styleId?.let { it in withText } != true }.mapNotNull { it.styleId }.sorted()
        assertEquals(listOf(
            "vivo-disney:chip_dale", "vivo-disney:donald_duck", "vivo-disney:judy",
            "vivo-disney:mickey", "vivo-disney:pluto",
        ), textFree, "只有迪士尼装饰卡允许没有文字行")
        val noText = irCards.filter { card ->
            card.styleId?.let { it in withText } == true && card.controls.none { it.visible && it.sourceKind == "text" }
        }
        assertTrue(noText.isEmpty(), "这些 IR 卡没有任何可改的文字行：${noText.map { it.cardId }}")

        val textControls = irCards.flatMap { card -> card.controls.filter { it.visible && it.sourceKind == "text" } }
        assertEquals(214, textControls.size, "84 条既有 + 87 条 W1 + 28 条 W4 机型行 + 7 条 leica 5/88/89 + 8 条 OPPO 新卡")

        // 注册表层 consumer 是 vendor 拼写；真正发给引擎的是绑定表的 bundle 节点路径。
        val binding = com.framemark.native.inspector.IrSlotBinding.load(
            RealData.text("framemark/bridge/desktop/ir-slot-binding.json"),
        )
        val bundleKey = Regex("params\\.slotText:b\\d+(/g\\d+)+")
        val bad = ArrayList<String>()
        for (card in irCards) {
            for (control in card.controls.filter { it.visible && it.sourceKind == "text" }) {
                val entry = binding.entry(card.styleId, control.controlId)
                val key = entry?.primaryConsumer
                if (entry == null) bad += "${card.styleId}::${control.controlId} 无绑定"
                else if (key == null || !bundleKey.matches(key)) bad += "${card.styleId}::${control.controlId} → $key"
            }
        }
        assertTrue(bad.isEmpty(), "文字控件必须落到 bundle 节点路径：${bad.take(6)}")
        println("P9_TEXT_CONTROLS ir=${irCards.size} text=${textControls.size}")
    }

    @Test
    fun gated_controls_stay_hidden_and_visible_ones_are_routable() {
        val registry = RealData.registry()
        val all = registry.cards.flatMap { card -> card.controls.map { card to it } }
        assertEquals(1023 + 585, all.size)

        var gated = 0
        all.forEach { (card, control) ->
            if (control.gated) {
                gated++
                assertTrue(!control.visible, "${card.cardId}::${control.controlId} gated 却可见")
            }
            if (control.visible) {
                assertTrue(control.consumer.isNotBlank(), "${card.cardId}::${control.controlId} 可见但无 consumer")
                assertTrue(control.routable, "${card.cardId}::${control.controlId} consumer 不可路由")
                assertTrue(control.label.any { it in '\u4e00'..'\u9fff' } || control.label.any { it.isLetter() })
            }
        }
        assertEquals(244, gated)
        assertEquals(244, all.count { it.second.gated })

        val irConsumed = registry.cards.filter { it.backend == ControlBackend.Ir }
            .flatMap { it.controls }.filter { it.consumed }
        assertTrue(irConsumed.isNotEmpty())
        assertTrue(irConsumed.all { it.consumer == "theme" }, "IR 目前只有 theme 被引擎读取")

        val handLogo = registry.cards.filter { it.backend == ControlBackend.Hand }
            .flatMap { it.controls }.filter { it.consumer == "logo" }
        assertTrue(handLogo.isNotEmpty())
        assertTrue(handLogo.none { it.consumed }, "logo 尚未经过桌面桥接（B3）")
    }

    @Test
    fun every_visible_control_writes_somewhere_in_the_design_snapshot() {
        val registry = RealData.registry()
        var applied = 0
        registry.cards.filter { it.visibleCount > 0 }.forEach { card ->
            card.visibleControls.forEach { control ->
                val value = control.currentValue
                    ?: control.defaultValue?.takeIf { it.toString().isNotBlank() }
                    ?: control.options.firstOrNull()?.value
                    ?: return@forEach
                var design = PhotoDesign.base(card.cardId, card.styleId ?: card.cardId)
                design = design.applyConsumer(control.consumer, value)
                // WP-FIX2 removed RenderRequest/RenderRequestFactory (no production
                // caller). `toDesignSnapshot` is the payload the desktop bridge reads,
                // so the "somewhere" a consumer writes must show up there.
                val snap = design.toDesignSnapshot()
                val key = control.consumer.substringAfter('.')
                val written = when {
                    control.consumer == "theme" -> snap.params.containsKey("theme")
                    control.consumer == "ratio" -> snap.params.containsKey("ratio")
                    control.consumer == "fontScale" -> snap.text.containsKey("fontScale")
                    control.consumer == "logo" -> {
                        val logo = value.toString()
                        if (logo.isBlank() || logo == "auto") snap.logoBrand == null else snap.logoBrand == logo
                    }
                    control.consumer.startsWith("params.") -> snap.params.containsKey(key)
                    else -> snap.text.containsKey(key)
                }
                assertTrue(written, "${card.cardId}::${control.controlId} 的 ${control.consumer} 没写进设计快照")
                applied++
            }
        }
        assertTrue(applied > 100, "抽样控件数太少：$applied")
    }

    @Test
    fun dump_registry_stats_and_noop_design_snapshots_for_evidence() {
        val registry = RealData.registry()
        val out = RealData.outputDir()
        Files.writeString(out.resolve("registry-stats.json"), statsJson(registry))

        // Evidence dump only (no assertions). The render-request JSON model was
        // deleted in WP-FIX2, so this dumps the design snapshot the bridge consumes.
        val snapshots = out.resolve("snapshots")
        Files.createDirectories(snapshots)
        listOf("stock-oppo-lumo_style_1", "simple-1", "card-1").forEach { cardId ->
            val card = registry.card(cardId) ?: error("card $cardId missing")
            val styleId = card.styleId ?: card.cardId
            Files.writeString(
                snapshots.resolve("$cardId.txt"),
                snapshotDump("base", PhotoDesign.base(card.cardId, styleId).toDesignSnapshot()),
            )
            val positive = positiveDesign(registry, card, styleId)
            if (positive != null) {
                Files.writeString(
                    snapshots.resolve("$cardId.positive.txt"),
                    snapshotDump("positive", positive.toDesignSnapshot()),
                )
            }
        }
    }

    private fun positiveDesign(registry: ControlRegistry, card: CardControlModel, styleId: String): PhotoDesign? {
        val visible = card.visibleControls
        // A slider whose max differs from the declared value always moves pixels on
        // hand cards; theme alone can land on a template that paints no theme area.
        val slider = visible.firstOrNull {
            it.type == ControlType.Slider && it.max != null &&
                it.consumer.startsWith("params.") &&
                it.max != (it.defaultValue as? Number)?.toDouble()
        }
        if (slider != null) {
            return PhotoDesign.base(card.cardId, styleId).applyConsumer(slider.consumer, slider.max)
        }
        val theme = visible.firstOrNull { it.consumer == "theme" && it.options.isNotEmpty() }
        if (theme != null) {
            val current = theme.currentValue?.toString() ?: theme.defaultValue?.toString()
            val other = theme.options.firstOrNull { it.value != current }?.value
            if (other != null) return PhotoDesign.base(card.cardId, styleId).withTheme(other)
        }
        return null
    }

    /** Human/agent-readable evidence dump; the JSON request model is gone (WP-FIX2). */
    private fun snapshotDump(kind: String, snapshot: DesignSnapshot): String = buildString {
        appendLine("kind=$kind")
        appendLine("cardId=${snapshot.cardId}")
        appendLine("styleId=${snapshot.styleId}")
        appendLine("params=${snapshot.params.toSortedMap()}")
        appendLine("text=${snapshot.text.toSortedMap()}")
        appendLine("logo=${snapshot.logoBrand ?: "-"}")
    }

    private fun statsJson(registry: ControlRegistry): String {
        val s = registry.stats
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"stats\": {\n")
        fun num(key: String, value: Number, last: Boolean = false) {
            builder.append("    \"").append(key).append("\": ").append(value)
            if (!last) builder.append(',')
            builder.append('\n')
        }
        num("cards", s.cards)
        num("auditedCards", s.auditedCards)
        num("unauditedCards", s.unauditedCards)
        num("cardsWithVisible", s.cardsWithVisible)
        num("emptyCards", s.emptyCards)
        num("capabilityRows", s.capabilityRows)
        num("effectiveRows", s.effectiveRows)
        num("gatedRows", s.gatedRows)
        num("platformRows", s.platformRows)
        num("platformStyles", s.platformStyles)
        num("metaRowsJoined", s.metaRowsJoined)
        num("vendoredJoined", s.vendoredJoined)
        num("handPanelRows", s.handPanelRows)
        num("handPanelCardPairs", s.handPanelCardPairs)
        num("visibleControls", s.visibleControls)
        num("hiddenControls", s.hiddenControls)
        num("gatedControls", s.gatedControls)
        num("routableControls", s.routableControls)
        num("consumedControls", s.consumedControls, last = true)
        builder.append("  },\n")

        builder.append("  \"unmappedCards\": [")
        builder.append(s.unmappedCards.joinToString(",") { ProductJson.quote(it) })
        builder.append("],\n")

        builder.append("  \"handPanelRangeMismatches\": [")
        builder.append(s.handPanelRangeMismatches.joinToString(",") { ProductJson.quote(it) })
        builder.append("],\n")

        val byReason = registry.cards.flatMap { it.controls }.filter { it.hiddenReason != null }
            .groupingBy { it.hiddenReason!! }.eachCount().toList().sortedByDescending { it.second }
        builder.append("  \"hiddenReasons\": {")
        builder.append(byReason.joinToString(",") { (k, v) -> ProductJson.quote(k) + ":" + v })
        builder.append("},\n")

        val byType = registry.cards.flatMap { it.controls }
            .groupingBy { it.type?.name ?: "null" }.eachCount().toList().sortedByDescending { it.second }
        builder.append("  \"types\": {")
        builder.append(byType.joinToString(",") { (k, v) -> ProductJson.quote(k) + ":" + v })
        builder.append("},\n")

        val byConsumer = registry.cards.flatMap { it.controls }
            .groupingBy { it.consumerSource.name }.eachCount()
        builder.append("  \"consumerSources\": {")
        builder.append(byConsumer.entries.joinToString(",") { (k, v) -> ProductJson.quote(k) + ":" + v })
        builder.append("},\n")

        val irConsumed = registry.cards.filter { it.backend == ControlBackend.Ir }
            .flatMap { it.controls }.count { it.consumed }
        val handConsumed = registry.cards.filter { it.backend == ControlBackend.Hand }
            .flatMap { it.controls }.count { it.consumed }
        builder.append("  \"consumedByBackend\": {\"Ir\":").append(irConsumed)
        builder.append(",\"Hand\":").append(handConsumed).append("},\n")

        builder.append("  \"visibleByBackend\": {\"Ir\":")
        builder.append(registry.cards.filter { it.backend == ControlBackend.Ir }.sumOf { it.visibleCount })
        builder.append(",\"Hand\":")
        builder.append(registry.cards.filter { it.backend == ControlBackend.Hand }.sumOf { it.visibleCount })
        builder.append("},\n")

        builder.append("  \"emptyCardsDetail\": [")
        builder.append(
            registry.cards.filter { it.visibleCount == 0 }.joinToString(",") { card ->
                "{\"cardId\":${ProductJson.quote(card.cardId)},\"reason\":${ProductJson.quote(card.emptyReason.orEmpty())}}"
            },
        )
        builder.append("],\n")

        builder.append("  \"cards\": [")
        builder.append(
            registry.cards.joinToString(",\n    ") { card ->
                "{\"cardId\":${ProductJson.quote(card.cardId)}" +
                    ",\"backend\":${ProductJson.quote(card.backend.name)}" +
                    ",\"audited\":${card.audited}" +
                    ",\"styleId\":${card.styleId?.let(ProductJson::quote) ?: "null"}" +
                    ",\"controls\":${card.controls.size}" +
                    ",\"visible\":${card.visibleCount}" +
                    ",\"gated\":${card.controls.count { it.gated }}" +
                    ",\"consumed\":${card.controls.count { it.consumed }}" +
                    ",\"emptyReason\":${card.emptyReason?.let(ProductJson::quote) ?: "null"}}"
            },
        )
        builder.append("]\n}\n")
        return builder.toString()
    }
}
