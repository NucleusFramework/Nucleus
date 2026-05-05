// User events posted from JNI calls into the Tao event loop, plus the wire
// constants and dispatch helpers that bridge native events back to Kotlin.

use jni::objects::JValue;
use jni::sys::{jint, jlong};

use tao::event::MouseButton;
use tao::keyboard::ModifiersState;
use tao::window::WindowId;

use crate::state::{CURRENT_MODIFIERS, EVENT_CALLBACK, JAVA_VM, WINDOWS};

// ── Modifier bitmask (mirrors `TaoModifierMask` on the JVM side) ──────────

pub(crate) const MOD_MASK_SHIFT: i32 = 1 << 0;
pub(crate) const MOD_MASK_CONTROL: i32 = 1 << 1;
pub(crate) const MOD_MASK_ALT: i32 = 1 << 2;
pub(crate) const MOD_MASK_META: i32 = 1 << 3;

pub(crate) fn pack_modifiers(state: ModifiersState) -> i32 {
    let mut m = 0;
    if state.shift_key() { m |= MOD_MASK_SHIFT; }
    if state.control_key() { m |= MOD_MASK_CONTROL; }
    if state.alt_key() { m |= MOD_MASK_ALT; }
    if state.super_key() { m |= MOD_MASK_META; }
    m
}

pub(crate) fn current_modifier_bits() -> i32 {
    CURRENT_MODIFIERS.lock().map(|g| *g).unwrap_or(0)
}

// Cursor position is reported in physical pixels via integers to keep the JNI
// signature uniform with the other event payloads (jint × 2). We multiply by a
// fixed scale factor (1024) to preserve sub-pixel precision on retina displays.
pub(crate) const CURSOR_FIXED_SCALE: f64 = 1024.0;

// ── Event codes mirrored on the Kotlin side ───────────────────────────────

pub(crate) const EVENT_LAUNCHED: jint = 1;
pub(crate) const EVENT_RESIZED: jint = 2;
pub(crate) const EVENT_CLOSE_REQUESTED: jint = 3;
pub(crate) const EVENT_DESTROYED: jint = 4;
pub(crate) const EVENT_REDRAW_REQUESTED: jint = 5;
pub(crate) const EVENT_FOCUSED: jint = 6;
pub(crate) const EVENT_UNFOCUSED: jint = 7;
pub(crate) const EVENT_SCALE_FACTOR_CHANGED: jint = 8; // scale * 1000 packed in `a`
pub(crate) const EVENT_CURSOR_MOVED: jint = 10; // a = x * 1024, b = y * 1024 (physical)
pub(crate) const EVENT_CURSOR_LEFT: jint = 11;
pub(crate) const EVENT_MOUSE_DOWN: jint = 12; // a = button code
pub(crate) const EVENT_MOUSE_UP: jint = 13; // a = button code
pub(crate) const EVENT_KEY_DOWN: jint = 14;
pub(crate) const EVENT_KEY_UP: jint = 15;
// Synthetic "typed character" event: corresponds to AWT's KEY_TYPED, fired
// once per Unicode scalar of the text Cocoa hands to us via insertText: /
// `WindowEvent::ReceivedImeText`. Compose's `BasicTextField` ignores key-down
// events without `isTypedEvent` for character insertion, so we must produce
// these separately from the physical KEY_DOWN.
pub(crate) const EVENT_KEY_TYPED: jint = 19;
// Fired once per Tao event-loop iteration after all in-flight events have
// been processed. Drives the JVM-side coroutine dispatcher pump
// (`TaoMainDispatcher`) so the Compose Recomposer can apply changes between
// platform events without spawning a worker thread.
pub(crate) const EVENT_MAIN_EVENTS_CLEARED: jint = 20;
// `a` and `b` carry x/y in physical pixels. Logical conversion is done on
// the JVM side using the cached scale factor.
pub(crate) const EVENT_MOVED: jint = 21;
pub(crate) const EVENT_WINDOW_READY: jint = 16; // a = width, b = height (logical)
// Scroll deltas come either as line counts (mouse wheel) or pixel deltas
// (trackpad). Compose's `MacOSCocoaConfig` (cf. compose-multiplatform-core)
// expects each kind to be shaped like AWT `MouseWheelEvent.preciseWheelRotation`,
// which has different scaling: lines map ≈ 1 notch, pixels map ≈ scrollingDelta/10.
// We split the event code so the JVM side can apply the right factor.
pub(crate) const EVENT_SCROLL_LINE: jint = 17; // a = dx * SCROLL_FIXED_SCALE, b = dy * SCROLL_FIXED_SCALE
pub(crate) const EVENT_SCROLL_PIXEL: jint = 18;

// Sub-pixel precision through the JNI int payload.
pub(crate) const SCROLL_FIXED_SCALE: f64 = 100.0;

