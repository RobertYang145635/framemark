package com.framemark.native.inspector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.ControlOption
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Brand Logo library (port of the webview `BrandPicker`).
 *
 * The old page was a 64-row text dropdown with no preview and no notion of a version,
 * which is why a pick often looked like nothing happened. This is the same information
 * architecture as the shipped webview version: brand grid with search + 相机/手机 filter,
 * a version page grouped by era, a 已选 badge, and 自动匹配 as an explicit choice.
 *
 * Deviations that come from the native build, not from taste:
 *  - Only cuts whose PNG ships in this app can be applied; the rest are listed as
 *    「资源未内置」 instead of being silently dropped.
 *  - Light/dark surface locking is not evaluated at runtime (no per-card surface here),
 *    so the surface of a version is shown as a badge instead of a lock.
 */
private const val LOGO_DIR = "files/logos/"
private const val LOGO_PREVIEW_DIR = "files/logov/"

data class LogoCut(val assetKey: String, val file: String, val preview: String)

data class LogoVersion(
    val id: String,
    val name: String,
    val era: String,
    val years: String,
    val surface: String,
    val cuts: List<LogoCut>,
) {
    val primaryStem: String? get() = cuts.firstOrNull()?.assetKey
    /** The webp this version is drawn with in the signed-off webview page. */
    val preview: String? get() = cuts.firstOrNull()?.preview
}

data class LogoBrand(
    val key: String,
    val label: String,
    val cn: String,
    val group: String,
    val canonicalVersion: String?,
    val versions: List<LogoVersion>,
    /** Source brands folded into this one, e.g. LUMIX → Panasonic. */
    val merged: List<String> = emptyList(),
) {
    fun stemFor(versionId: String?): String? {
        val preferred = versions.firstOrNull { it.id == versionId }
            ?: versions.firstOrNull { it.id == canonicalVersion }
            ?: versions.firstOrNull()
        return preferred?.primaryStem
    }

    /** Brand tile art: the canonical version's own webp, else the first version's. */
    val preview: String?
        get() = (versions.firstOrNull { it.id == canonicalVersion } ?: versions.firstOrNull())?.preview
}

/** Parsed catalog; [Res.readBytes] is the only source, so both platforms agree. */
object LogoBrandCatalog {
    private var cached: List<LogoBrand>? = null

    suspend fun load(): List<LogoBrand> {
        cached?.let { return it }
        val text = runCatching {
            withContext(Dispatchers.Default) { Res.readBytes("files/data/logos.json").decodeToString() }
        }.getOrNull() ?: return emptyList()
        return parse(text).also { cached = it }
    }

    internal fun parse(text: String): List<LogoBrand> = runCatching {
        val root = Json.parse(text).jsonMap()
        val rawBrands = root["brands"].jsonList().mapNotNull { raw ->
            val b = raw.jsonMap()
            val key = b.jsonString("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to b
        }
        val metaByKey = rawBrands.toMap()
        // Same folding as the signed-off page: LUMIX belongs to Panasonic, LUMO to OPPO,
        // and those two keys are not shown on their own (61 brands, not 63).
        val folded = mapOf("lumix" to "panasionic", "lumo" to "oppo")
        val displayKeys = LinkedHashSet<String>()
        rawBrands.forEach { (key, _) -> displayKeys += folded[key] ?: key }
        displayKeys.mapNotNull { displayKey ->
            val meta = metaByKey[displayKey] ?: return@mapNotNull null
            val sourceKeys = rawBrands.map { it.first }.filter { (folded[it] ?: it) == displayKey }
            val versions = root["versions"].jsonList().mapNotNull { vraw ->
                val v = vraw.jsonMap()
                val brandKey = v.jsonString("brandKey") ?: return@mapNotNull null
                if (brandKey !in sourceKeys) return@mapNotNull null
                // Only the versions the product page offers (218 of 268).
                if (v["selected"] != true) return@mapNotNull null
                LogoVersion(
                    id = v.jsonString("id") ?: return@mapNotNull null,
                    name = v.jsonString("name").orEmpty(),
                    era = v.jsonString("era") ?: "current",
                    years = v.jsonString("years").orEmpty(),
                    surface = v.jsonString("surface") ?: "light",
                    cuts = v["cuts"].jsonList().mapNotNull { craw ->
                        val c = craw.jsonMap()
                        val stem = c.jsonString("assetKey")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val resolved = c.jsonString("resolvedPath").orEmpty()
                        val preview = resolved.substringAfter("/assets/", "")
                            .takeIf { it.isNotBlank() }
                            ?.replace("/", "__")
                            .orEmpty()
                        LogoCut(stem, c.jsonString("file").orEmpty(), preview)
                    },
                )
            }
            if (versions.isEmpty()) return@mapNotNull null
            LogoBrand(
                key = displayKey,
                label = meta.jsonString("label") ?: displayKey,
                cn = meta.jsonString("cn").orEmpty(),
                group = meta.jsonString("group") ?: "camera",
                canonicalVersion = meta.jsonString("canonicalVersion"),
                versions = versions,
                merged = sourceKeys.filter { it != displayKey }
                    .map { metaByKey[it]?.jsonString("label") ?: it },
            )
        }
    }.getOrDefault(emptyList())
}

/** Version art exactly as the signed-off webview page draws it (webp from `assets/`). */
private object LogoPreviewBits {
    private val cache = mutableStateMapOf<String, ImageBitmap?>()
    private val order = ArrayDeque<String>()

