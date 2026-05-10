// Linux cursor handling: GTK 3 routes per-device cursors through XInput 2's
// per-device cursor table, which is what GTK itself uses internally and what
// shipping cursor themes hook into. We go through GDK so the icon follows
// the user's GTK theme and survives XWayland's cursor surface re-rendering.

use jni::sys::jint;

use tao::window::Window;

use crate::state::WINDOWS;

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
/// itself uses internally — it also covers MPX setups (multiple physical
/// mice each driving their own cursor) by walking every seat.
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
    for seat in display.list_seats() {
        if let Some(pointer) = seat.pointer() {
            gdk_window.set_device_cursor(&pointer, &cursor);
        }
    }
    display.flush();
}

/// Sets the cursor for a window.
pub(crate) fn set_cursor(handle: u64, code: jint) {
    let name = cursor_name_from_code(code);
    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&handle) {
        apply_cursor_via_gdk(window, name);
    }
}
