package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Current OpenJDK used as the jpackage / jlink / `run` JDK when
 * [dev.nucleusframework.desktop.application.dsl.NucleusOptimizationSettings.lastJdk]
 * is on.
 *
 * Pin from https://jdk.java.net/27/ (GA 2026-09-15). The last RC (build 35) was
 * promoted unchanged; the install id dropped the `-rc-b35` suffix so existing
 * caches re-provision under a stable GA directory.
 */
internal const val OPENJDK_27_FEATURE = 27
internal const val OPENJDK_27_BUILD = 35
internal const val OPENJDK_27_HASH = "55ce5470a6294008af0057ff4626d0e5"
internal const val OPENJDK_27_INSTALL_ID = "openjdk-27"

private const val OPENJDK_27_DOWNLOAD_BASE =
    "https://download.java.net/java/GA/jdk27/$OPENJDK_27_HASH/$OPENJDK_27_BUILD/GPL"

/** BellSoft Liberica JDK 27 for macOS x64 — Oracle dropped the port. */
internal const val LIBERICA_27_MACOS_X64_URL =
    "https://github.com/bell-sw/Liberica/releases/download/27+36/bellsoft-jdk27+36-macos-amd64.tar.gz"
private const val LIBERICA_27_MACOS_X64_SHA1 = "00c2e885219f9454a08aae944175758c8c2d3831"
private const val LIBERICA_27_INSTALL_ID = "liberica-jdk-27"

internal data class NucleusJdkToolchainRequest(
    val os: OS,
    val arch: Arch,
    val installBaseDir: File,
)

/**
 * Configuration-cache-safe entry point to [NucleusJdkToolchainProvisioner].
 * Stays lazy so `gradlew tasks` / an IDE sync never downloads the JDK.
 */
internal abstract class NucleusJdkToolchainValueSource :
    ValueSource<String, NucleusJdkToolchainValueSource.Params> {
    interface Params : ValueSourceParameters {
        val installBaseDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val request =
            NucleusJdkToolchainRequest(
                os = currentOS,
                arch = currentArch,
                installBaseDir = File(parameters.installBaseDir.get()),
            )
        return NucleusJdkToolchainProvisioner
            .provision(
                request,
                execOperations,
                Logging.getLogger(NucleusJdkToolchainProvisioner::class.java),
            ).absolutePath
    }
}

/**
 * Downloads and caches OpenJDK 27 for the JVM packaging toolchain, mirroring
 * [GraalvmToolchainProvisioner] for native-image.
 *
 * `NUCLEUS_JDK_HOME` pointing at a valid JDK 27 installation bypasses the
 * download. macOS Intel falls back to BellSoft Liberica JDK 27 (Oracle dropped
 * the port). Windows aarch64 is not published — set
 * [dev.nucleusframework.desktop.application.dsl.JvmApplication.javaHome] or
 * `NUCLEUS_JDK_HOME` to a local JDK 27 instead.
 */
@Suppress("TooManyFunctions")
internal object NucleusJdkToolchainProvisioner {
    private const val MARKER_FILE = ".nucleus-provisioned"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16
    private const val HTTP_FIRST_REDIRECT = 300
    private const val HTTP_FIRST_ERROR = 400
    private const val ENV_JDK_HOME = "NUCLEUS_JDK_HOME"

    fun provision(
        request: NucleusJdkToolchainRequest,
        execOperations: ExecOperations,
        logger: Logger,
    ): File {
        environmentOverride(logger)?.let { return it }

        val id = installationId(request)
        val installDir = File(request.installBaseDir, id)
        readMarker(installDir)?.let { return it }

        request.installBaseDir.mkdirs()
        RandomAccessFile(File(request.installBaseDir, "$id.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                readMarker(installDir)?.let { return it }
                return downloadAndInstall(request, id, installDir, execOperations, logger)
            }
        }
    }

