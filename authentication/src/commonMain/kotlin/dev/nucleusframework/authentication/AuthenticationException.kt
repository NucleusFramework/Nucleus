package dev.nucleusframework.authentication

/**
 * Why authentication failed or could not start. The desktop bridge reports these by
 * ordinal (`src/main/native/src/error.rs`): only ever append.
 */
public enum class AuthenticationError {
    /** The user dismissed the prompt. */
    UserCanceled,

    /**
     * The app dismissed the prompt from outside the calling coroutine; cancelling that
     * coroutine throws `CancellationException` instead.
     */
    AppCanceled,

    /** The system dismissed the prompt: another app came forward, the screen locked, the activity went away. */
    SystemCanceled,

    /** The user could not prove who they are. */
    Failed,

    /** Too many failed attempts: biometrics are locked until the device credential is entered again. */
    Lockout,

    /** The device has the sensor, but no fingerprint or face is enrolled. */
    NotEnrolled,

    /** No passcode, screen lock or Windows Hello is set up. */
    NoCredential,

    /** No usable sensor, no polkit agent, or an API level without the prompt. */
    Unavailable,

    /** An administrator disabled it: Windows group policy, or a polkit action denied to the user outright. */
    DisabledByPolicy,

    /** The sensor is in use by another request. */
    Busy,

    /** The process cannot show UI right now (Apple). */
    NotInteractive,

    /** This platform cannot enforce the policy; see [AuthenticationPolicy.Biometrics]. */
    UnsupportedPolicy,

    /** The prompt is unusable: a blank reason, no polkit action on Linux, an action no `.policy` file declares. */
    InvalidPrompt,

    /** This platform has no authentication backend, or the native bridge is unavailable. */
    Unsupported,

    /** The platform API failed; see the message. */
    Platform,
}

/** Thrown by [Authenticator.authenticate]; [error] says why. */
public class AuthenticationException(
    public val error: AuthenticationError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)
