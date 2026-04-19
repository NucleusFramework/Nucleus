# Roadmap

Modules planned for upcoming releases. Contributions welcome.

| Module | Description | macOS | Windows | Linux |
|--------|-------------|-------|---------|-------|
| `secure-storage` | Hardware-backed secret storage for tokens, passwords, keys | Keychain | Credential Manager / DPAPI | Secret Service (`libsecret`) |
| `biometric-auth` | Prompt for fingerprint / face authentication | `LocalAuthentication` (Touch ID / Face ID) | Windows Hello | `fprintd` via D-Bus / polkit |
| `share-sheet` | OS share sheet (URL, file, text) | `NSSharingService` | Windows `DataTransferManager` | xdg-desktop-portal `Share` |
| `power-events` | Sleep / wake / lock / unlock / screen-off / battery state events | `NSWorkspace` notifications | `WM_POWERBROADCAST` / `WTSRegisterSessionNotification` | `org.freedesktop.login1` D-Bus signals |
| `fs-watcher` | Native filesystem watcher (replaces slow `WatchService`) | `FSEvents` | `ReadDirectoryChangesW` | `inotify` |
| `clipboard` | Rich clipboard — image, files, HTML, RTF — plus change watcher. **macOS shipped** via [`clipboard-common`](runtime/clipboard-common.md) + [`clipboard-macos`](runtime/clipboard-macos.md); Windows and Linux backends pending | ✅ `NSPasteboard` | `AddClipboardFormatListener` / OLE | `wl-clipboard` / X11 selections |
| `screen-capture` | Native screenshot / screen recording | `CGDisplayCreateImage` / ScreenCaptureKit | Windows Graphics Capture / DXGI | xdg-desktop-portal `Screenshot` |
