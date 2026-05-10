// Linux touch + touchpad-gesture bridge for the Tao backend.
//
// Implements the recommendation from `TOUCH_LINUX_RESEARCH_REQUEST.md` /
// `TOUCH_LINUX_RESEARCH_RESPONSE.md` — Stage 1 (touchscreen multi-touch +
// Wayland trackpad pinch/rotate). Sits next to `dnd.rs`, follows the same
// per-window register/revoke pattern, and dispatches through a JNI callback
// installed by `TaoComposeSceneHostLinux.attach()`.
//
// Rationale (short version of the research):
//
//   1. The drop target is the GtkWindow's bin_child — same widget `dnd.rs`
//      already targets. Toplevel would fight Mutter's titlebar drag-on-touch,
//      and the EGL child surface has an empty input region so events bubble
//      naturally to the bin_child.
//
//   2. Touchscreen multi-touch arrives via `GdkEventTouch` once we add
//      `GDK_TOUCH_MASK` to the bin_child. GDK already binds `wl_touch` on
//      Wayland and `XInput2` on X11/XWayland; we never bind those protocols
//      ourselves.
//
//   3. Trackpad pinch/rotate arrives via `GdkEventTouchpadPinch` once we add
//      `GDK_TOUCHPAD_GESTURE_MASK`. GTK 3's X11 backend does NOT emit this
//      (Mozilla bug 1581126) — pinch is Wayland-only on this backend, same
//      compromise Firefox shipped. Touchscreen 2-finger pinch on X11 still
//      works through the multi-touch path; only trackpad pinch on X11 is
//      out-of-scope.
//
//   4. `GdkEventTouchpadPinch` lives behind GTK's generic `event` signal
//      (no dedicated `touchpad-pinch-event` signal in GTK 3); we use one
//      `connect_event` handler that switches on event type and routes touch
//      and pinch from the same hook.
//
//   5. `GdkEventSequence*` is unique-per-finger across BEGIN→END but GDK
//      reuses the pointer value once a sequence ends. We translate to a
//      stable monotonic `u64` via a thread_local map keyed on the raw
//      sequence pointer; the entry is purged on END/CANCEL so a recycled
//      sequence pointer is correctly seen as a *new* finger.
//
//   6. Coordinates from `GdkEventTouch` are bin_child-local logical px;
//      we multiply by `gdk_window_get_scale_factor` to reach the physical
//      pixel space Compose expects (same transform `dnd.rs` uses).
//
//   7. GDK's `EventTouchpadPinch.scale` is *absolute* (1.0 at BEGIN, e.g.
//      2.0 at 2× zoom) and `angle_delta` is per-event radians. The macOS
//      JVM-side synth math (`TaoComposeSceneHost.onTrackpadGesture`) expects
//      per-event ratio for MAGNIFY and per-event degrees for ROTATE; we
//      convert here so the JVM path is identical across platforms.
//
// Threading: every entry point runs on the GTK main thread (= Tao event
// loop = Compose dispatcher thread). All GTK signal callbacks fire on the
// same thread. No cross-thread synchronisation needed.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use jni::objects::{GlobalRef, JClass, JObject, JValue};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use gtk::gdk::{self, EventMask, EventTouch, EventTouchpadPinch, EventType};
use gtk::glib;
use gtk::prelude::*;
use tao::platform::unix::WindowExtUnix;

use crate::events::{
    CURSOR_FIXED_SCALE, TRACKPAD_GESTURE_MAGNIFY, TRACKPAD_GESTURE_ROTATE,
    TRACKPAD_PHASE_BEGAN, TRACKPAD_PHASE_CANCELLED, TRACKPAD_PHASE_CHANGED,
    TRACKPAD_PHASE_ENDED, TRACKPAD_VALUE_FIXED_SCALE,
    TOUCH_EVENT_CANCEL, TOUCH_EVENT_MOVE, TOUCH_EVENT_PRESS, TOUCH_EVENT_RELEASE,
};
use crate::state::{JAVA_VM, WINDOWS};

