// IME input-context activation and caret-rect plumbing.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use jni::objects::{JClass, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::platform::macos::ffi::{
    nucleus_tao_activate_input_context, nucleus_tao_current_input_source_id,
    nucleus_tao_inject_insert_text, nucleus_tao_inject_marked_text, nucleus_tao_kotoeri_available,
    nucleus_tao_kotoeri_restore, nucleus_tao_kotoeri_select, nucleus_tao_post_key_to_view,
    nucleus_tao_query_text_input_client, nucleus_tao_set_ime_document,
    nucleus_tao_set_ime_local_rect,
};
use crate::state::WINDOWS;

fn ns_view_for_handle(handle: jlong) -> Option<i64> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&(handle as u64))?;
    Some(window.ns_view() as i64)
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

/// Pushes the focused field's committed text (a bounded window), selection
/// and window offset to the swizzled `NSTextInputClient` cache — all offsets
/// UTF-16 and document-absolute, the same space `selectedRange` reports and
/// `insertText:replacementRange:` receives. Chromium parity: the async
/// renderer→browser selection/±100-chars push (`setTextSelectionText:`).
/// Negative [sel_start] invalidates the cache (no focused field).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetImeDocument(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    offset: jlong,
    sel_start: jlong,
    sel_end: jlong,
) {
    let Some(ns_view) = ns_view_for_handle(handle) else {
        return;
    };
    // Raw `GetStringRegion` rather than `get_string`: the safe wrapper goes
    // through modified UTF-8 and a lossy decode, which both costs two extra
    // copies per keystroke and can change the UTF-16 length (unpaired
    // surrogates), desynchronising the offsets the JVM computed. This reads
    // the JVM's UTF-16 verbatim, in one copy.
    let raw_env = env.get_raw();
    let jstr = text.as_raw();
    if raw_env.is_null() || jstr.is_null() {
        return;
    }
    let utf16: Vec<u16> = unsafe {
        let Some(get_length) = (**raw_env).GetStringLength else {
            return;
        };
        let len = get_length(raw_env, jstr);
        if len < 0 {
            return;
        }
        let mut buf = vec![0u16; len as usize];
        if len > 0 {
            let Some(get_region) = (**raw_env).GetStringRegion else {
                return;
            };
            get_region(raw_env, jstr, 0, len, buf.as_mut_ptr());
        }
        buf
    };
    unsafe {
        nucleus_tao_set_ime_document(
            ns_view,
            utf16.as_ptr(),
            utf16.len() as i64,
            offset,
            sel_start,
            sel_end,
        );
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
/// [autorepeat] marks the event as a key repeat (held key).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsPostKeyToView(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_code: jint,
    characters: JString,
    down: jboolean,
    autorepeat: jboolean,
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
            if autorepeat != JNI_FALSE { 1 } else { 0 },
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

/// Headful e2e: one snapshot. Fills [ranges_out] with 5×i64
/// (`marked loc/len`, `selected loc/len`, `characterIndex`; `NSNotFound` is
/// `-1`) and returns the marked-range substring.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsQueryTextInputClient(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ranges_out: jlongArray,
) -> jni::sys::jstring {
    let mut ranges = [-1i64; 5];
    let mut buf = [0u8; 4096];
    if let Some(ns_view) = ns_view_for_handle(handle) {
        unsafe {
            nucleus_tao_query_text_input_client(
                ns_view,
                ranges.as_mut_ptr(),
                buf.as_mut_ptr() as *mut c_char,
                buf.len() as i32,
            );
        }
    }
    let arr = unsafe { JLongArray::from_raw(ranges_out) };
    if env.get_array_length(&arr).unwrap_or(0) >= 5 {
        let _ = env.set_long_array_region(&arr, 0, &ranges);
    }
    let text = CStr::from_bytes_until_nul(&buf)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    env.new_string(&text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Headful e2e: `setMarkedText:selectedRange:replacementRange:` on TaoView.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsInjectMarkedText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    selected_location: jint,
    selected_length: jint,
) -> jboolean {
    let Some(ns_view) = ns_view_for_handle(handle) else {
        return JNI_FALSE;
    };
    let utf8: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let Ok(cstr) = CString::new(utf8) else {
        return JNI_FALSE;
    };
    let ok = unsafe {
        nucleus_tao_inject_marked_text(ns_view, cstr.as_ptr(), selected_location, selected_length)
    };
    if ok != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Headful e2e: `insertText:replacementRange:` on TaoView. A negative
/// [rr_loc] injects `{NSNotFound, 0}` (ordinary typing); a non-negative one
/// replays the accent-picker replacement commit.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsInjectInsertText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    rr_loc: jlong,
    rr_len: jlong,
) -> jboolean {
    let Some(ns_view) = ns_view_for_handle(handle) else {
        return JNI_FALSE;
    };
    let utf8: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let Ok(cstr) = CString::new(utf8) else {
        return JNI_FALSE;
    };
    let ok = unsafe { nucleus_tao_inject_insert_text(ns_view, cstr.as_ptr(), rr_loc, rr_len) };
    if ok != 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
