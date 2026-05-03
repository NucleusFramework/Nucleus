// nucleus_tao — JNI direct bridge over Tao for the Nucleus decorated-window-tao backend.
//
// Cross-platform: macOS (Metal renderer + AppKit chrome), Windows (WGL
// renderer + custom WndProc decoration) and Linux (EGL on X11/Wayland via
// GTK, native GTK decorations).
//
// Common responsibilities:
//   - Owns the Tao event loop on the platform main thread.
//   - Exposes the underlying native window handle (NSView on macOS, HWND on
//     Windows) so the JVM can attach a render surface and drive a Skiko/Compose
//     render pipeline outside AWT.
//   - Dispatches pointer / mouse-button / keyboard events to Kotlin.

mod keymap;
#[cfg(target_os = "linux")]
mod a11y_linux;

use std::collections::HashMap;
#[cfg(target_os = "macos")]
use std::ffi::c_void;
use std::sync::Mutex;

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::OnceCell;

use tao::dpi::LogicalSize;
use tao::event::{ElementState, Event, MouseButton, MouseScrollDelta, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::keyboard::ModifiersState;
#[cfg(target_os = "macos")]
use tao::platform::macos::WindowExtMacOS;
#[cfg(target_os = "windows")]
use tao::platform::windows::WindowExtWindows;
use tao::window::{CursorIcon, Window, WindowBuilder, WindowId};

#[cfg(target_os = "linux")]
use raw_window_handle::{HasDisplayHandle, HasWindowHandle, RawDisplayHandle, RawWindowHandle};

// ── Globals ────────────────────────────────────────────────────────────────

static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();
// Held in a Mutex (not OnceCell) so we can drop the GlobalRef after the Tao
// event loop exits — invokes DeleteGlobalRef and unpins the Kotlin callback.
static EVENT_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);
static EVENT_LOOP_PROXY: OnceCell<EventLoopProxy<UserEvent>> = OnceCell::new();

static WINDOWS: Mutex<Option<HashMap<u64, Window>>> = Mutex::new(None);

// Tracked across `WindowEvent::ModifiersChanged`. AWT-style modifier state
// (which Compose `KeyEvent` consumes) carries Shift/Ctrl/Alt/Meta booleans on
// every event, so we need to remember the latest snapshot. Stored as already-
// packed AWT-equivalent bitmask matching `TaoModifierMask` on the JVM side.
static CURRENT_MODIFIERS: Mutex<i32> = Mutex::new(0);

// Linux only: last cursor name requested per window, so we can re-apply it on
// every CursorMoved event. tao's GTK backend installs a motion-notify handler
// that calls `gdk_window_set_cursor("default")` on every motion (resize-edge
// detection on undecorated, resizable windows). That handler runs through
// XIDefineCursor under the hood, which takes precedence over legacy
// XDefineCursor for the matching master pointer — so we must re-apply our
// device cursor *after* tao's handler on every move, otherwise the icon the
// user sees flashes back to the default arrow on the next pixel of motion.
#[cfg(target_os = "linux")]
static LAST_CURSOR_BY_HANDLE: Mutex<Option<HashMap<u64, String>>> = Mutex::new(None);

const MOD_MASK_SHIFT: i32 = 1 << 0;
const MOD_MASK_CONTROL: i32 = 1 << 1;
const MOD_MASK_ALT: i32 = 1 << 2;
const MOD_MASK_META: i32 = 1 << 3;

fn pack_modifiers(state: ModifiersState) -> i32 {
    let mut m = 0;
    if state.shift_key() { m |= MOD_MASK_SHIFT; }
    if state.control_key() { m |= MOD_MASK_CONTROL; }
    if state.alt_key() { m |= MOD_MASK_ALT; }
    if state.super_key() { m |= MOD_MASK_META; }
    m
}

fn current_modifier_bits() -> i32 {
    CURRENT_MODIFIERS.lock().map(|g| *g).unwrap_or(0)
}

// Cursor position is reported in physical pixels via integers to keep the JNI
// signature uniform with the other event payloads (jint × 2). We multiply by a
// fixed scale factor (1024) to preserve sub-pixel precision on retina displays.
const CURSOR_FIXED_SCALE: f64 = 1024.0;

// ── macOS main-thread bouncing (JWM-style) ─────────────────────────────────
//
// JVM launchers (with or without -XstartOnFirstThread) and GraalVM
// native-image binaries can land us on a worker thread on macOS. Tao's
// `EventLoop::new` panics unless invoked on the OS main thread, and
// AppKit-driven animations (e.g. fullscreen) only complete when their
// notifications are dispatched via the main thread's run loop.
//
// `dispatch_sync(main_queue)` requires the main thread to already be inside
// an active run loop in default mode — otherwise the queued block never
// runs and we deadlock. `performSelectorOnMainThread:waitUntilDone:YES`,
// in contrast, uses a CFRunLoop source that wakes the main thread the next
// time it enters any run loop. Combined with the fact that AWT
// (transitively pulled by Compose Desktop) starts `[NSApp run]` on main
// during early init, this gives us a reliable rendezvous point.
//
// Implemented in C in `objc/main_thread_dispatch.m`, compiled by build.rs.

#[cfg(target_os = "macos")]
extern "C" {
    fn nucleus_tao_run_on_main_blocking(
        entry: extern "C" fn(*mut c_void),
        context: *mut c_void,
    );
    fn nucleus_tao_is_main_thread() -> i32;
    fn nucleus_tao_install_cmd_q_handler();
    fn nucleus_tao_enable_press_and_hold();
    fn nucleus_tao_activate_input_context(ns_view_handle: i64);
    fn nucleus_tao_set_ime_local_rect(
        ns_view_handle: i64,
        x_px: f64,
        y_px: f64,
        w_px: f64,
        h_px: f64,
    );
    fn nucleus_tao_attach_text_overlay(ns_view_handle: i64);
    fn nucleus_tao_focus_text_overlay(focused: i32);
    fn nucleus_tao_a11y_attach(ns_view_handle: i64);
    fn nucleus_tao_a11y_detach(ns_view_handle: i64);
    fn nucleus_tao_a11y_apply_snapshot(
        ns_view_handle: i64,
        bytes: *const u8,
        len: usize,
    ) -> i32;
    fn nucleus_tao_a11y_post_focus_changed(ns_view_handle: i64, node_id: u64);
    fn nucleus_tao_a11y_is_voiceover_running() -> i32;
    fn nucleus_tao_apple_events_install();
    fn nucleus_tao_a11y_is_active() -> i32;
    fn nucleus_tao_a11y_consume_resync() -> i32;
    fn nucleus_tao_a11y_note_pushed();
    fn nucleus_tao_install_drag_monitor();
    fn nucleus_tao_start_window_drag(ns_window_ptr: i64);
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeAttachTextOverlay(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        let ns_view = window.ns_view() as i64;
        unsafe { nucleus_tao_attach_text_overlay(ns_view) };
    }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeFocusTextOverlay(
    _env: JNIEnv,
    _class: JClass,
    focused: jboolean,
) {
    unsafe { nucleus_tao_focus_text_overlay(if focused != JNI_FALSE { 1 } else { 0 }) };
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeActivateInputContext(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        let ns_view = window.ns_view() as i64;
        unsafe { nucleus_tao_activate_input_context(ns_view) };
    }
}

// ── Apple Events bridge (macOS) ────────────────────────────────────────────
//
// Installs an `NSAppleEventManager` handler for `kInternetEventClass /
// kAEGetURL`. Must be called *before* `nativeRunBlocking` so the cold-start
// URL (when the app is launched via a `nucleus://…` link) is delivered to
// our handler instead of being lost.
//
// Replaces `Desktop.setOpenURIHandler` (AWT) which is incompatible with the
// Tao backend on macOS — AWT's `Desktop.getDesktop()` boots a second NSApp.

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeAppleEventsInstall(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { nucleus_tao_apple_events_install() };
}

/// Called from `objc/apple_events.m` on the main thread when AppKit delivers
/// a `kAEGetURL` event. Forwards the UTF-8 URL to
/// `NativeTaoBridge.dispatchDeepLink(String)`.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_apple_events_dispatch(utf8: *const u8, len: i32) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if utf8.is_null() || len <= 0 { return };
    let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
    let Ok(url) = std::str::from_utf8(slice) else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(url) else { return };
        let _ = env.call_static_method(
            class,
            "dispatchDeepLink",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr.into())],
        );
    }
}

