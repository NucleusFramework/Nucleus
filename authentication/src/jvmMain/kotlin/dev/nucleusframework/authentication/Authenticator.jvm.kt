package dev.nucleusframework.authentication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual val isPlatformAuthenticationSupported: Boolean
    get() = NativeAuthenticationBridge.isLoaded

internal actual suspend fun platformAvailability(policy: AuthenticationPolicy): AuthenticationAvailability =
    // Windows awaits WinRT and Linux pings polkit over D-Bus: never on the caller's thread.
    withContext(Dispatchers.IO) { NativeAuthenticationBridge.availability(policy) }

internal actual suspend fun platformAuthenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
): Unit = authenticateFromDesktop(prompt, policy, AuthenticationParent.Auto)

/**
 * [Authenticator.authenticate], owned by [parent].
 *
 * @throws AuthenticationException when the user did not authenticate or the prompt could not be shown
 */
public suspend fun Authenticator.authenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
    parent: AuthenticationParent,
): Unit = authenticateFromDesktop(prompt, policy, parent)

private suspend fun authenticateFromDesktop(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
    parent: AuthenticationParent,
) {
    prompt.validate()
    if (!NativeAuthenticationBridge.isLoaded) {
        throw AuthenticationException(
            AuthenticationError.Unsupported,
            "The nucleus_authentication native library is unavailable",
        )
    }
    // Starting a request does not block (the native side answers on its own threads).
    NativeAuthenticationBridge.authenticate(prompt, policy, (parent as? AuthenticationParent.Windows)?.hwnd ?: 0L)
}
