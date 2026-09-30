package dev.nucleusframework.updater.internal

import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.LocalFileProvider
import dev.nucleusframework.updater.provider.UpdateProvider
import java.io.File
import java.net.URI
import java.util.logging.Logger

/**
 * Resolves the launch-time feed redirect ([UpdaterSettings.FEED_URL]) into the provider that
 * replaces the configured one, or `null` when there is none or it must not be honoured.
 */
internal object FeedOverride {
    private val logger: Logger = Logger.getLogger(FeedOverride::class.java.name)

    /** A redirect that was honoured: [provider] replaces the configured one. */
    class Applied(
        val raw: String,
        val provider: UpdateProvider,
    )

    /**
     * @param packaged whether the app runs from an installed package, where the redirect needs
     *   [allowed]; an unpackaged run always honours it.
     */
    fun resolve(
        raw: String?,
        packaged: Boolean,
        allowed: Boolean,
    ): Applied? {
        if (raw.isNullOrBlank()) return null
        val source = "${UpdaterSettings.FEED_URL} / ${UpdaterSettings.environmentName(UpdaterSettings.FEED_URL)}"
        if (packaged && !allowed) {
            logger.warning(
                "Ignoring the update feed redirect $source=$raw: this installed app does not set " +
                    "UpdaterConfig.allowLaunchOverrides",
            )
            return null
        }
        val provider =
            try {
                providerFor(raw)
            } catch (e: IllegalArgumentException) {
                logger.warning("Ignoring the update feed redirect $source=$raw: ${e.message}")
                return null
            }
        logger.warning(
            "Update feed redirected by $source to $raw — updates no longer come from the configured provider",
        )
        return Applied(raw, provider)
    }

    fun providerFor(raw: String): UpdateProvider {
        val scheme =
            SCHEME
                .find(raw)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> GenericProvider(raw)
            scheme == "file" -> LocalFileProvider(File(URI.create(raw)))
            // A drive letter (`C:\…`) parses as a one-letter scheme.
            scheme == null || scheme.length == 1 -> LocalFileProvider(File(raw))
            else -> throw IllegalArgumentException(
                "unsupported scheme '$scheme' (use a path, file:, https: or loopback http:)",
            )
        }
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
}
