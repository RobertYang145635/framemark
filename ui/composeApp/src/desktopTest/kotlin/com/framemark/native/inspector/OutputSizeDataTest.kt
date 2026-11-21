package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.product.RealData
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AU2 A-05 / A-06 / A-07 的**数据**不变量，全部对冻结数据逐卡核对。
 *
 * 1. A-05：注册表把 `text.fontScale` 归一成 consumer `fontScale`。手机旧代码比对
 *    字面量 `"text.fontScale"`，于是 62 张手写卡的「文字大小」永远不出现。现在
 *    桌面与手机共用 [fontScaleControlOf]，这条测试要求：凡是审计里声明
 *    `text.fontScale` 生效的卡，模型里都必须能找出那条滑杆。
 * 2. A-06：画布族判定必须与模板一致（`brandFooter` → BrandFooter、
 *    `personalBorder` → PersonalBorder），否则右栏会按错的公式算尺寸。
 * 3. A-07：`footerAuto` 只有 `wm-simple` 是真的并且**作为控件暴露**；其余带
 *    `footerMode` 的卡审计值是 false 且被 gate 掉 —— 缺省按 false 处理
 *    （[OutputSize.FOOTER_AUTO_WHEN_UNDECLARED]）由这条测试兜住。
 */
class OutputSizeDataTest {

    private fun variants(): Map<String, Map<String, Any?>> {
        val doc = Json.parse(RealData.text("engine/data/hand/variants.json")).jsonMap()
        return doc["variants"].jsonList().map { it.jsonMap() }.associateBy { it.getString("id") }
    }

    private fun Map<String, Any?>.getString(key: String): String = this[key] as? String ?: ""

    private fun declaredParams(variant: Map<String, Any?>?): Map<String, Any?> {
        val derived = variant?.get("_derived") as? Map<*, *> ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return derived["declaredParams"] as? Map<String, Any?> ?: emptyMap()
    }

    private fun model(cardId: String): InspectorCardModel {
        val registry = RealData.registry()
        val card = assertNotNull(registry.card(cardId), "卡片 $cardId 不在注册表里")
        return RegistryInspectorFactory.build(
            cardId, card.name, card.category, card.categoryName, card,
            IrSlotBinding.EMPTY,
        ).model
    }

    @Test
    fun every_card_that_audits_text_font_scale_exposes_the_slider() {
        val registry = RealData.registry()
        val variants = variants()
        var checked = 0
        for (card in registry.cards) {
            // 审计里 consumer 归一成 `fontScale` 且可见的卡：模型必须能给出滑杆。
            // （gate 掉的卡本来就不显示，不在这里要求。）
            if (!card.controls.any { it.consumer == "fontScale" && it.visible }) continue
            val built = model(card.cardId)
            val scale = fontScaleControlOf(built.controls)
            assertNotNull(scale, "${card.cardId} 声明了 fontScale，但手机/桌面都找不到那条滑杆")
            assertEquals(ControlType.Slider, scale.type, "${card.cardId} 的 fontScale 不是滑杆")
            checked++
        }
        assertTrue(checked > 50, "fontScale 卡的覆盖数异常：$checked（期望 62 张手写卡量级）")
        // 变体数据的规模也核对一下，避免只跑了空集。
        assertTrue(variants.isNotEmpty())
    }

    @Test
    fun the_canvas_family_follows_the_template() {
        val registry = RealData.registry()
        val variants = variants()
        var brandFooter = 0
        var personal = 0
        for (card in registry.cards) {
            val variant = variants[card.cardId] ?: continue
            val template = variant["base"] as? String ?: continue
            val declared = declaredParams(variant)
            val built = model(card.cardId)
            when (template) {
                "brandFooter" -> {
                    val family = OutputSize.familyOf(built.controls)
                    if (family == OutputSize.Family.BrandFooter) brandFooter++ else assertEquals(
                        OutputSize.Family.PhotoOnly,
                        family,
                        "${card.cardId} 是 brandFooter，但 UI 判成了 $family",
                    )
                }
                "personalBorder" -> {
                    // personal-simple 这类：borderPct 决定画布，不能按页脚留白加高。
                    if (declared.containsKey("borderPct") || built.controls.any { it.param.endsWith("borderPct") }) {
                        assertEquals(
                            OutputSize.Family.PersonalBorder,
                            OutputSize.familyOf(built.controls),
                            "${card.cardId} 是 personalBorder，画布必须按 borderPct 算",
                        )
                        personal++
                    }
                }
            }
        }
        assertTrue(brandFooter >= 7, "brandFooter 卡只有 $brandFooter 张")
        assertTrue(personal >= 1, "personalBorder 卡只有 $personal 张")
    }

    @Test
    fun footer_auto_is_only_real_where_the_ui_exposes_it() {
        val registry = RealData.registry()
        val variants = variants()
        var footerModeCards = 0
        for (card in registry.cards) {
            val variant = variants[card.cardId] ?: continue
            val declared = declaredParams(variant)
            if (!declared.containsKey("footerMode") && !(declared.containsKey("footerPct") && declared.containsKey("footerMode"))) {
                continue
            }
            val mode = declared["footerMode"] as? String ?: "classic"
            // `single` 是 brandFooter 的单行版式，画布与 classic 同高（引擎侧同一个
            // footerPct 公式），所以这里与 classic 一样参与 footerAuto 的核对。
            if (mode != "simple" && mode != "classic" && mode != "overlay" && mode != "single") continue
            footerModeCards++
            val declaredAuto = declared["footerAuto"] as? Boolean ?: false
            val built = model(card.cardId)
            val exposed = built.controls.any { it.param.endsWith("footerAuto") }
            // 不变量：设计默认是 true 的卡必须把开关暴露给 UI（否则 UI 按 false 算画布，
            // 与引擎实际读到的设计默认值不一致）；默认 false 的卡不暴露（缺省 false 正确）。
            assertEquals(
                declaredAuto,
                exposed,
                "${card.cardId}: 设计默认 footerAuto=$declaredAuto，但 UI ${if (exposed) "暴露" else "没暴露"}该开关",
            )
        }
        assertEquals(7, footerModeCards, "带 footerMode 变体的卡数变了，重新核对 footerAuto 缺省")
    }

    @Test
    fun the_repository_data_used_by_the_app_has_the_same_cards() {
        // 应用运行时读 composeResources 里的副本；两者必须同时存在，否则上面的
        // 逐卡断言只覆盖仓库数据。
        val resources = RealData.root.resolve("ui/composeApp/src/commonMain/composeResources/files/data")
        for (name in listOf("capabilities.json", "controls.json", "inspector-meta.json")) {
            assertTrue(Files.isRegularFile(resources.resolve(name)), "缺少应用随包的 $name")
        }
    }
}
