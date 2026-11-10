package com.framemark.native.product

import com.framemark.native.workflow.WorkflowPlatform

/** Error codes follow ARCH §7.2 so the UI can show the same text as the rest of the app. */
object StoreCodes {
    const val IO_READ = "FM-E-IO-002"
    const val IO_WRITE = "FM-E-IO-003"
    const val INTERNAL = "FM-E-INTERNAL-001"
    const val PRESET = "FM-E-PRESET-001"
    const val BATCH = "FM-E-BATCH-001"
}

/**
 * A structured failure: the UI shows [message], the log keeps [code]/[path]/[cause].
 * Nothing in the store turns an exception into a silent success.
 */
data class StoreError(
    val code: String,
    val message: String,
    val path: String? = null,
    val retryable: Boolean = false,
    val cause: Throwable? = null,
)

sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>

    /** The document does not exist yet; not an error. */
    data object Missing : StoreResult<Nothing>

    data class Failed(val error: StoreError) : StoreResult<Nothing>
}

fun <T> StoreResult<T>.valueOrNull(): T? = (this as? StoreResult.Ok)?.value

fun StoreResult<*>.errorOrNull(): StoreError? = (this as? StoreResult.Failed)?.error

/**
 * The only file operations the product core needs. Both targets are JVM, but the
 * interface stays platform-neutral so tests can inject failures and so the store
 * never touches `java.io` directly.
 *
 * [rename] must replace `to` with `from` (same directory/volume), atomically where
 * the filesystem supports it, and return false when the replace did not happen.
 * `File.renameTo` does **not** qualify on Windows (it refuses to replace an
 * existing file), so the platform adapter uses `Files.move(..., ATOMIC_MOVE)` and
 * falls back to `REPLACE_EXISTING` + a target read-back: the fallback is not atomic,
 * and a short write must be reported as a failure, not as a saved document.
 */
interface ProductFileSystem {
    val dataDir: String
    fun exists(path: String): Boolean
    fun readText(path: String): String?

    /** Raw document bytes. Adapters used for backups should override this method. */
    fun readBytes(path: String): ByteArray? = readText(path)?.encodeToByteArray()

    /** True only when every byte reached the file. */
    fun writeBytes(path: String, bytes: ByteArray): Boolean

    /** Creates [path] only when it is absent; false leaves an existing file intact. */
    fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean {
        if (exists(path)) return false
        return writeBytes(path, bytes)
    }
    fun fileSize(path: String): Long
    fun rename(from: String, to: String): Boolean
    fun delete(path: String): Boolean
}

/**
 * Bridges the existing [WorkflowPlatform] read/write hooks into the store. The two
 * operations it does not expose (atomic replace, delete) are supplied by the
 * caller from its file API; wiring them into `AndroidWorkflowPlatform` /
 * `DesktopWorkflowPlatform` belongs to the UI slice.
 */
class PlatformFileSystem(
    private val platform: WorkflowPlatform,
    private val replaceFile: (from: String, to: String) -> Boolean,
    private val deleteFile: (path: String) -> Boolean,
) : ProductFileSystem {
    override val dataDir: String get() = platform.dataDir

    override fun exists(path: String): Boolean = platform.exists(path)

    override fun readText(path: String): String? = platform.readTextFile(path)

    override fun readBytes(path: String): ByteArray? = platform.readBytes(path)

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = platform.writeBytes(path, bytes)

    override fun fileSize(path: String): Long = platform.fileSize(path)

    override fun rename(from: String, to: String): Boolean = replaceFile(from, to)

    override fun delete(path: String): Boolean = deleteFile(path)
}

enum class DocumentKind(
    val documentId: String,
    val fileName: String,
    val currentVersion: Int,
    val corruptCode: String,
) {
    Project(Documents.PROJECT, "project.json", PROJECT_SCHEMA_VERSION, StoreCodes.INTERNAL),
    PresetLibrary(Documents.PRESETS, "presets.json", PRESET_SCHEMA_VERSION, StoreCodes.PRESET),
    PresetFile(Documents.PRESET, "preset.json", PRESET_SCHEMA_VERSION, StoreCodes.PRESET),
    Batch(Documents.BATCH, "batch-queue.json", BATCH_SCHEMA_VERSION, StoreCodes.BATCH),
}

