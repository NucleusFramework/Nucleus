// HWND handle JNI export. The JVM-side WGL helper consumes this to attach a
// rendering context, and the custom decoration helper installs its WndProc on
// the same HWND.
//
// winit exposes the HWND through `raw-window-handle 0.6`'s
// `RawWindowHandle::Win32`; we re-export it as a plain `jlong` so the JVM
// side keeps its existing pointer-to-int contract.

use jni::objects::JClass;
use jni::sys::jlong;
use jni::JNIEnv;

use winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use winit::window::Window;

use crate::state::WINDOWS;

/// Returns the underlying `HWND` (cast to `i64`) for the given winit window,
/// or `0` if the handle cannot be resolved.
pub(crate) fn hwnd_pointer(window: &Window) -> i64 {
    let Ok(handle) = window.window_handle() else { return 0 };
    match handle.as_raw() {
        RawWindowHandle::Win32(h) => h.hwnd.get() as i64,
        _ => 0,
    }
}

/// Returns the underlying HWND so the JVM can attach a WGL context and apply
/// custom decoration via the `nucleus_tao_windows_deco` helper.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeHwndHandle(
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
    hwnd_pointer(window) as jlong
}
