// NSView pointer JNI export. The JVM uses this to attach a CAMetalLayer.

use jni::objects::JClass;
use jni::sys::jlong;
use jni::JNIEnv;

use tao::platform::macos::WindowExtMacOS;

use crate::state::WINDOWS;

/// Returns the underlying NSView pointer so the JVM can attach a CAMetalLayer.
/// Must be called on the macOS main thread (i.e. from a Tao event handler).
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
    window.ns_view() as jlong
}
