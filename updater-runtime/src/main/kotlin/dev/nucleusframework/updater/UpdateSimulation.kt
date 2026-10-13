package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.UpdaterSettings
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A scripted update that [NucleusUpdater] plays instead of contacting any feed, to build and review
 * an app's whole update UI — "update available", download progress, failures, "just updated" — from
 * `./gradlew run`, with nothing published, packaged or installed.
 *
 * While a simulation is active every public entry point behaves as it would for a real update,
 * except that nothing leaves the machine and nothing is installed:
 * - [NucleusUpdater.isUpdateSupported] is `true`, even from an IDE run;
 * - [NucleusUpdater.checkForUpdates] answers after [checkDuration] according to [scenario];
 * - [NucleusUpdater.downloadUpdate] reports [downloadSize] bytes of progress over
 *   [downloadDuration], then hands over a placeholder file;
 * - [NucleusUpdater.installAndRestart] and [NucleusUpdater.installAndQuit] log what they would
 *   install and return, so the app keeps running;
 * - [NucleusUpdater.consumeUpdateEvent] reports an update from [justUpdatedFrom] once, when set.
 *
 * Set it in code with [UpdaterConfig.simulation], or at launch without touching the code:
 * `-Dnucleus.updater.simulate=update` (or the `NUCLEUS_UPDATER_SIMULATE` environment variable,
 * which also reaches an installed app), refined by `nucleus.updater.simulate.version`,
 * `.duration` (seconds), `.size` (bytes), `.differential` and `.justUpdatedFrom`. From Gradle,
 * `./gradlew run -Pnucleus.updater.simulate=update` forwards them to the app. An unpackaged run
 * always honours a launch-time simulation; an installed app only with
 * [UpdaterConfig.allowLaunchOverrides], since it would otherwise silence the app's real updates.
 */
public class UpdateSimulation(
    /** What [NucleusUpdater.checkForUpdates] and [NucleusUpdater.downloadUpdate] will do. */
    public val scenario: Scenario = Scenario.UPDATE_AVAILABLE,
    /** The version offered; `null` offers the next minor version of the running one. */
    public val version: String? = null,
    /** How long the simulated update check takes. */
    public val checkDuration: Duration = DEFAULT_CHECK_DURATION,
    /** How long the simulated download takes, from first to last progress report. */
    public val downloadDuration: Duration = DEFAULT_DOWNLOAD_DURATION,
    /** The size of the offered artifact, in bytes. */
    public val downloadSize: Long = DEFAULT_DOWNLOAD_SIZE,
    /** Whether the download reports itself as differential, transferring a fraction of [downloadSize]. */
    public val isDifferential: Boolean = false,
    /** When set, the next [NucleusUpdater.consumeUpdateEvent] reports an update from this version. */
    public val justUpdatedFrom: String? = null,
) {
    init {
        require(downloadSize > 0) { "downloadSize must be positive, got $downloadSize" }
        require(!checkDuration.isNegative() && !downloadDuration.isNegative()) { "durations must not be negative" }
    }

    /** The outcome a simulation plays. */
    public enum class Scenario(
        internal val id: String,
    ) {
        /** An update is available and downloads successfully. */
        UPDATE_AVAILABLE("update"),

        /** The running version is the latest one. */
        UP_TO_DATE("up-to-date"),

        /** The update check fails, as it does offline. */
        CHECK_ERROR("check-error"),

        /** The download fails part-way, as a dropped connection does. */
        DOWNLOAD_ERROR("download-error"),

        /** The whole artifact downloads, then fails its SHA-512 verification. */
        CHECKSUM_ERROR("checksum-error"),
    }

    override fun toString(): String =
        "UpdateSimulation(scenario=$scenario, version=${version ?: "next minor"}, " +
            "download=$downloadSize bytes in $downloadDuration, differential=$isDifferential, " +
            "justUpdatedFrom=$justUpdatedFrom)"

    /** Launch-time configuration of a simulation. */
    public companion object {
        private val DEFAULT_CHECK_DURATION = 800.milliseconds
        private val DEFAULT_DOWNLOAD_DURATION = 6.seconds
        private const val DEFAULT_DOWNLOAD_SIZE = 84L * 1024 * 1024

        private val logger = Logger.getLogger(UpdateSimulation::class.java.name)

        /**
         * The simulation requested at launch through `nucleus.updater.simulate*` (system properties
         * or environment variables), or `null` when none is. `nucleus.updater.simulate` takes a
         * [Scenario] id (`update`, `up-to-date`, `check-error`, `download-error`,
         * `checksum-error`), `true` for `update`, or a version to offer; `justUpdatedFrom` alone
         * simulates only the post-update launch.
         */
        public fun fromSettings(): UpdateSimulation? = fromSettings(UpdaterSettings::get)

        internal fun fromSettings(setting: (String) -> String?): UpdateSimulation? {
            val raw = setting(UpdaterSettings.SIMULATE)
            val justUpdatedFrom = setting(UpdaterSettings.SIMULATE_JUST_UPDATED_FROM)
            if (raw == null && justUpdatedFrom == null) return null
            if (raw.equals("false", ignoreCase = true) || raw == "0") return null

            val byId = Scenario.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
            val isFlag = raw == null || raw.equals("true", ignoreCase = true) || raw == "1"
            val looksLikeVersion = raw != null && raw.first().isDigit()
            if (byId == null && !isFlag && !looksLikeVersion) {
                logger.warning(
                    "Ignoring ${UpdaterSettings.SIMULATE}=$raw: expected true, a version, or one of " +
                        Scenario.entries.joinToString { it.id },
                )
                return null
            }
            return UpdateSimulation(
                // justUpdatedFrom alone: only the post-update launch is simulated, and a check finds nothing.
                scenario = byId ?: if (raw == null) Scenario.UP_TO_DATE else Scenario.UPDATE_AVAILABLE,
                version = setting(UpdaterSettings.SIMULATE_VERSION) ?: raw.takeIf { looksLikeVersion },
                downloadDuration =
                    setting(UpdaterSettings.SIMULATE_DURATION)?.toDoubleOrNull()?.takeIf { it >= 0 }?.seconds
                        ?: DEFAULT_DOWNLOAD_DURATION,
                downloadSize =
                    setting(UpdaterSettings.SIMULATE_SIZE)?.toLongOrNull()?.takeIf { it > 0 }
                        ?: DEFAULT_DOWNLOAD_SIZE,
                isDifferential = setting(UpdaterSettings.SIMULATE_DIFFERENTIAL).toBoolean(),
                justUpdatedFrom = justUpdatedFrom,
            )
        }
    }
}
