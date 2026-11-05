package com.framemark.native.userlogo

import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoImportStatus
import com.framemark.native.workflow.UserLogoLibrary
import kotlinx.coroutines.runBlocking
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop import entry: an AWT file dialog, then the same validation as Android. */
object DesktopUserLogoPicker {
    fun open() {
        val repo = UserLogoEnv.repository ?: run {
            UserLogoImportStatus.fail("用户 Logo 库不可用")
            return
        }
        Thread {
            java.awt.EventQueue.invokeLater {
                val dialog = FileDialog(null as Frame?, "选择用户 Logo", FileDialog.LOAD).apply {
                    filenameFilter = java.io.FilenameFilter { _, name ->
                        name.endsWith(".png", ignoreCase = true) || name.endsWith(".webp", ignoreCase = true)
                    }
                    isVisible = true
                }
                val selected = dialog.file ?: return@invokeLater
                val file = File(dialog.directory, selected)
                Thread {
                    runBlocking {
                        runCatching {
                            val bytes = file.inputStream().use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                var total = 0
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > UserLogoLibrary.MAX_BYTES) {
                                        throw IllegalArgumentException("图片不能超过 4 MB")
                                    }
                                    out.write(buffer, 0, read)
                                }
                                out.toByteArray()
                            }
                            repo.import(file.name, UserLogoLibrary.normalizeMime(null, file.name).orEmpty(), bytes)
                        }.onSuccess {
                            println("FM_USER_LOGO_IMPORTED id=${it.id} ${it.width}x${it.height} ${it.sizeBytes}B")
                        }.onFailure {
                            println("FM_USER_LOGO_IMPORT_FAIL ${it.message}")
                            UserLogoImportStatus.fail(it.message ?: "导入失败")
                        }
                    }
                }.start()
            }
        }.start()
    }
}
