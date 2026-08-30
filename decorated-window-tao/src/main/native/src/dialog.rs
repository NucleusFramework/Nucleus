// Native fatal-error dialog (issue #622) — the no-AWT replacement for
// Compose Desktop's Swing `showErrorDialog` default. App-modal, blocks
// until dismissed. Called from the Kotlin fatal-exception path right
// before a clean exit, so it must depend on nothing but the OS toolkit.

use jni::objects::{JClass, JString};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeShowErrorDialog(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    message: JString,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let message: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    show_error_dialog(&title, &message);
}

#[cfg(target_os = "macos")]
fn show_error_dialog(title: &str, message: &str) {
    use std::ffi::CString;
    use std::os::raw::c_char;
    extern "C" {
        // macos/error_dialog.m — app-modal NSAlert, main-thread bounced.
        fn nucleus_tao_show_error_dialog(title: *const c_char, message: *const c_char);
    }
    // Java strings may carry interior NULs; strip them so CString::new
    // cannot fail.
    let title = CString::new(title.replace('\0', "")).expect("NULs stripped");
    let message = CString::new(message.replace('\0', "")).expect("NULs stripped");
    unsafe { nucleus_tao_show_error_dialog(title.as_ptr(), message.as_ptr()) };
}

#[cfg(target_os = "windows")]
fn show_error_dialog(title: &str, message: &str) {
    // MessageBoxW reads NUL-terminated wide strings, so an interior NUL from
    // Java would silently truncate — strip them like the macOS path does.
    let title = title.replace('\0', "");
    let message = message.replace('\0', "");
    // Dedicated thread, same reason macOS goes out of process: the calling
    // thread just ran (and exited) the Tao event loop, and modal loops on it
    // return immediately — a leftover quit/thread message in its queue makes
    // MessageBoxW dismiss itself before the user sees anything. A fresh
    // thread gets a fresh message queue, so the box actually blocks until
    // dismissed; join() preserves the blocking contract for the caller.
    std::thread::spawn(move || {
        use windows::core::HSTRING;
        use windows::Win32::UI::WindowsAndMessaging::{
            MessageBoxW, MB_ICONERROR, MB_OK, MB_SETFOREGROUND, MB_TASKMODAL,
        };
        // No owner HWND: the Tao loop has exited and every window is
        // destroyed by the time the fatal path runs. MB_TASKMODAL keeps the
        // ownerless box modal to the process; MB_SETFOREGROUND raises it
        // above the corpse of the app so the user actually sees why it is
        // closing.
        unsafe {
            MessageBoxW(
                None,
                &HSTRING::from(message),
                &HSTRING::from(title),
                MB_OK | MB_ICONERROR | MB_TASKMODAL | MB_SETFOREGROUND,
            );
        }
    })
    .join()
    .ok();
}

// ponytail: Linux (gtk::MessageDialog / zenity) is still a silent no-op;
// the SEVERE log on the JVM side is the only signal there (#622).
#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn show_error_dialog(_title: &str, _message: &str) {}
