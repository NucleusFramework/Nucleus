package dev.nucleusframework.lab.probes.updater.engine

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.UpdateSimulation
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.GitHubProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Where the updater reads its feed from. */
sealed interface FeedSource {
    /** Through `nucleus.updater.feedUrl`, the documented redirect: a URL or a directory. */
    data class Redirect(
        val target: String,
    ) : FeedSource

    data class GitHub(
        val owner: String,
        val repo: String,
    ) : FeedSource

    data class Simulated(
        val simulation: UpdateSimulation,
    ) : FeedSource
}

data class UpdaterSetup(
    val source: FeedSource,
    /** `null` keeps the version the runtime resolves (NucleusApp, then `0.0.0-dev`). */
    val currentVersion: String?,
    val channel: String,
    val allowPrerelease: Boolean,
    val differential: Boolean,
)

/** What a freshly built updater reports about itself before any request. */
data class UpdaterFacts(
    val currentVersion: String,
    val updateSupported: Boolean,
    val feedOverride: String?,
    val simulation: String?,
    val wasJustUpdated: Boolean,
)

/** Port over `updater-runtime`. */
interface UpdaterGateway {
    /** Replaces the current updater; returns what it reports. */
    fun configure(setup: UpdaterSetup): UpdaterFacts

    suspend fun check(): UpdateResult

    fun download(info: UpdateInfo): Flow<DownloadProgress>

    /**
     * The "just updated" marker found when the Lab started, consumed once per process with the
     * runtime's own version — what an app shows in its "what's new" prompt.
     */
    val launchEvent: UpdateEvent?

    /** Consumes the current updater's event (a simulated `justUpdatedFrom`, or a marker). */
    fun consumeUpdateEvent(): UpdateEvent?

    val pendingRestartVersion: StateFlow<String?>

    /** Hands over to the installer. Never call it in a dev run: there is nothing to replace. */
    fun installAndRestart(file: File)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusUpdaterGateway : UpdaterGateway {
    private val propertyLock = Any()

    /** Built with the runtime's own version, which the marker is compared against. */
    private val initial: NucleusUpdater = build(defaultSetup)

    @Volatile
    private var updater: NucleusUpdater = initial

    override val launchEvent: UpdateEvent? by lazy { initial.consumeUpdateEvent() }

    override fun configure(setup: UpdaterSetup): UpdaterFacts {
        updater = build(setup)
        return facts()
    }

    override suspend fun check(): UpdateResult = updater.checkForUpdates()

    override fun download(info: UpdateInfo): Flow<DownloadProgress> = updater.downloadUpdate(info)

    override fun consumeUpdateEvent(): UpdateEvent? = updater.consumeUpdateEvent()

    override val pendingRestartVersion: StateFlow<String?> get() = updater.pendingRestartVersion

    override fun installAndRestart(file: File) {
        check(!ExecutableRuntime.isDev()) { "dev run: no installed copy to replace" }
        updater.installAndRestart(file)
    }

    private fun facts(): UpdaterFacts =
        UpdaterFacts(
            currentVersion = updater.currentVersion,
            updateSupported = updater.isUpdateSupported(),
            feedOverride = updater.feedOverride,
            simulation = updater.simulation?.toString(),
            wasJustUpdated = updater.wasJustUpdated(),
        )

    private fun build(setup: UpdaterSetup): NucleusUpdater =
        withFeedProperty((setup.source as? FeedSource.Redirect)?.target) {
            NucleusUpdater {
                setup.currentVersion?.takeIf { it.isNotBlank() }?.let { currentVersion = it }
                channel = setup.channel
                allowPrerelease = setup.allowPrerelease
                differentialDownload = setup.differential
                // A test bench: honour the launch-time switches in installed builds too.
                allowLaunchOverrides = true
                provider =
                    when (val source = setup.source) {
                        is FeedSource.GitHub -> GitHubProvider(source.owner, source.repo)
                        // Replaced by the redirect; a provider must still be set.
                        is FeedSource.Redirect -> GenericProvider("http://127.0.0.1:1")
                        is FeedSource.Simulated -> GenericProvider("http://127.0.0.1:1")
                    }
                simulation = (setup.source as? FeedSource.Simulated)?.simulation
            }
        }

    /**
     * The redirect is read once, while the updater is built: set the property for exactly that
     * long, so a GitHub updater built later is not redirected by a leftover.
     */
    private fun <T> withFeedProperty(
        target: String?,
        block: () -> T,
    ): T =
        synchronized(propertyLock) {
            val previous = System.getProperty(FEED_URL)
            if (target == null) System.clearProperty(FEED_URL) else System.setProperty(FEED_URL, target)
            try {
                block()
            } finally {
                if (previous == null) System.clearProperty(FEED_URL) else System.setProperty(FEED_URL, previous)
            }
        }

    companion object {
        const val FEED_URL = "nucleus.updater.feedUrl"

        val defaultSetup =
            UpdaterSetup(
                source = FeedSource.Simulated(UpdateSimulation(justUpdatedFrom = null, checkDuration = 1.seconds)),
                currentVersion = null,
                channel = "latest",
                allowPrerelease = false,
                differential = true,
            )
    }
}