    fun get(key: String): ImageBitmap? = cache[key]

    suspend fun load(key: String) {
        if (key.isBlank() || cache.containsKey(key)) return
        val bitmap = runCatching {
            withContext(Dispatchers.Default) {
                Res.readBytes("$LOGO_PREVIEW_DIR$key").decodeToImageBitmap()
            }
        }.getOrNull()
        cache[key] = bitmap
        order.addLast(key)
        while (order.size > 128) cache.remove(order.removeFirst())
    }
}

@Composable
private fun rememberPreview(key: String?): ImageBitmap? {
    if (key.isNullOrBlank()) return null
    val bitmap = LogoPreviewBits.get(key)
    LaunchedEffect(key) {
        if (bitmap == null) LogoPreviewBits.load(key)
    }
    return bitmap
}

/** Human label for a stored render key (a brand key or one specific cut stem). */
suspend fun logoValueLabel(value: String): String? {
    if (value.isBlank()) return null
    val brands = LogoBrandCatalog.load()
    brands.firstOrNull { it.key == value }?.let { return "${it.label} · ${it.cn}" }
    brands.forEach { brand ->
        brand.versions.forEach { version ->
            if (version.cuts.any { it.assetKey == value }) return "${brand.label} · ${version.name}"
        }
    }
    return null
}

private object LogoBitmaps {    private val cache = mutableStateMapOf<String, ImageBitmap?>()
    private val order = ArrayDeque<String>()

    fun get(stem: String): ImageBitmap? = cache[stem]

    fun known(stem: String): Boolean = cache.containsKey(stem)

    suspend fun load(stem: String) {
        if (cache.containsKey(stem)) return
        val bitmap = runCatching {
            withContext(Dispatchers.Default) {
                Res.readBytes("$LOGO_DIR$stem.png").decodeToImageBitmap()
            }
        }.getOrNull()
        cache[stem] = bitmap
        order.addLast(stem)
        while (order.size > 96) cache.remove(order.removeFirst())
    }

    /**
     * First candidate whose PNG actually ships. Catalog cuts point at webp assets that
     * this build does not carry, so a version falls back to its brand wordmark instead
     * of showing an empty box (and instead of applying a key the engine cannot resolve).
     */
    suspend fun resolve(candidates: List<String>): String? {
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            load(candidate)
            if (cache[candidate] != null) return candidate
        }
        return null
    }
}

@Composable
private fun rememberLogo(stem: String?): ImageBitmap? {
    if (stem == null) return null
    val bitmap = LogoBitmaps.get(stem)
    LaunchedEffect(stem) {
        if (bitmap == null) LogoBitmaps.load(stem)
    }
    return bitmap
}

