// IME input-context activation and caret-rect plumbing.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::platform::macos::ffi::{
    nucleus_tao_activate_input_context, nucleus_tao_current_input_source_id,
    nucleus_tao_kotoeri_available, nucleus_tao_kotoeri_restore, nucleus_tao_kotoeri_select,
    nucleus_tao_post_key_to_view, nucleus_tao_set_ime_local_rect,
};
use crate::state::{EVENT_CALLBACK, JAVA_VM, WINDOWS};

fn ns_view_for_handle(handle: jlong) -> Option<i64> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&(handle as u64))?;
    Some(window.ns_view() as i64)
}

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

/// PressAndHold picked an accent. Compose Desktop replaces the already-
/// committed base letter via `TextEditingScope`, not a Backspace key.
pub(crate) extern "C" fn ime_replace_commit_callback(ns_view: i64, utf8: *const c_char) {
    if utf8.is_null() {
        return;
    }
    let Some(handle) = handle_for_ns_view(ns_view) else {
        return;
    };
    let text = unsafe { CStr::from_ptr(utf8) }.to_string_lossy();
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(guard) = EVENT_CALLBACK.lock() else {
        return;
    };
    let Some(callback) = guard.as_ref() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return;
    };
    let Ok(jstr) = env.new_string(text.as_ref()) else {
        return;
    };
    let _ = env.call_method(
        callback.as_obj(),
        "onImeReplaceCommit",
        "(JLjava/lang/String;)V",
        &[JValue::Long(handle as jlong), JValue::Object(&jstr.into())],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
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
        unsafe { nucleus_tao_set_ime_local_rect(window.ns_view() as i64, lx, ly, lw, lh) };
    }
}

/// Headful e2e: Japanese Kotoeri (romaji/hiragana) is installed on this Mac.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsKotoeriAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if unsafe { nucleus_tao_kotoeri_available() } != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Headful e2e: enable+select Kotoeri Hiragana for [handle]'s view.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsKotoeriSelect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(ns_view) = ns_view_for_handle(handle) else {
        return JNI_FALSE;
    };
    if unsafe { nucleus_tao_kotoeri_select(ns_view) } != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Headful e2e: restore the keyboard input source saved by [nativeMacOsKotoeriSelect].
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsKotoeriRestore(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { nucleus_tao_kotoeri_restore() };
}

/// Headful e2e: deliver a real AppKit `keyDown:` / `keyUp:` to TaoView.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsPostKeyToView(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_code: jint,
    characters: JString,
    down: jboolean,
) -> jboolean {
    let Some(ns_view) = ns_view_for_handle(handle) else {
        return JNI_FALSE;
    };
    let text: String = match env.get_string(&characters) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let Ok(cstr) = CString::new(text) else {
        return JNI_FALSE;
    };
    let ok = unsafe {
        nucleus_tao_post_key_to_view(
            ns_view,
            key_code,
            cstr.as_ptr(),
            if down != JNI_FALSE { 1 } else { 0 },
        )
    };
    if ok != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Headful e2e: id of the current TIS keyboard input source.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsCurrentInputSource(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let mut buf = [0u8; 256];
    let ok = unsafe {
        nucleus_tao_current_input_source_id(buf.as_mut_ptr() as *mut c_char, buf.len() as i32)
    };
    let id = if ok != 0 {
        CStr::from_bytes_until_nul(&buf)
            .ok()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default()
    } else {
        String::new()
    };
    env.new_string(&id)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
