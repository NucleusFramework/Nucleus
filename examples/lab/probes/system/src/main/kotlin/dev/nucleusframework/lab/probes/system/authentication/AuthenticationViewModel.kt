package dev.nucleusframework.lab.probes.system.authentication

import androidx.lifecycle.ViewModel
import dev.nucleusframework.authentication.AuthenticationException
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.authentication.AuthenticationPrompt
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class AuthenticationViewModel(
    private val gateway: AuthenticationGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<AuthState, AuthIntent, AuthEvent, Nothing>(
        AuthState(),
        AuthReducer,
        timeline,
        AuthenticationProbe.ID,
    ) {
    private var nextId = 1

    init {
        dispatch(AuthEvent.Ready(gateway.supported()))
        launch { refresh() }
        // nucleus-lab://probe/system.authentication?policy=biometrics&mode=appcancel
        onParams(commands) { params ->
            params.enum<AuthenticationPolicy>("policy")?.let { onIntent(AuthIntent.SetPolicy(it)) }
            params.enum<AuthMode>("mode")?.let { onIntent(AuthIntent.Authenticate(it, window = null)) }
        }
    }

    override suspend fun handle(intent: AuthIntent) {
        when (intent) {
            AuthIntent.Refresh -> refresh()
            is AuthIntent.SetPolicy -> dispatch(AuthEvent.PolicyChanged(intent.policy))
            is AuthIntent.SetParent -> dispatch(AuthEvent.ParentChanged(intent.parent))
            is AuthIntent.SetReason -> reduceSilently(AuthEvent.ReasonChanged(intent.reason))
            is AuthIntent.SetLinuxAction -> reduceSilently(AuthEvent.LinuxActionChanged(intent.actionId))
            is AuthIntent.Authenticate -> authenticate(intent)
        }
    }

    private suspend fun refresh() {
        if (!state.value.supported.isAvailable) return
        val availability =
            try {
                AuthenticationPolicy.entries.associateWith { gateway.availability(it) }
            } catch (e: LinkageError) {
                dispatch(AuthEvent.Ready(Availability.Unavailable(e.summary)))
                return
            }
        dispatch(AuthEvent.AvailabilityRead(availability))
    }

    private suspend fun authenticate(intent: AuthIntent.Authenticate) {
        val id = nextId++
        val snapshot = state.value
        dispatch(
            AuthEvent.Started(
                AuthAttempt(id, System.currentTimeMillis(), snapshot.policy, snapshot.parent, intent.mode),
            ),
        )
        val prompt =
            AuthenticationPrompt(
                reason = if (intent.mode == AuthMode.BlankReason) " " else snapshot.reason,
                title = "Nucleus Lab",
                linuxActionId = snapshot.linuxActionId.ifBlank { null },
            )
        val (outcome, millis) =
            timedMillis {
                coroutineScope {
                    val attempt =
                        async {
                            try {
                                gateway.authenticate(prompt, snapshot.policy, snapshot.parent, intent.window)
                                AuthOutcome.Succeeded
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AuthenticationException) {
                                AuthOutcome.Failed(e.error, e.message ?: e.error.name)
                            } catch (e: Exception) {
                                AuthOutcome.Failed(null, e.summary)
                            } catch (e: LinkageError) {
                                // A broken native bridge: the attempt fails, the probe stays up.
                                AuthOutcome.Failed(null, e.summary)
                            }
                        }
                    if (intent.mode == AuthMode.AppCancel) {
                        delay(APP_CANCEL_DELAY_MILLIS)
                        attempt.cancel()
                    }
                    try {
                        attempt.await()
                    } catch (e: CancellationException) {
                        if (attempt.isCancelled) AuthOutcome.Cancelled else throw e
                    }
                }
            }
        val met = intent.mode.isMetBy(outcome)
        // An unexpected result is the finding; an expected rejection is the API working.
        dispatch(AuthEvent.Finished(id, outcome, millis), if (met == false) Severity.Error else Severity.Info)
    }

    private companion object {
        const val APP_CANCEL_DELAY_MILLIS = 2_000L
    }
}
