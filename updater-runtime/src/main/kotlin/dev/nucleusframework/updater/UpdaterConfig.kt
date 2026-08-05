package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.updater.provider.UpdateProvider
import java.io.File
import java.net.http.HttpClient

class UpdaterConfig {
    var currentVersion: String =
        NucleusApp.version
            ?: System.getProperty("jpackage.app-version")
            ?: ExecutableRuntime.markerVersion()
            ?: DEV_VERSION
    lateinit var provider: UpdateProvider
    var channel: String = "latest"
    var allowDowngrade: Boolean = false
    var allowPrerelease: Boolean = false
    var executableType: String? = null

    /**
     * Custom HTTP client used for all update checks and downloads.
     * Defaults to a standard client with redirect following enabled.
     * Override with [dev.nucleusframework.nativehttp.NativeHttpClient.create] to
     * trust enterprise or user-installed certificates.
     */
    var httpClient: HttpClient? = null

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
    var differentialDownload: Boolean = true

    /**
     * Directory holding the previously downloaded artifact and its block map, used as the basis for
     * the next differential download. Defaults to `%LOCALAPPDATA%/nucleus/updates/<appId>` on
     * Windows and `~/.cache/nucleus/updates/<appId>` elsewhere.
     */
    var cacheDir: File? = null

    internal fun resolvedAllowPrerelease(): Boolean = allowPrerelease || currentVersion.contains("-")

    internal fun isDevMode(): Boolean = currentVersion == DEV_VERSION

    companion object {
        const val DEV_VERSION = "0.0.0-dev"
    }
}

fun NucleusUpdater(block: UpdaterConfig.() -> Unit): NucleusUpdater {
    val config = UpdaterConfig().apply(block)
    return NucleusUpdater(config)
}
