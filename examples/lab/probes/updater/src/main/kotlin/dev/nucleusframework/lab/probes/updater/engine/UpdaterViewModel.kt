package dev.nucleusframework.lab.probes.updater.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.commands.LabParams
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.updater.feed.FeedServerHost
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.UpdateSimulation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class UpdaterViewModel(
    private val gateway: UpdaterGateway,
    private val feed: FeedServerHost,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<UpdaterState, UpdaterIntent, UpdaterEvent, Nothing>(
        UpdaterState(),
        UpdaterReducer,
        timeline,
        UpdaterProbe.ID,
    ) {
    private var pendingRestartWatch: Job? = null

    init {
        launch {
            dispatch(UpdaterEvent.LaunchEventRead(io { gateway.launchEvent }.describe("no update event at launch")))
            apply()
        }
        // nucleus-lab://probe/updater.engine?source=simulation&scenario=download_error&check=true
        onParams(commands) { params ->
            dispatch(UpdaterEvent.Edited(state.value.form.withParams(params)))
            apply()
            if (params.bool("check") == true) check()
        }
    }

    override suspend fun handle(intent: UpdaterIntent) {
        when (intent) {
            is UpdaterIntent.Edit -> reduceSilently(UpdaterEvent.Edited(intent.form))
            UpdaterIntent.Apply -> apply()
            UpdaterIntent.Check -> check()
            UpdaterIntent.Download -> download()
            is UpdaterIntent.ArmInstall -> dispatch(UpdaterEvent.Armed(intent.armed))
            UpdaterIntent.Install -> install()
            UpdaterIntent.ConsumeEvent ->
                dispatch(UpdaterEvent.EventConsumed(io { gateway.consumeUpdateEvent() }.describe("nothing to consume")))
        }
    }

    private suspend fun apply() {
        val form = state.value.form
        runCatching { io { gateway.configure(form.toSetup()) } }
            .onSuccess {
                dispatch(UpdaterEvent.Configured(it))
                // Only the current updater's install watcher matters.
                pendingRestartWatch?.cancel()
                pendingRestartWatch =
                    viewModelScope.launch {
                        gateway.pendingRestartVersion.collect { version ->
                            dispatch(UpdaterEvent.PendingRestart(version))
                        }
                    }
            }.onFailure {
                dispatch(UpdaterEvent.ConfigFailed(it.summary), Severity.Error)
            }
    }

    private suspend fun check() {
        dispatch(UpdaterEvent.CheckStarted)
        val outcome =
            when (val result = gateway.check()) {
                is UpdateResult.Available -> CheckOutcome.Available(result.info, result.level.name)
                UpdateResult.NotAvailable -> CheckOutcome.NotAvailable
                is UpdateResult.Error ->
                    CheckOutcome.Failed(
                        result.exception::class.simpleName.orEmpty(),
                        result.exception.message.orEmpty(),
                        result.exception.cause?.toString(),
                    )
            }
        dispatch(UpdaterEvent.Checked(outcome), if (outcome is CheckOutcome.Failed) Severity.Error else Severity.Info)
    }

    private suspend fun download() {
        val info = (state.value.outcome as? CheckOutcome.Available)?.info ?: return
        dispatch(UpdaterEvent.DownloadStarted(System.currentTimeMillis()))
        var lastLoggedDecile = -1
        runCatching {
            gateway.download(info).collect { progress ->
                val event =
                    UpdaterEvent.Progressed(
                        progress.bytesDownloaded,
                        progress.totalBytes,
                        progress.percent,
                        progress.isDifferential,
                    )
                // Every tenth of the way in the timeline; every step in the state.
                val decile = (progress.percent / 10).toInt()
                if (decile != lastLoggedDecile) dispatch(event) else reduceSilently(event)
                lastLoggedDecile = decile
                progress.file?.let { dispatch(UpdaterEvent.Downloaded(it.absolutePath, System.currentTimeMillis())) }
            }
        }.onFailure {
            dispatch(
                UpdaterEvent.DownloadFailed(it.summary, System.currentTimeMillis()),
                Severity.Error,
            )
        }
    }

    private suspend fun install() {
        val current = state.value
        val file = current.download?.file
        val refusal =
            when {
                file == null -> "nothing downloaded"
                ExecutableRuntime.isDev() ->
                    "refused: dev run (${ExecutableRuntime.type()}), there is no installed copy to replace"
                current.facts?.simulation != null -> "refused: the update is simulated, the file is not an installer"
                !current.armInstall -> "refused: arm the install first — it really replaces this app"
                else -> null
            }
        if (refusal != null) {
            dispatch(UpdaterEvent.InstallReported(refusal), Severity.Warning)
            return
        }
        dispatch(UpdaterEvent.InstallReported("installAndRestart(${File(file!!).name}) — the app should close now"))
        runCatching { io { gateway.installAndRestart(File(file)) } }
            .onFailure { dispatch(UpdaterEvent.InstallReported("install failed: ${it.summary}"), Severity.Error) }
    }

    private fun SetupForm.toSetup(): UpdaterSetup {
        val source =
            when (kind) {
                SourceKind.Simulation ->
                    FeedSource.Simulated(
                        UpdateSimulation(
                            scenario = scenario,
                            isDifferential = differential,
                            justUpdatedFrom = justUpdatedFrom.ifBlank { null },
                        ),
                    )
                SourceKind.FeedServer ->
                    FeedSource.Redirect(
                        checkNotNull(feed.status.value.baseUrl) {
                            "start the fault-injecting feed " +
                                "first"
                        },
                    )
                SourceKind.Directory ->
                    FeedSource.Redirect(
                        directory.also {
                            require(it.isNotBlank()) { "pick a directory holding a latest*.yml" }
                        },
                    )
                SourceKind.GitHub -> gitHubRepo.split('/').let { FeedSource.GitHub(it.first(), it.getOrElse(1) { "" }) }
            }
        return UpdaterSetup(source, currentVersion.ifBlank { null }, channel, allowPrerelease, differential)
    }
}

private fun UpdateEvent?.describe(none: String): String =
    this?.let { "$previousVersion → $newVersion ($updateLevel)" } ?: none

/** `?source=simulation&scenario=download_error&version=1.0.0&justUpdatedFrom=0.9.0`: applies what parses. */
private fun SetupForm.withParams(params: LabParams): SetupForm =
    copy(
        kind = params.enum<SourceKind>("source") ?: kind,
        scenario = params.enum<UpdateSimulation.Scenario>("scenario") ?: scenario,
        currentVersion = params["version"] ?: currentVersion,
        justUpdatedFrom = params["justUpdatedFrom"] ?: justUpdatedFrom,
    )
