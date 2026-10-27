# authentication internals

`authentication` asks the OS to confirm the device owner is present, through the OS's own
prompt. It is the second **Kotlin Multiplatform** library after `share` and copies its layout:
`commonMain` holds the API, Android and iOS are pure Kotlin, only the `jvm` target goes through a
Rust JNI crate (`src/main/native`, derived from
[`robius-authentication`](https://github.com/project-robius/robius/tree/main/crates/authentication),
THIRD_PARTY_NOTICES §6), and `nucleus.native-module` wires `jvmProcessResources`.

## A gate, not a key

`Authenticator.authenticate` resumes with `Unit` or throws `AuthenticationException`: nothing
cryptographic is released, so a process that can patch the app can skip the check. That is the
right tool for "confirm before showing the API key", not for keeping a secret at rest. Secrets
belong in a store that only releases them after the same check (Keychain items with
`SecAccessControl` `.userPresence`, Android Keystore keys with `setUserAuthenticationRequired`,
Windows Hello `KeyCredentialManager`). That store is a separate module; the types it needs to
show a prompt (`AuthenticationPrompt`, `AuthenticationPolicy`, `AuthenticationError`) live here
and carry no gate-specific state, so it can reuse them as is.

## Platforms

| Platform | Prompt | `Biometrics` policy | Parent |
|---|---|---|---|
| macOS | `LAContext.evaluatePolicy` (`coreautha` panel): Touch ID, Apple Watch, login password | `DeviceOwnerAuthenticationWithBiometrics`, `localizedFallbackTitle = ""` hides "Use Password…" | none: the panel is system-modal |
| iOS | same, Face ID / Touch ID / passcode | same | none |
| Windows | Windows Hello via `IUserConsentVerifierInterop::RequestVerificationForWindowAsync`; CredUI + `LogonUserW` when Hello is not set up | `UnsupportedPolicy`: Hello always accepts its PIN | the caller's HWND (`NucleusWindow.authenticate`), else the process's frontmost window |
| Linux | polkit `CheckAuthorization` with `AllowUserInteraction`, the desktop's polkit agent asks | `UnsupportedPolicy`: PAM decides how polkit authenticates | none: agent dialogs are not parented |
| Android | `android.hardware.biometrics.BiometricPrompt` (API 28+), `DEVICE_CREDENTIAL` fallback (API 29+), `BIOMETRIC_STRONG` | strong biometrics, negative button | the resumed activity (tracked by `NucleusAuthenticationProvider`), or an explicit `Activity` |

The framework `BiometricPrompt` is used rather than `androidx.biometric`: no AndroidX
dependency (like `share`), and no `FragmentActivity` requirement.

Where a policy cannot be honoured, the call fails (`UnsupportedPolicy`) rather than silently
accepting less: a biometrics-only request never ends up satisfied by a PIN. `availability()` reports
the same answer without UI, so an app can hide its "Unlock with Touch ID" button up front.

### Linux and polkit

polkit only prompts for an action declared by a `.policy` file in `/usr/share/polkit-1/actions/`,
and the dialog shows that file's `<message>`, never `AuthenticationPrompt.reason`. The app passes
the action id as `AuthenticationPrompt.linuxActionId`; a missing id is `InvalidPrompt` before any
D-Bus traffic, an undeclared one is `InvalidPrompt` from polkit's `Error.Failed … not registered`.
An action the user may never perform (`allow_active` `no`) is `DisabledByPolicy`, with no prompt.
The plugin's `linux { polkitActions { } }` ships the file in the Deb/RPM/Pacman payload, copies it
there from afterInstall and deletes it on uninstall but not on an upgrade (`LinuxPolkitActions`:
RPM runs the old package's `%postun` after the new one's `%post`). Texts are CDATA, never
escaped: polkitd keeps only the last chunk expat hands it, so `A &amp; B` would read ` B`. The Lab, run from Gradle and never installed, uses
`org.freedesktop.policykit.exec`, pkexec's own action, because every polkit system declares it.

The subject is `unix-process` with `pid`, `start-time` (field 22 of `/proc/self/stat`) and `uid`,
as polkit's own clients send it. A sandbox with its own pid namespace (Flatpak) breaks that
subject: polkit sees a pid that is not the caller's.

## Request lifecycle (desktop)

Kotlin allocates a request id, registers the continuation, then calls `nativeAuthenticate`. A
non-zero return code means nothing was shown and no callback follows. Otherwise the platform
answers exactly once through `NativeAuthenticationBridge.onResult(id, code, message)` on its own
thread (the LocalAuthentication reply queue, a per-request Windows thread in the MTA, a
per-request polkit thread). Codes are the `AuthenticationError` ordinals plus one; `jvmTest`'s
`NativeMirrorTest` fails when the Rust enums drift from the Kotlin ones.

Cancelling the caller removes the continuation first, then calls `nativeCancel(id)`, so a late
outcome finds nothing and is dropped:

| Platform | Cancel |
|---|---|
| macOS / iOS | `LAContext.invalidate()`; the reply then arrives with `LAErrorAppCancel` |
| Windows | `IAsyncOperation.Cancel()` on Hello, best effort (below); the in-process CredUI dialog has no cancel API, so its `Credential Dialog Xaml Host` window gets `WM_CLOSE` |
| Linux | `CancelCheckAuthorization(cancellation_id)` on the same connection (polkit only lets the caller that started a check cancel it), sent from another thread through a shared writer |

The cancellation handler is installed after the native call returns, so a caller already
cancelled at that point dismisses the prompt at once instead of leaving it up.

### Windows Hello may outlive the cancel

The caller always resumes at once, but the Hello dialog is not ours to close. It lives in
`CredentialUIBroker.exe`, which UIPI shields from this process: `WM_CLOSE`, `SendInput`, UI
Automation and `TerminateProcess` are all refused. `Cancel()` is the only lever, and on
Windows 11 26100 it closes the dialog 2-7 s later at best; in a run of attempts it stopped
closing it at all, cancel after cancel, the dialog staying up even after the app exited
(neither releasing the operation without `Cancel()` nor giving the dialog the foreground
changed that). Windows shows one Hello request at a time, so a later request queues behind
a dialog left up. The outcome of an abandoned dialog is dropped: answering it changes nothing.

Other libraries live with the same limit. Flutter `local_auth_windows`, Bitwarden, KeePassXC
and robius offer no cancel; Chrome's password-manager reauth abandons the result and lets the
OS prompt close whenever it does; docket (#178) hit the same stuck dialog and documents it.
Only `webauthn.dll` has a documented cancel (`WebAuthNCancelCurrentOperation`, Chromium's
WebAuthn path), at the price of a passkey ceremony instead of a consent prompt.

So a new Hello request first waits, up to 15 s after this process last cancelled one, for no
`Credential Dialog Xaml Host` window to be visible (`wait_for_cancelled_dialog`): when the
system does close the old dialog, the new one never queues behind it. A caller cancelled
while waiting ends without showing anything.

## Verification

- `NUCLEUS_AUTHENTICATION_SMOKE=1 ./gradlew :authentication:jvmTest` opens real prompts and
  dismisses them itself (cancel while shown, cancel before start, blank reason, cancellation
  surfacing as `CancellationException`). Nothing needs the user. On Linux the prompts check
  `NUCLEUS_AUTHENTICATION_LINUX_ACTION`, `org.freedesktop.policykit.exec` by default.
- Lab probe `system.authentication`: availability per policy, "Answer it yourself" (success and
  `UserCanceled` need a person), "Lab cancels after 2 s", blank reason. Scriptable at startup:
  `-Dlab.probe=system.authentication?mode=AppCancel&policy=Biometrics`.
- iOS: `scripts/ios-authentication-e2e.sh` builds `examples/authentication-ios-demo` (a bare
  UIKit app, one scenario per launch through `E2E_SCENARIO`) and drives a booted Face ID
  simulator without a person: `notifyutil -p com.apple.BiometricKit.enrollmentChanged` (after
  `-s … 0|1`) enrols or removes the face, `com.apple.BiometricKit_Sim.pearl.match` / `.nomatch`
  answers the panel. The panel needs ~1.5 s to animate in before it takes a face. A non-matching
  face leaves the prompt up ("Try Face ID Again" + the cancel label); its cancel button, the
  one case the script leaves out, ends `UserCanceled`. An unanswered prompt times out by itself
  after ~320 s with the undocumented `LAError` code `-1003`, mapped to `SystemCanceled`; any
  other unmapped code surfaces as `Platform` with the domain and code in the message.
- macOS E2E recipe: poll `CGWindowListCopyWindowInfo` for an on-screen window owned by
  `coreautha` (layer 1000) to time the panel's appearance and disappearance. Measured on macOS 27:
  the panel appears ~0.1 s after the request and is gone ~0.1 s after `invalidate()`.
- Windows E2E recipe: enumerate visible top-level windows of class `Credential Dialog Xaml Host`
  (owner process `CredentialUIBroker`, `GetWindow(GW_OWNER)` = the app's HWND with
  `NucleusWindow.authenticate`) to time the dialog without screenshots. Measured on Windows 11
  26100: the dialog appears ~0.4 s after the request; after a cancel it leaves 2-7 s later or
  not at all (above). A dialog left up can only be dismissed by a person.
- Linux E2E recipe (GNOME): gnome-shell runs `polkit-agent-helper-1` (`pgrep -x polkit-agent-he`)
  exactly while its dialog is up, so polling it times the dialog without screenshots. Measured on
  GNOME 50: the dialog appears ~0.1 s after the request and is gone ~0.25 s after the cancel.
