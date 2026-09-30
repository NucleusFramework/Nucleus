package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.NodeJsSettings
import dev.nucleusframework.desktop.application.internal.ToolchainDownloads.fetchText
import dev.nucleusframework.desktop.application.tasks.AbstractElectronBuilderPackageTask
import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Environment variable pointing at a Node.js installation to use instead of downloading one. */
internal const val NODE_HOME_ENV = "NUCLEUS_NODE_HOME"

/**
 * What Node.js toolchain to provision for the current build machine.
 *
 * @param version a major line tracking its newest release (`"22"`), the newest LTS (`"lts"`),
 *   or a pinned release (`"22.11.0"`).
 */
internal data class NodeToolchainRequest(
    val version: String,
    val os: OS,
    val arch: Arch,
    val installBaseDir: File,
)

/**
 * A provisioned Node.js installation: the directory the archive unpacked to, plus the two
 * executables the electron-builder pipeline runs.
 */
internal data class NodeInstallation(
    val home: File,
    val node: File,
    val npm: File,
)

/**
 * Downloads and caches the Node.js used to provision and run electron-builder, so packaging
 * needs nothing installed on the build machine — the same deal [GraalvmToolchainProvisioner]
 * gives native-image and [NucleusJdkToolchainProvisioner] gives jpackage.
 *
 * Archives come from `https://nodejs.org/dist/v<version>/`, verified against the `SHASUMS256.txt`
 * published alongside them. Each installation is unpacked under `<installBaseDir>/<id>/` with a
 * marker file recording its home directory; once provisioned, resolution is a single marker-file
 * read (no network). Floating versions ("22", "lts") are sticky — delete the directory to pick up
 * a newer release.
 *
 * A [NODE_HOME_ENV] environment variable pointing at a usable installation bypasses the download.
 *
 * Unlike the JDK toolchains this one is provisioned at execution time, from the packaging task
 * itself: nothing in the task graph needs the path at configuration time.
 */
internal object NodeToolchainProvisioner {
    private const val MARKER_FILE = ".nucleus-provisioned"
    private const val NODE_DIST_BASE = "https://nodejs.org/dist"
    private const val NODE_INDEX_URL = "$NODE_DIST_BASE/index.json"
    private const val LTS_VERSION = "lts"

    fun provision(
        request: NodeToolchainRequest,
        execOperations: ExecOperations,
        logger: Logger,
    ): NodeInstallation {
        environmentOverride(logger)?.let { return it }

        val id = installationId(request)
        val installDir = File(request.installBaseDir, id)
        readMarker(installDir)?.let { return it }

        // Guard against concurrent builds and parallel tasks provisioning the same toolchain.
        return ToolchainDownloads.withInstallLock(request.installBaseDir, id) {
            readMarker(installDir) ?: downloadAndInstall(request, id, installDir, execOperations, logger)
        }
    }

    /** The install directory name: the request's version, not the resolved one, so it stays sticky. */
    internal fun installationId(request: NodeToolchainRequest): String =
        "node-${request.version}-${platformToken(request.os)}-${archToken(request.arch)}"

    /** Node's own platform token, as it appears in the archive names. */
    internal fun platformToken(os: OS): String =
        when (os) {
            OS.Windows -> "win"
            OS.MacOS -> "darwin"
            OS.Linux -> "linux"
        }

    /** Node's own architecture token — `arm64`, not the `aarch64` the JDK archives use. */
    internal fun archToken(arch: Arch): String =
        when (arch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "arm64"
        }

    /** Archive name for a fully resolved version (`v22.11.0`). */
    internal fun archiveName(
        version: String,
        os: OS,
        arch: Arch,
    ): String {
        val ext = if (os == OS.Windows) "zip" else "tar.gz"
        return "node-$version-${platformToken(os)}-${archToken(arch)}.$ext"
    }

    /**
     * Resolves [requested] to a concrete `v`-prefixed release, hitting `index.json` only for the
     * floating forms ("22", "lts"). A pinned version resolves offline.
     */
    internal fun resolveVersion(
        requested: String,
        index: () -> String,
    ): String {
        val normalized = requested.removePrefix("v")
        if (normalized.count { it == '.' } == 2) return "v$normalized"

        @Suppress("UNCHECKED_CAST")
        val releases = JsonSlurper().parseText(index()) as List<Map<String, Any?>>
        val matching =
            releases.filter { release ->
                val version = release["version"] as? String ?: return@filter false
                if (normalized.equals(LTS_VERSION, ignoreCase = true)) {
                    release["lts"] != false
                } else {
                    majorOf(version) == normalized.toIntOrNull()
                }
            }
        // index.json is published newest-first, but sort rather than trust the order.
        return matching.maxWithOrNull(compareBy(versionOrder) { versionKey(it["version"] as String) })
            ?.get("version") as? String
            ?: error(
                "No Node.js release matches '$requested'. Set nativeDistributions { nodejs { version } } " +
                    "to a released version, or point at a local install with the " +
                    "'${NucleusProperties.ELECTRON_BUILDER_NODE_PATH}' Gradle property.",
            )
    }

