// nucleus_tao — JNI direct bridge over Tao for the Nucleus decorated-window-tao backend.
//
// Cross-platform: macOS (Metal renderer + AppKit chrome) and Windows (WGL
// renderer + custom WndProc decoration). Linux not yet wired in.
//
// Common responsibilities:
//   - Owns the Tao event loop on the platform main thread.
//   - Exposes the underlying native window handle (NSView on macOS, HWND on
//     Windows) so the JVM can attach a render surface and drive a Skiko/Compose
//     render pipeline outside AWT.
//   - Dispatches pointer / mouse-button / keyboard events to Kotlin.

mod keymap;

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

// ── Globals ────────────────────────────────────────────────────────────────

static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();
static EVENT_CALLBACK: OnceCell<GlobalRef> = OnceCell::new();
static EVENT_LOOP_PROXY: OnceCell<EventLoopProxy<UserEvent>> = OnceCell::new();

static WINDOWS: Mutex<Option<HashMap<u64, Window>>> = Mutex::new(None);

// Tracked across `WindowEvent::ModifiersChanged`. AWT-style modifier state
// (which Compose `KeyEvent` consumes) carries Shift/Ctrl/Alt/Meta booleans on
// every event, so we need to remember the latest snapshot. Stored as already-
// packed AWT-equivalent bitmask matching `TaoModifierMask` on the JVM side.
static CURRENT_MODIFIERS: Mutex<i32> = Mutex::new(0);

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
    let Some(callback) = EVENT_CALLBACK.get() else { return };
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
    let Some(callback) = EVENT_CALLBACK.get() else { return };
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
        let _ = EVENT_CALLBACK.set(global);
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
}

fn run_event_loop_blocking() {
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let _ = EVENT_LOOP_PROXY.set(event_loop.create_proxy());

    // Install the Cmd-Q interceptor once we're on the main thread (NSEvent
    // local monitors must be added there).
    #[cfg(target_os = "macos")]
    unsafe { nucleus_tao_install_cmd_q_handler() };
    // Enable macOS press-and-hold accent picker (opt-in via NSUserDefaults).
    #[cfg(target_os = "macos")]
    unsafe { nucleus_tao_enable_press_and_hold() };

    event_loop.run(move |event, target, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::NewEvents(StartCause::Init) => {
                dispatch(0, EVENT_LAUNCHED, 0, 0);
            }
            Event::UserEvent(user) => match user {
                UserEvent::CreateWindow {
                    handle,
                    title,
                    width,
                    height,
                    decorations,
                    resizable,
                    visible,
                } => {
                    let window = WindowBuilder::new()
                        .with_title(&title)
                        .with_inner_size(LogicalSize::new(width, height))
                        .with_decorations(decorations)
                        .with_resizable(resizable)
                        .with_visible(visible)
                        .build(target);
                    if let Ok(window) = window {
                        let logical_w = width as jint;
                        let logical_h = height as jint;
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
                    WindowEvent::ScaleFactorChanged { scale_factor, .. } => {
                        dispatch(handle, EVENT_SCALE_FACTOR_CHANGED, (scale_factor * 1000.0) as jint, 0);
                    }
                    WindowEvent::Focused(focused) => {
                        let code = if focused { EVENT_FOCUSED } else { EVENT_UNFOCUSED };
                        dispatch(handle, code, 0, 0);
                    }
                    WindowEvent::CursorMoved { position, .. } => {
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

/// Synchronous: starts a native window-drag session. Must be called from the
/// main thread while a mouse press is still active (i.e. inside a Compose
/// pointer-input handler dispatched by Tao on the same main-thread iteration).
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeDragWindow(
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
        let _ = window.drag_window();
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

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetCursorIcon(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        window.set_cursor_icon(cursor_from_code(code));
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