// Trackpad gesture wire encoding (macOS only). The Rust dispatcher forwards
// these values verbatim from `macos/touchpad_gestures.m` to the JVM, where
// `TaoTrackpadGesture` / `TaoTrackpadPhase` decode them. The constants are
// kept here for documentation; `#[allow(dead_code)]` because the Rust path
// never matches against them — only the C side and Kotlin side do.
#[allow(dead_code)]
pub(crate) const TRACKPAD_GESTURE_MAGNIFY: jint = 0;
#[allow(dead_code)]
pub(crate) const TRACKPAD_GESTURE_ROTATE: jint = 1;
#[allow(dead_code)]
pub(crate) const TRACKPAD_GESTURE_SMART_MAGNIFY: jint = 2;

#[allow(dead_code)]
pub(crate) const TRACKPAD_PHASE_BEGAN: jint = 0;
#[allow(dead_code)]
pub(crate) const TRACKPAD_PHASE_CHANGED: jint = 1;
#[allow(dead_code)]
pub(crate) const TRACKPAD_PHASE_ENDED: jint = 2;
#[allow(dead_code)]
pub(crate) const TRACKPAD_PHASE_CANCELLED: jint = 3;

// Magnify deltas are small floats (≈0.01..0.5 per event); rotate deltas are
// degrees. A 10 000× fixed scale keeps four decimal places and stays in i32
// range for any plausible cumulative gesture magnitude.
pub(crate) const TRACKPAD_VALUE_FIXED_SCALE: f64 = 10_000.0;

// ── Touch event wire encoding (Linux only — Windows + macOS go via Tao) ──
//
// Linux touchscreen events are intercepted by `platform/linux/touch.rs` from
// GTK 3 (`GdkEventTouch`) and forwarded through a per-window callback. The
// dispatch sends the *full* set of currently-down pointers on every event so
// the JVM side can call `ComposeScene.sendPointerEvent` with a `pointers`
// list — Compose treats absence as release. `pressed_mask` carries one bit
// per pointer so the released-this-event finger can be reported with
// `pressed=false` while still being present in the array.

#[allow(dead_code)]
pub(crate) const TOUCH_EVENT_PRESS:   jint = 0;
#[allow(dead_code)]
pub(crate) const TOUCH_EVENT_MOVE:    jint = 1;
#[allow(dead_code)]
pub(crate) const TOUCH_EVENT_RELEASE: jint = 2;
#[allow(dead_code)]
pub(crate) const TOUCH_EVENT_CANCEL:  jint = 3;

pub(crate) const MOUSE_BUTTON_LEFT: jint = 0;
pub(crate) const MOUSE_BUTTON_RIGHT: jint = 1;
pub(crate) const MOUSE_BUTTON_MIDDLE: jint = 2;
pub(crate) const MOUSE_BUTTON_OTHER: jint = 3;

// ── User events posted from JNI calls into the event loop ─────────────────

#[derive(Debug)]
pub(crate) enum UserEvent {
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
    SetVisible { handle: u64, visible: bool },
    SetTitle { handle: u64, title: String },
    RequestRedraw { handle: u64 },
    RequestClose { handle: u64 },
    SetMaximized { handle: u64, maximized: bool },
    SetMinimized { handle: u64, minimized: bool },
    SetAlwaysOnTop { handle: u64, always_on_top: bool },
    SetFocusable { handle: u64, focusable: bool },
    Focus { handle: u64 },
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
    SetInnerSize { handle: u64, width: f64, height: f64 },
    SetOuterPosition { handle: u64, x: f64, y: f64 },
    SetFullscreen { handle: u64, fullscreen: bool },
    Exit,
}

// ── Dispatch helpers ──────────────────────────────────────────────────────

pub(crate) fn dispatch(handle: u64, code: jint, a: jint, b: jint) {
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
pub(crate) fn dispatch_key(
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

#[allow(clippy::too_many_arguments, dead_code)]
pub(crate) fn dispatch_trackpad_gesture(
    handle: u64,
    kind: jint,
    phase: jint,
    x_fixed: jint,
    y_fixed: jint,
    value_fixed: jint,
) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(guard) = EVENT_CALLBACK.lock() else { return };
    let Some(callback) = guard.as_ref() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
    let _ = env.call_method(
        callback.as_obj(),
        "onTrackpadGesture",
        "(JIIIII)V",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(kind),
            JValue::Int(phase),
            JValue::Int(x_fixed),
            JValue::Int(y_fixed),
            JValue::Int(value_fixed),
        ],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

pub(crate) fn handle_for(window_id: WindowId) -> Option<u64> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.iter()
        .find(|(_, w)| w.id() == window_id)
        .map(|(h, _)| *h)
}

pub(crate) fn mouse_button_code(b: MouseButton) -> jint {
    match b {
        MouseButton::Left => MOUSE_BUTTON_LEFT,
        MouseButton::Right => MOUSE_BUTTON_RIGHT,
        MouseButton::Middle => MOUSE_BUTTON_MIDDLE,
        _ => MOUSE_BUTTON_OTHER,
    }
}
