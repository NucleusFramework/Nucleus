// NSView pointer JNI export. The JVM uses this to attach a CAMetalLayer.
//
// winit ships only the NSView pointer through `raw-window-handle 0.6`; the
// NSWindow is derived ObjC-side via `[view window]` when needed.

use jni::objects::JClass;
use jni::sys::jlong;
use jni::JNIEnv;

use winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use winit::window::Window;

use crate::state::WINDOWS;

/// Returns the underlying `NSView` pointer (cast to `i64`) for use by ObjC
/// helpers. Returns `0` if the handle cannot be resolved.
pub(crate) fn ns_view_pointer(window: &Window) -> i64 {
    let Ok(handle) = window.window_handle() else { return 0 };
    match handle.as_raw() {
        RawWindowHandle::AppKit(h) => h.ns_view.as_ptr() as i64,
        _ => 0,
    }
}

/// Returns the underlying NSView pointer so the JVM can attach a CAMetalLayer.
/// Must be called on the macOS main thread (i.e. from a winit event handler).
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
    ns_view_pointer(window) as jlong
}
