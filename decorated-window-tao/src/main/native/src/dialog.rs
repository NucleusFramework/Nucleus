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
    detail: JString,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let message: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let detail: String = match env.get_string(&detail) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    show_error_dialog(&title, &message, &detail);
}

/// `detail` is `Throwable.stackTraceToString()`: the throwable's `toString()`
/// (possibly multi-line) followed by "\n\tat ..." frames. Windows and the
/// macOS CFUserNotification fallback keep their compact one-shot alert, so
/// they show only the `toString()` part after the sentence — the frames
/// would not fit a system alert.
#[cfg(any(target_os = "macos", target_os = "windows"))]
fn message_with_summary(message: &str, detail: &str) -> String {
    let summary = detail.split("\n\tat ").next().unwrap_or("").trim_end();
    if summary.is_empty() {
        message.to_string()
    } else {
        format!("{message}\n\n{summary}")
    }
}

/// NSAlert with the stack trace in a scrollable monospace accessory view and
/// a Copy button — the macOS twin of the Linux GtkMessageDialog below. Run
/// by an `osascript -l JavaScript` child: our own NSApp cannot host it (tao
/// latches [NSApp stop:] on loop exit, so an in-process runModal returns
/// immediately — see error_dialog.m), while the child gets a fresh NSApp.
/// argv: title, message, detail.
///
/// JXA traps encoded below, in rendering order:
///   - osascript is an agent process: without the Accessory activation
///     policy + activate, the panel can open buried behind other apps.
///   - horizontal scrolling needs the full non-tracking text-container
///     dance; a bare NSTextView wraps at 560px and hides frame tails.
///   - `runModal` bridges to a JS *string* ("1001"), so a `===` against the
///     NSAlertSecondButtonReturn number never matches — coerce first.
///   - the general pasteboard is held by the pboard server, so the copied
///     trace survives the child's exit (no X11-style store dance needed).
#[cfg(target_os = "macos")]
const FATAL_ALERT_JXA: &str = r#"
ObjC.import('Cocoa')
function run(argv) {
  const title = argv[0], message = argv[1], detail = argv[2]
  const alert = $.NSAlert.alloc.init
  alert.alertStyle = $.NSAlertStyleCritical
  alert.messageText = title
  alert.informativeText = message
  alert.addButtonWithTitle('Close')
  alert.addButtonWithTitle('Copy')
  if (detail.length > 0) {
    const view = $.NSTextView.alloc.initWithFrame($.NSMakeRect(0, 0, 560, 160))
    view.editable = false
    view.font = $.NSFont.userFixedPitchFontOfSize(11)
    view.string = detail
    view.horizontallyResizable = true
    view.textContainer.widthTracksTextView = false
    view.textContainer.containerSize = $.NSMakeSize(10000000, 10000000)
    view.maxSize = $.NSMakeSize(10000000, 10000000)
    const scroll = $.NSScrollView.alloc.initWithFrame($.NSMakeRect(0, 0, 560, 160))
    scroll.hasVerticalScroller = true
    scroll.hasHorizontalScroller = true
    scroll.borderType = $.NSBezelBorder
    scroll.documentView = view
    alert.accessoryView = scroll
  }
  const app = $.NSApplication.sharedApplication
  app.setActivationPolicy($.NSApplicationActivationPolicyAccessory)
  app.activateIgnoringOtherApps(true)
  alert.window.level = 8 // NSModalPanelWindowLevel: above the corpse of the app
  while (Number(alert.runModal) === 1001) { // NSAlertSecondButtonReturn: Copy
    const pasteboard = $.NSPasteboard.generalPasteboard
    pasteboard.clearContents
    pasteboard.setStringForType($(detail), $.NSPasteboardTypeString)
  }
}
"#;

