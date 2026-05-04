// Linux cursor handling: GTK 3 routes per-device cursors through XInput 2's
// per-device cursor table, which beats legacy `XDefineCursor` and tao's own
// `set_cursor_icon` (the latter only updates the client pointer, not every
// master). We store the requested name per window so the `CursorMoved`
// handler in `event_loop` can re-apply it after each of tao's motion-handler
// resets.

use std::collections::HashMap;
use std::sync::Mutex;

use jni::sys::jint;

use tao::window::Window;

use crate::state::WINDOWS;

// Linux only: last cursor name requested per window, so we can re-apply it on
// every CursorMoved event. tao's GTK backend installs a motion-notify handler
// that calls `gdk_window_set_cursor("default")` on every motion (resize-edge
// detection on undecorated, resizable windows). That handler runs through
// XIDefineCursor under the hood, which takes precedence over legacy
// XDefineCursor for the matching master pointer — so we must re-apply our
// device cursor *after* tao's handler on every move, otherwise the icon the
// user sees flashes back to the default arrow on the next pixel of motion.
static LAST_CURSOR_BY_HANDLE: Mutex<Option<HashMap<u64, String>>> = Mutex::new(None);

/// Maps the JVM-side cursor codes to the freedesktop / Adwaita cursor-theme
/// names that `gdk_cursor_new_from_name` accepts. Going through the cursor
/// theme (rather than `XCreateFontCursor` core fonts) makes the icons follow
/// the user's GTK theme and survive XWayland's cursor surface re-rendering.
fn cursor_name_from_code(code: jint) -> &'static str {
    match code {
        1 => "text",
        2 => "pointer",
        3 => "crosshair",
        4 => "wait",
        5 => "move",
        6 => "not-allowed",
        7 => "help",
        8 => "progress",
        9 => "ew-resize",
        10 => "ns-resize",
        11 => "nesw-resize",
        12 => "nwse-resize",
        _ => "default",
    }
}

/// Iterates every master pointer of the window's GDK display and assigns the
/// given themed cursor on the GdkWindow via `gdk_window_set_device_cursor`,
/// which on GTK 3 / Linux ultimately calls XIDefineCursor for each device.
///
/// This is the X Input 2 equivalent of `XDefineCursor` and is what GTK 3
/// itself uses internally — legacy `XDefineCursor` is silently overridden
/// by the per-device cursor, so going through GDK is the only way to make
/// the icon stick across motion events on a window co-hosted with GTK.
fn apply_cursor_via_gdk(window: &Window, name: &str) {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    let Some(gdk_window) = WidgetExt::window(gtk_window) else { return };
    let display = WidgetExt::display(gtk_window);
    let Some(cursor) = gtk::gdk::Cursor::from_name(&display, name) else {
        // Theme miss — fall back to "default". `from_name("default")` is
        // guaranteed by every shipping cursor theme.
        if name != "default" {
            apply_cursor_via_gdk(window, "default");
        }
        return;
    };
    // Iterate every seat's master pointer. GTK 3 keeps one master pointer
    // per seat; on a typical desktop there's exactly one seat, but MPX
    // setups (multiple physical mice each driving their own cursor) expose
    // additional seats. `gdk_window_set_device_cursor` writes the per-device
    // XInput 2 cursor — that's what GTK itself does, and what overrides the
    // default cursor tao keeps re-applying via its motion handler.
    for seat in display.list_seats() {
        if let Some(pointer) = seat.pointer() {
            gdk_window.set_device_cursor(&pointer, &cursor);
        }
    }
    display.flush();
}

/// Re-applies the cursor stored for the given window (if any). Called from
/// the `WindowEvent::CursorMoved` handler, after tao's own motion handler
/// has had a chance to overwrite the cursor with the resize-edge default.
///
/// Skipped when the stored cursor is "default": tao already resets to
/// default on every motion event, so re-applying it would be a no-op
/// (and would waste an XIDefineCursor round-trip on every mouse movement).
/// In the common case (no hover icon active), this short-circuits before
/// touching GDK at all.
pub(crate) fn reapply_stored_cursor(handle: u64) {
    let name = {
        let Ok(guard) = LAST_CURSOR_BY_HANDLE.lock() else { return };
        match guard.as_ref().and_then(|m| m.get(&handle)) {
            Some(s) if s != "default" => s.clone(),
            _ => return,
        }
    };
    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&handle) {
        apply_cursor_via_gdk(window, &name);
    }
}

/// Sets the cursor for a window. Stores the requested name for re-application
/// on every CursorMoved event, then pushes it once now for the immediate
/// hover transition.
pub(crate) fn set_cursor(handle: u64, code: jint) {
    let name = cursor_name_from_code(code);
    if let Ok(mut guard) = LAST_CURSOR_BY_HANDLE.lock() {
        guard
            .get_or_insert_with(HashMap::new)
            .insert(handle, name.to_string());
    }
    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&handle) {
        apply_cursor_via_gdk(window, name);
    }
}

/// Drops the stored cursor name for a window that's being closed.
pub(crate) fn forget_cursor(handle: u64) {
    if let Ok(mut g) = LAST_CURSOR_BY_HANDLE.lock() {
        if let Some(m) = g.as_mut() {
            m.remove(&handle);
        }
    }
}
