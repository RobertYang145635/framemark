package com.framemark.native.product

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 最近一次渲染**按照片品牌**解析出的结果：照片的 EXIF `Make` 原文 + 命中的品牌键。
 *
 * 为什么需要它：Logo 页的说明行要写「当前：自动匹配 · 按照片品牌 X」，而 X 只有两个来源 ——
 * 文字 slot 里的品牌行，或照片自己的 EXIF。像「玻璃 04」这类卡把品牌画成**字标图片**，
 * 文字 slot 里只有机型（`Find X6 Pro`，没有厂商名），于是说明行一直落到「按边框自己的
 * 品牌」，而屏上明明画着 OPPO 的字标（真机 2026-11-20 实测）。
 *
 * 渲染器本来就已经在解析这件事（`AndroidNativeRender` / `DesktopNativeRender` 的
 * auto 分支），所以在解析成功后把结果留在这里供界面**只读**：不写设计值、不改渲染。
 * 显式选过字标时不写（那种情况说明行走「当前：品牌 · 款式」）。
 */
object PhotoBrandHint {

    /** 照片 EXIF 的 `Make` 原文（没有时 null）。 */
    var make: String? by mutableStateOf(null)
        private set

    /** `Make` 命中的品牌键（`PhotoBrandKeys`，没命中或没有 EXIF 时 null）。 */
    var brandKey: String? by mutableStateOf(null)
        private set

    /** 渲染器按照片品牌解析完成后调用；解析不到就传 null，说明行回到「按边框自己的品牌」。 */
    fun mark(make: String?, brandKey: String?) {
        val nextMake = make?.trim()?.takeIf { it.isNotEmpty() }
        val nextKey = brandKey?.trim()?.takeIf { it.isNotEmpty() }
        if (nextMake != this.make) this.make = nextMake
        if (nextKey != this.brandKey) this.brandKey = nextKey
    }
}
