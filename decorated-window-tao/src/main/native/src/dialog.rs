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
        // macos/error_dialog.m — CFUserNotificationDisplayAlert: rendered out
        // of process, callable from any thread, no NSApp/run-loop dependency
        // (NSAlert cannot run after tao latches [NSApp stop:] — see the .m).
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
            MessageBoxW, MB_ICONERROR, MB_OK, MB_SETFOREGROUND,
        };
        // No owner HWND: the Tao loop has exited and every Tao window is
        // destroyed by the time the fatal path runs. Note the box is NOT
        // modal to anything — MB_TASKMODAL would only disable top-level
        // windows of the calling thread, and this fresh thread owns none —
        // so surviving foreign windows (e.g. a Swing JFrame in the
        // swing-tao-demo interop mode) stay interactive behind it.
        // MB_SETFOREGROUND raises it above the corpse of the app so the user
        // actually sees why it is closing.
        unsafe {
            MessageBoxW(
                None,
                &HSTRING::from(message),
                &HSTRING::from(title),
                MB_OK | MB_ICONERROR | MB_SETFOREGROUND,
            );
        }
    })
    .join()
    .ok();
}

#[cfg(target_os = "linux")]
fn show_error_dialog(title: &str, message: &str) {
    use gtk::prelude::*;
    // The caller is the thread that just ran (and exited) the Tao event loop
    // — the GTK main thread. GTK survives the loop exit: tao iterates
    // `gtk_main_iteration_do` on the default MainContext inside
    // `with_thread_default`, which releases the context on return, so a
    // recursive `gtk_dialog_run` main loop works here. Guard anyway — a
    // fatal reached before the loop ever initialized GTK (or a torn-down
    // display) must not turn the fatal path into a second crash.
    if !gtk::is_initialized() && gtk::init().is_err() {
        return;
    }
    // No parent: every Tao window is already destroyed when the fatal path
    // runs. Title goes into the bold primary line as well — a GtkMessageDialog
    // shows no titlebar text on GNOME, so `set_title` alone would hide it.
    let dialog = gtk::MessageDialog::new(
        None::<&gtk::Window>,
        gtk::DialogFlags::MODAL,
        gtk::MessageType::Error,
        gtk::ButtonsType::Ok,
        title,
    );
    dialog.set_title(title);
    dialog.set_secondary_text(Some(message));
    // X11 only (no-op on Wayland): raise above the corpse of the app, same
    // intent as MB_SETFOREGROUND on Windows.
    dialog.set_keep_above(true);
    // Blocks in a recursive main loop until OK / close / Escape.
    dialog.run();
    unsafe { dialog.destroy() };
    // Flush the destroy so the dialog leaves the screen even if a non-daemon
    // JVM thread delays process exit for a moment. Bounded: an always-ready
    // source left behind by the app (a11y bus, a recurring timeout, a
    // torn-down display fd) can keep `events_pending()` true forever, and
    // this runs on the fatal path where a busy-spin would replace the exit.
    for _ in 0..64 {
        if !gtk::events_pending() {
            break;
        }
        gtk::main_iteration_do(false);
    }
}

// Other unixes (BSDs): still a silent no-op; the SEVERE log on the JVM side
// is the only signal there (#622).
#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn show_error_dialog(_title: &str, _message: &str) {}
