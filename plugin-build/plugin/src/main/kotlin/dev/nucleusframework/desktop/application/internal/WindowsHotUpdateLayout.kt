package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lays a Windows jpackage app image out for hot updates (every NSIS installer):
 *
 * ```
 * <App>.exe                     launcher, stays at the root
 * app\<App>.cfg                 rewritten to point into the version directory
 * versions\<version>\runtime\   was runtime\
 * versions\<version>\app\       was app\ (everything but the .cfg files)
 * ```
 *
 * The jpackage launcher reads `app\<name>.cfg` next to itself at every start and nothing more, so
 * a new version can be installed next to a running one — nothing the running JVM holds open is
 * overwritten — and the rewritten `.cfg` makes the next start pick it up. The `.cfg` names the
 * runtime with `app.runtime` and every `$APPDIR` reference becomes `$ROOTDIR\versions\<version>\app`.
 *
 * Must match `UpdateHandoff` / `WindowsHotUpdate` in the runtime, which recognize the layout from
 * `java.home` and read the installed version back from `app.runtime`.
 */
internal object WindowsHotUpdateLayout {
    internal const val VERSIONS_DIR_NAME = "versions"
    private const val APP_DIR_NAME = "app"
    private const val RUNTIME_DIR_NAME = "runtime"
    private const val APPLICATION_SECTION = "[Application]"
    private const val RUNTIME_KEY = "app.runtime"
    private const val APPDIR_MACRO = "\$APPDIR"
    private const val ROOTDIR_MACRO = "\$ROOTDIR"

    /**
     * Rewrites [appImageDir] in place. Returns `false`, leaving it untouched, when it is not a
     * jpackage image (a GraalVM native image has no `.cfg` nor `runtime\`) or is already versioned.
     */
    fun apply(
        appImageDir: File,
        version: String,
    ): Boolean {
        val appDir = File(appImageDir, APP_DIR_NAME)
        val runtimeDir = File(appImageDir, RUNTIME_DIR_NAME)
        val cfgFiles = appDir.listFiles { file -> file.isFile && file.extension.equals("cfg", ignoreCase = true) }
        if (cfgFiles.isNullOrEmpty() || !runtimeDir.isDirectory) return false
        if (File(appImageDir, VERSIONS_DIR_NAME).exists()) return false

        val versionName = versionDirName(version)
        val versionDir = File(appImageDir, "$VERSIONS_DIR_NAME/$versionName")
        val versionAppDir = File(versionDir, APP_DIR_NAME).apply { mkdirs() }
        move(runtimeDir, File(versionDir, RUNTIME_DIR_NAME))
        appDir.listFiles()?.filter { it !in cfgFiles }?.forEach { move(it, File(versionAppDir, it.name)) }

        val versionRoot = "$ROOTDIR_MACRO\\$VERSIONS_DIR_NAME\\$versionName"
        cfgFiles.forEach { cfg -> cfg.writeText(rewriteCfg(cfg.readText(), versionRoot)) }
        return true
    }

    /** A version string made safe as a directory name (it names `versions\<version>`). */
    internal fun versionDirName(version: String): String =
        version
            .trim()
            .replace(Regex("[^A-Za-z0-9._+-]"), "_")
            .trimEnd('.')
            .ifEmpty { "current" }

    /** Points a launcher `.cfg` at `<versionRoot>\app` and `<versionRoot>\runtime`. */
    internal fun rewriteCfg(
        cfg: String,
        versionRoot: String,
    ): String {
        val lineSeparator = if (cfg.contains("\r\n")) "\r\n" else "\n"
        val lines =
            cfg
                .lines()
                .filterNot { it.trim().startsWith("$RUNTIME_KEY=") }
                .map { it.replace(APPDIR_MACRO, "$versionRoot\\$APP_DIR_NAME") }
                .toMutableList()
        val runtimeLine = "$RUNTIME_KEY=$versionRoot\\$RUNTIME_DIR_NAME"
        val section = lines.indexOfFirst { it.trim() == APPLICATION_SECTION }
        if (section >= 0) {
            lines.add(section + 1, runtimeLine)
        } else {
            lines.addAll(0, listOf(APPLICATION_SECTION, runtimeLine, ""))
        }
        return lines.joinToString(lineSeparator)
    }

    private fun move(
        source: File,
        target: File,
    ) {
        target.parentFile.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
