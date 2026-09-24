// Event-loop liveness probe (#643).
//
// `IsHungAppWindow` is a pure query of state the OS already maintains — it is
// what the shell itself reads to decide whether to ghost a window. It sends
// nothing to the owning thread, so probing costs the event loop exactly
// nothing and, unlike a `SendMessageTimeout(WM_NULL)` probe, cannot deliver an
// inline sent message into a `PeekMessageW` the loop makes (the re-entrancy
// that deadlocked #640).
//
// Called from the watchdog thread, never from the event loop: it takes the
// HWND as a value and touches no crate state, so no lock the stalled loop
// might hold is on its path.

use std::ffi::c_void;

use jni::objects::JClass;
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use windows::Win32::Foundation::HWND;
use windows::Win32::UI::WindowsAndMessaging::{IsHungAppWindow, IsWindow};

/// `true` when Windows considers [hwnd]'s thread to have stopped pumping
/// messages (~5 s without a `GetMessage` / `PeekMessage`, the OS's own
/// threshold). `false` for a healthy window and for a handle that is no longer
/// a window.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeIsWindowHung(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) -> jboolean {
    if hwnd == 0 {
        return JNI_FALSE;
    }
    let hwnd = HWND(hwnd as *mut c_void);
    unsafe {
        if !IsWindow(Some(hwnd)).as_bool() {
            return JNI_FALSE;
        }
        if IsHungAppWindow(hwnd).as_bool() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }
}
