package dev.nucleusframework.authentication

/**
 * Asks the operating system to confirm that the device owner is present, through its
 * own authentication UI. The app only learns whether it succeeded: it never sees a
 * password or a biometric.
 *
 * | Platform | Prompt |
 * |---|---|
 * | Android | `BiometricPrompt` (API 28+), screen lock as fallback (API 29+) |
 * | iOS | `LAContext`: Face ID / Touch ID, passcode as fallback |
 * | macOS | `LAContext`: Touch ID, Apple Watch or login password |
 * | Windows | Windows Hello (face, fingerprint, PIN); CredUI account password when Hello is not set up |
 * | Linux | polkit: the desktop's polkit agent asks for the password of an action the app installed |
 *
 * ```kotlin
 * scope.launch {
 *     try {
 *         Authenticator.authenticate(AuthenticationPrompt(reason = "unlock your vault"))
 *         showVault()
 *     } catch (e: AuthenticationException) {
 *         if (e.error != AuthenticationError.UserCanceled) showError(e)
 *     }
 * }
 * ```
 *
 * This is a gate, not a key: success unlocks nothing cryptographically, so a process that
 * can patch the app can skip it. Secrets that must stay locked belong in a store that
 * releases them only after the same check (Keychain access control, Android Keystore).
 *
 * Cancelling the calling coroutine dismisses the prompt. Desktop and Android have
 * overloads naming the parent window or activity; without one, the frontmost one of the
 * app is used.
 */
public object Authenticator {
    /** Whether this platform has an authentication backend (on desktop: whether the native bridge loaded). */
    public val isSupported: Boolean
        get() = isPlatformAuthenticationSupported

    /**
     * Whether [policy] can be evaluated right now, and with which biometry, without showing
     * anything. Ask before offering an "Unlock with Touch ID" button rather than after a
     * failed [authenticate].
     */
    public suspend fun availability(
        policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
    ): AuthenticationAvailability =
        if (isSupported) {
            platformAvailability(policy)
        } else {
            AuthenticationAvailability(BiometryType.None, AuthenticationError.Unsupported)
        }

    /**
     * Shows the system prompt and returns once the device owner authenticated. Safe to
     * call from any dispatcher: the platform hops to its UI thread itself.
     *
     * @throws AuthenticationException when the user did not authenticate or the prompt
     * could not be shown; cancelling the caller dismisses the prompt instead
     */
    public suspend fun authenticate(
        prompt: AuthenticationPrompt,
        policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
    ) {
        prompt.validate()
        platformAuthenticate(prompt, policy)
    }
}

internal expect val isPlatformAuthenticationSupported: Boolean

internal expect suspend fun platformAvailability(policy: AuthenticationPolicy): AuthenticationAvailability

/** Shows an already [validated][validate] prompt. */
internal expect suspend fun platformAuthenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
)
