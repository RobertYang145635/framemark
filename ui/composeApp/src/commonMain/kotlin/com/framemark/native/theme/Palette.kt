package com.framemark.native.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 四套界面配色（配色方案 §6，2026-11-15）。
 *
 * 设计文档给出每套配色在浅色 / 深色下的 10 个**基础色**（画布、面板、输入框、
 * 主文字、次要文字、实心按钮、按钮上的文字、分隔线、柔和选中背景、链接文字）。
 * 其余角色（ink-2 / ink-4 / 描边加强 / 轨道 / 滑杆空闲段 / hover / press / scrim）
 * 由这 10 个基础色按固定规则派生，规则写在 [FmPalettes] 里，并且由
 * `PaletteContrastTest` 逐套逐模式钉住对比度，所以「再调一个颜色」不会悄悄把某个
 * 小字掉到 AA 以下。
 *
 * 舞台（照片区）不参与配色切换：深灰 / 中灰 / 浅灰三档沿用 `FmColors` 里的中性
 * 常量（配色方案 §7.2），四套配色、明暗两种外观看到的是同一个舞台。
 */
enum class PaletteId(val wire: String, val label: String) {
    /** 暖灰墨绿：自然、安静、画册感。默认。 */
    Forest("forest", "暖灰墨绿"),

    /** 石墨琥珀：沉稳、精确、器材感。 */
    Graphite("graphite", "石墨琥珀"),

    /** 瓷白靛青：清爽、有秩序、现代感。 */
    Indigo("indigo", "瓷白靛青"),

    /** 纸白陶土：温暖、纸张、手作感。 */
    Clay("clay", "纸白陶土"),

    /**
     * 经典蓝：V2 首发的那套蓝，给习惯旧界面的用户留的入口。
     *
     * 主色取 `#2E71E0`（首发值是 `#2F75E5`，深一档）：白字按钮 4.61:1，过 AA。
     * 其余基础色就是旧令牌的值，派生规则与其他四套完全一致。
     */
    Classic("classic", "经典蓝"),
    ;

    companion object {
        /** 默认配色：文档推荐的 `forest`，也是缺失 / 损坏文件的回退值。 */
        val Default: PaletteId = Forest

        fun fromWire(value: String?): PaletteId? = entries.firstOrNull { it.wire == value }
    }
}

/** 一套配色在一种明暗下的 10 个基础色（ARGB，`0xAARRGGBB`）。 */
private class PaletteSpec(
    val canvas: Long,
    val surface: Long,
    val surfaceMuted: Long,
    val ink: Long,
    val inkMuted: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryStrong: Long,
    val outline: Long,
    val soft: Long,
)

/**
 * 配色表 + 派生规则。`of()` 是唯一的构造入口：默认配色的浅色 / 深色快照
 * 只是 `of(Forest, false/true)` 的别名（[FmColors] 文档里写明了）。
 */
object FmPalettes {

