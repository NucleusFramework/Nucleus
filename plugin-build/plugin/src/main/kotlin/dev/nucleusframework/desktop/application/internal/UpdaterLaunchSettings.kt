package dev.nucleusframework.desktop.application.internal

import org.gradle.api.provider.ProviderFactory

/**
 * The updater's launch-time test switches (`nucleus.updater.feedUrl`, `nucleus.updater.simulate*`,
 * read by `updater-runtime`) given to Gradle as `-Pnucleus.updater.…=…`, which `run` forwards to the
 * app as system properties and `runDistributable` as environment variables.
 */
internal object UpdaterLaunchSettings {
    private const val PREFIX = "nucleus.updater."

    /** Settings of `serveUpdateFeed` itself, not the app's. */
    private const val SERVE_PREFIX = "nucleus.updater.serve."

    fun systemProperties(providers: ProviderFactory): Map<String, String> =
        providers
            .gradlePropertiesPrefixedBy(PREFIX)
            .get()
            .filterKeys { !it.startsWith(SERVE_PREFIX) }

    fun environment(providers: ProviderFactory): Map<String, String> =
        systemProperties(providers).mapKeys { (key, _) -> environmentName(key) }

    /** `nucleus.updater.simulate.justUpdatedFrom` → `NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM`, as the runtime reads it. */
    fun environmentName(key: String): String =
        key
            .replace(CAMEL_HUMP, "$1_$2")
            .replace('.', '_')
            .uppercase()

    /** `serveUpdateFeed`'s `-Pnucleus.updater.serve.<name>`. */
    fun serveSetting(
        providers: ProviderFactory,
        name: String,
    ): String? = providers.gradleProperty(SERVE_PREFIX + name).orNull?.trim()?.takeIf { it.isNotEmpty() }

    /** `2000000`, `512k`, `2m` → bytes. */
    fun parseByteRate(value: String): Long? {
        val trimmed = value.trim().lowercase()
        val multiplier =
            when (trimmed.lastOrNull()) {
                'k' -> KIB
                'm' -> MIB
                else -> 1L
            }
        val digits = if (multiplier == 1L) trimmed else trimmed.dropLast(1)
        return digits.toDoubleOrNull()?.let { (it * multiplier).toLong() }?.takeIf { it > 0 }
    }

    private const val KIB = 1024L
    private const val MIB = 1024L * 1024
    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")
}
