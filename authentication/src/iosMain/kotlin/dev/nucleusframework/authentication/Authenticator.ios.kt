package dev.nucleusframework.authentication

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeNone
import platform.LocalAuthentication.LABiometryTypeOpticID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicy
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual val isPlatformAuthenticationSupported: Boolean
    get() = true

internal actual suspend fun platformAvailability(policy: AuthenticationPolicy): AuthenticationAvailability {
    // A fresh context per probe: a context remembers past evaluations.
    val context = LAContext()
    val error = canEvaluate(context, policy.laPolicy)
    return AuthenticationAvailability(
        biometry = context.biometry(),
        error = error?.let { errorOf(it.code) },
        message = error?.localizedDescription,
    )
}

internal actual suspend fun platformAuthenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
) {
    // One context per request: a context that already succeeded skips the prompt.
    val context = LAContext()
    canEvaluate(context, policy.laPolicy)?.let { throw it.toException() }
    prompt.cancelLabel?.takeIf { it.isNotEmpty() }?.let { context.localizedCancelTitle = it }
    // An empty title hides "Enter Password", which would end a biometrics-only request with LAErrorUserFallback.
    if (policy == AuthenticationPolicy.Biometrics) context.localizedFallbackTitle = ""

    suspendCancellableCoroutine { continuation ->
        context.evaluatePolicy(policy.laPolicy, prompt.reason) { success, error ->
            when {
                success -> continuation.resume(Unit)
                // Already resumed with CancellationException when the caller cancelled.
                continuation.isActive ->
                    continuation.resumeWithException(
                        error?.toException() ?: AuthenticationException(AuthenticationError.Platform),
                    )
            }
        }
        // The reply then arrives with LAErrorAppCancel, after the continuation is cancelled.
        continuation.invokeOnCancellation { context.invalidate() }
    }
}

private val AuthenticationPolicy.laPolicy: LAPolicy
    get() =
        when (this) {
            AuthenticationPolicy.DeviceOwner -> LAPolicyDeviceOwnerAuthentication
            AuthenticationPolicy.Biometrics -> LAPolicyDeviceOwnerAuthenticationWithBiometrics
        }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun canEvaluate(
    context: LAContext,
    policy: LAPolicy,
): NSError? =
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (context.canEvaluatePolicy(policy, error.ptr)) null else error.value
    }

/** Only meaningful once `canEvaluatePolicy` ran on [this], whatever it answered. */
private fun LAContext.biometry(): BiometryType =
    when (biometryType) {
        LABiometryTypeTouchID -> BiometryType.Fingerprint
        LABiometryTypeFaceID -> BiometryType.Face
        LABiometryTypeOpticID -> BiometryType.Iris
        LABiometryTypeNone -> BiometryType.None
        else -> BiometryType.Unknown
    }

private fun NSError.toException(): AuthenticationException =
    AuthenticationException(errorOf(code), localizedDescription)

/** Maps an `LAError` by raw code, the same table as the macOS bridge (`macos.rs`). */
@Suppress("MagicNumber")
private fun errorOf(code: Long): AuthenticationError =
    when (code) {
        -1L -> AuthenticationError.Failed
        -2L, -3L -> AuthenticationError.UserCanceled
        -4L -> AuthenticationError.SystemCanceled
        -5L -> AuthenticationError.NoCredential
        -6L -> AuthenticationError.Unavailable
        -7L -> AuthenticationError.NotEnrolled
        -8L -> AuthenticationError.Lockout
        -9L -> AuthenticationError.AppCanceled
        in -13L..-11L -> AuthenticationError.Unavailable
        -1004L -> AuthenticationError.NotInteractive
        else -> AuthenticationError.Platform
    }
