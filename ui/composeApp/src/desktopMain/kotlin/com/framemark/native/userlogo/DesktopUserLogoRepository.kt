package com.framemark.native.userlogo

import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.workflow.PhotoMetadata
import com.framemark.native.workflow.UserLogo
import com.framemark.native.workflow.UserLogoAsset
import com.framemark.native.workflow.UserLogoImportStatus
import com.framemark.native.workflow.UserLogoLibrary
import com.framemark.native.workflow.UserLogoRepository
import com.framemark.native.engine.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Desktop app-global library. Stored under the runtime data root so every
 * project opened by this install sees the same user logos.
 */
class DesktopUserLogoRepository(
    private val dir: File = File(RuntimePaths.layout().dataRoot, "user-logos"),
) : UserLogoRepository {
    private val indexFile = File(dir, "index.properties")
    private val mutex = Mutex()

    override val revision: Int get() = UserLogoLibrary.revision

    override suspend fun list(): List<UserLogo> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex() }
    }

    override suspend fun load(id: String): UserLogoAsset? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = readIndex().firstOrNull { it.id == id } ?: return@withLock null
            val file = File(dir, "${entry.id}.${entry.extension}")
            if (!file.isFile) return@withLock null
            // Bounded: an entry larger than the import limit cannot be a library logo.
            val bytes = runCatching {
                readFileBytesBounded(file.path, UserLogoLibrary.MAX_BYTES)
            }.getOrNull() ?: return@withLock null
            if (bytes.size != entry.sizeBytes) return@withLock null
            UserLogoAsset(entry, bytes)
        }
    }

    override suspend fun import(name: String, mime: String, bytes: ByteArray): UserLogo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val normalized = UserLogoLibrary.normalizeMime(mime, name)
                    ?: throw IllegalArgumentException("只支持 PNG 或 WebP")
                if (bytes.size > UserLogoLibrary.MAX_BYTES) {
                    throw IllegalArgumentException("图片不能超过 4 MB")
                }
                dir.mkdirs()
                val existing = readIndexForWrite()
                val id = "ul-${System.currentTimeMillis()}-${sha256(bytes).take(6)}"
                val entryName = UserLogoLibrary.safeName(name, normalized)
                val target = File(dir, "$id.${if (normalized == UserLogoLibrary.MIME_WEBP) "webp" else "png"}")
                target.writeBytes(bytes)
                val meta = PhotoMetadata.read(target)
                if (meta == null) {
                    target.delete()
                    throw IllegalArgumentException("图片无法解码")
                }
                UserLogoLibrary.validate(meta.width, meta.height, bytes.size, normalized)?.let {
                    target.delete()
                    throw IllegalArgumentException(it)
                }
                val entry = UserLogo(
                    id = id,
                    name = entryName,
                    mime = normalized,
                    width = meta.width,
                    height = meta.height,
                    sizeBytes = bytes.size,
                    sha256 = sha256(bytes),
                    addedAt = System.currentTimeMillis(),
                )
                try {
                    writeIndex(existing.filterNot { it.id == id } + entry)
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
                UserLogoLibrary.changed()
                entry
            }
        }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readIndexForWrite()
            val entry = entries.firstOrNull { it.id == id } ?: return@withLock
            // Commit the index first; a failed write must not leave an index entry
            // pointing at a deleted image.
            writeIndex(entries.filterNot { it.id == id })
            UserLogoLibrary.changed()
            File(dir, "${entry.id}.${entry.extension}").delete()
        }
    }

    private fun readIndex(): List<UserLogo> = readIndexResult().getOrElse {
        UserLogoImportStatus.fail("用户 Logo 索引损坏：${it.message}")
        emptyList()
    }

    private fun readIndexForWrite(): List<UserLogo> = readIndexResult().getOrElse {
        backupCorruptIndex()
        throw IllegalArgumentException("用户 Logo 索引损坏，已拒绝写入：${it.message}")
    }

    private fun readIndexResult(): Result<List<UserLogo>> {
        if (!indexFile.isFile) return Result.success(emptyList())
        return runCatching {
            val props = Properties()
            FileInputStream(indexFile).use(props::load)
            val rawIds = props.getProperty("ids")
                ?: throw IllegalArgumentException("missing ids")
            val tokens = if (rawIds.isEmpty()) emptyList() else rawIds.split(',')
            require(tokens.all { it.isNotBlank() }) { "blank id token" }
            val entries = tokens.map { id ->
                val name = props.getProperty("$id.name")
                    ?: throw IllegalArgumentException("$id missing name")
                val mime = props.getProperty("$id.mime")
                    ?: throw IllegalArgumentException("$id missing mime")
                val width = props.getProperty("$id.width")?.toIntOrNull()
                    ?: throw IllegalArgumentException("$id missing width")
                val height = props.getProperty("$id.height")?.toIntOrNull()
                    ?: throw IllegalArgumentException("$id missing height")
                val sizeBytes = props.getProperty("$id.sizeBytes")?.toIntOrNull()
                    ?: throw IllegalArgumentException("$id missing sizeBytes")
                val sha256 = props.getProperty("$id.sha256")
                    ?: throw IllegalArgumentException("$id missing sha256")
                require(name.isNotBlank()) { "$id has blank name" }
                require(mime == UserLogoLibrary.MIME_PNG || mime == UserLogoLibrary.MIME_WEBP) {
                    "$id has invalid mime"
                }
                require(width > 0 && height > 0 && sizeBytes > 0) { "$id has invalid dimensions/size" }
                require(sha256.length == 64) { "$id has invalid sha256" }
                UserLogo(
                    id = id,
                    name = name,
                    mime = mime,
                    width = width,
                    height = height,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    addedAt = props.getProperty("$id.addedAt")?.toLongOrNull() ?: 0L,
                )
            }
            require(entries.map { it.id }.distinct().size == entries.size) { "duplicate ids in index" }
            entries
        }
    }

    private fun backupCorruptIndex() {
        if (!indexFile.isFile) return
        runCatching { indexFile.copyTo(File(indexFile.parentFile, "index.corrupt-${System.currentTimeMillis()}.properties"), overwrite = false) }
    }

    private fun writeIndex(entries: List<UserLogo>) {
        dir.mkdirs()
        val props = Properties()
        val sorted = entries.sortedByDescending { it.addedAt }
        props.setProperty("ids", sorted.joinToString(",") { it.id })
        sorted.forEach { entry ->
            props.setProperty("${entry.id}.name", entry.name)
            props.setProperty("${entry.id}.mime", entry.mime)
            props.setProperty("${entry.id}.width", entry.width.toString())
            props.setProperty("${entry.id}.height", entry.height.toString())
            props.setProperty("${entry.id}.sizeBytes", entry.sizeBytes.toString())
            props.setProperty("${entry.id}.sha256", entry.sha256)
            props.setProperty("${entry.id}.addedAt", entry.addedAt.toString())
        }
        val tmp = File(dir, "index.properties.tmp")
        FileOutputStream(tmp).use { stream ->
            props.store(stream, "framemark user logo index")
            stream.flush()
            stream.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            indexFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