#[cfg(target_os = "macos")]
fn show_error_dialog(title: &str, message: &str, detail: &str) {
    use std::ffi::CString;
    use std::os::raw::c_char;
    extern "C" {
        // macos/error_dialog.m — CFUserNotificationDisplayAlert: rendered out
        // of process, callable from any thread, no NSApp/run-loop dependency
        // (NSAlert cannot run after tao latches [NSApp stop:] — see the .m).
        fn nucleus_tao_show_error_dialog(title: *const c_char, message: *const c_char);
    }
    // Java strings may carry interior NULs; strip them so neither the child
    // argv (spawn refuses interior NULs) nor CString::new can fail.
    let title = title.replace('\0', "");
    let message = message.replace('\0', "");
    let detail = detail.replace('\0', "");
    // Primary path: the JXA NSAlert child. Blocks until dismissed, like the
    // other platforms. `success()` is only false when the dialog could not
    // run at all (osascript blocked by MDM policy, no WindowServer session,
    // JXA exception) — Close and Copy+Close both exit 0.
    let shown = std::process::Command::new("/usr/bin/osascript")
        .args(["-l", "JavaScript", "-e", FATAL_ALERT_JXA, "--"])
        .args([&title, &message, &detail])
        .status()
        .map(|status| status.success())
        .unwrap_or(false);
    if shown {
        return;
    }
    // Fallback: the compact CFUserNotification alert — no scrollable view,
    // so only the `toString()` summary fits after the sentence.
    let message = message_with_summary(&message, &detail);
    let title = CString::new(title).expect("NULs stripped");
    let message = CString::new(message).expect("NULs stripped");
    unsafe { nucleus_tao_show_error_dialog(title.as_ptr(), message.as_ptr()) };
}

#[cfg(target_os = "windows")]
fn show_error_dialog(title: &str, message: &str, detail: &str) {
    // MessageBoxW reads NUL-terminated wide strings, so an interior NUL from
    // Java would silently truncate — strip them like the macOS path does.
    let title = title.replace('\0', "");
    let message = message_with_summary(message, detail).replace('\0', "");
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
fn show_error_dialog(title: &str, message: &str, detail: &str) {
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
    // glib's &str → C-string conversion panics on interior NULs (Java strings
    // may carry them) — strip like the macOS/Windows paths do.
    let title = title.replace('\0', "");
    let message = message.replace('\0', "");
    let detail = detail.replace('\0', "");
    // No parent: every Tao window is already destroyed when the fatal path
    // runs. The title goes only to the window title; the bold primary line is
    // the short sentence — putting the title in both printed "Fatal Error"
    // twice on WMs that draw dialog titlebars.
    let dialog = gtk::MessageDialog::new(
        None::<&gtk::Window>,
        gtk::DialogFlags::MODAL,
        gtk::MessageType::Error,
        gtk::ButtonsType::None,
        &message,
    );
    dialog.set_title(&title);
    // The stack trace goes in a bounded scrollable monospace view, not the
    // secondary label — a Compose stack trace in the label used to size the
    // dialog to the whole screen.
    let buffer = gtk::TextBuffer::new(None::<&gtk::TextTagTable>);
    buffer.set_text(&detail);
    let view = gtk::TextView::with_buffer(&buffer);
    view.set_editable(false);
    view.set_cursor_visible(false);
    view.set_monospace(true);
    view.set_left_margin(8);
    view.set_right_margin(8);
    view.set_top_margin(8);
    view.set_bottom_margin(8);
    let scroll = gtk::ScrolledWindow::new(None::<&gtk::Adjustment>, None::<&gtk::Adjustment>);
    scroll.set_policy(gtk::PolicyType::Automatic, gtk::PolicyType::Automatic);
    scroll.set_shadow_type(gtk::ShadowType::In);
    scroll.set_min_content_width(560);
    scroll.set_min_content_height(160);
    scroll.set_margin_start(12);
    scroll.set_margin_end(12);
    scroll.set_margin_bottom(6);
    scroll.add(&view);
    dialog.content_area().pack_start(&scroll, true, true, 0);
    // MessageDialogs are non-resizable by default; a stack trace is worth
    // enlarging for.
    dialog.set_resizable(true);

    let copy_response = gtk::ResponseType::Other(1);
    dialog.add_button("_Copy", copy_response);
    dialog.add_button("_Close", gtk::ResponseType::Close);
    dialog.set_default_response(gtk::ResponseType::Close);
    // X11 only (no-op on Wayland): raise above the corpse of the app, same
    // intent as MB_SETFOREGROUND on Windows.
    dialog.set_keep_above(true);
    // `gtk_dialog_run` only shows the dialog itself, not children added after
    // construction (the scroller).
    dialog.show_all();
    // Blocks in a recursive main loop until Close / titlebar close / Escape.
    // Copy puts the stack trace on the clipboard and keeps the dialog open.
    loop {
        if dialog.run() != copy_response {
            break;
        }
        let clipboard = gtk::Clipboard::get(&gtk::gdk::SELECTION_CLIPBOARD);
        clipboard.set_text(&detail);
        // Hand the contents over to the clipboard manager (if one runs): the
        // process exits right after the dialog closes, and an unstored
        // X11/Wayland selection dies with its owner.
        clipboard.store();
    }
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
fn show_error_dialog(_title: &str, _message: &str, _detail: &str) {}
