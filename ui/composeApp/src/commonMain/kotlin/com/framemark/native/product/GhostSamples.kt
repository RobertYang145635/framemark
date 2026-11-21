package com.framemark.native.product

/**
 * 缺失行的「标准参数档」示例文案（2026-11-21 用户口径）。
 *
 * 照片没有 EXIF 或只缺几项时，边框上那一行什么都不画，用户看不出这里本来能填什么。
 * 编辑器里用这些**示例值**淡灰占位，告诉用户这一行的格式；示例只画在预览上，
 * 不进设计、不进导出，进过「文字」页就撤掉。
 *
 * 取值原则：与这台机器上真实照片的观感一致（OPPO 的机型/参数/日期格式），
 * 而不是「示例文字」这类说明性文案。
 */
object GhostSamples {

    /** 品牌行：有照片 Make 就用它，否则给一个常见品牌。 */
    fun brand(make: String?): String = make?.trim()?.takeIf { it.isNotEmpty() } ?: "OPPO"

    /** 机型行：常见手机机型；比「机型」两个字更像真实输出。 */
    const val MODEL = "Find X6 Pro"

    /** 拍摄参数行：这台机器上 23mm 广角主摄的典型组合。 */
    const val EXIF = "23mm F1.8 1/125s ISO250"

    /** 日期行：与相册里照片一致的格式。 */
    const val DATE = "2026-11-21 11:56"

    /**
     * 按文字页给出的角色名取示例。
     *
     * 角色为空（非经典四行的边框）时返回空串：宁可**不画**，也不能在「品牌行」上画一串
     * 拍摄参数 —— 猜错比不提示更糟（2026-11-21 逐一核对边框时发现的口径）。
     */
    fun forRole(role: String?, make: String? = null): String = when (role?.trim()) {
        "品牌" -> brand(make)
        "机型" -> MODEL
        "拍摄参数" -> EXIF
        "日期" -> DATE
        null, "" -> ""
        else -> role
    }

    /**
     * 边框不知道角色时按它声明的行挑：优先拍摄参数 → 日期 → 机型 → 品牌。
     * 一行都没声明就返回空串（没有任何文字行，不需要占位）。
     */
    fun forDeclaredRows(
        make: Boolean,
        model: Boolean,
        exif: Boolean,
        date: Boolean,
        makeValue: String? = null,
    ): String = when {
        exif -> EXIF
        date -> DATE
        model -> MODEL
        make -> brand(makeValue)
        else -> ""
    }

    /** 角色 → 示例（供探针渲染与舞台淡灰文字共用）。 */
    fun sampleFor(key: String, role: String?, make: String? = null): String = forRole(role, make)
}
