package dev.nucleusframework.lab.probes.system.authentication

import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.authentication.AuthenticationAvailability
import dev.nucleusframework.authentication.AuthenticationError
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.replaceWhere

/** Lab window = `NucleusWindow.authenticate` (Windows parents the prompt); Auto = `Authenticator.authenticate`. */
enum class AuthParentChoice { LabWindow, Auto }

/** How an attempt is driven: left to the user, or dismissed by the Lab itself after a delay. */
enum class AuthMode(
    val label: String,
) {
    Answer("Answer it yourself"),
    AppCancel("Lab cancels after 2 s"),
    BlankReason("Blank reason (must be rejected)"),
}

sealed interface AuthOutcome {
    data object Succeeded : AuthOutcome

    /** The Lab cancelled the calling coroutine: `CancellationException`, prompt dismissed. */
    data object Cancelled : AuthOutcome

    data class Failed(
        val error: AuthenticationError?,
        val message: String,
    ) : AuthOutcome
}

@Immutable
data class AuthAttempt(
    val id: Int,
    val epochMillis: Long,
    val policy: AuthenticationPolicy,
    val parent: AuthParentChoice,
    val mode: AuthMode,
    val outcome: AuthOutcome? = null,
    val millis: Long? = null,
)

@Immutable
data class AuthState(
    val supported: Availability = Availability.Unknown,
    val availability: Map<AuthenticationPolicy, AuthenticationAvailability> = emptyMap(),
    val policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
    val parent: AuthParentChoice = AuthParentChoice.LabWindow,
    val reason: String = "unlock the Nucleus Lab vault",
    val linuxActionId: String = DEFAULT_LINUX_ACTION,
    val attempts: List<AuthAttempt> = emptyList(),
) {
    companion object {
        /** pkexec's own action: declared on every polkit system, asks for an admin password. */
        const val DEFAULT_LINUX_ACTION = "org.freedesktop.policykit.exec"
    }
}

sealed interface AuthIntent {
    data object Refresh : AuthIntent

    data class SetPolicy(
        val policy: AuthenticationPolicy,
    ) : AuthIntent

    data class SetParent(
        val parent: AuthParentChoice,
    ) : AuthIntent

    data class SetReason(
        val reason: String,
    ) : AuthIntent

    data class SetLinuxAction(
        val actionId: String,
    ) : AuthIntent

    /** [window] comes from the composition: it is the desktop parent. */
    data class Authenticate(
        val mode: AuthMode,
        val window: NucleusWindow?,
    ) : AuthIntent {
        override fun toString(): String = "Authenticate($mode)"
    }
}

sealed interface AuthEvent {
    data class Ready(
        val supported: Availability,
    ) : AuthEvent

    data class AvailabilityRead(
        val availability: Map<AuthenticationPolicy, AuthenticationAvailability>,
    ) : AuthEvent

    data class PolicyChanged(
        val policy: AuthenticationPolicy,
    ) : AuthEvent

    data class ParentChanged(
        val parent: AuthParentChoice,
    ) : AuthEvent

    data class ReasonChanged(
        val reason: String,
    ) : AuthEvent

    data class LinuxActionChanged(
        val actionId: String,
    ) : AuthEvent

    data class Started(
        val attempt: AuthAttempt,
    ) : AuthEvent

    data class Finished(
        val id: Int,
        val outcome: AuthOutcome,
        val millis: Long,
    ) : AuthEvent
}

/** What [mode] must end with whatever the user does; `null` when only the user decides. */
fun AuthMode.expected(): AuthOutcome? =
    when (this) {
        AuthMode.Answer -> null
        AuthMode.AppCancel -> AuthOutcome.Cancelled
        AuthMode.BlankReason -> AuthOutcome.Failed(AuthenticationError.InvalidPrompt, "")
    }

/**
 * Whether [outcome] is what [mode] asked for; errors match on their kind, not their message.
 * `null` when only the user decides, or when the policy could not even show a prompt to cancel.
 */
fun AuthMode.isMetBy(outcome: AuthOutcome): Boolean? =
    when (val expected = expected()) {
        null -> null
        is AuthOutcome.Failed -> outcome is AuthOutcome.Failed && outcome.error == expected.error
        else -> if (outcome is AuthOutcome.Failed && outcome.error in NO_PROMPT) null else outcome == expected
    }

/** Refusals that come before any prompt: nothing was on screen for the Lab to cancel. */
private val NO_PROMPT =
    setOf(
        AuthenticationError.Unavailable,
        AuthenticationError.NotEnrolled,
        AuthenticationError.NoCredential,
        AuthenticationError.UnsupportedPolicy,
    )

object AuthReducer : Reducer<AuthState, AuthEvent> {
    override fun reduce(
        state: AuthState,
        event: AuthEvent,
    ): AuthState =
        when (event) {
            is AuthEvent.Ready -> state.copy(supported = event.supported)
            is AuthEvent.AvailabilityRead -> state.copy(availability = event.availability)
            is AuthEvent.PolicyChanged -> state.copy(policy = event.policy)
            is AuthEvent.ParentChanged -> state.copy(parent = event.parent)
            is AuthEvent.ReasonChanged -> state.copy(reason = event.reason)
            is AuthEvent.LinuxActionChanged -> state.copy(linuxActionId = event.actionId)
            is AuthEvent.Started -> state.copy(attempts = state.attempts.append(event.attempt))
            is AuthEvent.Finished ->
                state.copy(
                    attempts =
                        state.attempts.replaceWhere({ it.id }, event.id) {
                            it.copy(outcome = event.outcome, millis = event.millis)
                        },
                )
        }
}
