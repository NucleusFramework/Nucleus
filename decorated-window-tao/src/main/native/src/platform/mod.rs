// Platform-specific submodules. Each is gated by `cfg(target_os = …)` so the
// rest of the crate can refer to `crate::platform::<os>::…` without per-call
// site cfg gating (the parent module is enough).

#[cfg(target_os = "linux")]
pub(crate) mod linux;

#[cfg(target_os = "macos")]
pub(crate) mod macos;

#[cfg(target_os = "windows")]
pub(crate) mod windows;