    /** 浅色 / 深色各自的 10 个基础色，逐字取自配色方案 §6.1–§6.4。 */
    private val TABLE: Map<PaletteId, Pair<PaletteSpec, PaletteSpec>> = mapOf(
        PaletteId.Forest to (
            PaletteSpec(
                canvas = 0xFFF2F1ED,
                surface = 0xFFFCFBF8,
                surfaceMuted = 0xFFE7E8E2,
                ink = 0xFF242823,
                inkMuted = 0xFF646B63,
                primary = 0xFF31634F,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF31634F,
                outline = 0xFFD9DCD4,
                soft = 0xFFE2EBE4,
            ) to PaletteSpec(
                canvas = 0xFF181C19,
                surface = 0xFF222923,
                surfaceMuted = 0xFF2C352E,
                ink = 0xFFE8EDE7,
                inkMuted = 0xFFB1BBB1,
                primary = 0xFF93B9A2,
                onPrimary = 0xFF182A20,
                primaryStrong = 0xFFA9CBB6,
                outline = 0xFF3A453C,
                soft = 0xFF2E4537,
            )
            ),
        PaletteId.Graphite to (
            PaletteSpec(
                canvas = 0xFFF4F4F2,
                surface = 0xFFFFFFFF,
                surfaceMuted = 0xFFEAEAE6,
                ink = 0xFF242424,
                inkMuted = 0xFF666660,
                primary = 0xFF8A5A13,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF80520D,
                outline = 0xFFDCDCD6,
                soft = 0xFFF2E8D4,
            ) to PaletteSpec(
                canvas = 0xFF171717,
                surface = 0xFF222222,
                surfaceMuted = 0xFF2E2E2E,
                ink = 0xFFEFEFEC,
                inkMuted = 0xFFB7B7AF,
                primary = 0xFFD9B46D,
                onPrimary = 0xFF252016,
                primaryStrong = 0xFFE8C787,
                outline = 0xFF3C3C38,
                soft = 0xFF423726,
            )
            ),
        PaletteId.Indigo to (
            PaletteSpec(
                canvas = 0xFFF2F3F7,
                surface = 0xFFFFFFFF,
                surfaceMuted = 0xFFE9EBF2,
                ink = 0xFF24293A,
                inkMuted = 0xFF626A80,
                primary = 0xFF505B8A,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF505B8A,
                outline = 0xFFD9DDE8,
                soft = 0xFFE6E9F4,
            ) to PaletteSpec(
                canvas = 0xFF191B24,
                surface = 0xFF242735,
                surfaceMuted = 0xFF2E3343,
                ink = 0xFFECEEF7,
                inkMuted = 0xFFB4BCD3,
                primary = 0xFFA3AEDF,
                onPrimary = 0xFF20253E,
                primaryStrong = 0xFFBBC7F5,
                outline = 0xFF3C4256,
                soft = 0xFF333C59,
            )
            ),
        PaletteId.Clay to (
            PaletteSpec(
                canvas = 0xFFF4F0E9,
                surface = 0xFFFFFCF6,
                surfaceMuted = 0xFFEAE1D6,
                ink = 0xFF352D27,
                inkMuted = 0xFF77685B,
                primary = 0xFF9B4F38,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF8F442F,
                outline = 0xFFDED2C6,
                soft = 0xFFF3E2D9,
            ) to PaletteSpec(
                canvas = 0xFF211C19,
                surface = 0xFF2C2521,
                surfaceMuted = 0xFF3A3029,
                ink = 0xFFF3E9DF,
                inkMuted = 0xFFC5B0A2,
                primary = 0xFFE3A18B,
                onPrimary = 0xFF34221C,
                primaryStrong = 0xFFF0B6A1,
                outline = 0xFF4A3B32,
                soft = 0xFF4B342B,
            )
            ),
        PaletteId.Classic to (
            // V2 首发那套蓝：浅色是 `--canvas #F0F2F5` / `--surface #FFF` /
            // `--accent #2F75E5` / `--accent-strong #1D5FC6`，深色是 HANDOFF
            // 「深色（新）」列。柔和选中底是主色 10 %（浅）/ 18 %（深）叠面板的值。
            PaletteSpec(
                canvas = 0xFFF0F2F5,
                surface = 0xFFFFFFFF,
                surfaceMuted = 0xFFF6F8FB,
                ink = 0xFF14181F,
                inkMuted = 0xFF5C6673,
                primary = 0xFF2E71E0,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF1D5FC6,
                outline = 0xFFE3E7EC,
                soft = 0xFFEAF1FC,
            ) to PaletteSpec(
                canvas = 0xFF121316,
                surface = 0xFF1D1E22,
                surfaceMuted = 0xFF26282D,
                ink = 0xFFEDEFF3,
                inkMuted = 0xFF9AA1AC,
                primary = 0xFF2E71E0,
                onPrimary = 0xFFFFFFFF,
                primaryStrong = 0xFF7FB0FF,
                outline = 0xFF2E3036,
                soft = 0xFF202D44,
            )
            ),
    )

    /** 未知 id 回退到默认配色，调用点（预览、测试）不必自己判空。 */
    fun of(id: PaletteId, dark: Boolean): FmColors {
        val pair = TABLE[id] ?: TABLE.getValue(PaletteId.Default)
        return build(if (dark) pair.second else pair.first, dark)
    }

    /** 所有配色的 `(id, 浅色, 深色)`，给外观预览卡和测试遍历。 */
    fun all(): List<Triple<PaletteId, FmColors, FmColors>> =
        PaletteId.entries.map { Triple(it, of(it, dark = false), of(it, dark = true)) }

