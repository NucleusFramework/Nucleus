# Nucleus Authentication

Device-owner authentication for Kotlin Multiplatform, derived from
[`robius-authentication`](https://github.com/project-robius/robius/tree/main/crates/authentication).

| Platform | Prompt | Implementation |
|---|---|---|
| Android | `BiometricPrompt` (API 28+), screen lock fallback (API 29+) | Kotlin |
| iOS | `LAContext`: Face ID / Touch ID, passcode | Kotlin/Native |
| macOS | `LAContext`: Touch ID, Apple Watch, login password | Rust (JNI) |
| Windows | Windows Hello (face, fingerprint, PIN); account password when Hello is not set up | Rust (JNI) |
| Linux | polkit agent, for an action declared by the app's `.policy` file | Rust (JNI) |

```kotlin
implementation("dev.nucleusframework:nucleus.authentication:<version>")
```

```kotlin
scope.launch {
    if (!Authenticator.availability().isAvailable) return@launch showPasswordScreen()
    try {
        Authenticator.authenticate(
            AuthenticationPrompt(
                reason = "unlock your vault",
                linuxActionId = "com.example.app.unlock", // Linux only, see below
            ),
        )
        showVault()
    } catch (e: AuthenticationException) {
        if (e.error != AuthenticationError.UserCanceled) showError(e.error)
    }
}
```

From `nucleus-application`, `window.authenticate(prompt)` parents the Windows Hello dialog to the
window. Cancelling the calling coroutine dismisses the prompt.

`AuthenticationPolicy.Biometrics` requires a biometric. Windows (Hello always accepts its PIN) and
Linux (PAM decides) cannot enforce it and fail with `UnsupportedPolicy` instead of accepting less.

**This is a gate, not a key**: success releases nothing, so do not use it alone to protect a secret
at rest.

## Android

The library manifest declares `USE_BIOMETRIC` and a provider that tracks the resumed activity;
`Authenticator.authenticate(prompt, policy, activity)` names one explicitly.

## Linux

polkit only prompts for an action an installed `.policy` file declares, and shows that file's
message. Install one under `/usr/share/polkit-1/actions/` with your package:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
  "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <action id="com.example.app.unlock">
    <description>Unlock Example</description>
    <message>Authentication is required to unlock Example</message>
    <defaults>
      <allow_any>no</allow_any>
      <allow_inactive>no</allow_inactive>
      <allow_active>auth_self</allow_active>
    </defaults>
  </action>
</policyconfig>
```

`auth_self` asks for the user's own password; `auth_admin` for an administrator's.
