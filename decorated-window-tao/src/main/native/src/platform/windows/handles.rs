// HWND handle JNI export. The JVM-side WGL helper consumes this to attach a
// rendering context, and the custom decoration helper installs its WndProc on
// the same HWND.

use jni::objects::JClass;
use jni::sys::jlong;
use jni::JNIEnv;

use tao::platform::windows::WindowExtWindows;

use crate::state::WINDOWS;

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
    window.hwnd() as isize as jlong
}