// ── A11y bridge (macOS) ────────────────────────────────────────────────────
//
// The JVM side builds an immutable snapshot of the Compose semantics tree,
// serialises it to the wire format documented in `objc/a11y.m`, and pushes it
// here once per Compose tick. We forward the bytes verbatim to the ObjC
// projection, which rebuilds its NSAccessibilityElement tree and posts the
// appropriate notifications to AppKit.
//
// `Action` callbacks travel back the other direction: VoiceOver invokes
// `accessibilityPerformPress` on a NucleusA11yElement, which calls
// `nucleus_tao_a11y_invoke_action`, which turns into a JNI upcall to the
// Kotlin-side controller (`TaoAccessibilityBridge.invokeAction`).

// All a11y JNI exports take the NSView pointer directly (cached on the JVM
// side at attach time) rather than the window handle. This is critical
// because `EVENT_DESTROYED` is dispatched from inside `WINDOWS.lock()` —
// any reentrant lock attempt on the same thread (e.g. from
// `nativeA11yDetach`) would deadlock the Tao event loop.

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_attach(ns_view) };
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_detach(ns_view) };
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
    bytes: jni::objects::JByteArray,
) -> jboolean {
    if ns_view == 0 { return JNI_FALSE; }
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    let ok = unsafe {
        nucleus_tao_a11y_apply_snapshot(ns_view, buf.as_ptr() as *const u8, len)
    };
    if ok != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_is_voiceover_running() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_is_active() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_consume_resync() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { nucleus_tao_a11y_note_pushed() };
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
    node_id: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_post_focus_changed(ns_view, node_id as u64) };
}

/// Called from `objc/a11y.m` when VoiceOver edits a text field via
/// `setAccessibilityValue:`. Forwards the new string (UTF-8) to Kotlin so it
/// can invoke `SemanticsActions.SetText`.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_set_text(
    ns_view_handle: i64,
    node_id: u64,
    utf8: *const u8,
    len: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if utf8.is_null() || len < 0 { return };
    let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
    let Ok(text) = std::str::from_utf8(slice) else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(text) else { return };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetText",
            "(JJLjava/lang/String;)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Object(&jstr.into()),
            ],
        );
    }
}

/// Called from `objc/a11y.m` when VoiceOver invokes a Compose-defined custom
/// accessibility action (VO+Cmd+. menu). `action_index` is the position of
/// the action in the per-node list pushed via the wire format.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_invoke_custom_action(
    ns_view_handle: i64,
    node_id: u64,
    action_index: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yCustomAction",
            "(JJI)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(action_index),
            ],
        );
    }
}

/// Called from `objc/a11y.m` when VoiceOver moves a scroll bar to an absolute
/// position via `setAccessibilityValue:`. Delivers the precise (dx, dy)
/// delta to Compose's `SemanticsActions.ScrollBy`.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_scroll_by(
    ns_view_handle: i64,
    node_id: u64,
    dx: f32,
    dy: f32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yScrollBy",
            "(JJFF)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Float(dx),
                JValue::Float(dy),
            ],
        );
    }
}

/// Called from `objc/a11y.m` when VoiceOver places the caret / extends the
/// selection inside a text field (`setAccessibilitySelectedTextRange:`).
/// Routed to Compose's `SemanticsActions.SetSelection`.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_set_selection(
    ns_view_handle: i64,
    node_id: u64,
    start: i32,
    end: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetSelection",
            "(JJII)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(start),
                JValue::Int(end),
            ],
        );
    }
}

/// Called from `objc/a11y.m` when VoiceOver triggers an accessibility action.
/// Passes the NSView pointer through unchanged — the JVM-side registry is
/// indexed by NSView, so we can avoid acquiring `WINDOWS.lock()` here. That
/// keeps this callback safe to invoke from any AppKit context, including
/// nested ones where the Tao loop happens to hold the lock.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_invoke_action(
    ns_view_handle: i64,
    node_id: u64,
    action: u16,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yActionByNsView",
            "(JJI)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(action as i32),
            ],
        );
    }
}

// ── A11y bridge (Windows) ──────────────────────────────────────────────────
//
// Mirror of the macOS bridge above but the native projection lives in a
// sibling DLL (nucleus_tao_a11y.dll). We resolve its entry points lazily via
// GetProcAddress against an already-loaded module — Kotlin's NativeLibraryLoader
// loads the DLL by absolute path before the first JNI call here.

#[cfg(target_os = "windows")]
mod a11y_win {
    use super::*;
    use std::ffi::c_void;
    use std::sync::Mutex;

    type AttachFn = unsafe extern "system" fn(hwnd: i64);
    type DetachFn = unsafe extern "system" fn(hwnd: i64);
    type ApplyFn = unsafe extern "system" fn(hwnd: i64, bytes: *const u8, len: usize) -> i32;
    type IsActiveFn = unsafe extern "system" fn(hwnd: i64) -> i32;
    type ConsumeResyncFn = unsafe extern "system" fn(hwnd: i64) -> i32;
    type NotePushedFn = unsafe extern "system" fn(hwnd: i64);
    type RegisterInvokeCbFn = unsafe extern "system" fn(
        cb: extern "system" fn(hwnd: i64, node_id: u64, action: u16),
    );
    type RegisterSetTextCbFn = unsafe extern "system" fn(
        cb: extern "system" fn(hwnd: i64, node_id: u64, utf8: *const u8, len: i32),
    );
    type RegisterSetSelectionCbFn = unsafe extern "system" fn(
        cb: extern "system" fn(hwnd: i64, node_id: u64, start: i32, end: i32),
    );
    type RegisterScrollByCbFn = unsafe extern "system" fn(
        cb: extern "system" fn(hwnd: i64, node_id: u64, dx: f32, dy: f32),
    );
    type RegisterCustomActionCbFn = unsafe extern "system" fn(
        cb: extern "system" fn(hwnd: i64, node_id: u64, index: i32),
    );

    pub struct A11yApi {
        pub attach: AttachFn,
        pub detach: DetachFn,
        pub apply: ApplyFn,
        pub is_active: IsActiveFn,
        pub consume_resync: ConsumeResyncFn,
        pub note_pushed: NotePushedFn,
    }

    static API: Mutex<Option<A11yApi>> = Mutex::new(None);

    extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut c_void;
        fn GetModuleHandleW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
    }

    fn to_wide_nul(s: &str) -> Vec<u16> {
        let mut v: Vec<u16> = s.encode_utf16().collect();
        v.push(0);
        v
    }

    /// Resolve and cache the API on first use. Returns None if the sibling DLL
    /// isn't loaded yet (Kotlin must load it before any A11y JNI export is
    /// invoked — NativeLibraryLoader does this at module init time).
    pub fn api() -> Option<&'static A11yApi> {
        let mut guard = API.lock().ok()?;
        if guard.is_some() {
            // Re-borrow as 'static via Option::as_ref + leaking the lock guard
            // is fine because API is a static and we never drop the contents.
            // SAFETY: the API struct is initialised once and never mutated.
            let p = guard.as_ref().unwrap() as *const A11yApi;
            unsafe { return Some(&*p); }
        }
        let name = to_wide_nul("nucleus_tao_a11y.dll");
        let mut h = unsafe { GetModuleHandleW(name.as_ptr()) };
        if h.is_null() {
            // Last-ditch fallback: attempt LoadLibraryW (may fail if the DLL
            // isn't on PATH — typically it's been extracted by Kotlin's
            // NativeLibraryLoader which calls System.load with a full path).
            h = unsafe { LoadLibraryW(name.as_ptr()) };
        }
        if h.is_null() {
            return None;
        }
        let resolve = |name: &str| unsafe {
            let mut cstr: Vec<u8> = name.bytes().collect();
            cstr.push(0);
            GetProcAddress(h, cstr.as_ptr())
        };
        let attach     = resolve("nucleus_tao_a11y_attach_win");
        let detach     = resolve("nucleus_tao_a11y_detach_win");
        let apply      = resolve("nucleus_tao_a11y_apply_snapshot_win");
        let active     = resolve("nucleus_tao_a11y_is_active_win");
        let resync     = resolve("nucleus_tao_a11y_consume_resync_win");
        let pushed     = resolve("nucleus_tao_a11y_note_pushed_win");
        let reg_invoke = resolve("nucleus_tao_a11y_register_action_callback_win");
        let reg_settxt = resolve("nucleus_tao_a11y_register_set_text_callback_win");
        let reg_selrng = resolve("nucleus_tao_a11y_register_set_selection_callback_win");
        let reg_scroll = resolve("nucleus_tao_a11y_register_scroll_by_callback_win");
        let reg_custom = resolve("nucleus_tao_a11y_register_custom_action_callback_win");
        if attach.is_null() || detach.is_null() || apply.is_null() ||
           active.is_null() || resync.is_null() || pushed.is_null() ||
           reg_invoke.is_null() || reg_settxt.is_null() || reg_selrng.is_null() ||
           reg_scroll.is_null() || reg_custom.is_null() {
            return None;
        }
        let api = A11yApi {
            attach:         unsafe { std::mem::transmute(attach) },
            detach:         unsafe { std::mem::transmute(detach) },
            apply:          unsafe { std::mem::transmute(apply) },
            is_active:      unsafe { std::mem::transmute(active) },
            consume_resync: unsafe { std::mem::transmute(resync) },
            note_pushed:    unsafe { std::mem::transmute(pushed) },
        };
        // Wire the action callbacks so the C DLL can route into the JVM.
        let r_invoke: RegisterInvokeCbFn        = unsafe { std::mem::transmute(reg_invoke) };
        let r_settxt: RegisterSetTextCbFn       = unsafe { std::mem::transmute(reg_settxt) };
        let r_selrng: RegisterSetSelectionCbFn  = unsafe { std::mem::transmute(reg_selrng) };
        let r_scroll: RegisterScrollByCbFn      = unsafe { std::mem::transmute(reg_scroll) };
        let r_custom: RegisterCustomActionCbFn  = unsafe { std::mem::transmute(reg_custom) };
        unsafe {
            r_invoke(invoke_action_trampoline);
            r_settxt(set_text_trampoline);
            r_selrng(set_selection_trampoline);
            r_scroll(scroll_by_trampoline);
            r_custom(custom_action_trampoline);
        }
        *guard = Some(api);
        let p = guard.as_ref().unwrap() as *const A11yApi;
        unsafe { Some(&*p) }
    }

    /// Trampoline invoked by `nucleus_tao_a11y.dll` when UIA dispatches an
    /// action (Invoke, etc). Forwards into the JVM via the existing
    /// `dispatchA11yActionByNsView` upcall — semantically the JNI side already
    /// uses the "view handle" as an opaque key, so reusing it for HWND is
    /// fine. The Kotlin registry indexes by this same handle.
    extern "system" fn invoke_action_trampoline(hwnd: i64, node_id: u64, action: u16) {
        let Some(jvm) = JAVA_VM.get() else { return };
        if let Ok(mut env) = jvm.attach_current_thread() {
            let class = match env.find_class(
                "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
            ) {
                Ok(c) => c,
                Err(_) => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11yActionByNsView",
                "(JJI)V",
                &[
                    JValue::Long(hwnd),
                    JValue::Long(node_id as i64),
                    JValue::Int(action as i32),
                ],
            );
        }
    }

    extern "system" fn set_text_trampoline(
        hwnd: i64, node_id: u64, utf8: *const u8, len: i32,
    ) {
        let Some(jvm) = JAVA_VM.get() else { return };
        if utf8.is_null() || len < 0 { return };
        let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
        let Ok(text) = std::str::from_utf8(slice) else { return };
        if let Ok(mut env) = jvm.attach_current_thread() {
            let class = match env.find_class(
                "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
            ) {
                Ok(c) => c,
                Err(_) => return,
            };
            let Ok(jstr) = env.new_string(text) else { return };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetText",
                "(JJLjava/lang/String;)V",
                &[
                    JValue::Long(hwnd),
                    JValue::Long(node_id as i64),
                    JValue::Object(&jstr.into()),
                ],
            );
        }
    }

    extern "system" fn set_selection_trampoline(
        hwnd: i64, node_id: u64, start: i32, end: i32,
    ) {
        let Some(jvm) = JAVA_VM.get() else { return };
        if let Ok(mut env) = jvm.attach_current_thread() {
            let class = match env.find_class(
                "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
            ) {
                Ok(c) => c,
                Err(_) => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetSelection",
                "(JJII)V",
                &[
                    JValue::Long(hwnd),
                    JValue::Long(node_id as i64),
                    JValue::Int(start),
                    JValue::Int(end),
                ],
            );
        }
    }

    extern "system" fn scroll_by_trampoline(
        hwnd: i64, node_id: u64, dx: f32, dy: f32,
    ) {
        let Some(jvm) = JAVA_VM.get() else { return };
        if let Ok(mut env) = jvm.attach_current_thread() {
            let class = match env.find_class(
                "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
            ) {
                Ok(c) => c,
                Err(_) => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11yScrollBy",
                "(JJFF)V",
                &[
                    JValue::Long(hwnd),
                    JValue::Long(node_id as i64),
                    JValue::Float(dx),
                    JValue::Float(dy),
                ],
            );
        }
    }

    extern "system" fn custom_action_trampoline(
        hwnd: i64, node_id: u64, index: i32,
    ) {
        let Some(jvm) = JAVA_VM.get() else { return };
        if let Ok(mut env) = jvm.attach_current_thread() {
            let class = match env.find_class(
                "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
            ) {
                Ok(c) => c,
                Err(_) => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11yCustomAction",
                "(JJI)V",
                &[
                    JValue::Long(hwnd),
                    JValue::Long(node_id as i64),
                    JValue::Int(index),
                ],
            );
        }
    }
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 { return; }
    if let Some(api) = a11y_win::api() {
        unsafe { (api.attach)(hwnd) };
    }
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 { return; }
    if let Some(api) = a11y_win::api() {
        unsafe { (api.detach)(hwnd) };
    }
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
    bytes: jni::objects::JByteArray,
) -> jboolean {
    if hwnd == 0 { return JNI_FALSE; }
    let Some(api) = a11y_win::api() else { return JNI_FALSE; };
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    let ok = unsafe { (api.apply)(hwnd, buf.as_ptr() as *const u8, len) };
    if ok != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // The Kotlin bridge calls is_active without the handle (mirroring macOS).
    // The Windows projection tracks per-HWND state but the Kotlin observer
    // currently asks "is anyone active anywhere". We return true if any
    // tracked window is active — since we don't have a global registry here
    // we default to true to keep snapshots flowing while a UIA client is
    // attached. The native side still fast-paths when no listener is bound.
    JNI_TRUE
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_FALSE
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
    /* No-op on Windows; per-HWND tracking lives in the C DLL. */
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    /* No screen-reader-detect API exposed yet on Windows; report false. */
    JNI_FALSE
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    _hwnd: jlong,
    _node_id: jlong,
) {
    /* TODO: emit UIA_AutomationFocusChangedEventId via UiaRaiseAutomationEvent. */
}

