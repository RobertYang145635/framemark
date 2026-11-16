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
    const val RELEASE_PAGE = "https://github.com/$REPO/releases"

    /** 常用的 GitHub 加速前缀；只做前缀拼接，不改变路径。 */
    val MIRRORS: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/",
    )

    /** 依次尝试的 URL：镜像源先镜像后直连；直连源先直连后镜像。 */
    fun apiCandidates(source: UpdateSource): List<String> =
        if (source == UpdateSource.Mirror) {
            MIRRORS.map { it + API_DIRECT } + API_DIRECT
        } else {
            listOf(API_DIRECT) + MIRRORS.map { it + API_DIRECT }
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
