package dev.nucleusframework.nucleus.core.runtime.tools

import dev.nucleusframework.nucleus.core.runtime.NucleusApp
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Auto-detects the `.desktop` filename for the running application.
 *
 * Resolution order (first match wins):
 * 1. [NucleusApp.appId] (= `packageName` from the Nucleus DSL) — verified against XDG dirs
 * 2. `GIO_LAUNCHED_DESKTOP_FILE` env var (with PID check to avoid inheriting the parent's value)
 * 3. `BAMF_DESKTOP_FILE_HINT` env var (with `Exec=` match against `/proc/self/exe`)
 * 4. `/proc/self/exe` executable name → `{name}.desktop`
 * 5. Scanning XDG application directories for a `.desktop` file whose `Exec` matches the process
 */
object LinuxDesktopFileDetector {
    val desktopFilename: String? by lazy { detect() }

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    private fun detect(): String? {
        if (NucleusApp.isConfigured) {
            val candidate = ensureDesktopSuffix(NucleusApp.appId)
            if (desktopFileExists(candidate)) return candidate
        }

        System.getenv("GIO_LAUNCHED_DESKTOP_FILE")?.let { path ->
            val launchedPid = System.getenv("GIO_LAUNCHED_DESKTOP_FILE_PID")?.toLongOrNull()
            if (launchedPid != null && launchedPid == ProcessHandle.current().pid()) {
                return ensureDesktopSuffix(File(path).name)
            }
        }

        System.getenv("BAMF_DESKTOP_FILE_HINT")?.let { hint ->
            val hintFile = File(hint)
            if (hintFile.exists()) {
                val exe = readSelfExe()
                if (exe != null &&
                    hintFile.useLines { lines ->
                        lines.any { it.startsWith("Exec=") && it.contains(exe) }
                    }
                ) {
                    return ensureDesktopSuffix(hintFile.name)
                }
            }
        }

        readSelfExeName()?.let { name ->
            val candidate = "$name.desktop"
            if (desktopFileExists(candidate)) return candidate
        }

        return findDesktopFileByExec()
    }

    private fun readSelfExe(): String? =
        try {
            Files.readSymbolicLink(Path.of("/proc/self/exe")).toString()
        } catch (_: Exception) {
            null
        }

    private fun readSelfExeName(): String? {
        val name = Path.of(readSelfExe() ?: return null).fileName.toString()
        return if (name == "java" || name == "javaw") null else name
    }

    private fun ensureDesktopSuffix(name: String): String = if (name.endsWith(".desktop")) name else "$name.desktop"

    private fun desktopFileExists(name: String): Boolean = xdgAppDirs().any { it.resolve(name).exists() }

    @Suppress("TooGenericExceptionCaught")
    private fun findDesktopFileByExec(): String? {
        val exe = readSelfExe() ?: return null
        for (dir in xdgAppDirs()) {
            val files = dir.listFiles { f -> f.name.endsWith(".desktop") } ?: continue
            for (file in files) {
                try {
                    val matches =
                        file.useLines { lines ->
                            lines.any { it.startsWith("Exec=") && it.contains(exe) }
                        }
                    if (matches) return file.name
                } catch (_: Exception) {
                    // unreadable file, skip
                }
            }
        }
        return null
    }

    private fun xdgAppDirs(): List<File> {
        val dirs = mutableListOf<File>()
        val dataHome =
            System.getenv("XDG_DATA_HOME")
                ?: (System.getProperty("user.home") + "/.local/share")
        dirs.add(File(dataHome, "applications"))

        val dataDirs =
            System
                .getenv("XDG_DATA_DIRS")
                ?.takeIf { it.isNotBlank() }
                ?: "/usr/local/share:/usr/share"
        for (dir in dataDirs.split(':')) {
            dirs.add(File(dir, "applications"))
        }
        return dirs
    }
}