/* No-op stubs for nativeA11ySetAppName on macOS / Windows — the Linux path
 * uses this to override accesskit_unix's `current_exe()` fallback (which
 * returns "java" on the JVM). macOS reads the bundle CFBundleName and Windows
 * uses the HWND title, so neither needs overriding. */
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11ySetAppName(
    _env: JNIEnv,
    _class: JClass,
    _name: jni::objects::JString,
) {
}

#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11ySetAppName(
    _env: JNIEnv,
    _class: JClass,
    _name: jni::objects::JString,
) {
}

/// Called from `main_thread_dispatch.m` when the user hits Cmd-Q.
/// Posts a UserEvent::Exit on the running Tao event-loop proxy.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "C" fn nucleus_tao_post_exit() {
    if let Some(proxy) = EVENT_LOOP_PROXY.get() {
        let _ = proxy.send_event(UserEvent::Exit);
    }
}

#[cfg(target_os = "macos")]
fn is_macos_main_thread() -> bool {
    unsafe { nucleus_tao_is_main_thread() != 0 }
}

#[cfg(target_os = "macos")]
extern "C" fn run_event_loop_trampoline(_ctx: *mut c_void) {
    run_event_loop_blocking();
}

#[cfg(target_os = "macos")]
fn dispatch_run_event_loop_on_main() {
    unsafe {
        nucleus_tao_run_on_main_blocking(run_event_loop_trampoline, std::ptr::null_mut());
    }
}

// ── User events posted from JNI calls into the event loop ─────────────────

#[derive(Debug)]
enum UserEvent {
    /// No-op wakeup: posted by the JVM-side `TaoMainDispatcher.dispatch()` so a
    /// blocked `ControlFlow::Wait` loop returns immediately and `MainEventsCleared`
    /// fires, draining the dispatcher queue. Without this, coroutines posted
    /// while no OS event is pending (e.g. before any window is created) sit in
    /// the queue forever and the JVM hangs in `nativeRunBlocking`.
    Wake,
    CreateWindow {
        handle: u64,
        title: String,
        width: f64,
        height: f64,
        decorations: bool,
        resizable: bool,
        visible: bool,
    },
    SetVisible {
        handle: u64,
        visible: bool,
    },
    SetTitle {
        handle: u64,
        title: String,
    },
    RequestRedraw {
        handle: u64,
    },
    RequestClose {
        handle: u64,
    },
    SetMaximized {
        handle: u64,
        maximized: bool,
    },
    SetMinimized {
        handle: u64,
        minimized: bool,
    },
    SetAlwaysOnTop {
        handle: u64,
        always_on_top: bool,
    },
    SetFocusable {
        handle: u64,
        focusable: bool,
    },
    Focus {
        handle: u64,
    },
    SetMinInnerSize {
        handle: u64,
        // Negative width/height means "clear the minimum".
        width: f64,
        height: f64,
    },
    SetWindowIcon {
        handle: u64,
        // Premultiplied RGBA pixel buffer, row-major. Empty `pixels` clears.
        width: u32,
        height: u32,
        pixels: Vec<u8>,
    },
    SetInnerSize {
        handle: u64,
        width: f64,
        height: f64,
    },
    SetOuterPosition {
        handle: u64,
        x: f64,
        y: f64,
    },
    SetFullscreen {
        handle: u64,
        fullscreen: bool,
    },
    Exit,
}

// ── Event codes mirrored on the Kotlin side ────────────────────────────────

const EVENT_LAUNCHED: jint = 1;
const EVENT_RESIZED: jint = 2;
const EVENT_CLOSE_REQUESTED: jint = 3;
const EVENT_DESTROYED: jint = 4;
const EVENT_REDRAW_REQUESTED: jint = 5;
const EVENT_FOCUSED: jint = 6;
const EVENT_UNFOCUSED: jint = 7;
const EVENT_SCALE_FACTOR_CHANGED: jint = 8; // scale * 1000 packed in `a`
const EVENT_CURSOR_MOVED: jint = 10; // a = x * 1024, b = y * 1024 (physical)
const EVENT_CURSOR_LEFT: jint = 11;
const EVENT_MOUSE_DOWN: jint = 12; // a = button code
const EVENT_MOUSE_UP: jint = 13; // a = button code
const EVENT_KEY_DOWN: jint = 14;
const EVENT_KEY_UP: jint = 15;
// Synthetic "typed character" event: corresponds to AWT's KEY_TYPED, fired
// once per Unicode scalar of the text Cocoa hands to us via insertText: /
// `WindowEvent::ReceivedImeText`. Compose's `BasicTextField` ignores key-down
// events without `isTypedEvent` for character insertion, so we must produce
// these separately from the physical KEY_DOWN.
const EVENT_KEY_TYPED: jint = 19;
// Fired once per Tao event-loop iteration after all in-flight events have
// been processed. Drives the JVM-side coroutine dispatcher pump
// (`TaoMainDispatcher`) so the Compose Recomposer can apply changes between
// platform events without spawning a worker thread.
const EVENT_MAIN_EVENTS_CLEARED: jint = 20;
// `a` and `b` carry x/y in physical pixels. Logical conversion is done on
// the JVM side using the cached scale factor.
const EVENT_MOVED: jint = 21;
const EVENT_WINDOW_READY: jint = 16; // a = width, b = height (logical)
// Scroll deltas come either as line counts (mouse wheel) or pixel deltas
// (trackpad). Compose's `MacOSCocoaConfig` (cf. compose-multiplatform-core)
// expects each kind to be shaped like AWT `MouseWheelEvent.preciseWheelRotation`,
// which has different scaling: lines map ≈ 1 notch, pixels map ≈ scrollingDelta/10.
// We split the event code so the JVM side can apply the right factor.
const EVENT_SCROLL_LINE: jint = 17; // a = dx * SCROLL_FIXED_SCALE, b = dy * SCROLL_FIXED_SCALE
const EVENT_SCROLL_PIXEL: jint = 18;

// Sub-pixel precision through the JNI int payload.
const SCROLL_FIXED_SCALE: f64 = 100.0;

const MOUSE_BUTTON_LEFT: jint = 0;
const MOUSE_BUTTON_RIGHT: jint = 1;
const MOUSE_BUTTON_MIDDLE: jint = 2;
const MOUSE_BUTTON_OTHER: jint = 3;

// ── Helpers ────────────────────────────────────────────────────────────────

fn dispatch(handle: u64, code: jint, a: jint, b: jint) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(guard) = EVENT_CALLBACK.lock() else { return };
    let Some(callback) = guard.as_ref() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
    let _ = env.call_method(
        callback.as_obj(),
        "onEvent",
        "(JIII)V",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(code),
            JValue::Int(a),
            JValue::Int(b),
        ],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

#[allow(clippy::too_many_arguments)]
fn dispatch_key(
    handle: u64,
    type_code: jint,
    vk_code: jint,
    location: jint,
    modifiers: jint,
    code_point: jint,
) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(guard) = EVENT_CALLBACK.lock() else { return };
    let Some(callback) = guard.as_ref() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
    let _ = env.call_method(
        callback.as_obj(),
        "onKeyEvent",
        "(JIIIII)V",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(type_code),
            JValue::Int(vk_code),
            JValue::Int(location),
            JValue::Int(modifiers),
            JValue::Int(code_point),
        ],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

