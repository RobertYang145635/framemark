package com.framemark.native.product

/**
 * 照片 EXIF 的 `Make` → 品牌键（`logos.json` 的 `brands[].key`）的**唯一一张表**。
 *
 * 为什么在 product 层：这张表同时被界面（`inspector.LogoPhotoBrand`，摘要行的「按照片
 * 品牌 X」）和两个渲染器（`AndroidNativeRender` / `DesktopNativeRender`，真正决定画哪
 * 一枚字标）使用，而渲染器只允许向下依赖 `product` —— 表放在这里，两端读的是同一份，
 * 不会出现「界面说 OPPO、引擎画 Panasonic」。
 *
 * 表的顺序即优先级（与 `src/renderer/src/core/brands.ts` `matchBrand()`、
 * `engine/e2b/src/brand.rs` `match_brand()` 同序，首个命中生效）。只抄不增删：
 * 三个端对同一张照片必须给出同一个品牌。
 */
object PhotoBrandKeys {

    /**
     * 取值域里「没有显式选择」的两种写法：空（从没选过）与 `auto`（点过自动匹配）。
     * 两种都必须在**渲染时**按照片品牌解析 —— 只在点按钮时解析一次会把品牌冻进设计里，
     * 换一张照片就不跟随了。
     */
    fun isAuto(logoBrand: String?): Boolean =
        logoBrand.isNullOrBlank() || logoBrand.equals("auto", ignoreCase = true)

    /**
     * 厂商名 → 品牌键；空 / 未收录 → null（调用方退回卡片自带字标）。
     *
     * 命中写法按子串匹配（`"LEICA CAMERA AG"` 命中 `leica`），所以大小写与厂商名的
     * 前后缀都不影响结果。
     */
    fun brandKeyFor(make: String?): String? {
        val haystack = make?.trim()?.lowercase().orEmpty()
        if (haystack.isEmpty()) return null
        for ((key, words) in MatchTable) {
            if (words.any { haystack.contains(it) }) return KeyAlias[key] ?: key
        }
        return null
    }

    /** `matchBrand()` 的表，顺序即优先级。 */
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
}
