package dev.nucleusframework.authentication

/** Which ways of proving presence satisfy a request. */
public enum class AuthenticationPolicy {
    /**
     * Biometrics, or what unlocks the device instead: the passcode / screen lock, the macOS
     * login password or an Apple Watch, the Windows Hello PIN or the account password, the
     * password polkit's agent asks for on Linux. Available wherever the device is secured.
     */
    DeviceOwner,

    /**
     * Biometrics only. Unsupported on Windows (Hello always accepts its PIN) and Linux
     * (PAM decides how polkit authenticates): [Authenticator.authenticate] fails with
     * [AuthenticationError.UnsupportedPolicy] there rather than accept less.
     */
    Biometrics,
}

/** The biometric sensor a device offers. Desktop and Android ordinals are mirrored in native code: only ever append. */
public enum class BiometryType {
    /** No biometrics, or none this policy can use. */
    None,

    /** Touch ID, an Android fingerprint sensor. */
    Fingerprint,

    /** Face ID, Android face unlock. */
    Face,

    /** Optic ID, Android iris unlock. */
    Iris,

    /** Biometrics may be there, but the platform does not say which (Windows Hello, Android with several sensors). */
    Unknown,
}

/**
 * What [Authenticator.availability] found.
 *
 * @property biometry the biometric sensor the device offers, enrolled or not.
 * @property error why the policy cannot be evaluated, `null` when it can.
 * @property message the platform's own words about [error].
 */
public data class AuthenticationAvailability(
    public val biometry: BiometryType,
    public val error: AuthenticationError? = null,
    public val message: String? = null,
) {
    /** Whether [Authenticator.authenticate] would show a prompt. */
    public val isAvailable: Boolean
        get() = error == null
}