/**
 * One step of a schema upgrade. [transform] receives the parsed root (envelope
 * included) and must return it with `schemaVersion` bumped to [toVersion]; the
 * store verifies the bump and refuses a migration that silently does nothing.
 */
class SchemaMigration(
    val document: DocumentKind,
    val fromVersion: Int,
    val toVersion: Int,
    val transform: (Map<String, Any?>) -> Map<String, Any?>,
) {
    init {
        require(toVersion > fromVersion) { "迁移必须向前：v$fromVersion → v$toVersion" }
    }
}

/**
 * Reads and writes the project, the preset library, single preset files and the
 * batch queue under `fs.dataDir`.
 *
 * Writes are staged and verified: full bytes into `<path>.tmp`, a size check and a
 * byte-for-byte read-back, then a replace over the destination (atomic where the
 * filesystem supports it). A failure at any step returns the old document untouched
 * and a structured error. Durability is bounded by what the OS flushed: the platform
 * adapter requests an atomic move and a parent-directory fsync where the platform
 * allows it, but neither can promise more than the filesystem gives. Reads are
 * all-or-nothing: a wrong document id, a future version, a missing migrator or a bad
 * field fails the whole load instead of returning a half-populated model.
 */
private object DocumentWriteLocks {
    private val locks = Array(64) { Any() }
    fun forPath(path: String): Any = locks[(path.replace('\\', '/').lowercase().hashCode() ushr 1) % locks.size]
}