    internal fun downloadUrl(
        os: OS,
        arch: Arch,
    ): String =
        if (usesLibericaFallback(os, arch)) {
            LIBERICA_27_MACOS_X64_URL
        } else {
            "$OPENJDK_27_DOWNLOAD_BASE/${artifactName(os, arch)}"
        }

    internal fun installationId(request: NucleusJdkToolchainRequest): String {
        val vendor =
            if (usesLibericaFallback(request.os, request.arch)) {
                LIBERICA_27_INSTALL_ID
            } else {
                OPENJDK_27_INSTALL_ID
            }
        return "$vendor-${request.os.id}-${archToken(request.arch)}"
    }

    internal fun usesLibericaFallback(
        os: OS,
        arch: Arch,
    ): Boolean = os == OS.MacOS && arch == Arch.X64

    internal fun archToken(arch: Arch): String =
        when (arch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "aarch64"
        }

    internal fun checkSupported(
        os: OS,
        arch: Arch,
    ) {
        check(!(os == OS.Windows && arch == Arch.Arm64)) {
            "OpenJDK $OPENJDK_27_FEATURE has no ${os.id}-${archToken(arch)} build. " +
                "Set nucleus.application { javaHome = \"...\" } to a local JDK $OPENJDK_27_FEATURE, " +
                "or set $ENV_JDK_HOME."
        }
    }

    private fun artifactName(
        os: OS,
        arch: Arch,
    ): String {
        checkSupported(os, arch)
        val ext = if (os == OS.Windows) "zip" else "tar.gz"
        return "openjdk-${OPENJDK_27_FEATURE}_${os.id}-${archToken(arch)}_bin.$ext"
    }

    private fun environmentOverride(logger: Logger): File? {
        val env = System.getenv(ENV_JDK_HOME)?.takeIf { it.isNotBlank() } ?: return null
        val root = File(env)
        val home = root.resolve("Contents/Home").takeIf { it.isDirectory } ?: root
        if (javaBinary(home) == null) {
            logger.warn(
                "[nucleusOptimization] $ENV_JDK_HOME is set to $env but contains no bin/java — ignoring it",
            )
            return null
        }
        val feature = javaFeatureVersion(home)
        if (feature != OPENJDK_27_FEATURE) {
            logger.warn(
                "[nucleusOptimization] $ENV_JDK_HOME ($home) is JDK $feature, expected " +
                    "$OPENJDK_27_FEATURE — ignoring it and downloading OpenJDK $OPENJDK_27_FEATURE",
            )
            return null
        }
        logger.lifecycle("[nucleusOptimization] Using $ENV_JDK_HOME toolchain: $home")
        return home
    }

