package dev.nucleusframework.lab.probes.updater.engine

import dev.nucleusframework.lab.core.mvi.Reducer

object UpdaterReducer : Reducer<UpdaterState, UpdaterEvent> {
    override fun reduce(
        state: UpdaterState,
        event: UpdaterEvent,
    ): UpdaterState =
        when (event) {
            is UpdaterEvent.Edited -> state.copy(form = event.form)
            // A new updater invalidates what the old one found.
            is UpdaterEvent.Configured ->
                state.copy(
                    facts = event.facts,
                    configError = null,
                    outcome = null,
                    download = null,
                    install = null,
                )
            is UpdaterEvent.ConfigFailed -> state.copy(configError = event.reason)
            is UpdaterEvent.LaunchEventRead -> state.copy(launchEvent = event.description)
            is UpdaterEvent.EventConsumed -> state.copy(consumedEvent = event.description)
            UpdaterEvent.CheckStarted -> state.copy(checking = true, outcome = null, download = null, install = null)
            is UpdaterEvent.Checked -> state.copy(checking = false, outcome = event.outcome)
            is UpdaterEvent.DownloadStarted ->
                state.copy(
                    download = DownloadState(startedAt = event.at),
                    install = null,
                )
            is UpdaterEvent.Progressed ->
                state.copy(
                    download =
                        state.download?.copy(
                            bytes = event.bytes,
                            total = event.total,
                            percent = event.percent,
                            differential = event.differential,
                        ),
                )
            is UpdaterEvent.Downloaded ->
                state.copy(
                    download = state.download?.copy(file = event.file, finishedAt = event.at, percent = 100.0),
                )
            is UpdaterEvent.DownloadFailed ->
                state.copy(
                    download = state.download?.copy(error = event.reason, finishedAt = event.at),
                )
            is UpdaterEvent.Armed -> state.copy(armInstall = event.armed)
            is UpdaterEvent.InstallReported -> state.copy(install = event.description, armInstall = false)
            is UpdaterEvent.PendingRestart -> state.copy(pendingRestart = event.version)
        }
}
