package dev.nucleusframework.lab.probes.lifecycle.autolaunch

import androidx.lifecycle.ViewModel
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.lab.core.environment.EnvironmentProvider
import dev.nucleusframework.lab.core.format.summary
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
class AutoLaunchViewModel(
    private val gateway: AutoLaunchGateway,
    environment: EnvironmentProvider,
    timeline: Timeline,
) : MviViewModel<AutoLaunchProbeState, AutoLaunchIntent, AutoLaunchEvent, Nothing>(
        AutoLaunchProbeState(),
        AutoLaunchReducer(gateway.autostartArgument, environment.snapshot.value.executableType),
        timeline,
        AutoLaunchProbe.ID,
    ) {
    init {
        // The user can flip it in System Settings / Task Manager at any time: keep looking.
        poll(
            POLL_MS,
            read = ::read,
            toEvent = { it },
            isChange = ::isChange,
            severity = { if (it is AutoLaunchEvent.Attempted) Severity.Error else Severity.Info },
        )
    }

    override suspend fun handle(intent: AutoLaunchIntent) {
        when (intent) {
            AutoLaunchIntent.Enable -> attempt("enable") { gateway.enable() }
            AutoLaunchIntent.Disable -> attempt("disable") { gateway.disable() }
            AutoLaunchIntent.OpenSettings ->
                dispatch(AutoLaunchEvent.SettingsOpened(io { gateway.openSystemSettings() }))
            AutoLaunchIntent.Refresh -> {
                val event = io { read() }
                dispatch(event, if (event is AutoLaunchEvent.Attempted) Severity.Error else Severity.Info)
            }
        }
    }

    /** A read, or the failed "read" attempt when the backend threw. Blocking. */
    private fun read(): AutoLaunchEvent =
        runCatching {
            val diagnostic = gateway.diagnostic()
            AutoLaunchEvent.Read(
                state = gateway.state(),
                backend =
                    diagnostic
                        .lineSequence()
                        .firstOrNull()
                        ?.removePrefix("backend: ")
                        .orEmpty(),
                diagnostic = diagnostic,
                startedAtLogin = gateway.startedAtLogin(),
                at = System.currentTimeMillis(),
            )
        }.getOrElse {
            AutoLaunchEvent.Attempted(AutoLaunchAttempt(System.currentTimeMillis(), "read", null, null, it.summary))
        }

    /** Reads carry their time: only a different state (or a failure) is a change. */
    private fun isChange(
        previous: AutoLaunchEvent?,
        next: AutoLaunchEvent,
    ): Boolean =
        previous !is AutoLaunchEvent.Read ||
            next !is AutoLaunchEvent.Read ||
            previous.state != next.state ||
            previous.startedAtLogin != next.startedAtLogin

    private suspend fun attempt(
        action: String,
        call: () -> AutoLaunchResult,
    ) {
        val attempt =
            runCatching { io { call() to gateway.state() } }
                .fold(
                    onSuccess = { (result, after) ->
                        AutoLaunchAttempt(System.currentTimeMillis(), action, result, after)
                    },
                    onFailure = { AutoLaunchAttempt(System.currentTimeMillis(), action, null, null, it.summary) },
                )
        val severity =
            when (attempt.result) {
                null, AutoLaunchResult.ERROR -> Severity.Error
                AutoLaunchResult.BLOCKED_BY_USER,
                AutoLaunchResult.BLOCKED_BY_POLICY,
                AutoLaunchResult.UNSUPPORTED,
                -> Severity.Warning
                else -> Severity.Info
            }
        dispatch(AutoLaunchEvent.Attempted(attempt), severity)
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}
