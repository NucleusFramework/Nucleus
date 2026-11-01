package dev.nucleusframework.lab.probes.system.authentication

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.authenticate
import dev.nucleusframework.authentication.AuthenticationAvailability
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.authentication.AuthenticationPrompt
import dev.nucleusframework.authentication.Authenticator
import dev.nucleusframework.lab.core.Availability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `authentication`, with the desktop parent resolved by `nucleus-application`. */
interface AuthenticationGateway {
    fun supported(): Availability

    suspend fun availability(policy: AuthenticationPolicy): AuthenticationAvailability

    /** @throws dev.nucleusframework.authentication.AuthenticationException as the platform reports it */
    suspend fun authenticate(
        prompt: AuthenticationPrompt,
        policy: AuthenticationPolicy,
        parent: AuthParentChoice,
        window: NucleusWindow?,
    )
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusAuthenticationGateway : AuthenticationGateway {
    // A broken libnucleus_authentication throws a LinkageError from here: it must make the
    // probe report it, not take the Lab down.
    override fun supported(): Availability = Availability.catching { Authenticator.isSupported }

    override suspend fun availability(policy: AuthenticationPolicy): AuthenticationAvailability =
        Authenticator.availability(policy)

    override suspend fun authenticate(
        prompt: AuthenticationPrompt,
        policy: AuthenticationPolicy,
        parent: AuthParentChoice,
        window: NucleusWindow?,
    ) {
        when {
            parent == AuthParentChoice.LabWindow && window != null -> window.authenticate(prompt, policy)
            else -> Authenticator.authenticate(prompt, policy)
        }
    }
}