// GdkTouchpadGesturePhase enum values (gdk-sys exposes them as i32).
const GDK_TOUCHPAD_PHASE_BEGIN:  i32 = 0;
const GDK_TOUCHPAD_PHASE_UPDATE: i32 = 1;
const GDK_TOUCHPAD_PHASE_END:    i32 = 2;
const GDK_TOUCHPAD_PHASE_CANCEL: i32 = 3;

// ── Per-window registration ────────────────────────────────────────────────

#[allow(dead_code)]
struct Registration {
    callback: GlobalRef,
    /// Single `event` signal handler covering both touch and touchpad pinch.
    event_id: glib::SignalHandlerId,
    /// The toplevel `ApplicationWindow` the handler is attached to; kept
    /// alive so the SignalHandlerId stays valid until revoke.
    window: gtk::ApplicationWindow,
    /// State shared with the closure.
    state: Rc<TouchState>,
}

struct TouchState {
    /// Maps a raw `GdkEventSequence*` (cast to usize) to a stable monotonic
    /// 64-bit id we hand to Compose. Entry is added on TOUCH_BEGIN and
    /// removed on TOUCH_END/CANCEL — a future sequence at the same address
    /// is therefore correctly assigned a fresh id.
    seq_to_id: RefCell<HashMap<usize, u64>>,
    next_id: Cell<u64>,
    /// Currently-down fingers. Iteration order is preserved by HashMap +
    /// our explicit `order` Vec to keep `pressed_mask` bits stable across
    /// events for the JVM side.
    active: RefCell<HashMap<u64, ActivePointer>>,
    /// Stable iteration order for `active`. We push on Press, retain on
    /// Move, and remove on Release/Cancel after the dispatch.
    order: RefCell<Vec<u64>>,

    /// Cumulative scale at the start of the current touchpad pinch
    /// session. Compose-side expects a per-event *ratio* delta
    /// (`gestureScale *= 1 + value`), so we convert from GDK's absolute
    /// scale on each event: `value = (current_scale / last_scale) - 1`.
    /// `None` when no pinch is active.
    pinch_last_scale: Cell<Option<f64>>,
}

struct ActivePointer {
    x_fixed: i64,
    y_fixed: i64,
}

thread_local! {
    static REGISTRATIONS: RefCell<HashMap<u64, Registration>> = RefCell::new(HashMap::new());
}

// ── Helpers ────────────────────────────────────────────────────────────────

fn with_window<R, F: FnOnce(&tao::window::Window) -> R>(handle: u64, f: F) -> Option<R> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.get(&handle).map(f)
}

/// Translates `(x, y)` reported by a GTK event on the *toplevel* GtkWindow
/// (whose GdkWindow includes the GTK CSD headerbar on Linux) to physical
/// pixels of the bin-child content area — the coordinate space the Compose
/// scene operates in. Same transform `dnd.rs::translate_to_content_phys`
/// applies for drag-motion coordinates.
///
/// We attach to the toplevel because Tao 0.35's bin_child is a no-window
/// GtkBox: it has no GdkWindow of its own, so `connect_event` on it never
/// fires (`bin_child.window()` returns the toplevel's window, not a child).
fn to_physical_fixed(window: &gtk::ApplicationWindow, x: f64, y: f64) -> (i64, i64) {
    let scale = window
        .window()
        .map(|w| w.scale_factor())
        .unwrap_or(1)
        .max(1) as f64;
    let (lx, ly) = match window.child() {
        Some(child) => window.translate_coordinates(&child, x as i32, y as i32)
            .map(|(cx, cy)| (cx as f64, cy as f64))
            .unwrap_or((x, y)),
        None => (x, y),
    };
    let xp = lx * scale * CURSOR_FIXED_SCALE;
    let yp = ly * scale * CURSOR_FIXED_SCALE;
    (xp as i64, yp as i64)
}

// ── JNI dispatch helpers ───────────────────────────────────────────────────

