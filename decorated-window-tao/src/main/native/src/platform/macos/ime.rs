// IME input-context activation and caret-rect plumbing.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::events::{dispatch_key, EVENT_KEY_DOWN, EVENT_KEY_UP};
use crate::keymap;
use crate::platform::macos::ffi::{
    nucleus_tao_activate_input_context, nucleus_tao_set_ime_local_rect,
};
use crate::state::WINDOWS;

fn handle_for_ns_view(ns_view_ptr: i64) -> Option<u64> {
    if ns_view_ptr == 0 {
        return None;
    }
    let target = ns_view_ptr as usize;
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.iter()
        .find(|(_, w)| w.ns_view() as usize == target)
        .map(|(h, _)| *h)
}

/// Called from the ObjC `insertText:` swizzle when PressAndHold replaces a
/// previously committed character (e → é). Emits Backspace to Compose so
/// the next `ReceivedImeText` overwrites instead of appending.
pub(crate) extern "C" fn ime_delete_previous_callback(ns_view: i64, count: i32) {
    let Some(handle) = handle_for_ns_view(ns_view) else {
        return;
    };
    let n = count.clamp(0, 16);
    for _ in 0..n {
        dispatch_key(
            handle,
            EVENT_KEY_DOWN,
            8, // AWT VK_BACK_SPACE
            keymap::LOC_STANDARD,
            0,
            0,
        );
        dispatch_key(
            handle,
            EVENT_KEY_UP,
            8,
            keymap::LOC_STANDARD,
            0,
            0,
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeActivateInputContext(
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

/// Pushes the caret rectangle in *window-local physical pixels* (top-left origin)
/// to native. The ObjC side converts to Cocoa screen coordinates using
/// `NSView.convertRect:toView:` + `NSWindow.convertRectToScreen:`, then stores
/// the rect for our swizzled `firstRectForCharacterRange:` to return.
///
/// Tao's stock `firstRectForCharacterRange:` returns size 0×0, which prevents
/// native candidate windows from following the caret.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetImeRect(
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
