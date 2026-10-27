package dev.nucleusframework.application

import dev.nucleusframework.authentication.AuthenticationException
import dev.nucleusframework.authentication.AuthenticationParent
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.authentication.AuthenticationPrompt
import dev.nucleusframework.authentication.Authenticator
import dev.nucleusframework.authentication.authenticate
import dev.nucleusframework.core.runtime.Platform

/**
 * Asks the system to authenticate the device owner, with this window as the owner of the
 * prompt where the platform has one (Windows: the Hello dialog and the password prompt
 * are modal to the window's HWND). The macOS panel and the polkit agent's dialog belong to
 * the system and need no parent. A window that is not realized yet falls back to the
 * app's frontmost window.
 *
 * ```kotlin
 * val window = LocalNucleusWindow.current
 * scope.launch {
 *     window.authenticate(AuthenticationPrompt(reason = "unlock your vault"))
 * }
 * ```
 *
 * Cancelling the caller resumes it at once; the Hello dialog itself may stay up until the
 * user dismisses it (see `Authenticator`).
 *
 * Requires `nucleus.authentication` on the app's classpath; `nucleus-application` never ships it.
 *
 * @throws AuthenticationException when the user did not authenticate or the prompt could not be shown
 */
public suspend fun NucleusWindow.authenticate(
    prompt: AuthenticationPrompt,
    policy: AuthenticationPolicy = AuthenticationPolicy.DeviceOwner,
) {
    val hwnd = unsafe.taoWindow?.nativeHandle?.takeIf { Platform.Current == Platform.Windows && it != 0L }
    Authenticator.authenticate(prompt, policy, hwnd?.let(AuthenticationParent::Windows) ?: AuthenticationParent.Auto)
}