/// Dispatch a touch event to the per-window JVM callback. The full active
/// pointer set is sent every call; `pressed_mask` bit `i` corresponds to
/// `ids[i]` and is `0` for the finger that just released (so Compose can
/// observe the transition in a single sendPointerEvent call).
fn dispatch_touch(
    handle: u64,
    callback: &GlobalRef,
    event_type: jint,
    state: &TouchState,
    released_id: Option<u64>,
) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };

    let order = state.order.borrow();
    let active = state.active.borrow();
    let count = order.len() as i32;
    if count == 0 && event_type != TOUCH_EVENT_CANCEL {
        return;
    }

    let mut ids = Vec::<jlong>::with_capacity(order.len());
    let mut xs = Vec::<jlong>::with_capacity(order.len());
    let mut ys = Vec::<jlong>::with_capacity(order.len());
    let mut pressed_mask: jlong = 0;
    for (i, id) in order.iter().enumerate() {
        ids.push(*id as jlong);
        let p = match active.get(id) {
            Some(p) => p,
            None => continue,
        };
        xs.push(p.x_fixed);
        ys.push(p.y_fixed);
        let is_released = released_id.map(|r| r == *id).unwrap_or(false);
        if !is_released {
            pressed_mask |= 1i64 << i;
        }
    }

    let Ok(ids_arr) = env.new_long_array(count) else { return };
    if env.set_long_array_region(&ids_arr, 0, &ids).is_err() { return }
    let Ok(xs_arr) = env.new_long_array(count) else { return };
    if env.set_long_array_region(&xs_arr, 0, &xs).is_err() { return }
    let Ok(ys_arr) = env.new_long_array(count) else { return };
    if env.set_long_array_region(&ys_arr, 0, &ys).is_err() { return }

    let res = env.call_method(
        callback.as_obj(),
        "onTouchEvent",
        "(JII[J[J[JJ)V",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(event_type),
            JValue::Int(count),
            JValue::Object(&ids_arr.into()),
            JValue::Object(&xs_arr.into()),
            JValue::Object(&ys_arr.into()),
            JValue::Long(pressed_mask),
        ],
    );
    let _ = res;
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

/// Dispatch a trackpad gesture (MAGNIFY or ROTATE) to the per-window
/// callback. Same wire shape as the macOS path: kind + phase + position +
/// per-event delta value, all already converted to fixed-point on the Rust
/// side so the JVM-side synth math is platform-independent.
fn dispatch_trackpad(
    handle: u64,
    callback: &GlobalRef,
    kind: jint,
    phase: jint,
    x_fixed: i64,
    y_fixed: i64,
    value_fixed: i64,
) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else { return };

    let _ = env.call_method(
        callback.as_obj(),
        "onTrackpadGesture",
        "(JIIJJJ)V",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(kind),
            JValue::Int(phase),
            JValue::Long(x_fixed),
            JValue::Long(y_fixed),
            JValue::Long(value_fixed),
        ],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

// ── Touchscreen handling ──────────────────────────────────────────────────

fn handle_touch(
    handle: u64,
    callback: &GlobalRef,
    state: &TouchState,
    window: &gtk::ApplicationWindow,
    ev: &EventTouch,
) {
    let Some(seq) = ev.event_sequence() else {
        // GDK emits TOUCH_* without a sequence in some emulated paths; we
        // can't track per-finger identity without it, so drop the event
        // rather than mis-attribute it.
        return;
    };
    let seq_ptr = glib::translate::ToGlibPtr::<*mut gdk::ffi::GdkEventSequence>::to_glib_none(&seq).0 as usize;

    let (x, y) = ev.position();
    let (x_fixed, y_fixed) = to_physical_fixed(window, x, y);

    let kind = ev.event_type();
    match kind {
        EventType::TouchBegin => {
            let id = {
                let mut next = state.next_id.get();
                let assigned = next;
                next += 1;
                state.next_id.set(next);
                state.seq_to_id.borrow_mut().insert(seq_ptr, assigned);
                assigned
            };
            state.active.borrow_mut().insert(
                id,
                ActivePointer { x_fixed, y_fixed },
            );
            state.order.borrow_mut().push(id);
            dispatch_touch(handle, callback, TOUCH_EVENT_PRESS, state, None);
        }
        EventType::TouchUpdate => {
            let Some(&id) = state.seq_to_id.borrow().get(&seq_ptr) else { return };
            if let Some(p) = state.active.borrow_mut().get_mut(&id) {
                p.x_fixed = x_fixed;
                p.y_fixed = y_fixed;
            }
            dispatch_touch(handle, callback, TOUCH_EVENT_MOVE, state, None);
        }
        EventType::TouchEnd | EventType::TouchCancel => {
            let id_opt = state.seq_to_id.borrow_mut().remove(&seq_ptr);
            let Some(id) = id_opt else { return };
            // Final position update so the Release lands at the correct spot.
            if let Some(p) = state.active.borrow_mut().get_mut(&id) {
                p.x_fixed = x_fixed;
                p.y_fixed = y_fixed;
            }
            let event_type = if kind == EventType::TouchCancel {
                TOUCH_EVENT_CANCEL
            } else {
                TOUCH_EVENT_RELEASE
            };
            dispatch_touch(handle, callback, event_type, state, Some(id));
            // Purge from active set after the dispatch so the JVM saw the
            // released finger one last time with pressed=false.
            state.active.borrow_mut().remove(&id);
            state.order.borrow_mut().retain(|i| *i != id);
        }
        _ => {}
    }
}