fn handle_for(window_id: WindowId) -> Option<u64> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.iter()
        .find(|(_, w)| w.id() == window_id)
        .map(|(h, _)| *h)
}

fn mouse_button_code(b: MouseButton) -> jint {
    match b {
        MouseButton::Left => MOUSE_BUTTON_LEFT,
        MouseButton::Right => MOUSE_BUTTON_RIGHT,
        MouseButton::Middle => MOUSE_BUTTON_MIDDLE,
        _ => MOUSE_BUTTON_OTHER,
    }
}

// ── JNI exports ────────────────────────────────────────────────────────────
// Symbol naming follows the package
// io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRunBlocking(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    if let Ok(vm) = env.get_java_vm() {
        let _ = JAVA_VM.set(vm);
    }
    if let Ok(global) = env.new_global_ref(&callback) {
        if let Ok(mut guard) = EVENT_CALLBACK.lock() {
            *guard = Some(global);
        }
    }
    {
        let mut guard = WINDOWS.lock().unwrap();
        if guard.is_none() {
            *guard = Some(HashMap::new());
        }
    }

    // Tao's NSApplication-backed event loop must run on the macOS main thread.
    // GraalVM native-image binaries packaged via jpackage / Compose Desktop
    // sometimes invoke main() on a JVM worker thread, so we sync-dispatch
    // ourselves onto the main queue when needed. On Windows there's no such
    // constraint — Tao installs its WndProc on whatever thread runs the event
    // loop and the OS message pump works on any thread.
    #[cfg(target_os = "macos")]
    {
        if is_macos_main_thread() {
            run_event_loop_blocking();
        } else {
            dispatch_run_event_loop_on_main();
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        run_event_loop_blocking();
    }

    // Event loop has exited (UserEvent::Exit). Drop the Kotlin callback ref so
    // DeleteGlobalRef runs and the JVM can collect it. JAVA_VM stays in its
    // OnceCell — JavaVM is just a pointer wrapper, not a JVM-side resource.
    if let Ok(mut guard) = EVENT_CALLBACK.lock() {
        guard.take();
    }
}

