package dev.nucleusframework.authentication

import android.annotation.TargetApi
import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual val isPlatformAuthenticationSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

internal actual suspend fun platformAvailability(policy: AuthenticationPolicy): AuthenticationAvailability {
    val context =
        AndroidAuthenticationContext.application
            ?: return AuthenticationAvailability(
                BiometryType.None,
                AuthenticationError.Unavailable,
                "NucleusAuthenticationProvider has not run",
            )
    return availabilityOf(context, policy)
}

internal actual suspend fun platformAuthenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
) {
    val activity =
        AndroidAuthenticationContext.currentActivity
            ?: throw AuthenticationException(
                AuthenticationError.Unavailable,
                "No resumed activity to show the prompt from",
            )
    authenticateFrom(activity, prompt, policy)
}

/**
 * [Authenticator.authenticate], shown from [activity].
 *
 * @throws AuthenticationException when the user did not authenticate or the prompt could not be shown
 */
public suspend fun Authenticator.authenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
    activity: Activity,
) {
    prompt.validate()
    authenticateFrom(activity, prompt, policy)
}

private suspend fun authenticateFrom(
    activity: Activity,
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        throw AuthenticationException(AuthenticationError.Unavailable, "BiometricPrompt needs API 28")
    }
    // Checked first: BiometricPrompt reports a missing enrolment only after showing nothing.
    availabilityOf(activity, policy).error?.let { error ->
        throw AuthenticationException(error, "Cannot authenticate with $policy")
    }
    withContext(Dispatchers.Main) { showPrompt(activity, prompt, policy) }
}

@TargetApi(Build.VERSION_CODES.P)
private suspend fun showPrompt(
    activity: Activity,
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy,
): Unit =
    suspendCancellableCoroutine { continuation ->
        val credentialAllowed =
            policy == AuthenticationPolicy.DeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cancellation = CancellationSignal()
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence?,
                ) {
                    // Cancelled by the caller: the continuation already threw CancellationException.
                    if (continuation.isActive) {
                        val error = AuthenticationException(errorOf(errorCode), errString?.toString())
                        continuation.resumeWithException(error)
                    }
                }

                // A rejected finger or face: the prompt stays up for another try.
                override fun onAuthenticationFailed() = Unit
            }
        val builder =
            BiometricPrompt
                .Builder(activity)
                .setTitle(prompt.title ?: activity.applicationInfo.loadLabel(activity.packageManager))
                .setSubtitle(prompt.reason)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                builder.setAllowedAuthenticators(
                    Authenticators.BIOMETRIC_STRONG or if (credentialAllowed) Authenticators.DEVICE_CREDENTIAL else 0,
                )
            credentialAllowed -> setDeviceCredentialAllowed(builder)
        }
        if (!credentialAllowed) {
            // Required without a credential fallback: build() throws otherwise.
            builder.setNegativeButton(
                prompt.cancelLabel ?: activity.getString(android.R.string.cancel),
                activity.mainExecutor,
            ) { _, _ ->
                if (continuation.isActive) {
                    continuation.resumeWithException(AuthenticationException(AuthenticationError.UserCanceled))
                }
            }
        }
        builder.build().authenticate(cancellation, activity.mainExecutor, callback)
        continuation.invokeOnCancellation { cancellation.cancel() }
    }

@TargetApi(Build.VERSION_CODES.Q)
@Suppress("DEPRECATION")
private fun setDeviceCredentialAllowed(builder: BiometricPrompt.Builder) {
    // API 29 only: setAllowedAuthenticators replaces it from API 30.
    builder.setDeviceCredentialAllowed(true)
}

/** Maps a `BiometricPrompt.BIOMETRIC_ERROR_*` code. */
@Suppress("MagicNumber", "CyclomaticComplexMethod")
private fun errorOf(code: Int): AuthenticationError =
    when (code) {
        1, 12 -> AuthenticationError.Unavailable // HW_UNAVAILABLE, HW_NOT_PRESENT
        3, 5 -> AuthenticationError.SystemCanceled // TIMEOUT, CANCELED (the app going away)
        7, 9 -> AuthenticationError.Lockout // LOCKOUT, LOCKOUT_PERMANENT
        10 -> AuthenticationError.UserCanceled // USER_CANCELED
        11 -> AuthenticationError.NotEnrolled // NO_BIOMETRICS
        14 -> AuthenticationError.NoCredential // NO_DEVICE_CREDENTIAL
        else -> AuthenticationError.Platform // UNABLE_TO_PROCESS, NO_SPACE, VENDOR, SECURITY_UPDATE_REQUIRED
    }
