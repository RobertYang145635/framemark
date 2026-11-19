package com.framemark.native.workflow

import java.security.MessageDigest

/**
 * In-memory [WorkflowPlatform] for the batch/preview tests: the real export chain
 * (temporary file → no-clobber commit) and the preview file reads run unchanged
 * against a map instead of the disk.
 *
 * Thread-safe on purpose — the parallel batch export writes and commits from several
 * workers at once, so a test fake that is not is a fake that flakes.
 */
internal class InMemoryWorkflowPlatform(override val dataDir: String) : WorkflowPlatform {

    override val exportDir: String = "$dataDir/exports"

    private val files = LinkedHashMap<String, ByteArray>()

    @Synchronized
    fun seed(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
    }

    @Synchronized
    fun snapshot(path: String): ByteArray? = files[path]?.copyOf()

    @Synchronized
    override fun readTextFile(path: String): String? = null

    override fun writeTextFile(path: String, text: String) = Unit

    @Synchronized
    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        files[path] = bytes.copyOf()
        return true
    }

    @Synchronized
    override fun exists(path: String): Boolean = path in files

    @Synchronized
    override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

    @Synchronized
    override fun readBytes(path: String): ByteArray? = files[path]?.copyOf()

    @Synchronized
    override fun commitExport(temporaryPath: String, finalPath: String): Boolean {
        if (finalPath in files) return false
        val bytes = files[temporaryPath] ?: return false
        files[finalPath] = bytes.copyOf()
        files.remove(temporaryPath)
        return true
    }

    @Synchronized
    override fun deleteExportTemporary(path: String) {
        files.remove(path)
    }

    override fun fileFingerprint(path: String): String? {
        // Source photos answer with a stable identity (they are never written by an
        // export), generated files with the real digest — the two production shapes.
        if (path.startsWith("/photos/")) return "fingerprint-${path.substringAfterLast('/')}"
        val bytes = readBytes(path) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null

    override fun dateStamp(): String = "20261101"

    override fun timeStamp(): String = "120000"

    override fun formatDateTime(epochMillis: Long): String = ""

    override fun nowMillis(): Long = 1_800_000_000_000L
}
