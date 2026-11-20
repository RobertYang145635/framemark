package com.framemark.native.product.update

/**
 * 应用内「检查更新」的纯逻辑（2026-11-16）。
 *
 * 数据源是 GitHub Releases（公开仓库，无需 token），国内网络可走镜像前缀。
 * 解析与版本比较放在 commonMain，Android/桌面各自只提供 [fetchUrlText] 的实现。
 */
data class UpdateRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val url: String,
    val prerelease: Boolean,
)

/** 更新源：直连 GitHub 或走国内镜像（镜像优先，失败再回退直连）。 */
enum class UpdateSource(val label: String) {
    GitHub("GitHub"),
    Mirror("国内镜像"),
}

object UpdateUrls {
    const val REPO = "RobertYang145635/framemark"
    const val API_DIRECT = "https://api.github.com/repos/$REPO/releases?per_page=10"

    /** 随分支发布的更新清单；稳定 URL，国内镜像只需代理同一个文件。 */
    const val MANIFEST_DIRECT =
        "https://raw.githubusercontent.com/$REPO/ui-v2/main/updates/latest.json"
    const val RELEASE_PAGE = "https://github.com/$REPO/releases"

    /**
     * GitHub 加速前缀（只做前缀拼接，不改变路径），**顺序按实测速度**。
     *
     * 2026-11-20 本机（国内网络）实测，同一份 raw 清单 + 同一个 4 MB 资产分片：
     *  - `ghfast.top`       资产 ~249 KB/s，清单 2.4 s
     *  - `gh-proxy.org`     资产 ~155 KB/s，清单 1.5 s（官方文档域名）
     *  - `gh-proxy.com`     资产 ~80 KB/s，清单 2.2 s
     *  - `ghproxy.net`      清单 7.3 s（偏慢，兜底）
     *  - 直连 github.com    资产 ~18 KB/s、且会 302 到 objects.githubusercontent.com
     *  - `mirror.ghproxy.com` 已失效（12 s 超时）→ 从候选里删掉
     * 速度会随时间/地区波动，所以下面只是**起始顺序**：下载失败会依次换下一个。
     */
    val MIRRORS: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.org/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** 依次尝试的 URL：镜像源先镜像后直连；直连源先直连后镜像。 */
    fun apiCandidates(source: UpdateSource): List<String> =
        if (source == UpdateSource.Mirror) {
            MIRRORS.map { it + API_DIRECT } + API_DIRECT
        } else {
            listOf(API_DIRECT) + MIRRORS.map { it + API_DIRECT }
        }

    /** 清单候选：镜像源先镜像后直连；直连源相反。 */
    fun manifestCandidates(source: UpdateSource): List<String> =
        if (source == UpdateSource.Mirror) {
            MIRRORS.map { it + MANIFEST_DIRECT } + MANIFEST_DIRECT
        } else {
            listOf(MANIFEST_DIRECT) + MIRRORS.map { it + MANIFEST_DIRECT }
        }

    /**
     * 资产下载候选：清单里自带的镜像（[extraMirrors]，完整 URL，优先）→ 加速前缀 → 直连。
     *
     * 清单里的镜像让**没有发新版也能换渠道**（比如以后挂到国内对象存储上，只改
     * `updates/latest.json`），不用等用户升级到这个版本才认新地址。
     */
    fun assetCandidates(url: String, extraMirrors: List<String> = emptyList()): List<String> {
        val extras = extraMirrors.map { it.trim() }.filter { it.startsWith("https://") }
        val proxied = if (url.startsWith("https://github.com/") ||
            url.startsWith("https://objects.githubusercontent.com/")
        ) {
            MIRRORS.map { it + url } + url
        } else {
            listOf(url)
        }
        return (extras + proxied).distinct()
    }

    /** 发布页地址：镜像源用镜像前缀打开，直连源用原地址。 */
    fun releasePage(url: String, source: UpdateSource): String =
        if (source == UpdateSource.Mirror) MIRRORS.first() + url else url
}

