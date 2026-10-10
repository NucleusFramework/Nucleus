package dev.nucleusframework.lab.probes.lifecycle.smappservice

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class AppServiceViewModel(
    private val gateway: AppServiceGateway,
    timeline: Timeline,
) : MviViewModel<AppServiceState, AppServiceIntent, AppServiceEvent, Nothing>(
        AppServiceState(),
        AppServiceReducer,
        timeline,
        AppServiceProbe.ID,
    ) {
    init {
        // Approval happens in System Settings and the agent runs on its own: keep re-reading.
        poll(POLL_MS, read = ::read, toEvent = { it })
    }

    override suspend fun handle(intent: AppServiceIntent) {
        when (intent) {
            is AppServiceIntent.Register -> {
                val result = io { gateway.register(intent.service) }
                complete(intent.service, "register", result.exceptionOrNull()?.let { it.message ?: it.summary })
            }
            is AppServiceIntent.Unregister -> {
                val stamped = gateway.unregister(intent.service)
                complete(intent.service, "unregister", stamped.value, completedOn = stamped.thread)
            }
            AppServiceIntent.OpenLoginItems ->
                dispatch(AppServiceEvent.LoginItemsOpened(io { gateway.openLoginItems() }))
            AppServiceIntent.Refresh -> dispatch(io { read() })
        }
    }

    private suspend fun complete(
        service: LabService,
        action: String,
        error: String?,
        completedOn: String? = null,
    ) {
        val after = io { runCatching { gateway.status(service) }.getOrNull() }
        val call =
            CallRecord(
                epochMillis = System.currentTimeMillis(),
                call = "$action(${service.label})",
                outcome = if (error == null) CallOutcome.Ok else CallOutcome(false, error),
                returned = "$after" + completedOn?.let { " (completed on $it)" }.orEmpty(),
            )
        dispatch(
            AppServiceEvent.Completed(service, call, after),
            if (error == null) Severity.Info else Severity.Error,
        )
    }

    /** Blocking: every status is a native call. */
    private fun read(): AppServiceEvent.Read {
        val available = gateway.isAvailable
        return AppServiceEvent.Read(
            available = available,
            statuses = if (available) LabService.entries.associateWith { gateway.status(it) } else emptyMap(),
            heartbeats = gateway.heartbeats(),
        )
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}
