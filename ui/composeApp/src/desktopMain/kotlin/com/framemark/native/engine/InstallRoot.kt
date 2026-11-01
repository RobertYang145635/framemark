package com.framemark.native.engine

import java.io.File

/**
 * Where the running program keeps its shipped resources.
 *
 * The launch working directory is not an input any more: a shortcut, a service or
 * a shell started elsewhere must not change what the app can see. A packaged
 * install is located from the launcher itself (`jpackage.app-path`, the process
 * command, or the jar folder jpackage creates), so `<install>/data/registry`
 * resolves no matter where the process was started from.
 *
 * A dev checkout has no `data/` next to its classes, and silently walking up into
 * a source tree is exactly the coupling this replaces: that fallback is only
 * taken when `FM_RUNTIME_ROOT` says so explicitly.
 */
internal object InstallRoot {
    fun candidates(): List<File> {
        val out = LinkedHashSet<File>()
        System.getenv("FM_RUNTIME_ROOT")?.takeIf { it.isNotBlank() }?.let { out += File(it).absoluteFile }

        // The launcher we are actually running under (jpackage app-image or an exe).
        runCatching { ProcessHandle.current().info().command().orElse(null) }
            .getOrNull()
            ?.let(::File)
            ?.absoluteFile
            ?.parentFile
            ?.let { exeDir ->
                val name = exeDir.name.lowercase()
                if (name != "bin" && name != "runtime") out += exeDir
            }

        // jpackage keeps the jars in <install>/app; a plain `java -cp` over an
        // installed tree looks the same from here.
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .map(::File)
            .filter { it.name.endsWith(".jar") && it.parentFile?.name == "app" }
            .forEach { out += it.parentFile.parentFile }

        // The code source itself, for a launcher we could not identify.
        runCatching {
            RuntimePaths::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        }.getOrNull()?.let { src ->
            var dir: File? = if (src.isDirectory) src else src.parentFile
            var hops = 0
            while (dir != null && hops++ < 6) {
                if (File(dir, "data/registry").isDirectory || File(dir, "bin/e2b.exe").isFile) out += dir
                dir = dir.parentFile
            }
        }
        return out.filter { it.isDirectory }.distinct()
    }
}