object UpdateJson {
    /**
     * 从 `/releases` 的 JSON 数组里取每条 release 的首屏字段。
     *
     * 仓库返回的字段顺序稳定（tag_name 在前，name/body/html_url/prerelease 在后），
     * 所以按 `"tag_name"` 切段即可，不引入 JSON 依赖；正文里的转义按 GitHub 的
     * 约定还原（\\n、\\"、\\/、\\\\）。
     */
    fun releases(json: String): List<UpdateRelease> {
        val anchors = mutableListOf<Int>()
        var index = json.indexOf("\"tag_name\"")
        while (index >= 0) {
            anchors += index
            index = json.indexOf("\"tag_name\"", index + 1)
        }
        if (anchors.isEmpty()) return emptyList()
        return anchors.mapIndexedNotNull { i, start ->
            val end = anchors.getOrNull(i + 1) ?: json.length
            val chunk = json.substring(start, end)
            val tag = field(chunk, "tag_name") ?: return@mapIndexedNotNull null
            if (field(chunk, "draft") == "true") return@mapIndexedNotNull null
            UpdateRelease(
                tag = tag,
                name = field(chunk, "name") ?: tag,
                notes = field(chunk, "body").orEmpty(),
                url = field(chunk, "html_url") ?: UpdateUrls.RELEASE_PAGE,
                prerelease = field(chunk, "prerelease") == "true",
            )
        }
    }