fn run_event_loop_blocking() {
    // GTK backend selection. We can now drive native Wayland through a
    // wl_subsurface child of GTK's wl_surface (see nucleus_tao_egl.c
    // `nativeAttachWayland`), so the historic forcing of GDK_BACKEND=x11 is
    // gated on an env-var. Default behaviour stays on X11/XWayland (proven
    // path) until the Wayland subsurface path racks up a few weeks of usage.
    //
    //   NUCLEUS_TAO_LINUX_RENDERER=wayland → release GDK_BACKEND, GDK
    //                                        will auto-pick Wayland on a
    //                                        Wayland session
    //   anything else (default)            → force GDK_BACKEND=x11 so a
    //                                        Wayland session lands on
    //                                        XWayland (subsurface code
    //                                        unreachable)
    #[cfg(target_os = "linux")]
    {
        let opt_in_wayland = std::env::var_os("NUCLEUS_TAO_LINUX_RENDERER")
            .map(|v| v.to_string_lossy().eq_ignore_ascii_case("wayland"))
            .unwrap_or(false);
        if !opt_in_wayland && std::env::var_os("GDK_BACKEND").is_none() {
            std::env::set_var("GDK_BACKEND", "x11");
        }
    }

    let mut builder = EventLoopBuilder::<UserEvent>::with_user_event();
    // GTK enforces that gtk_main_init be called from the OS process main
    // thread (= tid == pid). On a regular JVM the Java "main" thread is *not*
    // process thread 0 — javaw / java spawn a worker for it — so Tao's stock
    // assertion would panic at startup. `with_any_thread(true)` opts into the
    // documented escape hatch (`EventLoopBuilderExtUnix`), letting us drive
    // the GTK loop from whichever thread the JVM hands us. The caveat noted
    // in the Tao docs (windows die with the thread) doesn't bite us: the
    // event-loop thread is the process's main Java thread, which lives until
    // the JVM exits.
    #[cfg(target_os = "linux")]
    {
        use tao::platform::unix::EventLoopBuilderExtUnix;
        builder.with_any_thread(true);
    }
    let event_loop = builder.build();
    let _ = EVENT_LOOP_PROXY.set(event_loop.create_proxy());

    // Install the Cmd-Q interceptor once we're on the main thread (NSEvent
    // local monitors must be added there).
    #[cfg(target_os = "macos")]
    unsafe { nucleus_tao_install_cmd_q_handler() };
    // Enable macOS press-and-hold accent picker (opt-in via NSUserDefaults).
    #[cfg(target_os = "macos")]
    unsafe { nucleus_tao_enable_press_and_hold() };
    // Latch the most recent NSLeftMouseDown for `nativeDragWindow` (mirrors
    // JNI's NucleusDragView.lastMouseDownEvent).
    #[cfg(target_os = "macos")]
    unsafe { nucleus_tao_install_drag_monitor() };

    event_loop.run(move |event, target, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::NewEvents(StartCause::Init) => {
                dispatch(0, EVENT_LAUNCHED, 0, 0);
            }
            Event::UserEvent(user) => match user {
                UserEvent::Wake => {
                    // No-op: the side-effect we want is the loop returning from
                    // its `Wait` to dispatch this event, which guarantees a
                    // following `MainEventsCleared` tick that drains
                    // `TaoMainDispatcher`.
                }
                UserEvent::CreateWindow {
                    handle,
                    title,
                    width,
                    height,
                    decorations,
                    resizable,
                    visible,
                } => {
                    let mut builder = WindowBuilder::new()
                        .with_title(&title)
                        .with_inner_size(LogicalSize::new(width, height))
                        .with_decorations(decorations)
                        .with_resizable(resizable)
                        .with_visible(visible);
                    // Linux: request an ARGB visual so the GTK window's X
                    // visual matches the canonical visual that Mesa's EGL
                    // exposes through its EGLConfigs. Without this, GDK
                    // assigns a non-canonical 24-bit RGB visual and
                    // `eglCreateWindowSurface` fails with EGL_BAD_CONFIG
                    // because no EGLConfig advertises that visual ID.
                    // The GLX path is unaffected — its `glXChooseVisual`
                    // already requests ALPHA_SIZE=8, and ARGB GTK lets the
                    // helper render directly into the parent without the
                    // child-window fallback.
                    #[cfg(target_os = "linux")]
                    {
                        builder = builder.with_transparent(true);
                    }
                    let window = builder.build(target);
                    if let Ok(window) = window {
                        let logical_w = width as jint;
                        let logical_h = height as jint;

                        // GTK realizes its widgets lazily, so the underlying
                        // `GdkWindow` (= source of the X11 XID / Wayland
                        // wl_surface that the EGL helper needs) doesn't
                        // exist yet right after `build()`. Force realization
                        // here so `nativeLinuxHandles` returns a valid handle
                        // synchronously when the JVM-side WINDOW_READY
                        // callback runs. macOS / Windows do this implicitly.
                        #[cfg(target_os = "linux")]
                        {
                            use gtk::prelude::WidgetExt;
                            use tao::platform::unix::WindowExtUnix;
                            window.gtk_window().realize();
                        }

                        {
                            let mut guard = WINDOWS.lock().unwrap();
                            if let Some(map) = guard.as_mut() {
                                map.insert(handle, window);
                            }
                        }
                        dispatch(handle, EVENT_WINDOW_READY, logical_w, logical_h);
                    } else {
                    }
                }
                UserEvent::SetVisible { handle, visible } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_visible(visible);
                        }
                    }
                }
                UserEvent::SetTitle { handle, title } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_title(&title);
                        }
                    }
                }
                UserEvent::RequestRedraw { handle } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.request_redraw();
                        }
                    }
                }
                UserEvent::RequestClose { handle } => {
                    let mut guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_mut() {
                        if map.remove(&handle).is_some() {
                            #[cfg(target_os = "linux")]
                            if let Ok(mut g) = LAST_CURSOR_BY_HANDLE.lock() {
                                if let Some(m) = g.as_mut() {
                                    m.remove(&handle);
                                }
                            }
                            dispatch(handle, EVENT_DESTROYED, 0, 0);
                        }
                    }
                }
                UserEvent::SetMaximized { handle, maximized } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_maximized(maximized);
                        }
                    }
                }
                UserEvent::SetMinimized { handle, minimized } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_minimized(minimized);
                        }
                    }
                }
                UserEvent::SetAlwaysOnTop { handle, always_on_top } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_always_on_top(always_on_top);
                        }
                    }
                }
                UserEvent::SetFocusable { handle, focusable } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_focusable(focusable);
                        }
                    }
                }
                UserEvent::Focus { handle } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            // Undo a prior `set_minimized(true)` first so the
                            // window is eligible for foreground activation.
                            w.set_minimized(false);
                            w.set_focus();
                        }
                    }
                }
                UserEvent::SetMinInnerSize { handle, width, height } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if width < 0.0 || height < 0.0 {
                                w.set_min_inner_size::<LogicalSize<f64>>(None);
                            } else {
                                w.set_min_inner_size(Some(LogicalSize::new(width, height)));
                                // Tao only stores the constraint; Windows enforces it via
                                // WM_GETMINMAXINFO during user-initiated resizes. Clamp the
                                // current inner size now so the minimum is honored immediately.
                                let scale = w.scale_factor();
                                let current = w.inner_size().to_logical::<f64>(scale);
                                let new_w = current.width.max(width);
                                let new_h = current.height.max(height);
                                if new_w > current.width || new_h > current.height {
                                    w.set_inner_size(LogicalSize::new(new_w, new_h));
                                }
                            }
                        }
                    }
                }
                UserEvent::SetWindowIcon { handle, width, height, pixels } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if pixels.is_empty() || width == 0 || height == 0 {
                                w.set_window_icon(None);
                            } else if let Ok(icon) =
                                tao::window::Icon::from_rgba(pixels, width, height)
                            {
                                w.set_window_icon(Some(icon));
                            }
                        }
                    }
                }
                UserEvent::SetInnerSize { handle, width, height } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_inner_size(LogicalSize::new(width, height));
                        }
                    }
                }
                UserEvent::SetOuterPosition { handle, x, y } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_outer_position(tao::dpi::LogicalPosition::new(x, y));
                        }
                    }
                }
                UserEvent::SetFullscreen { handle, fullscreen } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if fullscreen {
                                w.set_fullscreen(Some(tao::window::Fullscreen::Borderless(None)));
                            } else {
                                w.set_fullscreen(None);
                            }
                        }
                    }
                }
                UserEvent::Exit => {
                    *control_flow = ControlFlow::Exit;
                }
            },
            Event::WindowEvent { window_id, event, .. } => {
                let Some(handle) = handle_for(window_id) else { return };
                match event {
                    WindowEvent::CloseRequested => {
                        dispatch(handle, EVENT_CLOSE_REQUESTED, 0, 0);
                    }
                    WindowEvent::Destroyed => {
                        dispatch(handle, EVENT_DESTROYED, 0, 0);
                    }
                    WindowEvent::Resized(size) => {
                        dispatch(handle, EVENT_RESIZED, size.width as jint, size.height as jint);
                    }
                    WindowEvent::Moved(pos) => {
                        dispatch(handle, EVENT_MOVED, pos.x, pos.y);
                    }
                    WindowEvent::ScaleFactorChanged { scale_factor, .. } => {
                        dispatch(handle, EVENT_SCALE_FACTOR_CHANGED, (scale_factor * 1000.0) as jint, 0);
                    }
                    WindowEvent::Focused(focused) => {
                        let code = if focused { EVENT_FOCUSED } else { EVENT_UNFOCUSED };
                        dispatch(handle, code, 0, 0);
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        // Re-apply our XI2 device cursor BEFORE dispatching the
                        // event to the JVM. tao's GTK signal handler ran first
                        // and reset `gdk_window_set_cursor("default")` on the
                        // parent for resize-edge detection — without this
                        // re-apply our hover icon would only flash for a
                        // single pixel of motion before being overwritten.
                        #[cfg(target_os = "linux")]
                        reapply_stored_cursor(handle);
                        dispatch(
                            handle,
                            EVENT_CURSOR_MOVED,
                            (position.x * CURSOR_FIXED_SCALE) as jint,
                            (position.y * CURSOR_FIXED_SCALE) as jint,
                        );
                    }
                    WindowEvent::CursorLeft { .. } => {
                        dispatch(handle, EVENT_CURSOR_LEFT, 0, 0);
                    }
                    WindowEvent::MouseInput { state, button, .. } => {
                        let code = match state {
                            ElementState::Pressed => EVENT_MOUSE_DOWN,
                            ElementState::Released => EVENT_MOUSE_UP,
                            _ => return,
                        };
                        dispatch(handle, code, mouse_button_code(button), 0);
                    }
                    WindowEvent::MouseWheel { delta, .. } => {
                        // Pass the raw NSEvent values straight through; the JVM
                        // side reshapes them to match AWT's `preciseWheelRotation`
                        // semantics so Compose's `MacOSCocoaConfig` can apply its
                        // standard `× 10dp × -scrollAmount` formula.
                        let (code, dx, dy) = match delta {
                            MouseScrollDelta::LineDelta(x, y) => {
                                (EVENT_SCROLL_LINE, x as f64, y as f64)
                            }
                            MouseScrollDelta::PixelDelta(p) => {
                                (EVENT_SCROLL_PIXEL, p.x, p.y)
                            }
                            _ => return,
                        };
                        dispatch(
                            handle,
                            code,
                            (dx * SCROLL_FIXED_SCALE) as jint,
                            (dy * SCROLL_FIXED_SCALE) as jint,
                        );
                    }
                    WindowEvent::ReceivedImeText(text) => {
                        let mods = current_modifier_bits();
                        for ch in text.chars() {
                            dispatch_key(
                                handle,
                                EVENT_KEY_TYPED,
                                0,
                                keymap::LOC_STANDARD,
                                mods,
                                ch as jint,
                            );
                        }
                    }
                    WindowEvent::ModifiersChanged(state) => {
                        if let Ok(mut g) = CURRENT_MODIFIERS.lock() {
                            *g = pack_modifiers(state);
                        }
                    }
                    WindowEvent::KeyboardInput { event: ke, .. } => {
                        let type_code = match ke.state {
                            ElementState::Pressed => EVENT_KEY_DOWN,
                            ElementState::Released => EVENT_KEY_UP,
                            _ => return,
                        };
                        let (vk, location) = keymap::map(ke.physical_key);
                        // First Unicode scalar of the produced text (if any). Modifier
                        // keys, arrows, etc. emit `text = None`; printable keys emit
                        // the post-layout / post-modifiers character — exactly what
                        // AWT delivers as `KeyEvent.keyChar`.
                        let code_point = ke
                            .text
                            .and_then(|s| s.chars().next())
                            .map(|c| c as jint)
                            .unwrap_or(0);
                        dispatch_key(
                            handle,
                            type_code,
                            vk,
                            location,
                            current_modifier_bits(),
                            code_point,
                        );
                    }
                    _ => {}
                }
            }
            Event::RedrawRequested(window_id) => {
                if let Some(handle) = handle_for(window_id) {
                    dispatch(handle, EVENT_REDRAW_REQUESTED, 0, 0);
                }
            }
            Event::MainEventsCleared => {
                dispatch(0, EVENT_MAIN_EVENTS_CLEARED, 0, 0);
            }
            _ => {}
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeCreateWindow(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    title: JString,
    width: jdouble,
    height: jdouble,
    decorations: jboolean,
    resizable: jboolean,
    visible: jboolean,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::CreateWindow {
        handle: handle as u64,
        title,
        width,
        height,
        decorations: decorations != JNI_FALSE,
        resizable: resizable != JNI_FALSE,
        visible: visible != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetVisible(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    visible: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetVisible {
        handle: handle as u64,
        visible: visible != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetTitle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    title: JString,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetTitle {
        handle: handle as u64,
        title,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRequestRedraw(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::RequestRedraw {
        handle: handle as u64,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRequestClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::RequestClose {
        handle: handle as u64,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeExit(
    _env: JNIEnv,
    _class: JClass,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::Exit);
}

/// Wakes the Tao event loop so a queued `TaoMainDispatcher` block runs on the
/// next tick. Cheap no-op when the loop is already busy.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeWake(
    _env: JNIEnv,
    _class: JClass,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::Wake);
}

/// Brings the window to the foreground and gives it keyboard focus. On Win32
/// this also de-minimizes the window so the foreground activation actually
/// takes effect (a minimized HWND ignores `SetForegroundWindow`).
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeFocus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::Focus { handle: handle as u64 });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

/// Returns the underlying NSView pointer so the JVM can attach a CAMetalLayer.
/// Must be called on the macOS main thread (i.e. from a Tao event handler).
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeNsViewHandle(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return 0,
    };
    let Some(map) = guard.as_ref() else { return 0 };
    let Some(window) = map.get(&(handle as u64)) else { return 0 };
    window.ns_view() as jlong
}

/// Returns the underlying HWND so the JVM can attach a WGL context and apply
/// custom decoration via the `nucleus_tao_windows_deco` helper.
#[cfg(target_os = "windows")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeHwndHandle(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return 0,
    };
    let Some(map) = guard.as_ref() else { return 0 };
    let Some(window) = map.get(&(handle as u64)) else { return 0 };
    window.hwnd() as isize as jlong
}

/// Returns the underlying X11 / Wayland window handles so the JVM can attach
/// an EGL context via the `nucleus_tao_egl` helper.
///
/// The returned `long[]` has length 3 with one of the following shapes:
///   `[0, 0, 0]`       → handle unavailable (window not yet realised).
///   `[1, display, xid]` → Xlib backend; `display` is `Display*`,
///                         `xid` is the X11 `Window`.
///   `[2, display, surface]` → Wayland backend; `display` is `wl_display*`,
///                             `surface` is `wl_surface*`.
///
/// Tao's GTK-based Linux windowing layer wraps both X11 and Wayland — the
/// concrete backend is decided at GDK init time. We mirror its
/// `raw_window_handle_rwh_06`/`raw_display_handle_rwh_06` impls and expose the
/// underlying pointers directly, then let the C-side EGL helper pick the right
/// `EGLNativeWindowType` (Window / wl_egl_window).
#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeLinuxHandles(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jni::sys::jlongArray {
    // Resolve handles inside the WINDOWS lock so the Tao Window can't be
    // dropped between the `window_handle()` and `display_handle()` calls.
    let mut out = [0i64; 3];
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                fill_linux_handles(window, &mut out);
            }
        }
    }
    match env.new_long_array(3) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &out);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "linux")]
