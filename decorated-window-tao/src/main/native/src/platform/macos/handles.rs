// NSView / NSWindow pointer JNI exports.
//
// - NSView: JVM attaches a CAMetalLayer for Compose rendering.
// - NSWindow: dialog parenting (FileKit sheets via beginSheetModalForWindow:).
//   An NSView is not a valid sheet parent — callers must use the NSWindow.

use jni::objects::JClass;
use jni::sys::jlong;
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::state::WINDOWS;

/// Returns the underlying NSView pointer so the JVM can attach a CAMetalLayer.
/// Must be called on the macOS main thread (i.e. from a Tao event handler).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeNsViewHandle(
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

/// Returns the underlying NSWindow pointer for dialog parenting (FileKit sheets,
/// `beginSheetModalForWindow:`, etc.). Distinct from the NSView returned by
/// [nativeNsViewHandle]. Safe while the Tao window is alive; 0 if unknown.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeNsWindowHandle(
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
    window.ns_window() as jlong
}

/// Headful e2e only: present a real `NSOpenPanel` sheet on [ns_window], verify
/// it attaches (and that [ns_view] lives in that window's hierarchy), then
/// cancel. See `macos/dialog_parent.m` for return codes.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeMacOsProbeSheetParent(
    _env: JNIEnv,
    _class: JClass,
    ns_window: jlong,
    ns_view: jlong,
) -> jni::sys::jint {
    use crate::platform::macos::ffi::nucleus_tao_probe_sheet_parent;
    unsafe { nucleus_tao_probe_sheet_parent(ns_window as i64, ns_view as i64) }
}