    /**
     * 把 10 个基础色展开成 [FmColors] 的 33 个角色。
     *
     * 派生规则（`PaletteContrastTest` 逐条钉住）：
     *  - `onSurface` = 主文字；`onSurfaceMuted` = 次要文字，先按 AA 4.5:1 相对画布 /
     *    面板 / 输入框 / 柔和选中底色校一遍，不足时按 1 % 步长往主文字方向挪（浅色
     *    加深、深色提亮），这样四套配色的「次要文字」都还在 AA 内；
     *  - `onSurfaceSecondary` = 主文字与次要文字的中点（ink-2，正文里比次要文字更重）；
     *  - `onSurfaceFaint` = 次要文字往面板方向 34 %，只给禁用态和占位文字（低于 AA，
     *    与 `Tokens.kt` 的使用规则一致）；
     *  - `outlineStrong` = 分隔线往主文字 12 %（深色外观的 1 dp 立面描边）；
     *  - `track` = 分隔线往主文字 5 %（滑杆底轨，比分隔线略深）；
     *  - `sliderIdle` = 次要文字往面板方向 38 %（浅色）/ 30 %（深色），深色下保证
     *    相对面板 ≥ 3:1；
     *  - `primarySoft` **不**从主色透明叠加算，直接用文档给的柔和选中底色 —— 墨绿的
     *    柔和底带绿调，10 % 主色叠面板只会得到一块灰绿；
     *  - hover / press 用主文字 4.3 % / 7.8 %（浅色）、5.9 % / 10.2 %（深色）；
     *    scrim 浅色用主文字 32 %、深色用 60 % 黑；
     *  - 状态色（error / warning / success）、阴影、舞台三档不随配色变化。
     */
    private fun build(spec: PaletteSpec, dark: Boolean): FmColors {
        val canvas = Color(spec.canvas)
        val surface = Color(spec.surface)
        val surfaceMuted = Color(spec.surfaceMuted)
        val ink = Color(spec.ink)
        val primary = Color(spec.primary)
        val outline = Color(spec.outline)
        val soft = Color(spec.soft)

        val muted = ensureContrast(
            color = Color(spec.inkMuted),
            backgrounds = listOf(canvas, surface, surfaceMuted, soft),
            target = 4.5,
            toward = ink,
        )
        val secondary = mix(ink, muted, 0.5f)
        val faint = mix(muted, surface, 0.34f)
        val sliderIdle = ensureContrast(
            color = mix(muted, surface, if (dark) 0.30f else 0.38f),
            backgrounds = listOf(surface),
            target = if (dark) 3.0 else 2.3,
            toward = ink,
        )

        return FmColors(
            canvas = canvas,
            surface = surface,
            surfaceMuted = surfaceMuted,
            onSurface = ink,
            onSurfaceSecondary = secondary,
            onSurfaceMuted = muted,
            onSurfaceFaint = faint,
            outline = outline,
            outlineStrong = mix(outline, ink, 0.12f),
            primary = primary,
            primaryStrong = Color(spec.primaryStrong),
            onPrimary = Color(spec.onPrimary),
            error = if (dark) Color(0xFFF06A5F) else Color(0xFFD2453B),
            warning = if (dark) Color(0xFFE0A23A) else Color(0xFFC27A0E),
            success = if (dark) Color(0xFF3CC07A) else Color(0xFF1E9E5A),
            // 浅色：主文字压暗画布；深色：纯黑压暗（深色面板上白色 scrim 会发灰）。
            scrim = if (dark) Color.Black.copy(alpha = 0.60f) else ink.copy(alpha = 0.32f),
            hover = ink.copy(alpha = if (dark) 0.059f else 0.043f),
            press = ink.copy(alpha = if (dark) 0.102f else 0.078f),
            track = mix(outline, ink, 0.05f),
            sliderIdle = sliderIdle,
            stage = PaletteSpecStage.Dark,
            stageDark = PaletteSpecStage.Dark,
            stageMid = PaletteSpecStage.Mid,
            stageLight = PaletteSpecStage.Light,
            stageLine = PaletteSpecStage.Line,
            stageInk = PaletteSpecStage.Ink,
            stageMuted = PaletteSpecStage.Muted,
            shadowNavAmbient = if (dark) Color(0x1F000000) else Color(0x0D101828),
            shadowNavSpot = if (dark) Color(0x40000000) else Color(0x1A101828),
            shadowCardAmbient = if (dark) Color(0x14000000) else Color(0x08101828),
            shadowCardSpot = if (dark) Color(0x24000000) else Color(0x0F101828),
            primarySoftAlpha = if (dark) 0.18f else 0.10f,
            ringAlpha = if (dark) 0.40f else 0.28f,
            softBase = soft,
        )
    }
}

/** 舞台三档 + 舞台墨色：四套配色 / 两种外观共用（配色方案 §7.2）。 */
private object PaletteSpecStage {
    val Dark = Color(0xFF2B2C2F)
    val Mid = Color(0xFF5A5C61)
    val Light = Color(0xFFD9DADD)
    val Line = Color(0xFF3A3F47)
    val Ink = Color(0xFFD4DAE3)
    val Muted = Color(0xFF969AA4)
}

// ---- 颜色数学（sRGB，与 WCAG 2.x 相对亮度一致）---------------------------------

internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun linear(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

internal fun relativeLuminance(color: Color): Double =
    0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

/** WCAG 2.1 对比度，`1.0..21.0`。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * 把 [color] 按 1 % 步长往 [toward] 挪，直到它相对 [backgrounds] 中**最差**的一块的
 * 对比度达到 [target]。找不到就返回 100 % 时的值（每块背景都该由测试兜住）。
 */
internal fun ensureContrast(
    color: Color,
    backgrounds: List<Color>,
    target: Double,
    toward: Color,
): Color {
    var out = color
    for (step in 0..100) {
        if (backgrounds.all { contrastRatio(out, it) >= target }) return out
        out = mix(color, toward, (step + 1) / 100f)
    }
    return out
}