/** Resolves a renderable stem for a brand or version, with the brand wordmark as fallback. */
@Composable
private fun rememberStem(cutStem: String?, brandKey: String): String? {
    var resolved by remember(cutStem, brandKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(cutStem, brandKey) {
        resolved = LogoBitmaps.resolve(
            listOfNotNull(cutStem, brandKey, "${brandKey}_black", "${brandKey}_white"),
        )
    }
    return resolved
}

private fun groupLabel(group: String): String = when (group) {
    "camera" -> "相机"
    "phone" -> "手机"
    else -> group
}

private fun surfaceBadge(surface: String): String = when (surface) {
    "dark" -> "仅深底"
    "both" -> "黑白双版"
    else -> "仅浅底"
}

private fun eraLabel(era: String): String = when (era) {
    "current" -> "现行"
    "classic" -> "经典"
    "historical" -> "历史"
    "legacy" -> "既有"
    else -> era
}

private val ERA_ORDER = listOf("current", "classic", "historical", "legacy")

/**
 * The library dialog. [control] is the brand control of the active card; selecting a
 * brand or version writes the render key (`brandLogoFile`) straight into the session,
 * so the preview and the export both change on the next frame.
 */
@Composable
fun BrandLogoLibraryDialog(
    control: InspectorControl,
    session: DesignSession,
    onDismiss: () -> Unit,
) {
    val brands = remember { mutableStateOf<List<LogoBrand>>(emptyList()) }
    LaunchedEffect(Unit) { LogoBrandCatalog.load().let { brands.value = it } }
    val current = (session.value(control) as? String).orEmpty()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("all") }
    var openedBrand by remember { mutableStateOf<LogoBrand?>(null) }

    val filtered = remember(brands.value, query, group) {
        val q = query.trim().lowercase()
        brands.value.filter { brand ->
            (group == "all" || brand.group == group) &&
                (q.isEmpty() || listOf(brand.label, brand.cn, brand.key).any { it.lowercase().contains(q) } ||
                    brand.versions.any { it.name.lowercase().contains(q) })
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FmSpacing.md)
                .background(FmPalette.surface, RoundedCornerShape(FmRadii.card))
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                .padding(FmSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (openedBrand == null) "品牌 Logo 库" else "选择 Logo 版本",
                        style = FmType.titleMedium,
                        color = FmPalette.onSurface,
                    )
                    Text(
                        text = openedBrand?.let { "${it.label} · ${it.cn} · ${it.versions.size} 个版本" }
                            ?: "${brands.value.size} 个品牌 · 当前底色：浅色",
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                Text(
                    text = "关闭",
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(FmSpacing.sm))

            if (openedBrand == null) {
                Toolbar(
                    query = query,
                    onQuery = { query = it },
                    group = group,
                    onGroup = { group = it },
                    onAuto = {
                        session.set(control, "")
                        onDismiss()
                    },
                )
                Spacer(Modifier.height(FmSpacing.sm))
                if (filtered.isEmpty()) {
                    Text("没有匹配的品牌。", style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                    ) {
                        items(filtered, key = { it.key }) { brand ->
                            BrandCard(
                                brand = brand,
                                active = brand.versions.any { it.primaryStem == current } || brand.key == current,
                                onClick = { stem ->
                                    // One-version brands apply directly, like the webview page.
                                    if (brand.versions.size <= 1) {
                                        session.set(control, stem ?: brand.key)
                                        onDismiss()
                                    } else {
                                        openedBrand = brand
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(FmSpacing.xs))
                Text(
                    text = "品牌归属：LUMIX → Panasonic，LUMO → OPPO。黑标只用于浅色底，白标只用于深色底。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    VersionPage(
                        brand = openedBrand!!,
                        current = current,
                        onBack = { openedBrand = null },
                        onPick = { stem ->
                            session.set(control, stem)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    query: String,
    onQuery: (String) -> Unit,
    group: String,
    onGroup: (String) -> Unit,
    onAuto: () -> Unit,
) {
    Column {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .padding(horizontal = FmSpacing.md, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索品牌，如 FUJIFILM / Hasselblad / 柯达…",
                            style = FmType.bodyMedium,
                            color = FmPalette.onSurfaceMuted,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(FmSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            listOf("all" to "全部", "camera" to "相机", "phone" to "手机").forEach { (value, label) ->
                val on = group == value
                Text(
                    text = label,
                    style = FmType.labelMedium,
                    color = if (on) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(if (on) FmPalette.primary else FmPalette.surfaceMuted)
                        .clickable { onGroup(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "自动匹配",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onAuto)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BrandCard(brand: LogoBrand, active: Boolean, onClick: (String?) -> Unit) {
    val renderStem = rememberStem(brand.stemFor(null), brand.key)
    val bitmap = rememberPreview(brand.preview)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(if (active) FmPalette.primarySoft else FmPalette.surface)
            .border(
                1.dp,
                if (active) FmPalette.primary else FmPalette.outline,
                RoundedCornerShape(FmRadii.card),
            )
            .clickable { onClick(renderStem) }
            .padding(FmSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = brand.label,
                    modifier = Modifier.fillMaxWidth(0.7f).height(32.dp),
                )
            } else {
                Text("…", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            text = brand.label,
            style = FmType.bodyMedium,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(brand.cn)
                if (brand.versions.isNotEmpty()) append(" · ${brand.versions.size} 个版本")
                if (brand.merged.isNotEmpty()) append(" · 含 ${brand.merged.joinToString(" / ")}")
            },
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VersionPage(
    brand: LogoBrand,
    current: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val grouped = remember(brand) {
        ERA_ORDER.mapNotNull { era ->
            val list = brand.versions.filter { it.era == era }
            if (list.isEmpty()) null else era to list
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "← 返回品牌",
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surfaceMuted)
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(FmSpacing.sm))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
            grouped.forEach { (era, versions) ->
                item(key = "era:$era") {
                    Text(
                        text = "${eraLabel(era)} · ${versions.size}",
                        style = FmType.labelMedium,
                        color = FmPalette.onSurfaceSecondary,
                        modifier = Modifier.padding(top = FmSpacing.sm, bottom = FmSpacing.xxs),
                    )
                }
                items(versions, key = { it.id }) { version ->
                    VersionRow(
                        version = version,
                        brandKey = brand.key,
                        selected = version.primaryStem == current,
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(version: LogoVersion, brandKey: String, selected: Boolean, onPick: (String) -> Unit) {
    // Art = the version's own webp; render key = a stem the engine can resolve.
    val bitmap = rememberPreview(version.preview)
    val renderStem = rememberStem(version.primaryStem, brandKey)
    var exact by remember(version.primaryStem) { mutableStateOf(false) }
    LaunchedEffect(version.primaryStem) {
        exact = LogoBitmaps.resolve(listOfNotNull(version.primaryStem)) != null
    }
    val available = renderStem != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(
                1.dp,
                if (selected) FmPalette.primary else FmPalette.outline,
                RoundedCornerShape(FmRadii.card),
            )
            .clickable(enabled = available) { renderStem?.let(onPick) }
            .padding(FmSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = version.name,
                        modifier = Modifier.fillMaxWidth(0.75f).height(26.dp),
                    )
                }
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (selected) "已选" else surfaceBadge(version.surface),
                        style = FmType.labelSmall,
                        color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                    )
                    if (!exact) {
                        Spacer(Modifier.width(FmSpacing.xs))
                        Text("回退品牌标", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
                    }
                    if (!available) {
                        Spacer(Modifier.width(FmSpacing.xs))
                        Text("不可用", style = FmType.labelSmall, color = FmPalette.error)
                    }
                }
                Text(
                    text = version.name,
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${eraLabel(version.era)} · ${version.years}",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

/** Options for a plain dropdown fallback (kept for cards without a brand picker). */
internal fun logoOptions(brands: List<LogoBrand>): List<ControlOption> =
    listOf(ControlOption("auto", "自动匹配")) + brands.map { ControlOption(it.key, it.label) }
