package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.updater.provider.UpdateProvider
import java.io.File
import java.net.http.HttpClient

public class UpdaterConfig {
    public var currentVersion: String =
        NucleusApp.version
            ?: System.getProperty("jpackage.app-version")
            ?: ExecutableRuntime.markerVersion()
            ?: DEV_VERSION
    public lateinit var provider: UpdateProvider
    public var channel: String = "latest"
    public var allowDowngrade: Boolean = false
    public var allowPrerelease: Boolean = false
    public var executableType: String? = null

    /**
     * Custom HTTP client used for all update checks and downloads.
     * Defaults to a standard client with redirect following enabled.
     * Override with [dev.nucleusframework.nativehttp.NativeHttpClient.create] to
     * trust enterprise or user-installed certificates.
     */
    public var httpClient: HttpClient? = null

    /**
     * Whether an update may be assembled from the copy already on this machine, downloading only the
     * blocks that changed (typically a few percent of the artifact) with HTTP range requests.
     *
     * Requires the release to publish electron-builder block maps next to the artifacts — the Nucleus
     * Gradle plugin does for NSIS installers, AppImages, macOS ZIPs and DMGs. Anything missing or
     * inconsistent silently degrades to a full download, so leaving this on is always safe.
     *
     * AppImages update differentially from the first update, since the running executable is itself
     * the previous artifact. Other formats need the previous artifact in the update cache, which the
     * updater fills on every download, so they benefit from the second update on.
     */
    public var differentialDownload: Boolean = true

    /**
     * Directory holding the previously downloaded artifact and its block map, used as the basis for
     * the next differential download. Defaults to `%LOCALAPPDATA%/nucleus/updates/<appId>` on
     * Windows and `~/.cache/nucleus/updates/<appId>` elsewhere.
     */
    public var cacheDir: File? = null

    /**
     * Validates the config and freezes it into an immutable snapshot, so a [NucleusUpdater]
     * never observes post-construction mutation and a missing [provider] fails at
     * construction instead of at the first network call.
     */
    internal fun resolve(): ResolvedUpdaterConfig {
        require(::provider.isInitialized) {
            "UpdaterConfig.provider must be set, e.g. NucleusUpdater { provider = GitHubProvider(\"owner/repo\") }"
        }
        return ResolvedUpdaterConfig(
            currentVersion = currentVersion,
            provider = provider,
            channel = channel,
            allowDowngrade = allowDowngrade,
            allowPrerelease = allowPrerelease,
            executableType = executableType,
            httpClient = httpClient,
            differentialDownload = differentialDownload,
            cacheDir = cacheDir,
        )
    }

    public companion object {
        public const val DEV_VERSION: String = "0.0.0-dev"
    }
}

/** The immutable snapshot of an [UpdaterConfig], taken once when a [NucleusUpdater] is constructed. */
internal data class ResolvedUpdaterConfig(
    val currentVersion: String,
    val provider: UpdateProvider,
    val channel: String,
    val allowDowngrade: Boolean,
    val allowPrerelease: Boolean,
    val executableType: String?,
    val httpClient: HttpClient?,
    val differentialDownload: Boolean,
    val cacheDir: File?,
) {
    fun resolvedAllowPrerelease(): Boolean = allowPrerelease || currentVersion.contains("-")

    fun isDevMode(): Boolean = currentVersion == UpdaterConfig.DEV_VERSION
}

public fun NucleusUpdater(block: UpdaterConfig.() -> Unit): NucleusUpdater {
    val config = UpdaterConfig().apply(block)
    return NucleusUpdater(config)
}
