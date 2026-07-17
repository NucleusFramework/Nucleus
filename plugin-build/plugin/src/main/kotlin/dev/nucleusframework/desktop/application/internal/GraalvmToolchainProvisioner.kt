package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import groovy.json.JsonSlurper
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
 * What GraalVM toolchain to provision for the current build machine.
 *
 * @param version Oracle GraalVM version: an innovation release (`"25i1"`), a feature
 *   version tracking the latest CPU (`"25"`), or a pinned patch release (`"25.0.1"`).
 * @param macosIntelFallback use Liberica NIK on macOS x64, where Oracle GraalVM is no
 *   longer shipped (dropped after 25.0.1).
 */
internal data class GraalvmToolchainRequest(
    val version: String,
    val os: OS,
    val arch: Arch,
    val macosIntelFallback: Boolean,
    val installBaseDir: File,
)

/**
 * Configuration-cache-safe entry point to [GraalvmToolchainProvisioner]. Provisioning runs
 * at configuration time (the resolved home feeds `Exec.executable` and `Copy.from`), and a
 * [ValueSource] with injected [ExecOperations] is Gradle's sanctioned way to start external
 * processes (`tar`) there. The value is also re-checked on configuration-cache hits, so a
 * deleted toolchain directory invalidates the entry and re-provisions.
 */
internal abstract class GraalvmToolchainValueSource :
    ValueSource<String, GraalvmToolchainValueSource.Params> {
    interface Params : ValueSourceParameters {
        val version: Property<String>
        val macosIntelFallback: Property<Boolean>
        val installBaseDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val request =
            GraalvmToolchainRequest(
                version = parameters.version.get(),
                os = currentOS,
                arch = currentArch,
                macosIntelFallback = parameters.macosIntelFallback.get(),
                installBaseDir = File(parameters.installBaseDir.get()),
            )
        return GraalvmToolchainProvisioner
            .provision(request, execOperations, Logging.getLogger(GraalvmToolchainProvisioner::class.java))
            .absolutePath
    }
}

/**
 * Downloads and caches the GraalVM JDK used for native-image builds, so no locally
 * installed GraalVM is required.
 *
 * Sources:
 * - Oracle GraalVM innovation releases (`25i1`) from
 *   `https://gds.oracle.com/download/graal/<v>/latest/graalvm-jdk-<v>-<base>_<os>-<arch>_bin.<ext>`
 * - Oracle GraalVM LTS/latest (`25`) and pinned (`25.0.1`) releases from
 *   `https://download.oracle.com/graalvm/<feature>/{latest,archive}/graalvm-jdk-<v>_<os>-<arch>_bin.<ext>`
 * - BellSoft Liberica NIK on Intel macs, resolved through the BellSoft discovery API
 *   (`api.bell-sw.com/v1/nik/releases`).
 *
 * Each toolchain is unpacked under `<installBaseDir>/<id>/` with a marker file recording
 * the java home; once provisioned, resolution is a single marker-file read (no network).
 * "latest" versions are sticky — delete the directory to pick up a newer build. A
 * `GRAALVM_HOME` environment variable pointing at a valid installation bypasses the
 * download entirely.
 */
internal object GraalvmToolchainProvisioner {
    private const val MARKER_FILE = ".nucleus-provisioned"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16
    private const val HTTP_FIRST_REDIRECT = 300
    private const val HTTP_FIRST_ERROR = 400
    private const val BITNESS_64 = 64
    private const val BELLSOFT_NIK_API = "https://api.bell-sw.com/v1/nik/releases?os=macos&output=json"

    fun provision(
        request: GraalvmToolchainRequest,
        execOperations: ExecOperations,
        logger: Logger,
    ): File {
        environmentOverride(logger)?.let { return it }

        val id = installationId(request)
        val installDir = File(request.installBaseDir, id)
        readMarker(installDir)?.let { return it }

        request.installBaseDir.mkdirs()
        // Guard against concurrent Gradle builds provisioning the same toolchain.
        RandomAccessFile(File(request.installBaseDir, "$id.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                readMarker(installDir)?.let { return it }
                return downloadAndInstall(request, id, installDir, execOperations, logger)
            }
        }
    }