    /** Resolves the executables inside an unpacked (or user-supplied) Node.js home. */
    internal fun installationAt(home: File): NodeInstallation? {
        val windows = home.resolve("node.exe")
        if (windows.isFile) {
            return NodeInstallation(home, windows, home.resolve("npm.cmd"))
        }
        val unix = home.resolve("bin/node")
        if (unix.isFile) {
            return NodeInstallation(home, unix, home.resolve("bin/npm"))
        }
        return null
    }

    internal fun majorOf(version: String): Int? = version.removePrefix("v").substringBefore('.').toIntOrNull()

    private fun environmentOverride(logger: Logger): NodeInstallation? {
        val home = System.getenv(NODE_HOME_ENV)?.takeIf { it.isNotBlank() } ?: return null
        val installation = installationAt(File(home))
        if (installation == null) {
            logger.warn("[nodejs] Ignoring $NODE_HOME_ENV=$home — no node executable found there")
            return null
        }
        logger.info("[nodejs] Using $NODE_HOME_ENV=${installation.home}")
        return installation
    }

    private fun readMarker(installDir: File): NodeInstallation? {
        val marker = File(installDir, MARKER_FILE).takeIf { it.isFile } ?: return null
        val home = File(installDir, marker.readText().trim())
        return installationAt(home)
    }

    private fun downloadAndInstall(
        request: NodeToolchainRequest,
        id: String,
        installDir: File,
        execOperations: ExecOperations,
        logger: Logger,
    ): NodeInstallation {
        val version = resolveVersion(request.version) { fetchText(NODE_INDEX_URL) }
        val name = archiveName(version, request.os, request.arch)
        val url = "$NODE_DIST_BASE/$version/$name"

        logger.lifecycle("[nodejs] Downloading Node.js ${version.removePrefix("v")} from $url")
        val archive = File(request.installBaseDir, "$id.download")
        val extractDir = File(request.installBaseDir, "$id.extract")
        try {
            try {
                ToolchainDownloads.download(url, archive)
            } catch (e: IOException) {
                throw IOException("Failed to download Node.js from $url: ${e.message}", e)
            }
            verifyChecksum(archive, version, name, logger)

            extractDir.deleteRecursively()
            ToolchainDownloads.extract(archive, extractDir, execOperations)

            val topDir =
                extractDir.listFiles()?.singleOrNull { it.isDirectory }
                    ?: error("Unexpected archive layout for $url: expected a single top-level directory")
            checkNotNull(installationAt(topDir)) { "Downloaded Node.js archive $name contains no node executable" }

            installDir.deleteRecursively()
            installDir.mkdirs()
            Files.move(
                topDir.toPath(),
                installDir.toPath().resolve(topDir.name),
                StandardCopyOption.ATOMIC_MOVE,
            )
            File(installDir, MARKER_FILE).writeText(topDir.name)

            val installation =
                checkNotNull(installationAt(File(installDir, topDir.name))) {
                    "Node.js was installed to $installDir but its node executable is missing"
                }
            logger.lifecycle("[nodejs] Node.js ${version.removePrefix("v")} installed to ${installation.home}")
            return installation
        } finally {
            archive.delete()
            extractDir.deleteRecursively()
        }
    }

    /**
     * Verifies the archive against the release's `SHASUMS256.txt`, which lists every artifact of
     * that release as `<sha256>  <filename>`.
     */
    private fun verifyChecksum(
        archive: File,
        version: String,
        archiveName: String,
        logger: Logger,
    ) {
        val url = "$NODE_DIST_BASE/$version/SHASUMS256.txt"
        val sums =
            runCatching { fetchText(url) }.getOrElse {
                logger.warn("[nodejs] Could not fetch checksums $url (${it.message}) — skipping verification")
                return
            }
        val expected =
            sums
                .lineSequence()
                .firstOrNull { it.trim().endsWith("  $archiveName") }
                ?.trim()
                ?.substringBefore(' ')
        if (expected == null) {
            logger.warn("[nodejs] $url lists no entry for $archiveName — skipping verification")
            return
        }
        ToolchainDownloads.verifyChecksum(archive, archiveName, "SHA-256", expected)
    }

    private fun versionKey(version: String): List<Int> =
        version
            .removePrefix("v")
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private val versionOrder: Comparator<List<Int>> =
        Comparator { left, right ->
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
                if (comparison != 0) return@Comparator comparison
            }
            0
        }
}

/**
 * Copies the `nodejs { }` DSL onto a packaging task. The cache directory defaults to
 * `<gradle-user-home>/nucleus/nodejs`, next to the GraalVM and JDK toolchains.
 */
internal fun AbstractElectronBuilderPackageTask.configureNodeJs(
    project: Project,
    nodejs: NodeJsSettings,
) {
    nodeAutoDownload.set(nodejs.autoDownload)
    nodeVersion.set(nodejs.version)
    nodeInstallDir.set(
        nodejs.installDir
            .map { it.asFile.absolutePath }
            .orElse(project.gradle.gradleUserHomeDir.resolve("nucleus/nodejs").absolutePath),
    )
}