// ── Touchpad pinch handling ───────────────────────────────────────────────

fn handle_touchpad_pinch(
    handle: u64,
    callback: &GlobalRef,
    state: &TouchState,
    window: &gtk::ApplicationWindow,
    ev: &EventTouchpadPinch,
) {
    let raw = ev.as_ref();
    let phase = raw.phase as i32;
    let scale = ev.scale();
    let angle_delta_rad = ev.angle_delta();
    let (x, y) = ev.position();
    let (x_fixed, y_fixed) = to_physical_fixed(window, x, y);

    let macos_phase: jint = match phase {
        GDK_TOUCHPAD_PHASE_BEGIN  => TRACKPAD_PHASE_BEGAN,
        GDK_TOUCHPAD_PHASE_UPDATE => TRACKPAD_PHASE_CHANGED,
        GDK_TOUCHPAD_PHASE_END    => TRACKPAD_PHASE_ENDED,
        GDK_TOUCHPAD_PHASE_CANCEL => TRACKPAD_PHASE_CANCELLED,
        _ => return,
    };

    // ── Magnify ────────────────────────────────────────────────────────
    //
    // Compose-side expects a per-event ratio: `gestureScale *= (1 + value)`.
    // GDK gives us absolute scale (1.0 at BEGIN, e.g. 2.0 at 2× zoom), so
    // `value = (current_scale / last_scale) - 1` reproduces the macOS
    // semantics exactly. On BEGIN we anchor `last_scale = 1.0` and emit a
    // 0-delta to start the gesture.
    match phase {
        GDK_TOUCHPAD_PHASE_BEGIN => {
            state.pinch_last_scale.set(Some(scale.max(1e-6)));
            dispatch_trackpad(
                handle,
                callback,
                TRACKPAD_GESTURE_MAGNIFY,
                macos_phase,
                x_fixed,
                y_fixed,
                0,
            );
        }
        GDK_TOUCHPAD_PHASE_UPDATE => {
            let last = state.pinch_last_scale.get().unwrap_or(scale.max(1e-6));
            let cur = scale.max(1e-6);
            let value = (cur / last) - 1.0;
            state.pinch_last_scale.set(Some(cur));
            let value_fixed = (value * TRACKPAD_VALUE_FIXED_SCALE) as i64;
            dispatch_trackpad(
                handle,
                callback,
                TRACKPAD_GESTURE_MAGNIFY,
                macos_phase,
                x_fixed,
                y_fixed,
                value_fixed,
            );
        }
        GDK_TOUCHPAD_PHASE_END | GDK_TOUCHPAD_PHASE_CANCEL => {
            state.pinch_last_scale.set(None);
            dispatch_trackpad(
                handle,
                callback,
                TRACKPAD_GESTURE_MAGNIFY,
                macos_phase,
                x_fixed,
                y_fixed,
                0,
            );
        }
        _ => {}
    }

    // ── Rotate ─────────────────────────────────────────────────────────
    //
    // GDK reports `angle_delta` in radians per event. macOS NSEvent.rotation
    // is degrees per event and the JVM-side synth code applies the
    // radian-to-degree conversion itself; we feed degrees so the math is
    // identical. Skip when there's no rotation component to avoid spamming
    // 0-deltas (the user might pinch without rotating).
    if angle_delta_rad.abs() > f64::EPSILON || phase == GDK_TOUCHPAD_PHASE_BEGIN || phase == GDK_TOUCHPAD_PHASE_END || phase == GDK_TOUCHPAD_PHASE_CANCEL {
        let degrees = angle_delta_rad * (180.0 / std::f64::consts::PI);
        let value_fixed = (degrees * TRACKPAD_VALUE_FIXED_SCALE) as i64;
        dispatch_trackpad(
            handle,
            callback,
            TRACKPAD_GESTURE_ROTATE,
            macos_phase,
            x_fixed,
            y_fixed,
            value_fixed,
        );
    }
}