    /** 取 `"key":"value"` 的字符串值；布尔值也按 true/false 文本返回。 */
    private fun field(chunk: String, key: String): String? {
        val marker = "\"$key\""
        val at = chunk.indexOf(marker)
        if (at < 0) return null
        val colon = chunk.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < chunk.length && chunk[i].isWhitespace()) i++
        if (i >= chunk.length) return null
        if (chunk[i] != '"') {
            val end = chunk.indexOfAny(charArrayOf(',', '}', ']'), i).let { if (it < 0) chunk.length else it }
            return chunk.substring(i, end).trim()
        }
        val out = StringBuilder()
        i++
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == '\\' && i + 1 < chunk.length) {
                when (val next = chunk[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        val hex = chunk.substring(i + 2, minOf(i + 6, chunk.length))
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            } else if (c == '"') {
                return out.toString()
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** 版本比较：`0.9.0-rc13`、`0.9.0`、`1.2.3`；预发布小于同版本正式版。 */
object UpdateVersion {
    private data class Parsed(val numbers: List<Int>, val suffix: String?, val suffixNumber: Int)

    private fun parse(version: String): Parsed? {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val dash = cleaned.indexOf('-')
        val base = if (dash >= 0) cleaned.substring(0, dash) else cleaned
        val suffix = if (dash >= 0) cleaned.substring(dash + 1) else null
        val numbers = base.split('.').map { it.toIntOrNull() ?: return null }
        val suffixNumber = suffix?.takeLastWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return Parsed(numbers, suffix, suffixNumber)
    }

    /** remote 比 current 新时返回 true。 */
    fun isNewer(current: String, remote: String): Boolean = compare(current, remote) < 0

    fun compare(current: String, remote: String): Int {
        val a = parse(current) ?: return 0
        val b = parse(remote) ?: return 0
        val size = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until size) {
            val x = a.numbers.getOrElse(i) { 0 }
            val y = b.numbers.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 同一基础版本：正式版 > 预发布；同为预发布按后缀数字比较。
        if (a.suffix == null && b.suffix != null) return 1
        if (a.suffix != null && b.suffix == null) return -1
        if (a.suffix != null && b.suffix != null) {
            val nameA = a.suffix.takeWhile { !it.isDigit() }
            val nameB = b.suffix.takeWhile { !it.isDigit() }
            if (nameA == nameB) return a.suffixNumber.compareTo(b.suffixNumber)
            return nameA.compareTo(nameB)
        }
        return 0
    }

    /** 在 release 列表里挑出比当前版本新的最高版本；没有则 null。 */
    fun newestAvailable(current: String, releases: List<UpdateRelease>): UpdateRelease? =
        releases
            .filter { isNewer(current, it.tag) }
            .maxWithOrNull { x, y -> compare(x.tag, y.tag) }
}

/** 平台 HTTP GET；失败返回 null（调用方按候选顺序重试）。 */
expect fun fetchUrlText(url: String): String?

/**
 * 一次「检查更新」的结果。手动检查（关于页）与启动自动检查共用同一份逻辑，
 * 免得两条路对「有没有新版」给出不同答案。
 */
sealed interface UpdateCheckOutcome {
    /** 清单可用：有平台资产就顺手带出来（下载走 [installUpdate]）。 */
    data class Available(val manifest: UpdateManifest, val asset: UpdateAsset?) : UpdateCheckOutcome

    /** 清单不可用，退回 GitHub Releases：只有 tag/notes，没有直链。 */
    data class AvailableRelease(val release: UpdateRelease) : UpdateCheckOutcome

    data object Latest : UpdateCheckOutcome

    data object Failed : UpdateCheckOutcome
}


/**
 * 启动路径用的**只要清单**的检查：清单里就有版本号与资产直链，比 Releases API 快得多。
 *
 * 为什么要限时：`fetchUrlText` 是阻塞调用（各平台 8 s 超时），冷启动时如果第一个候选
 * 不可达，用户会等十几秒才看到弹窗 —— 那比没有弹窗更糟。这里给候选循环一个总预算
 * （[LAUNCH_CHECK_BUDGET_MS]），超预算就放弃，交给关于页的手动检查。
 */
private const val LAUNCH_CHECK_BUDGET_MS = 6_000L

/** 每次检查都拼一个递增的查询参数：绕开镜像 CDN 缓存的旧清单（见 checkManifestForLaunch）。 */
private var manifestProbeSeq = 0

private fun withCacheBuster(url: String): String {
    val seq = ++manifestProbeSeq
    val separator = if (url.contains('?')) '&' else '?'
    return "$url${separator}fm=$seq"
}


suspend fun checkManifestForLaunch(
    currentVersion: String,
    source: UpdateSource = UpdateSource.Mirror,
): UpdateCheckOutcome {
    val started = kotlin.time.TimeSource.Monotonic.markNow()
    for (url in UpdateUrls.manifestCandidates(source)) {
        if (started.elapsedNow().inWholeMilliseconds > LAUNCH_CHECK_BUDGET_MS) break
        val text = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchUrlText(withCacheBuster(url)) } }
            .getOrNull() ?: continue
        val manifest = UpdateManifestJson.parse(text) ?: continue
        return if (UpdateVersion.isNewer(currentVersion, manifest.versionName)) {
            UpdateCheckOutcome.Available(manifest, manifest.assetFor(updatePlatform()))
        } else {
            UpdateCheckOutcome.Latest
        }
    }
    // 清单都没读到：不回退 Releases API（那是关于页的活），按「这次不提示」处理。
    return UpdateCheckOutcome.Failed
}

/**
 * 检查更新：先读清单（随分支发布，镜像只需代理同一份文件），读不到再退回 Releases API。
 *
 * 清单优先的理由：它带**平台资产直链 + sha256 + 大小**，能直接下载并校验；
 * Releases API 只能拿到页面地址。两条路都失败才是 [UpdateCheckOutcome.Failed]。
 */
suspend fun checkForUpdate(
    currentVersion: String,
    source: UpdateSource = UpdateSource.Mirror,
    platform: UpdatePlatform = updatePlatform(),
): UpdateCheckOutcome {
    val manifest = runCatching {
        UpdateUrls.manifestCandidates(source).firstNotNullOfOrNull { url ->
            fetchUrlText(withCacheBuster(url))?.let { UpdateManifestJson.parse(it) }
        }
    }.getOrNull()
    if (manifest != null && UpdateVersion.isNewer(currentVersion, manifest.versionName)) {
        return UpdateCheckOutcome.Available(manifest, manifest.assetFor(platform))
    }
    val releases = runCatching {
        UpdateUrls.apiCandidates(source).firstNotNullOfOrNull { url ->
            fetchUrlText(url)?.let { body -> UpdateJson.releases(body) }
        }
    }.getOrNull()
    if (releases != null) {
        UpdateVersion.newestAvailable(currentVersion, releases)?.let {
            return UpdateCheckOutcome.AvailableRelease(it)
        }
        return UpdateCheckOutcome.Latest
    }
    return if (manifest != null) UpdateCheckOutcome.Latest else UpdateCheckOutcome.Failed
}