    /** `GRAALVM_HOME` always wins — e.g. CI environments using `setup-graalvm`. */
    private fun environmentOverride(logger: Logger): File? {
        val env = System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() } ?: return null
        val root = File(env)
        val home = root.resolve("Contents/Home").takeIf { it.isDirectory } ?: root
        return if (nativeImageExecutable(home) != null) {
            logger.lifecycle("[graalvm] Using GRAALVM_HOME toolchain: $home")
            home
        } else {
            logger.warn(
                "[graalvm] GRAALVM_HOME is set to $env but contains no bin/native-image — ignoring it",
            )
            null
        }
    }

    private fun installationId(request: GraalvmToolchainRequest): String =
        if (request.usesLibericaFallback) {
            "liberica-nik-jdk${javaFeatureVersion(request.version)}-macos-x64"
        } else {
            "graalvm-jdk-${request.version}-${request.os.id}-${archToken(request.arch)}"
        }

    private val GraalvmToolchainRequest.usesLibericaFallback: Boolean
        get() = os == OS.MacOS && arch == Arch.X64 && macosIntelFallback

    private fun readMarker(installDir: File): File? {
        val marker = File(installDir, MARKER_FILE)
        if (!marker.isFile) return null
        val home = File(installDir, marker.readText().trim())
        return home.takeIf { it.isDirectory }
    }

    private fun downloadAndInstall(
        request: GraalvmToolchainRequest,
        id: String,
        installDir: File,
        execOperations: ExecOperations,
        logger: Logger,
    ): File {
        val source =
            if (request.usesLibericaFallback) {
                resolveLibericaDownload(javaFeatureVersion(request.version), logger)
            } else {
                resolveOracleDownload(request)
            }

        logger.lifecycle("[graalvm] Downloading ${source.description} from ${source.url}")
        val archive = File(request.installBaseDir, "$id.download")
        val extractDir = File(request.installBaseDir, "$id.extract")
        try {
            download(source.url, archive, request)
            verifyChecksum(archive, source, logger)

            extractDir.deleteRecursively()
            extract(archive, extractDir, execOperations)

            val topDir =
                extractDir.listFiles()?.singleOrNull { it.isDirectory }
                    ?: error("Unexpected archive layout for ${source.url}: expected a single top-level directory")
            val homeRelative =
                if (topDir.resolve("Contents/Home").isDirectory) {
                    "${topDir.name}/Contents/Home"
                } else {
                    topDir.name
                }
            checkNotNull(nativeImageExecutable(File(extractDir, homeRelative))) {
                "Downloaded toolchain ${source.description} contains no bin/native-image ($topDir)"
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
            logger.lifecycle("[graalvm] ${source.description} installed to $home")
            return home
        } finally {
            archive.delete()
            extractDir.deleteRecursively()
        }
    }

    // ── Oracle GraalVM ──

    private fun resolveOracleDownload(request: GraalvmToolchainRequest): DownloadSource {
        check(!(request.os == OS.Windows && request.arch == Arch.Arm64)) {
            "Oracle GraalVM is not available for windows-aarch64. Set GRAALVM_HOME to a manually " +
                "installed toolchain, or disable graalvm { toolchain { autoDownload } } and use " +
                "the Gradle toolchain resolver."
        }
        val version = request.version
        val osToken = request.os.id
        val archToken = archToken(request.arch)
        val ext = if (request.os == OS.Windows) "zip" else "tar.gz"
        val url =
            when {
                // Innovation releases ("25i1") are distributed through GDS only.
                version.contains('i') -> {
                    val base = version.substringBefore('i')
                    "https://gds.oracle.com/download/graal/$version/latest/" +
                        "graalvm-jdk-$version-${base}_$osToken-${archToken}_bin.$ext"
                }
                // Pinned patch release ("25.0.1").
                version.contains('.') -> {
                    val feature = version.substringBefore('.')
                    "https://download.oracle.com/graalvm/$feature/archive/" +
                        "graalvm-jdk-${version}_$osToken-${archToken}_bin.$ext"
                }
                // Feature version tracking the latest CPU ("25").
                else ->
                    "https://download.oracle.com/graalvm/$version/latest/" +
                        "graalvm-jdk-${version}_$osToken-${archToken}_bin.$ext"
            }
        return DownloadSource(
            url = url,
            description = "Oracle GraalVM $version ($osToken-$archToken)",
            // GDS (innovation releases) publishes no .sha256 side-file; download.oracle.com does.
            sha256Url = "$url.sha256".takeUnless { url.startsWith("https://gds.oracle.com/") },
        )
    }

    // ── Liberica NIK (Intel macs) ──

    @Suppress("UNCHECKED_CAST")
    private fun resolveLibericaDownload(
        javaFeature: Int,
        logger: Logger,
    ): DownloadSource {
        val releases =
            JsonSlurper().parseText(fetchText(BELLSOFT_NIK_API)) as? List<Map<String, Any?>>
                ?: error("Unexpected response from BellSoft NIK API ($BELLSOFT_NIK_API)")
        val candidates =
            releases.filter {
                it["architecture"] == "x86" &&
                    (it["bitness"] as? Number)?.toInt() == BITNESS_64 &&
                    it["bundleType"] == "standard" &&
                    it["packageType"] == "tar.gz" &&
                    it["installationType"] == "archive"
            }
        val forFeature =
            candidates.filter { (it["annualVersion"] as? Number)?.toInt() == javaFeature }
        val pool =
            forFeature.ifEmpty {
                logger.warn(
                    "[graalvm] No Liberica NIK based on JDK $javaFeature for macOS x64 yet — " +
                        "falling back to the latest available NIK release",
                )
                candidates
            }
        val chosen =
            pool.maxWithOrNull(compareBy(versionOrder) { versionKey(it["version"] as? String ?: "0") })
                ?: error(
                    "No Liberica NIK release found for macOS x64 via $BELLSOFT_NIK_API. " +
                        "Set GRAALVM_HOME to a manually installed toolchain.",
                )
        return DownloadSource(
            url = chosen["downloadUrl"] as String,
            description = "Liberica NIK ${chosen["version"]} (macos-x64)",
            sha1 = chosen["sha1"] as? String,
        )
    }

    /** Sortable key for versions like "25.0.3+2". */
    private fun versionKey(version: String): List<Int> =
        version
            .split('.', '+', '-')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private val versionOrder: Comparator<List<Int>> =
        Comparator { a, b ->
            (0 until maxOf(a.size, b.size))
                .map { i -> a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 }) }
                .firstOrNull { it != 0 } ?: 0
        }

    // ── Shared helpers ──

    private data class DownloadSource(
        val url: String,
        val description: String,
        val sha256Url: String? = null,
        val sha1: String? = null,
    )

    private fun javaFeatureVersion(version: String): Int =
        version.takeWhile(Char::isDigit).toIntOrNull()
            ?: error(
                "Invalid graalvm.toolchain.version '$version' — expected e.g. \"25\", \"25.0.1\" or \"25i1\"",
            )

    private fun archToken(arch: Arch): String =
        when (arch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "aarch64"
        }

    private fun nativeImageExecutable(home: File): File? =
        listOf("native-image", "native-image.cmd", "native-image.exe")
            .map { home.resolve("bin/$it") }
            .firstOrNull { it.isFile }

    private fun verifyChecksum(
        archive: File,
        source: DownloadSource,
        logger: Logger,
    ) {
        val (algorithm, expected) =
            when {
                source.sha1 != null -> "SHA-1" to source.sha1
                source.sha256Url != null -> {
                    val text =
                        runCatching { fetchText(source.sha256Url) }.getOrElse {
                            // Some networks filter the checksum side-file while allowing the
                            // archive itself; integrity failure would still surface in tar.
                            logger.warn(
                                "[graalvm] Could not fetch checksum ${source.sha256Url} (${it.message}) — " +
                                    "skipping verification",
                            )
                            return
                        }
                    "SHA-256" to text.trim().substringBefore(' ')
                }
                else -> return
            }
        val actual = archive.digest(algorithm)
        check(actual.equals(expected, ignoreCase = true)) {
            "Checksum mismatch for ${source.url}: expected $expected, got $actual"
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
        request: GraalvmToolchainRequest,
    ) {
        try {
            openConnection(url).inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, DOWNLOAD_BUFFER_SIZE) }
            }
        } catch (e: IOException) {
            val macIntelHint =
                if (request.os == OS.MacOS && request.arch == Arch.X64) {
                    " Oracle no longer ships macOS Intel builds — keep " +
                        "graalvm { toolchain { macosIntelFallback } } enabled to use Liberica NIK."
                } else {
                    ""
                }
            throw IOException("Failed to download GraalVM toolchain from $url: ${e.message}.$macIntelHint", e)
        }
    }

    private fun fetchText(url: String): String = openConnection(url).inputStream.use { it.readBytes().decodeToString() }

    /** Opens a connection following redirects across hosts (HttpURLConnection won't by itself). */
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

    /**
     * Extracts with the system `tar`, which preserves permissions and symlinks (Gradle's
     * tarTree does not) and is available on Linux, macOS and Windows 10+ (bsdtar, which
     * also handles zip). Runs through [ExecOperations] so it stays legal at configuration
     * time under the configuration cache (see [GraalvmToolchainValueSource]).
     */
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