class SessionStore(
    private val fs: ProductFileSystem,
    private val migrations: List<SchemaMigration> = emptyList(),
) {
    fun documentPath(kind: DocumentKind): String = join(fs.dataDir, kind.fileName)

    /** Raw text of a document, for callers that must tell legacy from product. */
    fun documentText(kind: DocumentKind): String? = runCatching { fs.readText(documentPath(kind)) }.getOrNull()

    fun saveProject(project: Project): StoreResult<Unit> =
        saveAt(documentPath(DocumentKind.Project), { ProjectCodec.encode(project) })

    fun loadProject(): StoreResult<Project> =
        loadAt(documentPath(DocumentKind.Project), DocumentKind.Project, ProjectCodec::decode)

    fun savePresets(presets: List<ProjectPreset>): StoreResult<Unit> =
        saveAt(documentPath(DocumentKind.PresetLibrary), { PresetCodec.encodeLibrary(presets) })

    fun loadPresets(): StoreResult<List<ProjectPreset>> =
        loadAt(documentPath(DocumentKind.PresetLibrary), DocumentKind.PresetLibrary, PresetCodec::decodeLibrary)

    /**
     * The sole write path for upgrading the legacy preset library. It validates
     * the exact source rows, makes a byte-for-byte SHA-named backup, and only
     * replaces the legacy file after the staged product document reads back as
     * the complete converted list.
     */
    fun migrateLegacyPresetLibrary(converted: List<ProjectPreset>): StoreResult<List<ProjectPreset>> {
        val path = documentPath(DocumentKind.PresetLibrary)
        return synchronized(DocumentWriteLocks.forPath(path)) {
            migrateLegacyPresetLibraryLocked(path, converted)
        }
    }

    private fun migrateLegacyPresetLibraryLocked(path: String, converted: List<ProjectPreset>): StoreResult<List<ProjectPreset>> {
        val temp = "$path.tmp"
        var tempMayExist = false
        fun stagedFailure(message: String, cause: Throwable? = null): StoreResult.Failed {
            val note = if (tempMayExist) cleanupNote(temp) else ""
            return failed(StoreCodes.IO_WRITE, "$message$note", path, retryable = true, cause = cause)
        }

        val exists = try {
            fs.exists(path)
        } catch (t: Throwable) {
            return failed(StoreCodes.IO_READ, "无法检查旧预设库：${t.message}", path, cause = t)
        }
        if (!exists) return StoreResult.Missing

        val originalBytes = try {
            fs.readBytes(path)
        } catch (t: Throwable) {
            return failed(StoreCodes.IO_READ, "无法读取旧预设库原始字节：${t.message}", path, cause = t)
        } ?: return failed(StoreCodes.IO_READ, "旧预设库存在但无法读取原始字节", path)
        val originalText = try {
            originalBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (t: Throwable) {
            return failed(StoreCodes.PRESET, "旧预设库不是有效 UTF-8，未执行迁移", path, cause = t)
        }
        if (!LegacyDocuments.isLegacyPresetLibrary(originalText)) {
            return failed(StoreCodes.PRESET, "当前文件不是支持迁移的版本 1 旧预设库", path)
        }
        val legacyIds = LegacyDocuments.legacyPresetIds(originalText)
            ?: return failed(StoreCodes.PRESET, "旧预设库包含无效条目，未执行迁移", path)
        val convertedIds = converted.map { it.id }
        val isVerifiedEmptyLegacyLibrary = LegacyDocuments.isEmptyLegacyPresetLibrary(originalText)
        if ((legacyIds.isEmpty() && (!isVerifiedEmptyLegacyLibrary || converted.isNotEmpty())) ||
            legacyIds.size != legacyIds.distinct().size ||
            converted.size != legacyIds.size || convertedIds != legacyIds ||
            convertedIds.size != convertedIds.distinct().size ||
            converted.any { it.schemaVersion != PRESET_SCHEMA_VERSION || it.name.isBlank() }
        ) {
            return failed(StoreCodes.PRESET, "旧预设条目数或 ID 与转换结果不完全匹配，原文件未更改", path)
        }

        val expected = converted.map { it.copy(exportSettings = it.exportSettings.copy(quality = 100)) }
        val encoded = try {
            PresetCodec.encodeLibrary(converted).encodeToByteArray()
        } catch (t: Throwable) {
            return failed(StoreCodes.PRESET, "无法编码迁移后的预设库：${t.message}", path, cause = t)
        }

        val sha = Sha256.hex(originalBytes)
        val backupPath = "$path.legacy-$sha.bak"
        val backupReady = try {
            if (fs.exists(backupPath)) {
                fs.readBytes(backupPath)?.contentEquals(originalBytes) == true
            } else {
                fs.writeBytesIfAbsent(backupPath, originalBytes) ||
                    fs.readBytes(backupPath)?.contentEquals(originalBytes) == true
            }
        } catch (_: Throwable) {
            false
        }
        if (!backupReady || runCatching { fs.readBytes(backupPath)?.contentEquals(originalBytes) != true }.getOrDefault(true)) {
            return failed(StoreCodes.PRESET, "旧预设备份写入或 SHA 内容校验失败；原文件未更改", path)
        }
        if (runCatching { fs.readBytes(path)?.contentEquals(originalBytes) != true }.getOrDefault(true)) {
            return failed(StoreCodes.PRESET, "备份后旧预设库已变化；原文件未更改（备份：$backupPath）", path)
        }

        tempMayExist = true
        val written = try {
            fs.writeBytes(temp, encoded)
        } catch (t: Throwable) {
            return stagedFailure("写入迁移暂存文件失败：${t.message}", t)
        }
        if (!written) return stagedFailure("写入迁移暂存文件失败；原库保持不变")

        val stagedSize = try {
            fs.fileSize(temp)
        } catch (t: Throwable) {
            return stagedFailure("无法检查迁移暂存文件：${t.message}", t)
        }
        if (stagedSize != encoded.size.toLong()) {
            return stagedFailure("迁移暂存文件不完整（$stagedSize/${encoded.size} 字节）；原库保持不变")
        }
        val stagedBytes = try {
            fs.readBytes(temp)
        } catch (t: Throwable) {
            return stagedFailure("无法读取迁移暂存文件：${t.message}", t)
        } ?: return stagedFailure("迁移暂存文件无法读取；原库保持不变")
        if (!stagedBytes.contentEquals(encoded)) {
            return stagedFailure("迁移暂存文件内容被篡改；原库保持不变")
        }
        when (val readback = loadAt(temp, DocumentKind.PresetLibrary, PresetCodec::decodeLibrary)) {
            is StoreResult.Ok -> if (readback.value != expected) {
                return stagedFailure("迁移暂存读回与完整转换列表不一致；原库保持不变")
            }
            is StoreResult.Failed -> return stagedFailure("迁移暂存文档校验失败：${readback.error.message}", readback.error.cause)
            StoreResult.Missing -> return stagedFailure("迁移暂存文件消失；原库保持不变")
        }

        val unchanged = try {
            fs.readBytes(path)?.contentEquals(originalBytes) == true
        } catch (t: Throwable) {
            return stagedFailure("替换前无法复核旧预设库：${t.message}", t)
        }
        if (!unchanged) return stagedFailure("替换前旧预设库已变化；原文件保持不变（备份：$backupPath）")

        val replaced = try {
            fs.rename(temp, path)
        } catch (t: Throwable) {
            return stagedFailure("替换旧预设库失败：${t.message}", t)
        }
        if (!replaced) return stagedFailure("替换旧预设库失败；原文件保持不变")
        tempMayExist = false

        val finalBytes = try {
            fs.readBytes(path)
        } catch (t: Throwable) {
            null
        }
        val finalReadback = loadAt(path, DocumentKind.PresetLibrary, PresetCodec::decodeLibrary)
        val finalPresets = (finalReadback as? StoreResult.Ok)?.value
        if (finalBytes?.contentEquals(encoded) != true || finalPresets != expected) {
            val restored = restoreDocumentBytes(path, originalBytes)
            val restoreNote = if (restored) "；已恢复原始旧库" else "；恢复失败，请使用备份 $backupPath"
            val details = (finalReadback as? StoreResult.Failed)?.error?.message
            return failed(StoreCodes.IO_WRITE, "迁移替换后复核失败${details?.let { "：$it" } ?: ""}$restoreNote", path)
        }
        return StoreResult.Ok(requireNotNull(finalPresets))
    }

    fun saveBatch(queue: BatchQueue): StoreResult<Unit> =
        saveAt(documentPath(DocumentKind.Batch), { BatchCodec.encode(queue) })

    fun loadBatch(): StoreResult<BatchQueue> =
        loadAt(documentPath(DocumentKind.Batch), DocumentKind.Batch, BatchCodec::decode)

    /** Writes one portable preset file (watermark bytes inside) to an arbitrary path. */
    fun exportPreset(preset: ProjectPreset, path: String): StoreResult<Unit> =
        saveAt(path, { PresetCodec.encodeDocument(preset) })

    fun importPreset(path: String): StoreResult<ProjectPreset> =
        loadAt(path, DocumentKind.PresetFile, PresetCodec::decodeDocument)

    // ---- write ------------------------------------------------------------

    private fun saveAt(path: String, encode: () -> String): StoreResult<Unit> = synchronized(DocumentWriteLocks.forPath(path)) {
        saveAtLocked(path, encode)
    }

    private fun saveAtLocked(path: String, encode: () -> String): StoreResult<Unit> {
        val text = try {
            encode()
        } catch (t: Throwable) {
            return failed(StoreCodes.INTERNAL, "无法序列化文档：${t.message}", path, cause = t)
        }
        val bytes = text.encodeToByteArray()
        val temp = "$path.tmp"

        val written = try {
            fs.writeBytes(temp, bytes)
        } catch (t: Throwable) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "写入临时文件失败：${t.message}$note", path, retryable = true, cause = t)
        }
        if (!written) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "写入临时文件失败$note", path, retryable = true)
        }

        val size = try {
            fs.fileSize(temp)
        } catch (t: Throwable) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "无法校验临时文件：${t.message}$note", path, retryable = true, cause = t)
        }
        if (size != bytes.size.toLong()) {
            val note = cleanupNote(temp)
            return failed(
                StoreCodes.IO_WRITE,
                "临时文件不完整（$size/${bytes.size} 字节），原文件保持不变$note",
                path,
                retryable = true,
            )
        }

        val staged = try {
            fs.readBytes(temp)
        } catch (t: Throwable) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "无法读取并校验暂存文件：${t.message}$note", path, retryable = true, cause = t)
        } ?: run {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "暂存文件无法读取；原文件保持不变$note", path, retryable = true)
        }
        if (!staged.contentEquals(bytes)) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "暂存文件内容与待写入文档不一致；原文件保持不变$note", path, retryable = true)
        }
        try {
            val stagedRoot = ProductJson.parseRoot(staged.decodeToString(throwOnInvalidSequence = true))
            if (stagedRoot.isEmpty()) throw ProductDecodeException("暂存 JSON 对象为空")
        } catch (t: Throwable) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "暂存 JSON 结构无效；原文件保持不变：${t.message}$note", path, retryable = true, cause = t)
        }

        val replaced = try {
            fs.rename(temp, path)
        } catch (t: Throwable) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "替换文档失败：${t.message}$note", path, retryable = true, cause = t)
        }
        if (!replaced) {
            val note = cleanupNote(temp)
            return failed(StoreCodes.IO_WRITE, "替换文档失败（rename 未完成），原文件保持不变$note", path, retryable = true)
        }
        return StoreResult.Ok(Unit)
    }

    private fun cleanupNote(temp: String): String = try {
        fs.delete(temp)
        if (fs.exists(temp)) "；临时文件未能清理：$temp" else ""
    } catch (t: Throwable) {
        "；临时文件清理失败：${t.message}"
    }

    private fun restoreDocumentBytes(path: String, bytes: ByteArray): Boolean {
        val temp = "$path.restore.tmp"
        return try {
            if (!fs.writeBytes(temp, bytes) || fs.fileSize(temp) != bytes.size.toLong()) return false
            if (fs.readBytes(temp)?.contentEquals(bytes) != true) return false
            if (!fs.rename(temp, path)) return false
            fs.readBytes(path)?.contentEquals(bytes) == true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { fs.delete(temp) }
        }
    }

    // ---- read -------------------------------------------------------------

    private fun <T> loadAt(
        path: String,
        kind: DocumentKind,
        decode: (Map<String, Any?>) -> T,
    ): StoreResult<T> {
        val exists = try {
            fs.exists(path)
        } catch (t: Throwable) {
            return failed(StoreCodes.IO_READ, "无法访问文档：${t.message}", path, cause = t)
        }
        if (!exists) return StoreResult.Missing

        val text = try {
            fs.readText(path)
        } catch (t: Throwable) {
            return failed(StoreCodes.IO_READ, "读取文档失败：${t.message}", path, cause = t)
        } ?: return failed(StoreCodes.IO_READ, "文档存在但无法读取（权限或 IO 错误）", path)

        val root = try {
            ProductJson.parseRoot(text)
        } catch (t: Throwable) {
            return failed(kind.corruptCode, "文档已损坏：${t.message}", path, cause = t)
        }

        val documentId = root["document"] as? String
        if (documentId != kind.documentId) { 

            return failed(
                kind.corruptCode,
                "文档类型不符：期望 ${kind.documentId}，实际 ${documentId ?: "缺失"}",
                path,
            )
        }

        var version = (root["schemaVersion"] as? Number)?.toInt()
            ?: return failed(kind.corruptCode, "缺少 schemaVersion", path)
        if (version > kind.currentVersion) {
            return failed(
                kind.corruptCode,
                "文档版本过高（v$version > v${kind.currentVersion}），请升级应用后再打开",
                path,
            )
        }

        var current = root
        var hops = 0
        while (version < kind.currentVersion) {
            if (hops++ > 16) return failed(kind.corruptCode, "迁移链过长，已中止", path)
            val migration = migrations.firstOrNull { it.document == kind && it.fromVersion == version }
                ?: return failed(
                    kind.corruptCode,
                    "文档版本过旧（v$version < v${kind.currentVersion}），没有可用的迁移入口",
                    path,
                )
            val migrated = try {
                migration.transform(current)
            } catch (t: Throwable) {
                return failed(
                    kind.corruptCode,
                    "文档迁移失败（v${migration.fromVersion}→v${migration.toVersion}）：${t.message}",
                    path,
                    cause = t,
                )
            }
            val next = (migrated["schemaVersion"] as? Number)?.toInt()
                ?: return failed(kind.corruptCode, "迁移未写入 schemaVersion", path)
            if (next <= version) {
                return failed(kind.corruptCode, "迁移未推进版本（v$version → v$next）", path)
            }
            if (next > kind.currentVersion) {
                return failed(kind.corruptCode, "迁移越过目标版本（v$next > v${kind.currentVersion}）", path)
            }
            current = migrated
            version = next
        }

        return try {
            StoreResult.Ok(decode(current))
        } catch (t: Throwable) {
            failed(kind.corruptCode, "文档字段无效：${t.message}", path, cause = t)
        }
    }

    private fun failed(
        code: String,
        message: String,
        path: String?,
        retryable: Boolean = false,
        cause: Throwable? = null,
    ): StoreResult.Failed = StoreResult.Failed(StoreError(code, message, path, retryable, cause))

    private fun join(dir: String, name: String): String = dir.trimEnd('/', '\\') + "/" + name
}
