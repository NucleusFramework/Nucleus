// macOS platform code.
//
// Split into:
//   - `ffi`           — `extern "C"` block declaring symbols exported by the
//                       Objective-C helpers compiled by `build.rs`.
//   - `main_thread`   — JWM-style main-thread bouncing (NSApplication's event
//                       loop must run on the OS main thread).
//   - `handles`       — NSView pointer JNI export.
//   - `text_overlay`  — invisible NSTextView used as IME input host.
//   - `ime`           — caret rectangle / input-context activation.
//   - `apple_events`  — `kAEGetURL` deep-link bridge.
//   - `a11y`          — VoiceOver bridge (snapshot push + action callbacks).

pub(crate) mod a11y;
pub(crate) mod apple_events;
pub(crate) mod ffi;
pub(crate) mod handles;
pub(crate) mod ime;
pub(crate) mod main_thread;
pub(crate) mod text_overlay;
pub(crate) mod trackpad;

pub(crate) use main_thread::{dispatch_run_event_loop_on_main, is_main_thread};
pub(crate) use trackpad::trackpad_gesture_callback;
