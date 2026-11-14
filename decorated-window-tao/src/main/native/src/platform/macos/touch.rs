// Direct touch bridge (#892).
//
// `macos/direct_touch.m` attaches a contact-observing NSGestureRecognizer to
// every tao content view and fires this `extern "C"` callback once per finger
// update. We resolve the NSWindow back to its JVM handle and forward the
// contact through `EventCallback.onTouchInput`, the wire the Windows
// touchscreen already uses, so the Compose host aggregates it the same way.

use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::events::{dispatch_touch_input, CURSOR_FIXED_SCALE, POINTER_KIND_TOUCH};
use crate::platform::macos::ffi::{nucleus_tao_attach_direct_touch, nucleus_tao_has_direct_touch};
use crate::platform::macos::trackpad::handle_for_ns_window;
use crate::state::WINDOWS;

/// `TaoTouchEvent.FORCE_UNKNOWN`: AppKit reports no pressure for a finger.
const FORCE_UNKNOWN: jint = -1;

pub(crate) extern "C" fn direct_touch_callback(
    ns_window_ptr: i64,
    phase: i32,
    id: i64,
    x_px: f64,
    y_px: f64,
) {
    let Some(handle) = handle_for_ns_window(ns_window_ptr) else {
        return;
    };
    dispatch_touch_input(
        handle,
        phase as jint,
        id as u64,
        (x_px * CURSOR_FIXED_SCALE) as jint,
        (y_px * CURSOR_FIXED_SCALE) as jint,
        FORCE_UNKNOWN,
        POINTER_KIND_TOUCH,
    );
}

/// Attaches the direct touch forwarder to [handle]'s content view, once. The
/// host calls it when it starts consuming `onTouchInput`: a window nobody
/// reads touches from keeps AppKit's mouse emulation. Event-loop thread only.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTouchBridge_nativeAttachDirectTouch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(window) = guard.as_ref().and_then(|map| map.get(&(handle as u64))) else {
        return;
    };
    unsafe { nucleus_tao_attach_direct_touch(window.ns_view() as i64) };
}

/// Whether [handle]'s content view carries the direct touch forwarder (e2e).
/// Event-loop thread only.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTouchBridge_nativeHasDirectTouch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Ok(guard) = WINDOWS.lock() else {
        return JNI_FALSE;
    };
    let Some(window) = guard.as_ref().and_then(|map| map.get(&(handle as u64))) else {
        return JNI_FALSE;
    };
    if unsafe { nucleus_tao_has_direct_touch(window.ns_view() as i64) } != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
