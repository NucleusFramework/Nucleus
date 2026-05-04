// nucleus_tao — JNI direct bridge over Tao for the Nucleus decorated-window-tao backend.
//
// Cross-platform: macOS (Metal renderer + AppKit chrome), Windows (WGL
// renderer + custom WndProc decoration) and Linux (EGL on X11/Wayland via
// GTK, native GTK decorations).
//
// Common responsibilities:
//   - Owns the Tao event loop on the platform main thread.
//   - Exposes the underlying native window handle (NSView on macOS, HWND on
//     Windows, X11 / Wayland handles on Linux) so the JVM can attach a render
//     surface and drive a Skiko/Compose render pipeline outside AWT.
//   - Dispatches pointer / mouse-button / keyboard events to Kotlin.
//
// Module layout (Rust idiomatic split):
//
//   state         — process-wide statics (JAVA_VM, EVENT_CALLBACK, WINDOWS…)
//   events        — UserEvent enum, wire constants, dispatch helpers
//   event_loop    — `run_event_loop_blocking()` (Tao loop body)
//   window_jni    — cross-platform JNI exports for window lifecycle/state
//   cursor        — cross-platform cursor JNI export
//   keymap        — Tao physical key → AWT VK mapping
//   platform::macos     — AppKit helpers (FFI, main-thread dance, IME,
//                         text overlay, Apple events, VoiceOver bridge)
//   platform::windows   — HWND handle + UIA bridge (loaded from sibling DLL)
//   platform::linux     — X11/Wayland handle, AT-SPI bridge, GDK cursor

mod cursor;
mod event_loop;
mod events;
mod keymap;
mod platform;
mod state;
mod window_jni;
