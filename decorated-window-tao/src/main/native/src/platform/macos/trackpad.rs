// Trackpad gesture callback bridge.
//
// `macos/touchpad_gestures.m` installs a global NSEvent monitor that fires this
// `extern "C"` callback for every NSEventTypeMagnify / NSEventTypeRotate /
// NSEventTypeSmartMagnify event. We resolve the NSWindow pointer back to a JVM
// window handle and forward the gesture to Kotlin via
// `EventCallback.onTrackpadGesture`.

use jni::sys::jint;

use tao::platform::macos::WindowExtMacOS;

use crate::events::{
    dispatch_trackpad_gesture, CURSOR_FIXED_SCALE, TRACKPAD_VALUE_FIXED_SCALE,
};
use crate::state::WINDOWS;

fn handle_for_ns_window(ns_window_ptr: i64) -> Option<u64> {
    if ns_window_ptr == 0 {
        return None;
    }
    let target = ns_window_ptr as usize;
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.iter()
        .find(|(_, w)| w.ns_window() as usize == target)
        .map(|(h, _)| *h)
}

pub(crate) extern "C" fn trackpad_gesture_callback(
    ns_window_ptr: i64,
    kind: i32,
    phase: i32,
    x_px: f64,
    y_px: f64,
    value: f64,
) {
    let Some(handle) = handle_for_ns_window(ns_window_ptr) else { return };
    dispatch_trackpad_gesture(
        handle,
        kind as jint,
        phase as jint,
        (x_px * CURSOR_FIXED_SCALE) as jint,
        (y_px * CURSOR_FIXED_SCALE) as jint,
        (value * TRACKPAD_VALUE_FIXED_SCALE) as jint,
    );
}
