package com.framemark.native.userlogo

import android.content.Context
import android.graphics.BitmapFactory
import com.framemark.native.workflow.UserLogo
import com.framemark.native.workflow.UserLogoAsset
import com.framemark.native.workflow.UserLogoImportStatus
import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.workflow.UserLogoLibrary
import com.framemark.native.workflow.UserLogoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * App-global user logo library for Android: `filesDir/user-logos/index.json`
 * plus one PNG/WebP per entry. It is independent of the current project, so the
 * same library is available after switching projects or restarting the app.
 */
class AndroidUserLogoRepository(context: Context) : UserLogoRepository {
    private val dir = File(context.filesDir, "user-logos")
    private val indexFile = File(dir, "index.json")
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
            // Bounded: the stored logo can never exceed the import limit, so a larger
            // file is corrupt and must be refused without being loaded.
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
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val width = options.outWidth
                val height = options.outHeight
                UserLogoLibrary.validate(width, height, bytes.size, normalized)?.let {
                    throw IllegalArgumentException(it)
                }
                dir.mkdirs()
                val existing = readIndexForWrite()
                val id = "ul-${System.currentTimeMillis()}-${sha256(bytes).take(6)}"
                val entry = UserLogo(
                    id = id,
                    name = UserLogoLibrary.safeName(name, normalized),
                    mime = normalized,
                    width = width,
                    height = height,
                    sizeBytes = bytes.size,
                    sha256 = sha256(bytes),
                    addedAt = System.currentTimeMillis(),
                )
                val target = File(dir, "$id.${entry.extension}")
                target.writeBytes(bytes)
                val next = existing.filterNot { it.id == id } + entry
                try {
                    writeIndex(next)
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
            // Commit the index first: if this fails the image is still referenced and
            // remains valid. A successfully removed entry leaves only a harmless
            // orphan file if the unlink below fails.
            writeIndex(entries.filterNot { it.id == id })
            UserLogoLibrary.changed()
            File(dir, "${entry.id}.${entry.extension}").delete()
        }
    }

    private fun readIndex(): List<UserLogo> = readIndexResult().getOrElse {
        UserLogoImportStatus.fail("用户 Logo 索引损坏：${it.message}")
        emptyList()
    }

    /**
     * Writes must never turn a corrupt index into a one-entry index. Preserve a
     * copy for recovery and refuse the mutation until the index is repaired.
     */
    private fun readIndexForWrite(): List<UserLogo> = readIndexResult().getOrElse {
        backupCorruptIndex()
        throw IllegalArgumentException("用户 Logo 索引损坏，已拒绝写入：${it.message}")
    }

    private fun readIndexResult(): Result<List<UserLogo>> {
        if (!indexFile.isFile) return Result.success(emptyList())
        return runCatching {
            val array = JSONArray(indexFile.readText())
            val entries = (0 until array.length()).map { index ->
                val o = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("entry $index is not an object")
                val id = o.optString("id")
                val name = o.optString("name")
                val mime = o.optString("mime")
                val width = o.optInt("width")
                val height = o.optInt("height")
                val sizeBytes = o.optInt("sizeBytes")
                val sha256 = o.optString("sha256")
                require(id.isNotBlank()) { "entry $index has blank id" }
                require(name.isNotBlank()) { "entry $index has blank name" }
                require(mime == UserLogoLibrary.MIME_PNG || mime == UserLogoLibrary.MIME_WEBP) {
                    "entry $index has invalid mime"
                }
                require(width > 0 && height > 0 && sizeBytes > 0) { "entry $index has invalid dimensions/size" }
                require(sha256.length == 64) { "entry $index has invalid sha256" }
                UserLogo(
                    id = id,
                    name = name,
                    mime = mime,
                    width = width,
                    height = height,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    addedAt = o.optLong("addedAt"),
                )
            }
            require(entries.map { it.id }.distinct().size == entries.size) { "duplicate ids in index" }
            entries
        }
    }

    private fun backupCorruptIndex() {
        if (!indexFile.isFile) return
        val backup = File(dir, "index.corrupt-${System.currentTimeMillis()}.json")
        runCatching { indexFile.copyTo(backup, overwrite = false) }
    }

    private fun writeIndex(entries: List<UserLogo>) {
        dir.mkdirs()
        val array = JSONArray()
        entries.sortedByDescending { it.addedAt }.forEach { entry ->
            array.put(JSONObject()
                .put("id", entry.id)
                .put("name", entry.name)
                .put("mime", entry.mime)
                .put("width", entry.width)
                .put("height", entry.height)
                .put("sizeBytes", entry.sizeBytes)
                .put("sha256", entry.sha256)
                .put("addedAt", entry.addedAt))
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.outputStream().use { stream ->
            stream.write(array.toString().toByteArray())
            stream.flush()
            stream.fd.sync()
        }
        if (!tmp.renameTo(indexFile)) {
            tmp.delete()
            throw IllegalStateException("用户 Logo 索引原子替换失败")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
