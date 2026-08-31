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
/// (possibly multi-line) followed by "\n\tat ..." frames. The compact
/// fallback alerts (macOS CFUserNotification, Windows MessageBoxW) show only
/// the `toString()` part after the sentence — the frames would not fit a
/// system alert. The summary itself is capped at 2000 chars: a VerifyError's
/// `toString()` is a ~100 KB bytecode dump with no "\n\tat " before the end,
/// and MessageBoxW silently fails (never shows) on a string that large.
#[cfg(any(target_os = "macos", target_os = "windows"))]
fn message_with_summary(message: &str, detail: &str) -> String {
    let summary = detail.split("\n\tat ").next().unwrap_or("").trim_end();
    if summary.is_empty() {
        return message.to_string();
    }
    if summary.len() <= 2000 {
        return format!("{message}\n\n{summary}");
    }
    let mut end = 2000;
    while !summary.is_char_boundary(end) {
        end -= 1;
    }
    format!("{message}\n\n{}…", &summary[..end])
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

// Control ids for the Windows fatal dialog (IDCANCEL = 2 is the stock Close
// id so Esc and the titlebar X both route through it).
#[cfg(target_os = "windows")]
const FATAL_DLG_ID_ICON: u16 = 101;
#[cfg(target_os = "windows")]
const FATAL_DLG_ID_TRACE: u16 = 102;
#[cfg(target_os = "windows")]
const FATAL_DLG_ID_COPY: u16 = 103;

/// Builds the in-memory `DLGTEMPLATE` for the fatal dialog: error icon +
/// message line up top, the stack trace in a read-only multiline EDIT with
/// both scrollbars (no `ES_AUTOHSCROLL` word-wrap — frame tails matter), and
/// Copy / Close buttons. Returned as `Vec<u32>` because `DLGTEMPLATE` needs
/// DWORD alignment and `Vec<u16>` only guarantees 2 bytes.
///
/// The trace text is deliberately NOT part of the template: templates choke
/// on large item strings (`DialogBoxIndirectParamW` returns -1 outright for
/// a ~100 KB VerifyError dump), so the EDIT starts empty and the dialog proc
/// fills it from `dwInitParam` via `SetWindowTextW`, which has no such limit.
///
/// Everything below is in dialog units (Segoe UI 9pt ⇒ ~1.75×1.9 px/DLU),
/// sized to match the 560×160 px trace views on macOS and Linux.
#[cfg(target_os = "windows")]
fn fatal_dialog_template(title: &str, message: &str) -> Vec<u32> {
    // Raw style bits, spelled out because the template wants untyped u32s
    // (the windows-crate consts come as WINDOW_STYLE / i32 grab-bags).
    const WS_POPUP: u32 = 0x8000_0000;
    const WS_VISIBLE: u32 = 0x1000_0000;
    const WS_CAPTION: u32 = 0x00C0_0000;
    const WS_SYSMENU: u32 = 0x0008_0000;
    const WS_CHILD: u32 = 0x4000_0000;
    const WS_BORDER: u32 = 0x0080_0000;
    const WS_VSCROLL: u32 = 0x0020_0000;
    const WS_HSCROLL: u32 = 0x0010_0000;
    const WS_TABSTOP: u32 = 0x0001_0000;
    const DS_SETFONT: u32 = 0x0040;
    const DS_MODALFRAME: u32 = 0x0080;
    const DS_CENTER: u32 = 0x0800;
    // Raise above the corpse of the app, same intent as MB_SETFOREGROUND.
    const DS_SETFOREGROUND: u32 = 0x0200;
    const SS_ICON: u32 = 0x0003;
    // Exception messages may contain '&' — don't turn it into an accelerator.
    const SS_NOPREFIX: u32 = 0x0080;
    const ES_MULTILINE: u32 = 0x0004;
    const ES_AUTOVSCROLL: u32 = 0x0040;
    const ES_READONLY: u32 = 0x0800;
    const BS_DEFPUSHBUTTON: u32 = 0x0001;
    // Window-class ordinals for template items.
    const CLASS_BUTTON: u16 = 0x0080;
    const CLASS_EDIT: u16 = 0x0081;
    const CLASS_STATIC: u16 = 0x0082;
    const IDCANCEL: u16 = 2;

    let mut w: Vec<u16> = Vec::with_capacity(256 + title.len() + message.len());
    let push_u32 = |w: &mut Vec<u16>, v: u32| {
        w.push(v as u16);
        w.push((v >> 16) as u16);
    };
    let push_wstr = |w: &mut Vec<u16>, s: &str| {
        w.extend(s.encode_utf16());
        w.push(0);
    };
    // DLGTEMPLATE header.
    push_u32(
        &mut w,
        DS_SETFONT
            | DS_MODALFRAME
            | DS_CENTER
            | DS_SETFOREGROUND
            | WS_POPUP
            | WS_CAPTION
            | WS_SYSMENU,
    );
    push_u32(&mut w, 0); // dwExtendedStyle
    w.push(5); // cdit: icon, message, trace, Copy, Close
    w.extend([0u16, 0]); // x, y (DS_CENTER positions it)
    w.extend([320u16, 154]); // cx, cy
    w.push(0); // no menu
    w.push(0); // default dialog class
    push_wstr(&mut w, title);
    w.push(9); // DS_SETFONT point size
    push_wstr(&mut w, "Segoe UI");

    let push_item =
        |w: &mut Vec<u16>, style: u32, rect: [u16; 4], id: u16, class: u16, text: &str| {
            if w.len() % 2 == 1 {
                w.push(0); // DLGITEMTEMPLATE entries are DWORD-aligned
            }
            push_u32(w, style);
            push_u32(w, 0); // dwExtendedStyle
            w.extend(rect);
            w.push(id);
            w.extend([0xFFFF, class]);
            push_wstr(w, text);
            w.push(0); // no creation data
        };
    push_item(
        &mut w,
        WS_CHILD | WS_VISIBLE | SS_ICON,
        [7, 7, 21, 20],
        FATAL_DLG_ID_ICON,
        CLASS_STATIC,
        "",
    );
    push_item(
        &mut w,
        WS_CHILD | WS_VISIBLE | SS_NOPREFIX,
        [36, 9, 277, 16],
        0xFFFF, // stock "don't care" static id
        CLASS_STATIC,
        message,
    );
    push_item(
        &mut w,
        WS_CHILD
            | WS_VISIBLE
            | WS_BORDER
            | WS_VSCROLL
            | WS_HSCROLL
            | WS_TABSTOP
            | ES_MULTILINE
            | ES_AUTOVSCROLL
            | ES_READONLY,
        [7, 30, 306, 96],
        FATAL_DLG_ID_TRACE,
        CLASS_EDIT,
        "", // filled in WM_INITDIALOG — see the doc comment above
    );
    push_item(
        &mut w,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP,
        [205, 133, 52, 14],
        FATAL_DLG_ID_COPY,
        CLASS_BUTTON,
        "Copy",
    );
    push_item(
        &mut w,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_DEFPUSHBUTTON,
        [261, 133, 52, 14],
        IDCANCEL,
        CLASS_BUTTON,
        "Close",
    );

    // Re-home into a Vec<u32> for the DWORD alignment DLGTEMPLATE requires.
    let mut aligned: Vec<u32> = vec![0; w.len().div_ceil(2)];
    // SAFETY: the destination is freshly allocated and at least w.len() u16s.
    unsafe {
        std::ptr::copy_nonoverlapping(w.as_ptr(), aligned.as_mut_ptr().cast::<u16>(), w.len());
    }
    aligned
}

/// Dialog procedure for the fatal dialog. `WM_INITDIALOG`'s lParam
/// (`dwInitParam`) carries the NUL-terminated wide trace text, set into the
/// EDIT here because the template cannot carry it (see
/// [`fatal_dialog_template`]). Copy selects-all + `WM_COPY` on the trace EDIT
/// (works on read-only edits, and the selection doubles as visual feedback)
/// and keeps the dialog open, matching macOS and Linux; Close / Esc /
/// titlebar X all arrive as IDCANCEL and end the dialog with 1 so the caller
/// can tell "dismissed" from "failed to create" (0 / -1).
#[cfg(target_os = "windows")]
unsafe extern "system" fn fatal_dialog_proc(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> isize {
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::{LPARAM, WPARAM};
    use windows::Win32::Graphics::Gdi::{
        CreateFontW, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, DEFAULT_CHARSET, FF_MODERN,
        FIXED_PITCH, FW_NORMAL, OUT_DEFAULT_PRECIS,
    };
    use windows::Win32::UI::Input::KeyboardAndMouse::SetFocus;
    use windows::Win32::UI::WindowsAndMessaging::{
        EndDialog, GetDlgItem, LoadIconW, SendMessageW, SetWindowTextW, IDI_ERROR, STM_SETICON,
        WM_COMMAND, WM_COPY, WM_INITDIALOG, WM_SETFONT,
    };
    // Lives in UI::Controls in the windows crate — not worth the feature.
    const EM_SETSEL: u32 = 0x00B1;
    const IDCANCEL: u16 = 2;
    match msg {
        WM_INITDIALOG => {
            if let Ok(icon) = LoadIconW(None, IDI_ERROR) {
                if let Ok(item) = GetDlgItem(Some(hwnd), FATAL_DLG_ID_ICON as i32) {
                    SendMessageW(
                        item,
                        STM_SETICON,
                        Some(WPARAM(icon.0 as usize)),
                        Some(LPARAM(0)),
                    );
                }
            }
            if let Ok(trace) = GetDlgItem(Some(hwnd), FATAL_DLG_ID_TRACE as i32) {
                if lparam.0 != 0 {
                    let _ = SetWindowTextW(trace, PCWSTR(lparam.0 as *const u16));
                }
                // Monospace trace like the other platforms. Deliberately not
                // freed: the process exits right after the fatal dialog.
                let face: Vec<u16> = "Consolas\0".encode_utf16().collect();
                let font = CreateFontW(
                    -12,
                    0,
                    0,
                    0,
                    FW_NORMAL.0 as i32,
                    0,
                    0,
                    0,
                    DEFAULT_CHARSET,
                    OUT_DEFAULT_PRECIS,
                    CLIP_DEFAULT_PRECIS,
                    CLEARTYPE_QUALITY,
                    FIXED_PITCH.0 as u32 | FF_MODERN.0 as u32,
                    PCWSTR(face.as_ptr()),
                );
                if !font.is_invalid() {
                    SendMessageW(
                        trace,
                        WM_SETFONT,
                        Some(WPARAM(font.0 as usize)),
                        Some(LPARAM(1)),
                    );
                }
            }
            // Focus Close, not the trace edit — the dialog manager would
            // otherwise select the whole trace on open. Returning 0 tells it
            // we set focus ourselves.
            if let Ok(close) = GetDlgItem(Some(hwnd), IDCANCEL as i32) {
                let _ = SetFocus(Some(close));
            }
            0
        }
        WM_COMMAND => {
            match (wparam.0 & 0xFFFF) as u16 {
                FATAL_DLG_ID_COPY => {
                    if let Ok(trace) = GetDlgItem(Some(hwnd), FATAL_DLG_ID_TRACE as i32) {
                        SendMessageW(trace, EM_SETSEL, Some(WPARAM(0)), Some(LPARAM(-1)));
                        SendMessageW(trace, WM_COPY, None, None);
                    }
                }
                IDCANCEL => {
                    let _ = EndDialog(hwnd, 1);
                }
                _ => return 0,
            }
            1
        }
        _ => 0,
    }
}

#[cfg(target_os = "windows")]
fn show_error_dialog(title: &str, message: &str, detail: &str) {
    // The dialog manager and MessageBoxW read NUL-terminated wide strings, so
    // an interior NUL from Java would silently truncate — strip them like the
    // macOS path does.
    let title = title.replace('\0', "");
    let message = message.replace('\0', "");
    // EDIT controls only break lines on CRLF; a bare '\n' renders as a box.
    let detail = detail
        .replace('\0', "")
        .replace("\r\n", "\n")
        .replace('\n', "\r\n");
    // Dedicated thread, same reason macOS goes out of process: the calling
    // thread just ran (and exited) the Tao event loop, and modal loops on it
    // return immediately — a leftover quit/thread message in its queue makes
    // the dialog dismiss itself before the user sees anything. A fresh
    // thread gets a fresh message queue, so it actually blocks until
    // dismissed; join() preserves the blocking contract for the caller.
    std::thread::spawn(move || {
        use windows::core::HSTRING;
        use windows::Win32::Foundation::LPARAM;
        use windows::Win32::UI::WindowsAndMessaging::{
            DialogBoxIndirectParamW, MessageBoxW, DLGTEMPLATE, MB_ICONERROR, MB_OK,
            MB_SETFOREGROUND,
        };
        // Primary path: the scrollable-trace dialog — the Windows twin of the
        // macOS NSAlert accessory / Linux GtkMessageDialog. No owner HWND:
        // the Tao loop has exited and every Tao window is destroyed by the
        // time the fatal path runs. Note the dialog is NOT modal to anything
        // (this fresh thread owns no other windows), so surviving foreign
        // windows (e.g. a Swing JFrame in the swing-tao-demo interop mode)
        // stay interactive behind it.
        if !detail.is_empty() {
            let template = fatal_dialog_template(&title, &message);
            // The trace goes through dwInitParam, not the template (see
            // fatal_dialog_template). `detail_w` outlives the modal call.
            let detail_w: Vec<u16> = detail.encode_utf16().chain(std::iter::once(0)).collect();
            let dismissed = unsafe {
                DialogBoxIndirectParamW(
                    None,
                    template.as_ptr().cast::<DLGTEMPLATE>(),
                    None,
                    Some(fatal_dialog_proc),
                    LPARAM(detail_w.as_ptr() as isize),
                )
            } > 0;
            if dismissed {
                return;
            }
        }
        // Fallback (no detail, or the dialog could not be created): the
        // compact one-shot MessageBoxW with only the `toString()` summary.
        // MB_SETFOREGROUND raises it above the corpse of the app so the user
        // actually sees why it is closing.
        let message = message_with_summary(&message, &detail);
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
