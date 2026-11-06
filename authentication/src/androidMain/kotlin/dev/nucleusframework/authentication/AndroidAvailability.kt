package dev.nucleusframework.authentication

import android.annotation.TargetApi
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.Build

/** Whether [policy] can be evaluated on this device, without showing anything. */
internal fun availabilityOf(
    context: Context,
    policy: AuthenticationPolicy,
): AuthenticationAvailability {
    val biometry = biometryOf(context.packageManager)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return AuthenticationAvailability(biometry, AuthenticationError.Unavailable, "BiometricPrompt needs API 28")
    }
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val error =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> canAuthenticate(context, policy)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> canAuthenticateQ(context, policy, keyguard)
            // API 28 has no query: a secured device with a fingerprint sensor is the best guess.
            biometry == BiometryType.None -> AuthenticationError.Unavailable
            keyguard?.isDeviceSecure != true -> AuthenticationError.NoCredential
            else -> null
        }
    return AuthenticationAvailability(biometry, error)
}

@TargetApi(Build.VERSION_CODES.R)
private fun canAuthenticate(
    context: Context,
    policy: AuthenticationPolicy,
): AuthenticationError? {
    val manager = context.getSystemService(BiometricManager::class.java) ?: return AuthenticationError.Unavailable
    val biometric = manager.canAuthenticate(Authenticators.BIOMETRIC_STRONG)
    if (policy == AuthenticationPolicy.Biometrics || biometric == BiometricManager.BIOMETRIC_SUCCESS) {
        return errorOfStatus(biometric)
    }
    // DeviceOwner with no usable biometric: the screen lock alone still does.
    return errorOfStatus(manager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL)).let {
        if (it == AuthenticationError.NotEnrolled) AuthenticationError.NoCredential else it
    }
}

@TargetApi(Build.VERSION_CODES.Q)
@Suppress("DEPRECATION")
private fun canAuthenticateQ(
    context: Context,
    policy: AuthenticationPolicy,
    keyguard: KeyguardManager?,
): AuthenticationError? {
    val manager = context.getSystemService(BiometricManager::class.java) ?: return AuthenticationError.Unavailable
    val biometric = errorOfStatus(manager.canAuthenticate())
    return when {
        biometric == null || policy == AuthenticationPolicy.Biometrics -> biometric
        keyguard?.isDeviceSecure == true -> null
        else -> AuthenticationError.NoCredential
    }
}

private fun errorOfStatus(status: Int): AuthenticationError? =
    when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthenticationError.NotEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            AuthenticationError.Unavailable
        else -> AuthenticationError.Platform
    }

private fun biometryOf(packageManager: PackageManager): BiometryType {
    val sensors =
        buildList {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) add(BiometryType.Fingerprint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) add(BiometryType.Face)
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS)) add(BiometryType.Iris)
            }
        }
    return when (sensors.size) {
        0 -> BiometryType.None
        1 -> sensors.single()
        else -> BiometryType.Unknown
    }
}
