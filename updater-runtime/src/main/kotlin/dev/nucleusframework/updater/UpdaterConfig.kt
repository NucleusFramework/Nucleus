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
     * Whether an **installed** app honours the launch-time test switches, set as system properties
     * or, easier for an installed app, as environment variables:
     *
     * - the feed redirect `nucleus.updater.feedUrl` / `NUCLEUS_UPDATER_FEED_URL`, which replaces
     *   [provider] with a local directory
     *   ([dev.nucleusframework.updater.provider.LocalFileProvider]) or a test server
     *   ([dev.nucleusframework.updater.provider.GenericProvider]) — a local path or `file:` URL,
     *   `https`, or plain `http` to a loopback host;
     * - the simulation `nucleus.updater.simulate*` / `NUCLEUS_UPDATER_SIMULATE*` (see
     *   [UpdateSimulation.fromSettings]).
     *
     * ```
     * NUCLEUS_UPDATER_FEED_URL=C:\work\app\build\compose\binaries\main\nsis   MyApp.exe
     * NUCLEUS_UPDATER_FEED_URL=http://127.0.0.1:8080                         MyApp.exe
     * ```
     *
     * The redirect is how the next version is tested on a machine running the current one with
     * nothing published: the installed app checks, downloads, verifies and installs it through the
     * production path.
     *
     * An unpackaged run (`./gradlew run`, an IDE) always honours both — it has no production feed
     * to protect, and like electron-updater's `dev-app-update.yml` this is what makes the check and
     * the download testable there (installing is skipped: there is no installed app to replace). An
     * installed app honours them only when this is `true`, since whoever sets the variable would
     * otherwise choose what the app installs, or silence its real updates. Leave it `false` in
     * release builds unless the switches are part of how you test them; ignored switches are logged.
     */
    public var allowLaunchOverrides: Boolean = false

    /**
     * Plays a scripted update instead of contacting [provider], to build and review the update UI
     * without publishing anything — see [UpdateSimulation]. When `null` (the default), the
     * simulation requested at launch with `nucleus.updater.simulate` applies, if any (see
     * [allowLaunchOverrides]).
     */
    public var simulation: UpdateSimulation? = null

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
            allowLaunchOverrides = allowLaunchOverrides,
            simulation = simulation,
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
    val allowLaunchOverrides: Boolean = false,
    val simulation: UpdateSimulation? = null,
) {
    fun resolvedAllowPrerelease(): Boolean = allowPrerelease || currentVersion.contains("-")

    fun isDevMode(): Boolean = currentVersion == UpdaterConfig.DEV_VERSION
}

public fun NucleusUpdater(block: UpdaterConfig.() -> Unit): NucleusUpdater {
    val config = UpdaterConfig().apply(block)
    return NucleusUpdater(config)
}
