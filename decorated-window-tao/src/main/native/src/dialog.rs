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

// ponytail: macOS only for now (#622) — Windows (MessageBoxW) and Linux
// (gtk::MessageDialog) are silent no-ops; the SEVERE log on the JVM side
// is the only signal there until they are implemented.
#[cfg(not(target_os = "macos"))]
fn show_error_dialog(_title: &str, _message: &str) {}
