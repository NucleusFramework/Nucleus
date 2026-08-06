package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.ExecutableType
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.exception.NoMatchingFileException
import dev.nucleusframework.updater.exception.UpdateException
import dev.nucleusframework.updater.internal.ChecksumVerifier
import dev.nucleusframework.updater.internal.FileSelector
import dev.nucleusframework.updater.internal.PlatformInfo
import dev.nucleusframework.updater.internal.PlatformInstaller
import dev.nucleusframework.updater.internal.UpdateMarker
import dev.nucleusframework.updater.internal.YamlParser
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaResolver
import dev.nucleusframework.updater.internal.delta.DifferentialDownloader
import dev.nucleusframework.updater.internal.delta.UpdateCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

public class NucleusUpdater(
    private val config: UpdaterConfig,
) {
    public val currentVersion: String get() = config.currentVersion

    private var pendingUpdateVersion: String? = null

    private val httpClient: HttpClient =
        config.httpClient
            ?: HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

    /** Holds the last downloaded artifact, which the next differential download builds upon. */
    private val cache: UpdateCache by lazy {
        config.cacheDir?.let(::UpdateCache) ?: UpdateCache.default()
    }

    public fun isUpdateSupported(): Boolean {
        val type = resolveExecutableType()
        return type in SELF_UPDATABLE_TYPES
    }

    public suspend fun checkForUpdates(): UpdateResult {
        if (config.isDevMode()) return UpdateResult.NotAvailable
        if (!isUpdateSupported()) return UpdateResult.NotAvailable
        return withContext(Dispatchers.IO) {
            try {
                doCheckForUpdates()
            } catch (e: UpdateException) {
                UpdateResult.Error(e)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                UpdateResult.Error(NetworkException("Failed to check for updates", e))
            }
        }
    }

    public fun downloadUpdate(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            pendingUpdateVersion = info.version
            val targetFile = info.currentFile
            val tempDir = System.getProperty("java.io.tmpdir")
            val tempFile = File(tempDir, "${targetFile.fileName}.download")
            val finalFile = File(tempDir, targetFile.fileName)

            try {
                val outcome =
                    downloadDifferentially(targetFile, tempFile)
                        ?: downloadFully(targetFile, tempFile)

                // Rename to final file
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)

                // Best-effort: fetch the detached signature next to the package so a signature-verified
                // silent install (Linux passwordless update) can use it. Absent signature is not fatal —
                // the installer simply falls back to the standard (password-prompting) path.
                downloadDetachedSignature(targetFile.url, File(finalFile.parentFile, "${finalFile.name}.asc"))

                // Keep this artifact around so the next update only has to fetch what changed.
                cacheForNextUpdate(targetFile, finalFile, info.version, outcome.blockMapGzip)

                emit(
                    DownloadProgress(
                        bytesDownloaded = outcome.bytesTransferred,
                        totalBytes = outcome.bytesTransferred,
                        percent = PERCENT_MAX,
                        file = finalFile,
                        isDifferential = outcome.isDifferential,
                    ),
                )
            } catch (e: UpdateException) {
                tempFile.delete()
                throw e
            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                tempFile.delete()
                throw NetworkException("Download failed", e)
            }
        }.flowOn(Dispatchers.IO)

    /** Outcome of one of the two download strategies. */
    private class DownloadOutcome(
        val bytesTransferred: Long,
        val isDifferential: Boolean,
        val blockMapGzip: ByteArray?,
    )

    /**
     * Assembles [targetFile] from the copy already on this machine plus range requests for the
     * changed blocks, or returns `null` when that is not possible so the caller downloads it whole.
     *
     * Every failure mode — no local artifact, no block map published, a server that ignores `Range`,
     * a digest mismatch on the assembled file — resolves to `null`, because a full download is
     * always a correct answer. Only cancellation propagates.
     */
    private suspend fun FlowCollector<DownloadProgress>.downloadDifferentially(
        targetFile: UpdateFile,
        tempFile: File,
    ): DownloadOutcome? {
        if (!config.differentialDownload) return null
        return try {
            val resolver = DeltaResolver(httpClient, config.provider.authHeaders(), cache)
            val resolved =
                resolver.resolve(
                    target = targetFile,
                    blockMapUrl = config.provider.getBlockMapUrl(targetFile.url),
                    destination = tempFile,
                ) ?: return null

            val plannedBytes = DeltaPlan.downloadSize(resolved.download.operations)
            logger.info(
                "Differential update of ${targetFile.fileName}: fetching $plannedBytes " +
                    "of ${targetFile.size} bytes",
            )
            emit(DownloadProgress(0, plannedBytes, 0.0, isDifferential = true))

            val transferred =
                DifferentialDownloader(httpClient, config.provider.authHeaders())
                    .download(resolved.download) { downloaded, total ->
                        emit(DownloadProgress(downloaded, total, percentOf(downloaded, total), isDifferential = true))
                    }
            DownloadOutcome(transferred, isDifferential = true, blockMapGzip = resolved.blockMapGzip)
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.INFO, "Differential update unavailable, downloading the full artifact", e)
            tempFile.delete()
            null
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.downloadFully(
        targetFile: UpdateFile,
        tempFile: File,
    ): DownloadOutcome {
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(targetFile.url))
                .GET()
        applyAuthHeaders(requestBuilder)
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != HTTP_OK) {
            throw NetworkException("HTTP ${response.statusCode()} downloading ${targetFile.url}")
        }

        val totalBytes = targetFile.size
        var bytesDownloaded = 0L

        response.body().use { inputStream ->
            tempFile.outputStream().use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    emit(DownloadProgress(bytesDownloaded, totalBytes, percentOf(bytesDownloaded, totalBytes)))
                }
            }
        }

        // Verify checksum
        if (!ChecksumVerifier.verify(tempFile, targetFile.sha512)) {
            val actual = ChecksumVerifier.computeSha512Base64(tempFile)
            tempFile.delete()
            throw ChecksumException(targetFile.sha512, actual)
        }

        // Fetch the block map so the *next* update can be differential. Artifacts that embed their
        // own (AppImage, nsis-web) need no companion file, and nothing needs one at all when
        // differential downloads are off.
        val blockMapGzip =
            if (config.differentialDownload && !DeltaResolver.embedsBlockMap(targetFile)) {
                fetchBlockMap(config.provider.getBlockMapUrl(targetFile.url))
            } else {
                null
            }
        return DownloadOutcome(bytesDownloaded, isDifferential = false, blockMapGzip = blockMapGzip)
    }

    private fun percentOf(
        downloaded: Long,
        total: Long,
    ): Double =
        if (total > 0) {
            (downloaded.toDouble() / total * PERCENT_MAX).coerceAtMost(PERCENT_MAX)
        } else {
            0.0
        }

    /** Downloads a block map, or returns `null` when the release does not publish one. */
    private fun fetchBlockMap(url: String): ByteArray? =
        try {
            val requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
            applyAuthHeaders(requestBuilder)
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            response.body()?.takeIf { response.statusCode() == HTTP_OK && it.isNotEmpty() }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.FINE, "No block map at $url; the next update will be a full download", e)
            null
        }

    private fun cacheForNextUpdate(
        targetFile: UpdateFile,
        artifact: File,
        version: String,
        blockMapGzip: ByteArray?,
    ) {
        if (!config.differentialDownload) return
        cache.store(artifact, targetFile.fileName, version, blockMapGzip)
    }

    /**
     * Downloads `<url>.asc` to [dest] if present. Failures are swallowed: the detached signature is
     * optional and only used by the Linux passwordless self-update helper.
     */
    private fun downloadDetachedSignature(
        url: String,
        dest: File,
    ) {
        try {
            val requestBuilder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$url.asc"))
                    .GET()
            applyAuthHeaders(requestBuilder)
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() == HTTP_OK) {
                dest.writeBytes(response.body())
            }
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Deliberately swallowed: the detached signature is optional, so any failure here
            // (missing .asc, network error) just means the silent update falls back to the
            // standard password-prompting install path — not an error worth surfacing.
        }
    }

    public fun installAndRestart(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = true)
    }

    public fun installAndQuit(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = false)
    }

    /**
     * Returns the update event if the application was just updated, and consumes it
     * so that subsequent calls return `null`. Use this on startup to detect a
     * post-update launch (e.g. to show a "What's new" dialog or run migrations).
     */
    public fun consumeUpdateEvent(): UpdateEvent? {
        val event = peekUpdateEvent() ?: return null
        UpdateMarker.delete()
        return event
    }

    /**
     * Returns `true` if the application was launched after an update.
     * Does **not** consume the event — call [consumeUpdateEvent] to clear it.
     */
    public fun wasJustUpdated(): Boolean = UpdateMarker.exists()

    private fun peekUpdateEvent(): UpdateEvent? {
        val (previousVersion, newVersion) = UpdateMarker.read() ?: return null
        val level = Version.fromString(newVersion).levelFrom(Version.fromString(previousVersion))
        return UpdateEvent(previousVersion, newVersion, level)
    }

    private fun writeUpdateMarker() {
        val targetVersion = pendingUpdateVersion ?: return
        try {
            UpdateMarker.write(config.currentVersion, targetVersion)
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // Best-effort: don't prevent the update if the marker can't be written
        }
    }

    private fun doCheckForUpdates(): UpdateResult {
        val platform = PlatformInfo.currentPlatform()
        val arch = PlatformInfo.currentArch()
        val metadataUrl = config.provider.resolveMetadataUrl(config.channel, platform, httpClient)

        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(metadataUrl))
                .GET()
        applyAuthHeaders(requestBuilder)
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != HTTP_OK) {
            return UpdateResult.Error(NetworkException("HTTP ${response.statusCode()} for $metadataUrl"))
        }

        val metadata = YamlParser.parse(response.body())
        val currentVersion = Version.fromString(config.currentVersion)
        val remoteVersion = Version.fromString(metadata.version)

        val isNewer = remoteVersion > currentVersion
        val isDowngrade = remoteVersion < currentVersion

        if (!isNewer && !(config.allowDowngrade && isDowngrade)) {
            return UpdateResult.NotAvailable
        }

        // Skip pre-release remote unless allowed
        if (remoteVersion.meta.isNotEmpty() && !config.resolvedAllowPrerelease()) {
            return UpdateResult.NotAvailable
        }

        // On macOS, ignore the build-time system property so auto-detection
        // can prefer ZIP (silent install). Users can still force DMG via config.executableType.
        val format =
            config.executableType
                ?: if (platform == Platform.MacOS) {
                    null
                } else {
                    System.getProperty("nucleus.executable.type")
                }

        val selectedFile =
            FileSelector.select(
                files = metadata.files,
                platform = platform,
                arch = arch,
                format = format,
            ) ?: return UpdateResult.Error(
                NoMatchingFileException(
                    platform.name,
                    arch.name,
                    format ?: "auto",
                ),
            )

        val updateInfo =
            UpdateInfo(
                version = metadata.version,
                releaseDate = metadata.releaseDate,
                files =
                    metadata.files.map { file ->
                        UpdateFile(
                            url = config.provider.getDownloadUrl(file.url, metadata.version),
                            sha512 = file.sha512,
                            size = file.size,
                            blockMapSize = file.blockMapSize,
                            fileName = file.url,
                        )
                    },
                currentFile =
                    UpdateFile(
                        url = config.provider.getDownloadUrl(selectedFile.url, metadata.version),
                        sha512 = selectedFile.sha512,
                        size = selectedFile.size,
                        blockMapSize = selectedFile.blockMapSize,
                        fileName = selectedFile.url,
                    ),
            )

        val level = remoteVersion.levelFrom(currentVersion)

        return UpdateResult.Available(updateInfo, level)
    }

    private fun resolveExecutableType(): ExecutableType {
        val explicit = config.executableType
        if (explicit != null) return ExecutableRuntime.parseType(explicit)
        return ExecutableRuntime.type()
    }

    private fun applyAuthHeaders(builder: HttpRequest.Builder) {
        config.provider.authHeaders().forEach { (key, value) ->
            builder.header(key, value)
        }
    }

    public companion object {
        private const val HTTP_OK = 200
        private const val PERCENT_MAX = 100.0

        private val logger: Logger = Logger.getLogger(NucleusUpdater::class.java.name)

        private val SELF_UPDATABLE_TYPES =
            setOf(
                ExecutableType.EXE,
                ExecutableType.NSIS,
                ExecutableType.NSIS_WEB,
                ExecutableType.MSI,
                ExecutableType.DMG,
                ExecutableType.ZIP,
                ExecutableType.APPIMAGE,
                ExecutableType.DEB,
                ExecutableType.RPM,
            )
    }
}
