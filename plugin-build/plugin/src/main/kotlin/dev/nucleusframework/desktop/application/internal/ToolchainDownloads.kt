package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared download / verify / extract plumbing for the toolchains the plugin provisions itself:
 * GraalVM ([GraalvmToolchainProvisioner]), the packaging JDK ([NucleusJdkToolchainProvisioner])
 * and Node.js ([NodeToolchainProvisioner]).
 *
 * Each provisioner keeps its own resolution logic (where an archive lives, how its checksum is
 * published) — only the transport is shared.
 */
internal object ToolchainDownloads {
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16
    private const val HTTP_FIRST_REDIRECT = 300
    private const val HTTP_FIRST_ERROR = 400

    /** One monitor per lock file, so threads of this JVM queue up instead of colliding. */
    private val inProcessLocks = ConcurrentHashMap<String, Any>()

    /**
     * Runs [action] while holding the install lock `<installBaseDir>/<id>.lock`, against both other
     * Gradle processes (a file lock) and other threads of this one. The file lock alone is not
     * enough: parallel tasks in one daemon share the JVM, and a second `FileChannel.lock()` there
     * throws `OverlappingFileLockException` instead of waiting.
     */
    fun <T> withInstallLock(
        installBaseDir: File,
        id: String,
        action: () -> T,
    ): T {
        installBaseDir.mkdirs()
        val lockFile = File(installBaseDir, "$id.lock")
        val monitor = inProcessLocks.computeIfAbsent(lockFile.canonicalPath) { Any() }
        return synchronized(monitor) {
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use { action() }
            }
        }
    }

    /** Downloads [url] into [dest]. Throws [IOException] with the URL in the message. */
    fun download(
        url: String,
        dest: File,
    ) {
        openConnection(url).inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, DOWNLOAD_BUFFER_SIZE) }
        }
    }

    /** Fetches [url] as text — checksum side-files, JSON indexes, discovery APIs. */
    fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = openConnection(url, headers).inputStream.use { it.readBytes().decodeToString() }

    /** Hex digest of this file under [algorithm] ("SHA-256", "SHA-1"). */
    fun File.digest(algorithm: String): String {
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

    /** Fails the build unless [archive] hashes to [expected] under [algorithm]. */
    fun verifyChecksum(
        archive: File,
        source: String,
        algorithm: String,
        expected: String,
    ) {
        val actual = archive.digest(algorithm)
        check(actual.equals(expected, ignoreCase = true)) {
            "Checksum mismatch for $source: expected $expected, got $actual"
        }
    }

    /**
     * Reads a checksum published as a side-file next to the archive, or `null` when it cannot be
     * fetched — some networks filter the side-file while allowing the archive itself, and an
     * integrity failure would still surface when `tar` chokes on the payload.
     */
    fun fetchOptionalChecksum(
        url: String,
        logTag: String,
        logger: Logger,
    ): String? =
        runCatching { fetchText(url) }
            .map { it.trim().substringBefore(' ') }
            .getOrElse {
                logger.warn("$logTag Could not fetch checksum $url (${it.message}) — skipping verification")
                null
            }

    /** Opens a connection following redirects across hosts (HttpURLConnection won't by itself). */
    // Redirect handling has three distinct failure modes worth reporting separately.
    @Suppress("ThrowsCount")
    fun openConnection(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
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
     * time under the configuration cache.
     */
    fun extract(
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
