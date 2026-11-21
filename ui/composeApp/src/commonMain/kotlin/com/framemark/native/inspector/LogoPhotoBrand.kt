package com.framemark.native.inspector

import com.framemark.native.product.PhotoBrandKeys
import com.framemark.native.workflow.TextSlot

/**
 * 自动匹配 = **按照片的品牌**（演示稿 `logo.js` `lgAutoBrand()` / `lgPaneLive()`）：
 * 照片 EXIF 的 `Make` → 品牌键 → 品牌库条目。演示稿把这条链写在 `core/js`，
 * 原生端「照片品牌」有两个来源，两个都必须指向同一张表：
 *  - 引擎渲染报告里的文字 slot（`wm-classic#make#0`，`source=make`），界面拿它写
 *    「当前：自动匹配 · 按照片品牌 X」；
 *  - 照片文件自己的 EXIF `Make`（[com.framemark.native.product.ExifMake]），渲染器
 *    拿它在**每一帧**决定画哪一枚字标 —— 用户不需要再点一次「自动匹配」。
 *
 * 这里只做**只读映射**：
 *  - 厂商名 → 品牌键的匹配表在 [PhotoBrandKeys]（product 层，渲染器与界面共用一份）；
 *  - 命中的键再落回 [LogoBrandCatalog] 的条目（目录把 LUMIX 折进 Panasonic，
 *    所以 `lumix` → `panasionic`，与 `logoBrandForAssetKey()` 用的是同一份目录）；
 *  - [autoCutKey] 取引擎实际画的那一枚字标（app-logo `<key>_black`），没有时退回
 *    `LogoBrand.stemFor(null)`，与演示稿注释「按文件主干对上」同义。
 *
 * 不读文件、不改设计值：自动匹配仍然只写「没有覆盖值」，屏上显示的品牌是**读出来**的。
 */
internal object LogoPhotoBrand {

    /** 品牌行的 logical name：`classicTextRows` 认的两种写法。 */
    private val BrandSlotNames = setOf("make", "brand")

    /**
     * 机型行。有些卡把品牌画成图片（字标），品牌那一行的 `text` 是空的，机型行却带着
     * 「OPPO Find X6 Pro」这种带品牌名的文字 —— 只认品牌行的话，摘要会退回「按边框
     * 自己的品牌」，而屏上画的明明是照片品牌的字标。
     */
    private val ModelSlotNames = setOf("model", "camera")

