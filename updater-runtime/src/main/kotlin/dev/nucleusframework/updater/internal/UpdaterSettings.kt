package dev.nucleusframework.updater.internal

/**
 * The launch-time switches that let a developer test updates without publishing a release: a
 * system property first, then the matching environment variable (`nucleus.updater.feedUrl` →
 * `NUCLEUS_UPDATER_FEED_URL`), since an installed app is far easier to start with an environment
 * variable than with a JVM option — and a native image has no JVM options at all.
 */
internal object UpdaterSettings {
    /** Redirects the update feed to a local directory or a test server (see `NucleusUpdater`). */
    const val FEED_URL = "nucleus.updater.feedUrl"

    /** Plays an [dev.nucleusframework.updater.UpdateSimulation] instead of contacting any feed. */
    const val SIMULATE = "nucleus.updater.simulate"
    const val SIMULATE_VERSION = "nucleus.updater.simulate.version"
    const val SIMULATE_DURATION = "nucleus.updater.simulate.duration"
    const val SIMULATE_SIZE = "nucleus.updater.simulate.size"
    const val SIMULATE_DIFFERENTIAL = "nucleus.updater.simulate.differential"
    const val SIMULATE_JUST_UPDATED_FROM = "nucleus.updater.simulate.justUpdatedFrom"

    fun get(
        key: String,
        property: (String) -> String? = System::getProperty,
        environment: (String) -> String? = System::getenv,
    ): String? =
        property(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: environment(environmentName(key))?.trim()?.takeIf { it.isNotEmpty() }

    /** `nucleus.updater.simulate.justUpdatedFrom` → `NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM`. */
    fun environmentName(key: String): String =
        key
            .replace(CAMEL_HUMP, "$1_$2")
            .replace('.', '_')
            .uppercase()

    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")
}
