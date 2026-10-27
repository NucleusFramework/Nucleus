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
Installing the file is the app's job for now (a packaging DSL is a separate change). The Lab uses
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
| Windows | `IAsyncOperation.Cancel()` on Hello; the in-process CredUI dialog has no cancel API, so its `Credential Dialog Xaml Host` window gets `WM_CLOSE` |
| Linux | `CancelCheckAuthorization(cancellation_id)` on the same connection (polkit only lets the caller that started a check cancel it), sent from another thread through a shared writer |

The cancellation handler is installed after the native call returns, so a caller already
cancelled at that point dismisses the prompt at once instead of leaving it up.

## Verification

- `NUCLEUS_AUTHENTICATION_SMOKE=1 ./gradlew :authentication:jvmTest` opens real prompts and
  dismisses them itself (cancel while shown, cancel before start, blank reason, cancellation
  surfacing as `CancellationException`). Nothing needs the user.
- Lab probe `system.authentication`: availability per policy, "Answer it yourself" (success and
  `UserCanceled` need a person), "Lab cancels after 2 s", blank reason. Scriptable at startup:
  `-Dlab.probe=system.authentication?mode=AppCancel&policy=Biometrics`.
- macOS E2E recipe: poll `CGWindowListCopyWindowInfo` for an on-screen window owned by
  `coreautha` (layer 1000) to time the panel's appearance and disappearance. Measured on macOS 27:
  the panel appears ~0.1 s after the request and is gone ~0.1 s after `invalidate()`.
