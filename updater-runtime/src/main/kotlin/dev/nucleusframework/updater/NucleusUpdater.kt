package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.ExecutableType
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.exception.NoMatchingFileException
import dev.nucleusframework.updater.exception.UpdateException
import dev.nucleusframework.updater.internal.ChecksumVerifier
import dev.nucleusframework.updater.internal.FeedFetcher
import dev.nucleusframework.updater.internal.FeedOverride
import dev.nucleusframework.updater.internal.FileSelector
import dev.nucleusframework.updater.internal.InstalledVersionWatcher
import dev.nucleusframework.updater.internal.PlatformInfo
import dev.nucleusframework.updater.internal.PlatformInstaller
import dev.nucleusframework.updater.internal.SimulatedUpdate
import dev.nucleusframework.updater.internal.UpdateMarker
import dev.nucleusframework.updater.internal.UpdaterSettings
import dev.nucleusframework.updater.internal.WindowsHotUpdate
import dev.nucleusframework.updater.internal.YamlParser
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaResolver
import dev.nucleusframework.updater.internal.delta.DifferentialDownloader
import dev.nucleusframework.updater.internal.delta.UpdateCache
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

public class NucleusUpdater(
    config: UpdaterConfig,
) {
    /**
     * The config is validated and frozen here, once: a missing provider fails at construction,
     * and mutating the [UpdaterConfig] afterwards has no effect on this updater.
     */
    private val config: ResolvedUpdaterConfig = config.resolve()

    public val currentVersion: String get() = this.config.currentVersion

    /**
     * The update simulation this updater plays instead of contacting any feed
     * ([UpdaterConfig.simulation], or the one requested at launch with `nucleus.updater.simulate`),
     * `null` for real updates — handy to badge a test build's update UI.
     */
    public val simulation: UpdateSimulation? =
        this.config.simulation ?: UpdateSimulation.fromSettings()?.takeIf { launchSimulation ->
            (isUnpackaged || this.config.allowLaunchOverrides).also { honoured ->
                if (!honoured) {
                    logger.warning(
                        "Ignoring the launch-time update simulation ($launchSimulation): this installed app " +
                            "does not set UpdaterConfig.allowLaunchOverrides",
                    )
                }
            }
        }

    private val simulated: SimulatedUpdate? =
        simulation?.let { SimulatedUpdate(it, this.config.currentVersion) }?.also {
            logger.warning("Update simulation active, no feed will be contacted: $simulation")
        }

    private val redirect: FeedOverride.Applied? =
        if (simulated != null) {
            null
        } else {
            FeedOverride.resolve(
                raw = UpdaterSettings.get(UpdaterSettings.FEED_URL),
                packaged = !isUnpackaged,
                allowed = this.config.allowLaunchOverrides,
            )
        }

    /**
     * The feed this updater reads when it was redirected at launch (see
     * [UpdaterConfig.allowLaunchOverrides]), or `null` when it reads the configured provider.
     */
    public val feedOverride: String? get() = redirect?.raw

    /** The configured provider, unless the feed was redirected at launch. */
    private val provider: UpdateProvider = redirect?.provider ?: this.config.provider

    private var pendingUpdateVersion: String? = null

    /** Whether the next [consumeUpdateEvent] still reports [UpdateSimulation.justUpdatedFrom]. */
    private val simulatedEventPending = AtomicBoolean(simulation?.justUpdatedFrom != null)

    private val httpClient: HttpClient =
        config.httpClient
            ?: HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

    private val fetcher = FeedFetcher(httpClient) { provider.authHeaders() }

    /** Holds the last downloaded artifact, which the next differential download builds upon. */
    private val cache: UpdateCache by lazy {
        config.cacheDir?.let(::UpdateCache) ?: UpdateCache.default()
    }

    /**
     * Whether this app can update itself: it runs from a self-updatable package (NSIS, MSI, DMG,
     * macOS ZIP, AppImage, DEB, RPM, Developer ID PKG), updates are simulated ([simulation]), or it
     * runs unpackaged with its feed redirected at launch — where checking and downloading work and
     * installing is skipped.
     */
    public fun isUpdateSupported(): Boolean {
        if (simulated != null) return true
        val type = resolveExecutableType()
        if (type == ExecutableType.DEV) return redirect != null
        if (type in SELF_UPDATABLE_TYPES) return true
        // A PKG installs an ordinary .app in /Applications, exactly like a DMG, so a Developer ID
        // PKG can update itself from the ZIP/DMG artifacts of the same release. Only the Mac App
        // Store build cannot — and that one is sandboxed, which is what distinguishes the two.
        return type == ExecutableType.PKG && !ExecutableRuntime.isSandboxed()
    }

    public suspend fun checkForUpdates(): UpdateResult {
        simulated?.let { return it.check() }
        if (config.isDevMode() && redirect == null) return UpdateResult.NotAvailable
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

    /**
     * Downloads [info]'s artifact — differentially when the previous one is cached and the host
     * serves ranges — and verifies its SHA-512. The last progress report carries the staged file.
     */
    public fun downloadUpdate(info: UpdateInfo): Flow<DownloadProgress> = simulated?.download(info) ?: download(info)

    private fun download(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            pendingUpdateVersion = info.version
            val targetFile = info.currentFile
            // A fresh, owner-only (0700 on POSIX) staging directory: a predictable path in the
            // shared temp dir would be a pre-created-file/symlink hazard on multi-user systems.
            val stagingDir = Files.createTempDirectory("nucleus-update-").toFile()
            val tempFile = File(stagingDir, "${targetFile.fileName}.download")
            val finalFile = File(stagingDir, targetFile.fileName)

            try {
                val outcome =
                    downloadDifferentially(targetFile, tempFile)
                        ?: downloadFully(targetFile, tempFile)

                // Rename to final file (the staging directory is fresh, so the name is free)
                check(tempFile.renameTo(finalFile)) { "Could not stage $finalFile" }

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
                stagingDir.deleteRecursively()
                throw e
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                stagingDir.deleteRecursively()
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
        // Range requests are what make a download differential; a local feed has nothing to save.
        if (!config.differentialDownload || FeedFetcher.isLocal(targetFile.url)) return null
        return try {
            val resolver = DeltaResolver(httpClient, provider.authHeaders(), cache)
            val resolved =
                resolver.resolve(
                    target = targetFile,
                    blockMapUrl = provider.getBlockMapUrl(targetFile.url),
                    destination = tempFile,
                ) ?: return null

            val plannedBytes = DeltaPlan.downloadSize(resolved.download.operations)
            logger.info(
                "Differential update of ${targetFile.fileName}: fetching $plannedBytes " +
                    "of ${targetFile.size} bytes",
            )
            emit(DownloadProgress(0, plannedBytes, 0.0, isDifferential = true))

            val transferred =
                DifferentialDownloader(httpClient, provider.authHeaders())
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
        val totalBytes = targetFile.size
        var bytesDownloaded = 0L

        fetcher.open(targetFile.url).use { inputStream ->
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
                fetchBlockMap(provider.getBlockMapUrl(targetFile.url))
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
        fetcher.readBytesOrNull(url).also {
            if (it == null) logger.log(Level.FINE, "No block map at $url; the next update will be a full download")
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
            fetcher.readBytesOrNull("$url.asc")?.let(dest::writeBytes)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Deliberately swallowed: the detached signature is optional, so any failure here
            // (missing .asc, network error) just means the silent update falls back to the
            // standard password-prompting install path — not an error worth surfacing.
        }
    }

    /**
     * Installs [installerFile] and restarts the application on the new version.
     *
     * On a per-user Windows NSIS install of a JVM app (the plugin lays every one out for it) this
     * returns immediately: the new version is installed while the application keeps running, then
     * launched, and this process exits once the new version's first window is on screen — the
     * application never disappears while it updates. If that install fails, the application keeps
     * running on its current version.
     * Everywhere else the application exits right away, the installer runs, and the new version
     * is relaunched.
     */
    public fun installAndRestart(installerFile: File) {
        installAndRestart(installerFile, relaunchArguments = emptyList())
    }

    /**
     * [installAndRestart] that starts the new version with [relaunchArguments] — for an app that
     * runs one instance per document, the document this instance has open.
     *
     * The original command line is deliberately not replayed (Chromium does not either): it may
     * hold one-shot arguments — the autostart marker, which would make the new version believe it
     * was started at login, or a deep link that would fire a second time. Honoured on Windows;
     * macOS and Linux relaunch without arguments.
     */
    public fun installAndRestart(
        installerFile: File,
        relaunchArguments: List<String>,
    ) {
        if (skipsInstall(installerFile, restart = true)) return
        writeUpdateMarker()
        val platform = PlatformInfo.currentPlatform()
        val hotInstall = WindowsHotUpdate.eligibleInstall(installerFile, platform, resolveExecutableType())
        if (hotInstall != null) {
            WindowsHotUpdate.start(installerFile, hotInstall, relaunchArguments)
            return
        }
        PlatformInstaller.install(installerFile, platform, restart = true, relaunchArguments = relaunchArguments)
    }

    /**
     * The version installed on disk when it is not the one running — another instance of an app
     * without single instance installed an update — or `null`. Windows hot-update installs only;
     * elsewhere it stays `null`.
     *
     * Like Chromium's upgrade detector, this is how the other instances learn about an update:
     * locally, without downloading anything. Observe it to offer "Restart to update", then call
     * [restartToInstalledVersion]. Nothing restarts on its own — the instance may hold unsaved
     * work the user has not decided to give up.
     */
    public val pendingRestartVersion: StateFlow<String?> by lazy {
        val install = WindowsHotUpdate.currentInstall(PlatformInfo.currentPlatform(), resolveExecutableType())
        install?.let { InstalledVersionWatcher(it).apply { start() }.version }
            ?: MutableStateFlow<String?>(null).asStateFlow()
    }

    /**
     * Hands over to the version another instance already installed ([pendingRestartVersion]),
     * started with [relaunchArguments] (see [installAndRestart]): nothing is downloaded or
     * installed, and this process exits once the new version is on screen.
     *
     * Returns `false`, doing nothing, when no other version is installed.
     */
    public fun restartToInstalledVersion(relaunchArguments: List<String> = emptyList()): Boolean {
        val install =
            WindowsHotUpdate.currentInstall(PlatformInfo.currentPlatform(), resolveExecutableType())
                ?: return false
        val installed = WindowsHotUpdate.installedVersionDir(install) ?: return false
        writeUpdateMarker(installed.name)
        WindowsHotUpdate.startHandOff(install, relaunchArguments)
        return true
    }

    public fun installAndQuit(installerFile: File) {
        if (skipsInstall(installerFile, restart = false)) return
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
        if (simulatedEventPending.getAndSet(false)) return simulatedUpdateEvent()
        if (!UpdateMarker.exists()) return null
        val event = peekUpdateEvent()
        // Consumed either way: a marker for another version is stale and must not linger.
        UpdateMarker.delete()
        return event
    }

    /**
     * Returns `true` if the application was launched after an update.
     * Does **not** consume the event — call [consumeUpdateEvent] to clear it.
     */
    public fun wasJustUpdated(): Boolean = (simulatedEventPending.get() || peekUpdateEvent() != null)

    private fun simulatedUpdateEvent(): UpdateEvent? {
        val previous = simulation?.justUpdatedFrom ?: return null
        val level = Version.fromString(config.currentVersion).levelFrom(Version.fromString(previous))
        return UpdateEvent(previous, config.currentVersion, level)
    }

    /**
     * A simulation installs nothing, and neither does an unpackaged run: it has no installed app to
     * replace, so the installer would install a copy beside the IDE run and exit it. Both log what
     * would have been installed and return, leaving the app running.
     */
    private fun skipsInstall(
        installerFile: File,
        restart: Boolean,
    ): Boolean {
        val reason =
            when {
                simulated != null -> "updates are simulated"
                isUnpackaged -> "the app runs unpackaged, with no installed copy to replace"
                else -> return false
            }
        val action = if (restart) "installAndRestart" else "installAndQuit"
        logger.warning("$action skipped because $reason: would install ${installerFile.absolutePath}")
        return true
    }

    /**
     * The event recorded before the last install, if that install is the version now running. The
     * marker is written *before* the installer runs, so an install that failed — or was never
     * completed — leaves a marker naming a version this is not; reporting it would announce an
     * update that did not happen.
     */
    private fun peekUpdateEvent(): UpdateEvent? {
        val (previousVersion, newVersion) = UpdateMarker.read() ?: return null
        val installed = Version.fromString(newVersion)
        if (installed.compareTo(Version.fromString(config.currentVersion)) != 0) return null
        val level = installed.levelFrom(Version.fromString(previousVersion))
        return UpdateEvent(previousVersion, newVersion, level)
    }

    private fun writeUpdateMarker(targetVersion: String? = pendingUpdateVersion) {
        if (targetVersion == null) return
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
        val metadataUrl = provider.resolveMetadataUrl(config.channel, platform, httpClient)
        val metadata = YamlParser.parse(fetcher.readText(metadataUrl))
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

        // Another instance already installed it: nothing to download, only a restart
        // (pendingRestartVersion).
        if (isInstalledOnDisk(remoteVersion)) return UpdateResult.NotAvailable

        // On macOS, ignore the build-time system property so auto-detection
        // can prefer ZIP (silent install). Users can still force DMG via config.executableType.
        // An unpackaged run has no format of its own: it takes what an install on this OS would.
        val format =
            when {
                isUnpackaged -> null
                config.executableType != null -> config.executableType
                platform == Platform.MacOS -> null
                else -> System.getProperty("nucleus.executable.type")
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
                            url = provider.getDownloadUrl(file.url, metadata.version),
                            sha512 = file.sha512,
                            size = file.size,
                            blockMapSize = file.blockMapSize,
                            fileName = file.url,
                        )
                    },
                currentFile =
                    UpdateFile(
                        url = provider.getDownloadUrl(selectedFile.url, metadata.version),
                        sha512 = selectedFile.sha512,
                        size = selectedFile.size,
                        blockMapSize = selectedFile.blockMapSize,
                        fileName = selectedFile.url,
                    ),
            )

        val level = remoteVersion.levelFrom(currentVersion)

        return UpdateResult.Available(updateInfo, level)
    }

    private fun isInstalledOnDisk(version: Version): Boolean {
        val install = WindowsHotUpdate.currentInstall(PlatformInfo.currentPlatform(), resolveExecutableType())
        val installed = install?.let(WindowsHotUpdate::installedVersionDir) ?: return false
        return Version.fromString(installed.name) >= version
    }

    private fun resolveExecutableType(): ExecutableType {
        val explicit = config.executableType
        if (explicit != null) return ExecutableRuntime.parseType(explicit)
        return ExecutableRuntime.type()
    }

    /** Whether this process runs unpackaged (`./gradlew run`, an IDE), with no installed app to replace. */
    private val isUnpackaged: Boolean get() = resolveExecutableType() == ExecutableType.DEV

    public companion object {
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