fn fill_linux_handles(window: &Window, out: &mut [jlong; 3]) {
    let Ok(wh) = window.window_handle() else { return };
    let Ok(dh) = window.display_handle() else { return };
    match (wh.as_raw(), dh.as_raw()) {
        (RawWindowHandle::Xlib(w), RawDisplayHandle::Xlib(_)) => {
            // Tao's `raw_display_handle_rwh_06` calls `XOpenDisplay(NULL)`
            // and returns a *fresh* X11 connection. GLX requires the context,
            // drawable and display to all share the same connection — using
            // tao's display with a GDK-owned XID makes `glXMakeCurrent` fail
            // silently. Pull GDK's actual `Display*` via `gdk_x11_*`.
            out[0] = 1;
            out[1] = gdk_x11_display_for_window(window).unwrap_or(0);
            out[2] = w.window as jlong;
        }
        (RawWindowHandle::Wayland(w), RawDisplayHandle::Wayland(d)) => {
            out[0] = 2;
            out[1] = d.display.as_ptr() as jlong;
            out[2] = w.surface.as_ptr() as jlong;
        }
        _ => {}
    }
}

#[cfg(target_os = "linux")]
fn gdk_x11_display_for_window(window: &Window) -> Option<jlong> {
    use glib::translate::ToGlibPtr;
    use gtk::prelude::WidgetExt;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    let gdk_display = WidgetExt::display(gtk_window);
    // `gdk_display.to_glib_none().0` returns `*mut gdk_sys::GdkDisplay`. Tao's
    // transitive `gtk` crate doesn't expose the `gdk` crate publicly, so we
    // erase to `*mut c_void` and re-cast — `GdkX11Display` is a
    // newtype over `GdkDisplay` at the C level.
    let raw_display_ptr: *mut std::ffi::c_void =
        glib::translate::ToGlibPtr::<*mut gtk::gdk::ffi::GdkDisplay>::to_glib_none(&gdk_display).0
            as *mut std::ffi::c_void;
    if raw_display_ptr.is_null() {
        return None;
    }
    let xdisplay = unsafe {
        gdk_x11_sys::gdk_x11_display_get_xdisplay(raw_display_ptr as *mut gdk_x11_sys::GdkX11Display)
    };
    if xdisplay.is_null() {
        None
    } else {
        Some(xdisplay as jlong)
    }
}

