// Apple Events bridge.
//
// Installs an `NSAppleEventManager` handler for `kInternetEventClass /
// kAEGetURL`. Must be called *before* `nativeRunBlocking` so the cold-start
// URL (when the app is launched via a `nucleus://…` link) is delivered to
// our handler instead of being lost.
//
// Replaces `Desktop.setOpenURIHandler` (AWT) which is incompatible with the
// Tao backend on macOS — AWT's `Desktop.getDesktop()` boots a second NSApp.

use jni::objects::{JClass, JValue};
use jni::JNIEnv;

use crate::platform::macos::ffi::nucleus_tao_apple_events_install;
use crate::state::JAVA_VM;

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeAppleEventsInstall(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { nucleus_tao_apple_events_install() };
}

/// Called from `macos/apple_events.m` on the main thread when AppKit delivers
/// a `kAEGetURL` event. Forwards the UTF-8 URL to
/// `NativeTaoBridge.dispatchDeepLink(String)`.
#[no_mangle]
pub extern "C" fn nucleus_tao_apple_events_dispatch(utf8: *const u8, len: i32) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if utf8.is_null() || len <= 0 { return };
    let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
    let Ok(url) = std::str::from_utf8(slice) else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(url) else { return };
        let _ = env.call_static_method(
            class,
            "dispatchDeepLink",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr.into())],
        );
    }
}
