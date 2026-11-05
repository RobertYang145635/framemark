package com.framemark.native.library

/**
 * “最近使用”的存取口子。本包只放纯逻辑和一份测试用的内存实现；**真正写文件的实现不在本包**：
 * D1、P1 接线时在 WP-T 提供的 `settings/` 目录下实现，文件名 `recent-frames.json`，
 * 编码统一走 [RecentFrames.encode] / [RecentFrames.decode]，这样文件损坏时的回落只有一处。
 */
interface RecentFramesStore {
    /** 读原始记录；文件缺失、内容损坏都要返回空列表，不抛异常。 */
    fun load(): List<String>

    /** 整份覆盖写入。 */
    fun save(ids: List<String>)
}

/**
 * “最近使用”：最多 [MAX] 个，最近的在前；再次使用同一张卡时把它移到最前，不重复记。
 *
 * 编解码格式固定为 `{"version":1,"cards":["id",…]}`：文件缺失、JSON 损坏、版本号不认识，
 * [decode] 一律返回空列表，不崩溃；catalog 里没有的卡 id 在 [load] / [record] 里被丢弃。
 */
object RecentFrames {

    /** 最多记几个。 */
    const val MAX = 8

    /** 编码格式的版本号。 */
    const val VERSION = 1

    /** 真实实现落盘用的文件名（由 D1 / P1 在 `settings/` 目录下使用）。 */
    const val FILE_NAME = "recent-frames.json"

    /**
     * 编码成 `{"version":1,"cards":["id",…]}`。写入前先去空、去重、截到 [MAX]，
     * 免得调用方把不合规的列表写进文件。
     */
    fun encode(ids: List<String>): String {
        val clean = normalize(ids)
        return buildString {
            append("{\"version\":")
            append(VERSION)
            append(",\"cards\":[")
            clean.forEachIndexed { index, id ->
                if (index > 0) append(',')
                append(quote(id))
            }
            append("]}")
        }
    }

    /**
     * 解码。`null` / 空白 / JSON 损坏 / 版本号不认识 / 结构不对，一律返回空列表；
     * `cards` 里的非字符串项跳过，重复项去重，超过 [MAX] 的部分截掉。
     */
    fun decode(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parse(text) }.getOrNull() ?: return emptyList()
        val map = root.jsonMap()
        if (map.jsonInt("version") != VERSION) return emptyList()
        val raw = map["cards"] as? List<*> ?: return emptyList()
        val out = ArrayList<String>(MAX)
        for (item in raw) {
            if (out.size >= MAX) break
            val id = item as? String ?: continue
            if (id.isBlank() || out.contains(id)) continue
            out.add(id)
        }
        return out
    }

    /**
     * 去空、去重（保留第一次出现的位置）、截到 [MAX]。给了 [known] 时，catalog 里没有的 id 直接丢弃。
     */
    fun normalize(ids: List<String>, known: Set<String>? = null): List<String> {
        val out = LinkedHashSet<String>()
        for (id in ids) {
            if (out.size >= MAX) break
            if (id.isBlank()) continue
            if (known != null && id !in known) continue
            out.add(id)
        }
        return out.toList()
    }

    /** 把 [id] 记到最前面：已有的移走再插到队首，没有的直接插到队首，最后去重截断。 */
    fun touch(current: List<String>, id: String): List<String> {
        if (id.isBlank()) return normalize(current)
        return normalize(listOf(id) + current)
    }

    /** 从 [store] 读一份，丢掉 catalog 里没有的 id；[store] 抛异常也当空列表处理。 */
    fun load(store: RecentFramesStore, known: Set<String>? = null): List<String> =
        normalize(runCatching { store.load() }.getOrDefault(emptyList()), known)

    /**
     * 记一次使用：读 → 移到最前 → 写回，返回新的列表。`id` 不在 catalog 里时不记。
     * 写失败（例如文件被占用）不抛出，界面不该因为“最近使用”写不进去而中断。
     */
    fun record(
        store: RecentFramesStore,
        id: String,
        known: Set<String>? = null,
    ): List<String> {
        if (id.isBlank() || (known != null && id !in known)) return load(store, known)
        val next = touch(load(store, known), id)
        runCatching { store.save(next) }
        return next
    }

    /** 用索引里的 catalog 做校验的简写。 */
    fun load(store: RecentFramesStore, index: LibraryIndex): List<String> = load(store, index.knownIds)

    /** 用索引里的 catalog 做校验的简写。 */
    fun record(store: RecentFramesStore, id: String, index: LibraryIndex): List<String> =
        record(store, id, index.knownIds)

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}

/**
 * 测试用的内存实现：把 [fileText] 当成磁盘上的那个文件，`null` 表示“文件不存在”。
 * 读写都走和真实实现一样的 [RecentFrames.encode] / [RecentFrames.decode]，
 * 所以文件缺失、内容损坏、版本号不认识这些情形在这里可以原样复现。
 */
class InMemoryRecentFramesStore(initialFileText: String? = null) : RecentFramesStore {

    /** 当前“文件内容”，`null` 表示文件不存在。可以直接改它来模拟损坏。 */
    var fileText: String? = initialFileText
        private set

    /** 最近一次写入的原始文本，`null` 表示从未写过。 */
    var lastSavedText: String? = null
        private set

    /** 写入次数，用来验证“重复使用只写一次”之类的行为。 */
    var saveCount: Int = 0
        private set

    override fun load(): List<String> = RecentFrames.decode(fileText)

    override fun save(ids: List<String>) {
        val text = RecentFrames.encode(ids)
        fileText = text
        lastSavedText = text
        saveCount += 1
    }

    /** 直接覆盖“文件内容”，用于模拟文件损坏或被外部改写。 */
    fun rewriteFile(text: String?) {
        fileText = text
    }

    /** 模拟文件被删掉。 */
    fun deleteFile() {
        fileText = null
    }
}