/// Starts a native window-drag session.
///
/// On macOS we go through our own ObjC helper rather than `tao::Window::
/// drag_window()` for two reasons (mirrors the JNI backend):
///   1. The helper uses the latched NSLeftMouseDown event, not whichever event
///      AppKit happens to be processing (Tao calls `[NSApp currentEvent]`,
///      which is a NSLeftMouseDragged when this is invoked from a Compose
///      Move handler — `performWindowDragWithEvent:` documents a mouseDown).
///   2. The helper posts the call via `dispatch_async(dispatch_get_main_queue())`
///      so AppKit's modal event-tracking loop only starts on the next runloop
///      iteration. This gives Compose's child gesture detectors (e.g.
///      reorderable's drag handle) a chance to consume the in-flight gesture
///      before the modal loop steals it.
///
/// On Windows / Linux we keep the synchronous Tao call — those backends
/// don't drive a modal AppKit-style loop and the Compose-side hit-testing is
/// reliable enough.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeDragWindow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    #[cfg(target_os = "macos")]
    {
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            let ns_window = window.ns_window() as i64;
            unsafe { nucleus_tao_start_window_drag(ns_window) };
        }
        return;
    }

    #[cfg(not(target_os = "macos"))]
    {
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            let _ = window.drag_window();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsMaximized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(map) = guard.as_ref() else { return JNI_FALSE };
    if let Some(window) = map.get(&(handle as u64)) {
        if window.is_maximized() { JNI_TRUE } else { JNI_FALSE }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMaximized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    maximized: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetMaximized {
        handle: handle as u64,
        maximized: maximized != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMinimized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    minimized: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetMinimized {
        handle: handle as u64,
        minimized: minimized != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetAlwaysOnTop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    always_on_top: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetAlwaysOnTop {
        handle: handle as u64,
        always_on_top: always_on_top != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetFocusable(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    focusable: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetFocusable {
        handle: handle as u64,
        focusable: focusable != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMinInnerSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jdouble,
    height: jdouble,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetMinInnerSize {
        handle: handle as u64,
        width,
        height,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetWindowIcon(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
    pixels: jni::objects::JByteArray,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let buf = if pixels.is_null() || width <= 0 || height <= 0 {
        Vec::new()
    } else {
        match env.convert_byte_array(&pixels) {
            Ok(b) => b,
            Err(_) => return,
        }
    };
    let _ = proxy.send_event(UserEvent::SetWindowIcon {
        handle: handle as u64,
        width: width.max(0) as u32,
        height: height.max(0) as u32,
        pixels: buf,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetInnerSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jdouble,
    height: jdouble,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetInnerSize {
        handle: handle as u64,
        width,
        height,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetOuterPosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jdouble,
    y: jdouble,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetOuterPosition {
        handle: handle as u64,
        x,
        y,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsFullscreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(map) = guard.as_ref() else { return JNI_FALSE };
    if let Some(w) = map.get(&(handle as u64)) {
        if w.fullscreen().is_some() { JNI_TRUE } else { JNI_FALSE }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetFullscreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fullscreen: jboolean,
) {
    let Some(proxy) = EVENT_LOOP_PROXY.get() else { return };
    let _ = proxy.send_event(UserEvent::SetFullscreen {
        handle: handle as u64,
        fullscreen: fullscreen != JNI_FALSE,
    });
}

/// Pushes the caret rectangle in *window-local physical pixels* (top-left origin)
/// to native. The ObjC side converts to Cocoa screen coordinates using
/// `NSView.convertRect:toView:` + `NSWindow.convertRectToScreen:`, then stores
/// the rect for our swizzled `firstRectForCharacterRange:` to return.
///
/// AppKit's press-and-hold accent picker is gated on
/// `firstRectForCharacterRange:` returning a rect with non-zero size — Tao's
/// stock impl returns size 0×0, which short-circuits the picker.
#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetImeRect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x_px: jint,
    y_px: jint,
    w_px: jint,
    h_px: jint,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        let scale = window.scale_factor();
        // Convert physical pixels → logical points (NSView coordinate system).
        let lx = x_px as f64 / scale;
        let ly = y_px as f64 / scale;
        let lw = (w_px as f64 / scale).max(1.0);
        let lh = (h_px as f64 / scale).max(1.0);
        unsafe {
            nucleus_tao_set_ime_local_rect(window.ns_view() as i64, lx, ly, lw, lh)
        };
    }
}

/// Mirrors `TaoCursorIcon` on the JVM side. Numeric codes only, so the JNI
/// signature stays `(JI)V`. Subset chosen to cover what Compose Desktop's
/// `PointerIcon` constants surface — additional shapes can be added later.
#[cfg(not(target_os = "linux"))]
fn cursor_from_code(code: jint) -> CursorIcon {
    match code {
        1 => CursorIcon::Text,
        2 => CursorIcon::Hand,
        3 => CursorIcon::Crosshair,
        4 => CursorIcon::Wait,
        5 => CursorIcon::Move,
        6 => CursorIcon::NotAllowed,
        7 => CursorIcon::Help,
        8 => CursorIcon::Progress,
        9 => CursorIcon::EwResize,
        10 => CursorIcon::NsResize,
        11 => CursorIcon::NeswResize,
        12 => CursorIcon::NwseResize,
        _ => CursorIcon::Default,
    }
}

/// Linux-only: maps the JVM-side cursor codes to the freedesktop / Adwaita
/// cursor-theme names that `gdk_cursor_new_from_name` accepts. Going through
/// the cursor theme (rather than `XCreateFontCursor` core fonts) makes the
/// icons follow the user's GTK theme and survive XWayland's cursor surface
/// re-rendering.
#[cfg(target_os = "linux")]
fn cursor_name_from_code(code: jint) -> &'static str {
    match code {
        1 => "text",
        2 => "pointer",
        3 => "crosshair",
        4 => "wait",
        5 => "move",
        6 => "not-allowed",
        7 => "help",
        8 => "progress",
        9 => "ew-resize",
        10 => "ns-resize",
        11 => "nesw-resize",
        12 => "nwse-resize",
        _ => "default",
    }
}

/// Iterates every master pointer of the window's GDK display and assigns the
/// given themed cursor on the GdkWindow via `gdk_window_set_device_cursor`,
/// which on GTK 3 / Linux ultimately calls XIDefineCursor for each device.
///
/// This is the X Input 2 equivalent of `XDefineCursor` and is what GTK 3
/// itself uses internally — legacy `XDefineCursor` is silently overridden
/// by the per-device cursor, so going through GDK is the only way to make
/// the icon stick across motion events on a window co-hosted with GTK.
#[cfg(target_os = "linux")]
fn apply_cursor_via_gdk(window: &Window, name: &str) {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    let Some(gdk_window) = WidgetExt::window(gtk_window) else { return };
    let display = WidgetExt::display(gtk_window);
    let Some(cursor) = gtk::gdk::Cursor::from_name(&display, name) else {
        // Theme miss — fall back to "default". `from_name("default")` is
        // guaranteed by every shipping cursor theme.
        if name != "default" {
            apply_cursor_via_gdk(window, "default");
        }
        return;
    };
    // Iterate every seat's master pointer. GTK 3 keeps one master pointer
    // per seat; on a typical desktop there's exactly one seat, but MPX
    // setups (multiple physical mice each driving their own cursor) expose
    // additional seats. `gdk_window_set_device_cursor` writes the per-device
    // XInput 2 cursor — that's what GTK itself does, and what overrides the
    // default cursor tao keeps re-applying via its motion handler.
    for seat in display.list_seats() {
        if let Some(pointer) = seat.pointer() {
            gdk_window.set_device_cursor(&pointer, &cursor);
        }
    }
    display.flush();
}

/// Re-applies the cursor stored for the given window (if any). Called from
/// the `WindowEvent::CursorMoved` handler, after tao's own motion handler
/// has had a chance to overwrite the cursor with the resize-edge default.
///
/// Skipped when the stored cursor is "default": tao already resets to
/// default on every motion event, so re-applying it would be a no-op
/// (and would waste an XIDefineCursor round-trip on every mouse movement).
/// In the common case (no hover icon active), this short-circuits before
/// touching GDK at all.
#[cfg(target_os = "linux")]
fn reapply_stored_cursor(handle: u64) {
    let name = {
        let Ok(guard) = LAST_CURSOR_BY_HANDLE.lock() else { return };
        match guard.as_ref().and_then(|m| m.get(&handle)) {
            Some(s) if s != "default" => s.clone(),
            _ => return,
        }
    };
    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&handle) {
        apply_cursor_via_gdk(window, &name);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetCursorIcon(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    #[cfg(target_os = "linux")]
    {
        // GTK 3 manages cursors through XInput 2's per-device cursor table,
        // which beats legacy `XDefineCursor` and tao's own `set_cursor_icon`
        // (the latter only updates the client pointer, not every master).
        // Store the requested name so the CursorMoved handler can re-apply
        // it after each of tao's motion-handler resets, then push it once now
        // for the immediate hover transition.
        let name = cursor_name_from_code(code);
        if let Ok(mut guard) = LAST_CURSOR_BY_HANDLE.lock() {
            guard
                .get_or_insert_with(HashMap::new)
                .insert(handle as u64, name.to_string());
        }
        let Ok(guard) = WINDOWS.lock() else { return };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            apply_cursor_via_gdk(window, name);
        }
        return;
    }
    #[cfg(not(target_os = "linux"))]
    {
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            window.set_cursor_icon(cursor_from_code(code));
        }
    }
}

/// Returns the current scale factor of the window (Retina = 2.0, 3.0…).
/// Encoded as `(scale * 1000) as i32` to keep a single JNI signature.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeScaleFactor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return 1000,
    };
    let Some(map) = guard.as_ref() else { return 1000 };
    let Some(window) = map.get(&(handle as u64)) else { return 1000 };
    (window.scale_factor() * 1000.0) as jint
}