    /**
     * 照片品牌原文：文字 slot 里 `source=make`（或 `brand`）那一行的内容。
     * 只取**没有改过**的原文（`raw` 优先）：用户改过的品牌行是他自己的字，
     * 不能拿来当「照片品牌」。
     */
    fun makeText(slots: List<TextSlot>?): String? {
        val list = slots ?: return null
        // 一张卡可能同时报多行品牌（`wm-classic#make#0` 与 `hand:wm-classic:brand`）：
        // 逐行取第一份**有字**的内容。品牌画成图片时该行会没有文字，不能因此判成「没有品牌」。
        for (slot in list) {
            if (logicalName(slot) !in BrandSlotNames) continue
            val text = (slot.raw.ifBlank { slot.text }).trim()
            if (text.isNotEmpty()) return text
        }
        // 品牌行没字（画成图片）时退回机型行：机型文字里通常带着厂商名
        // （「OPPO Find X6 Pro」），品牌表按子串匹配，照样能落到同一个品牌。
        for (slot in list) {
            if (logicalName(slot) !in ModelSlotNames) continue
            val text = (slot.raw.ifBlank { slot.text }).trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /**
     * 说明行里的「照片品牌」：**只**认渲染器按片解析的结果
     * （[com.framemark.native.product.PhotoBrandHint.brandKey]）。
     *
     * 引擎报告的品牌行文字不能拿来当照片品牌：没有 EXIF 的照片，那一行是卡片样张
     * （「SONY」）或用户手填的署名（「QA54-ALPHA」），而引擎画的字标来自照片自己的
     * Make —— 读文字就会写出「按照片品牌 Sony」，屏上却是边框自带的 Panasonic。
     */
    fun captionLabel(hintBrandKey: String?, brands: List<LogoBrand>): String? {
        val key = hintBrandKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return brands.firstOrNull { it.key == key }?.label
    }

    /** 那一行 slot 本身（供调用方判断「有没有这一行」）。 */
    fun makeSlot(slots: List<TextSlot>): TextSlot? = slots.firstOrNull { slot ->
        logicalName(slot) in BrandSlotNames
    }

    /** `matchBrand()` 的原生版（表在 [PhotoBrandKeys]）：命中返回品牌键，没命中返回 null。 */
    fun brandKeyForMake(make: String): String? = PhotoBrandKeys.brandKeyFor(make)

    /**
     * 取值域里「没有显式选择」的两种写法：空（从没选过）与 `auto`（点过自动匹配）。
     * 两种都必须**在渲染时**按照片品牌解析 —— 只在点按钮时解析一次会把品牌冻进设计里，
     * 换一张照片就不跟随了。
     */
    fun isAutoValue(logoBrand: String?): Boolean = PhotoBrandKeys.isAuto(logoBrand)

    /**
     * 照片品牌 → 该画的那一枚字标（渲染器侧的唯一入口）。
     *
     * [logoBrand] 是显式选择时返回 null —— **用户选过的品牌优先，任何自动解析都不覆盖**；
     * 只有空 / `auto` 才按照片品牌解析。照片没有 Make、品牌不在库里、或目录还没载入时
     * 同样返回 null，调用方保持「引擎用卡片自带字标」的现状，不报错也不画空白。
     */
    fun cutKeyFor(logoBrand: String?, make: String?, brands: List<LogoBrand>): String? {
        if (!isAutoValue(logoBrand)) return null
        val key = brandKeyForMake(make ?: return null) ?: return null
        val brand = brands.firstOrNull { it.key == key } ?: return null
        return autoCutKey(brand)
    }

    /**
     * 照片品牌在品牌库里的条目 + 自动匹配实际画的那枚字标。
     * 照片没有品牌行、或该品牌不在库里时返回 null —— 那才是「未收录」。
     */
    fun resolve(slots: List<TextSlot>?, brands: List<LogoBrand>): LogoPhotoBrandMatch? {
        val make = makeText(slots) ?: return null
        val key = brandKeyForMake(make) ?: return null
        val brand = brands.firstOrNull { it.key == key } ?: return null
        val version = brand.versions.firstOrNull { it.id == brand.canonicalVersion }
            ?: brand.versions.firstOrNull()
        return LogoPhotoBrandMatch(
            make = make,
            brand = brand,
            version = version,
            assetKey = autoCutKey(brand),
        )
    }

    /**
     * 自动匹配画的字标：先用品牌自己的 app-logo（`<key>_black`，引擎
     * `brand.rs` 走的就是这本 `logos/` 目录），没有就退回
     * `LogoBrand.stemFor(null)`（canonical version 的第一枚 cut）。
     */
    fun autoCutKey(brand: LogoBrand): String? {
        val stems = listOf("${brand.key}_black", "${brand.key}_white")
        for (stem in stems) {
            for (version in brand.versions) {
                version.cuts.firstOrNull { it.assetKey == stem }?.let { return it.assetKey }
            }
        }
        return brand.stemFor(null)
    }

    /**
     * 这一行 slot 的语义名。两种 id 都见过：`hand:wm-classic:make`（2 段）与
     * `wm-classic#make#0`（3 段，`text-slots.json` 的写法），所以按分隔符逐段找，
     * 取第一个正好是行名字的段；最后一段是行序号（`…model#0`），所以退回时取它前面
     * 那一段，而不是把序号当名字。
     */
    private fun logicalName(slot: TextSlot): String {
        val raw = slot.key ?: slot.id
        val parts = raw.split(':', '#').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (name in parts) {
            if (name in BrandSlotNames || name in ModelSlotNames) return name
        }
        if (parts.size >= 2 && parts.last().all { it.isDigit() }) return parts[parts.size - 2]
        return raw.substringAfterLast(':').substringAfterLast('#').lowercase()
    }
}

/** 一次「自动匹配」解析结果：照片品牌原文 + 库条目 + 该画哪一枚字标。 */
internal data class LogoPhotoBrandMatch(
    val make: String,
    val brand: LogoBrand,
    val version: LogoVersion?,
    val assetKey: String?,
)
