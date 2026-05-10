package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.updater.provider.UpdateProvider
import java.net.http.HttpClient

class UpdaterConfig {
    var currentVersion: String =
        System.getProperty("jpackage.app-version")
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
     * Override with [dev.nucleusframework.nucleus.nativehttp.NativeHttpClient.create] to
     * trust enterprise or user-installed certificates.
     */
    var httpClient: HttpClient? = null

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