// ── Install / revoke ──────────────────────────────────────────────────────

fn install(handle: u64, callback: GlobalRef) -> Result<(), &'static str> {
    let window: gtk::ApplicationWindow =
        with_window(handle, |w| w.gtk_window().clone()).ok_or("window not found")?;

    // Tao 0.35's bin_child is a no-window GtkBox — `connect_event` on it
    // never fires because it has no GdkWindow of its own. Attach to the
    // toplevel ApplicationWindow instead (its GdkWindow is the one that
    // receives all input from the compositor). Coordinates are then
    // translated to bin-child-local space via `to_physical_fixed`.
    window.add_events(EventMask::TOUCH_MASK | EventMask::TOUCHPAD_GESTURE_MASK);
    if let Some(gw) = window.window() {
        let mask = gw.events()
            | EventMask::TOUCH_MASK
            | EventMask::TOUCHPAD_GESTURE_MASK;
        gw.set_events(mask);
    }

    let state = Rc::new(TouchState {
        seq_to_id: RefCell::new(HashMap::new()),
        next_id: Cell::new(1),
        active: RefCell::new(HashMap::new()),
        order: RefCell::new(Vec::new()),
        pinch_last_scale: Cell::new(None),
    });

    let cb = callback.clone();
    let state_for_cb = Rc::clone(&state);
    let window_for_cb = window.clone();
    let event_id = window.connect_event(move |_w, ev| {
        match ev.event_type() {
            EventType::TouchBegin
            | EventType::TouchUpdate
            | EventType::TouchEnd
            | EventType::TouchCancel => {
                if let Ok(touch) = ev.clone().downcast::<EventTouch>() {
                    handle_touch(handle, &cb, &state_for_cb, &window_for_cb, &touch);
                    return glib::Propagation::Stop;
                }
            }
            EventType::TouchpadPinch => {
                if let Ok(pe) = ev.clone().downcast::<EventTouchpadPinch>() {
                    handle_touchpad_pinch(handle, &cb, &state_for_cb, &window_for_cb, &pe);
                    return glib::Propagation::Stop;
                }
            }
            _ => {}
        }
        glib::Propagation::Proceed
    });

    let registration = Registration {
        callback,
        event_id,
        window: window.clone(),
        state,
    };
    REGISTRATIONS.with(|m| {
        m.borrow_mut().insert(handle, registration);
    });
    Ok(())
}

fn revoke(handle: u64) {
    let Some(reg) = REGISTRATIONS.with(|m| m.borrow_mut().remove(&handle)) else {
        return;
    };
    reg.window.disconnect(reg.event_id);
    drop(reg.callback); // explicit DeleteGlobalRef
}

// ── JNI exports ────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoLinuxTouchBridge_nativeRegister(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let global = match env.new_global_ref(&callback) {
        Ok(g) => g,
        Err(_) => return -2,
    };
    match install(handle as u64, global) {
        Ok(()) => 0,
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoLinuxTouchBridge_nativeRevoke(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    revoke(handle as u64);
    0
}
