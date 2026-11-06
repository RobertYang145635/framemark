package com.framemark.native.inspector

import com.framemark.native.workflow.TextSlot

/**
 * 自动匹配 = **按照片的品牌**（演示稿 `logo.js` `lgAutoBrand()` / `lgPaneLive()`）：
 * 照片 EXIF 的 `Make` → 品牌键 → 品牌库条目。演示稿把这条链写在 `core/js`，
 * 原生端唯一能读到的「照片品牌」是引擎渲染报告里的文字 slot：
 * `engine/data/hand/text-slots.json` 的 `wm-classic#make#0`（`source=make`，
 * 内容就是照片 EXIF 的 Make），也就是文字页「品牌」那一行。
 *
 * 这里只做**只读映射**：
 *  - [MatchTable] 与 `src/renderer/src/core/brands.ts` `matchBrand()`、
 *    `engine/e2b/src/brand.rs` `match_brand()` 同序（首个命中生效）。表只抄不增删，
 *    免得两端对同一张照片给出不同品牌；
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

    /** `matchBrand()` 的表，顺序即优先级（`core/brands.ts:120`、`brand.rs:69`）。 */
    private val MatchTable: List<Pair<String, List<String>>> = listOf(
        "laika" to listOf("leica", "leitz"),
        "canon" to listOf("canon"),
        "nikon" to listOf("nikon"),
        "sony" to listOf("sony", "ilce", "alpha"),
        "panasionic" to listOf("panasonic", "dc-", "lumix"),
        "lumix" to listOf("lumix"),
        "fushi" to listOf("fuji", "fujifilm"),
        "hasu" to listOf("hasselblad"),
        "aolinbasi" to listOf("olympus", "om system"),
        "binde" to listOf("pentax"),
        "ricoh" to listOf("ricoh", "gr "),
        "caisi" to listOf("zeiss", "carl zeiss"),
        "dji" to listOf("dji", "mavic", "osmo"),
        "insta360" to listOf("insta360"),
        "konica_minolta" to listOf("konica minolta", "konicaminolta"),
        "contax" to listOf("contax"),
        "minolta" to listOf("minolta"),
        "konica" to listOf("konica"),
        "yashica" to listOf("yashica"),
        "rollei" to listOf("rollei", "rolleiflex"),
        "voigtlander" to listOf("voigtlander", "voigtländer"),
        "mamiya" to listOf("mamiya"),
        "bronica" to listOf("bronica", "zenza"),
        "kodak" to listOf("kodak", "eastman"),
        "polaroid" to listOf("polaroid"),
        "agfa" to listOf("agfa"),
        "praktica" to listOf("praktica", "practika"),
        "exakta" to listOf("exakta"),
        "minox" to listOf("minox"),
        "sinar" to listOf("sinar"),
        "linhof" to listOf("linhof"),
        "alpa" to listOf("alpa"),
        "zenit" to listOf("zenit"),
        "lomo" to listOf("lomo", "lomography"),
        "seagull" to listOf("seagull"),
        "casio" to listOf("casio"),
        "cosina" to listOf("cosina"),
        "horseman" to listOf("horseman"),
        "graflex" to listOf("graflex"),
        "sigma" to listOf("sigma"),
        "tamron" to listOf("tamron"),
        "tokina" to listOf("tokina"),
        "samyang" to listOf("samyang"),
        "phaseone" to listOf("phase one", "phaseone", "leaf"),
        "gopro" to listOf("gopro"),
        "nokia" to listOf("nokia"),
        "motorola" to listOf("motorola", "moto "),
        "google" to listOf("google", "pixel"),
        "realme" to listOf("realme"),
        "nothing" to listOf("nothing"),
        "nubia" to listOf("nubia", "zte "),
        "honor" to listOf("honor"),
        "huawei" to listOf("huawei"),
        "xiaomi" to listOf("xiaomi", "redmi", "mi "),
        "oppo" to listOf("oppo"),
        "vivo" to listOf("vivo"),
        "samsung" to listOf("samsung", "galaxy"),
        "iqoo" to listOf("iqoo"),
        "meizu" to listOf("meizu"),
        "yijia" to listOf("oneplus", "one plus"),
        "apple" to listOf("apple", "iphone"),
    )

    /** 目录折叠：`LogoBrandCatalog` 把 LUMIX 并进 Panasonic，条目键也要跟着折。 */
    private val KeyAlias = mapOf("lumix" to "panasionic")

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
        return null
    }

    /** 那一行 slot 本身（供调用方判断「有没有这一行」）。 */
    fun makeSlot(slots: List<TextSlot>): TextSlot? = slots.firstOrNull { slot ->
        logicalName(slot) in BrandSlotNames
    }

    /** `matchBrand()` 的原生版：命中返回品牌键，没命中返回 null。 */
    fun brandKeyForMake(make: String): String? {
        val haystack = make.trim().lowercase()
        if (haystack.isEmpty()) return null
        for ((key, words) in MatchTable) {
            if (words.any { haystack.contains(it) }) return KeyAlias[key] ?: key
        }
        return null
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
     * 取第一个正好是品牌行名字的段；都命不中才退回最后一段。
     */
    private fun logicalName(slot: TextSlot): String {
        val raw = slot.key ?: slot.id
        for (part in raw.split(':', '#')) {
            val name = part.trim().lowercase()
            if (name in BrandSlotNames) return name
        }
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
