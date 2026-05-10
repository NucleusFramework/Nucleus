package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.core.runtime.ExecutableType
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.updater.exception.ChecksumException
import dev.nucleusframework.nucleus.updater.exception.NetworkException
import dev.nucleusframework.nucleus.updater.exception.NoMatchingFileException
import dev.nucleusframework.nucleus.updater.exception.UpdateException
import dev.nucleusframework.nucleus.updater.internal.ChecksumVerifier
import dev.nucleusframework.nucleus.updater.internal.FileSelector
import dev.nucleusframework.nucleus.updater.internal.PlatformInfo
import dev.nucleusframework.nucleus.updater.internal.PlatformInstaller
import dev.nucleusframework.nucleus.updater.internal.UpdateMarker
import dev.nucleusframework.nucleus.updater.internal.YamlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

class NucleusUpdater(
    private val config: UpdaterConfig,
) {
    val currentVersion: String get() = config.currentVersion

    private var pendingUpdateVersion: String? = null

    private val httpClient: HttpClient =
        config.httpClient
            ?: HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

    fun isUpdateSupported(): Boolean {
        val type = resolveExecutableType()
        return type in SELF_UPDATABLE_TYPES
    }

    suspend fun checkForUpdates(): UpdateResult {
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

    fun downloadUpdate(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            pendingUpdateVersion = info.version
            val targetFile = info.currentFile
            val tempDir = System.getProperty("java.io.tmpdir")
            val tempFile = File(tempDir, "${targetFile.fileName}.download")
            val finalFile = File(tempDir, targetFile.fileName)

            try {
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
                            val percent =
                                if (totalBytes > 0) {
                                    (bytesDownloaded.toDouble() / totalBytes * PERCENT_MAX).coerceAtMost(PERCENT_MAX)
                                } else {
                                    0.0
                                }
                            emit(DownloadProgress(bytesDownloaded, totalBytes, percent))
                        }
                    }
                }

                // Verify checksum
                if (!ChecksumVerifier.verify(tempFile, targetFile.sha512)) {
                    val actual = ChecksumVerifier.computeSha512Base64(tempFile)
                    tempFile.delete()
                    throw ChecksumException(targetFile.sha512, actual)
                }

                // Rename to final file
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)

                emit(DownloadProgress(bytesDownloaded, totalBytes, PERCENT_MAX, finalFile))
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

    fun installAndRestart(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = true)
    }

    fun installAndQuit(installerFile: File) {
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        PlatformInstaller.install(installerFile, platform, restart = false)
    }

    /**
     * Returns the update event if the application was just updated, and consumes it
     * so that subsequent calls return `null`. Use this on startup to detect a
     * post-update launch (e.g. to show a "What's new" dialog or run migrations).
     */
    fun consumeUpdateEvent(): UpdateEvent? {
        val event = peekUpdateEvent() ?: return null
        UpdateMarker.delete()
        return event
    }

    /**
     * Returns `true` if the application was launched after an update.
     * Does **not** consume the event — call [consumeUpdateEvent] to clear it.
     */
    fun wasJustUpdated(): Boolean = UpdateMarker.exists()

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
        val metadataUrl = config.provider.getUpdateMetadataUrl(config.channel, platform)

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

    companion object {
        private const val HTTP_OK = 200
        private const val PERCENT_MAX = 100.0

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
