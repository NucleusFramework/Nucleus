package dev.nucleusframework.authentication

/**
 * The text of an authentication prompt. Each platform shows what it has room for.
 *
 * @property reason why the app asks, shown on every platform but Linux: the `localizedReason`
 * on Apple platforms ("*App* is trying to *reason*" on macOS), the message of Windows Hello
 * and of the password prompt, the subtitle on Android.
 * @property title Android's prompt title (the app's label when `null`) and the caption of the
 * Windows password prompt; Apple platforms and Windows Hello have none.
 * @property cancelLabel the cancel button on Apple platforms and of Android's biometrics-only
 * prompt; the platform's localized default when `null`.
 * @property linuxActionId the polkit action to check on Linux, required there: polkit only
 * prompts for an action declared by a `.policy` file installed in
 * `/usr/share/polkit-1/actions/`, and shows that file's `<message>`, not [reason].
 */
public data class AuthenticationPrompt(
    public val reason: String,
    public val title: String? = null,
    public val cancelLabel: String? = null,
    public val linuxActionId: String? = null,
)

/** Rejects what no platform would show, before anything reaches it. */
internal fun AuthenticationPrompt.validate() {
    // An empty reason is what a missing translation resolves to; Apple aborts on it.
    if (reason.isBlank()) throw AuthenticationException(AuthenticationError.InvalidPrompt, "The reason is blank")
}