    private fun javaFeatureVersion(javaHome: File): Int? {
        val release = javaHome.resolve("release")
        if (!release.isFile) return null
        val raw =
            release
                .readLines()
                .firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter("JAVA_VERSION=")
                ?.trim('"')
                ?: return null
        return raw.takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun readMarker(installDir: File): File? {
        val marker = File(installDir, MARKER_FILE)
        if (!marker.isFile) return null
        val home = File(installDir, marker.readText().trim())
        return home.takeIf { it.isDirectory && javaBinary(it) != null }
    }

    private fun downloadAndInstall(
        request: NucleusJdkToolchainRequest,
        id: String,
        installDir: File,
        execOperations: ExecOperations,
        logger: Logger,
    ): File {
        val url = downloadUrl(request.os, request.arch)
        val description =
            if (usesLibericaFallback(request.os, request.arch)) {
                "Liberica JDK $OPENJDK_27_FEATURE (${request.os.id}-${archToken(request.arch)})"
            } else {
                "OpenJDK $OPENJDK_27_FEATURE+$OPENJDK_27_BUILD " +
                    "(${request.os.id}-${archToken(request.arch)})"
            }
        logger.lifecycle("[nucleusOptimization] Downloading $description from $url")
        val archive = File(request.installBaseDir, "$id.download")
        val extractDir = File(request.installBaseDir, "$id.extract")
        try {
            download(url, archive)
            verifyChecksum(archive, url, request, logger)

            extractDir.deleteRecursively()
            extract(archive, extractDir, execOperations)

            val topDir =
                extractDir.listFiles()?.singleOrNull { it.isDirectory }
                    ?: error("Unexpected archive layout for $url: expected a single top-level directory")
            val homeRelative =
                if (topDir.resolve("Contents/Home").isDirectory) {
                    "${topDir.name}/Contents/Home"
                } else {
                    topDir.name
                }
            checkNotNull(javaBinary(File(extractDir, homeRelative))) {
                "Downloaded toolchain $description contains no bin/java ($topDir)"
            }

            installDir.deleteRecursively()
            installDir.mkdirs()
            Files.move(
                topDir.toPath(),
                installDir.toPath().resolve(topDir.name),
                StandardCopyOption.ATOMIC_MOVE,
            )
            File(installDir, MARKER_FILE).writeText(homeRelative)

            val home = File(installDir, homeRelative)
            logger.lifecycle("[nucleusOptimization] $description installed to $home")
            return home
        } finally {
            archive.delete()
            extractDir.deleteRecursively()
        }
    }

    private fun javaBinary(home: File): File? =
        listOf("java", "java.exe")
            .map { home.resolve("bin/$it") }
            .firstOrNull { it.isFile }

    private fun verifyChecksum(
        archive: File,
        url: String,
        request: NucleusJdkToolchainRequest,
        logger: Logger,
    ) {
        if (usesLibericaFallback(request.os, request.arch)) {
            val actual = archive.digest("SHA-1")
            check(actual.equals(LIBERICA_27_MACOS_X64_SHA1, ignoreCase = true)) {
                "Checksum mismatch for $url: expected $LIBERICA_27_MACOS_X64_SHA1, got $actual"
            }
            return
        }
        val sha256Url = "$url.sha256"
        val text =
            runCatching { fetchText(sha256Url) }.getOrElse {
                logger.warn(
                    "[nucleusOptimization] Could not fetch checksum $sha256Url (${it.message}) — " +
                        "skipping verification",
                )
                return
            }
        val expected = text.trim().substringBefore(' ')
        val actual = archive.digest("SHA-256")
        check(actual.equals(expected, ignoreCase = true)) {
            "Checksum mismatch for $sha256Url: expected $expected, got $actual"
        }
    }

    private fun File.digest(algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(
        url: String,
        dest: File,
    ) {
        try {
            openConnection(url).inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, DOWNLOAD_BUFFER_SIZE) }
            }
        } catch (e: IOException) {
            throw IOException(
                "Failed to download JDK $OPENJDK_27_FEATURE from $url: ${e.message}",
                e,
            )
        }
    }

    private fun fetchText(url: String): String =
        openConnection(url).inputStream.use { it.readBytes().decodeToString() }

    @Suppress("ThrowsCount")
    private fun openConnection(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            when {
                code in HTTP_FIRST_REDIRECT until HTTP_FIRST_ERROR -> {
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw IOException("Redirect without Location header from $current")
                    connection.disconnect()
                    current = location
                }
                code >= HTTP_FIRST_ERROR -> throw IOException("HTTP $code from $current")
                else -> return connection
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private fun extract(
        archive: File,
        destDir: File,
        execOperations: ExecOperations,
    ) {
        destDir.mkdirs()
        val output = ByteArrayOutputStream()
        val result =
            execOperations.exec { spec ->
                spec.commandLine("tar", "-xf", archive.absolutePath, "-C", destDir.absolutePath)
                spec.standardOutput = output
                spec.errorOutput = output
                spec.isIgnoreExitValue = true
            }
        check(result.exitValue == 0) { "tar failed extracting ${archive.name}: $output" }
    }
}
