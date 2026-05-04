// Invisible NSTextView used as the IME input host. AppKit needs a real
// NSTextInputClient-conformant first responder to deliver IME events; the
// JVM-side render layer doesn't qualify, so we attach a 1×1 NSTextView
// behind the Compose canvas and proxy keyboard focus through it.

use jni::objects::JClass;
use jni::sys::{jboolean, jlong, JNI_FALSE};
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::platform::macos::ffi::{
    nucleus_tao_attach_text_overlay, nucleus_tao_focus_text_overlay,
};
use crate::state::WINDOWS;

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

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeFocusTextOverlay(
    _env: JNIEnv,
    _class: JClass,
    focused: jboolean,
) {
    unsafe { nucleus_tao_focus_text_overlay(if focused != JNI_FALSE { 1 } else { 0 }) };
}
