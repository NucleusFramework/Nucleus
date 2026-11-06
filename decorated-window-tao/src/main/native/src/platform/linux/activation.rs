// Focus with an xdg-activation token (#739).
//
// A Wayland compositor only lets a surface take focus with a token it handed
// out itself — for a global shortcut, the portal's `Activated` signal carries
// one. Without it Mutter's focus-stealing prevention leaves the window
// unfocused and shows "<App> is ready" instead.
//
// GDK 3 has no per-call API for an external token, but `gdk_wayland_window_focus`
// (reached through `gtk_window_present_with_time`) consumes the display's
// startup notification id as the token for `xdg_activation_v1.activate` before
// minting one of its own from the last input serial — which, for an app that
// received no input, is exactly what the compositor refuses. Setting that id
// right before presenting hands our token over; GDK clears it on use.

use std::ffi::CString;

use gtk::prelude::*;
use tao::platform::unix::WindowExtUnix;
use tao::window::Window;

/// Raises and focuses `window` with `token`. Returns `false` when the window is
/// not on a Wayland surface — the caller then takes the regular focus path
/// (X11 activation is stamped with a server time instead, tao patch 0005).
pub(crate) fn focus_with_token(window: &Window, token: &str) -> bool {
    let gtk_window = window.gtk_window();
    let display = WidgetExt::display(gtk_window);
    if !display.backend().is_wayland() {
        return false;
    }
    let Ok(token) = CString::new(token) else {
        return false;
    };
    // A hidden window is shown first: `present` on an unmapped window only maps
    // it and never reaches `gdk_window_focus`, which would leave the token
    // pending on the display for whatever focus request comes next. And before
    // the id is set: the process's first map reports startup completion, which
    // consumes the display's startup id.
    if !gtk_window.is_visible() {
        gtk_window.show();
    }
    let raw_display: *mut gtk::gdk::ffi::GdkDisplay =
        glib::translate::ToGlibPtr::to_glib_none(&display).0;
    unsafe {
        gdk_wayland_sys::gdk_wayland_display_set_startup_notification_id(
            raw_display as *mut gdk_wayland_sys::GdkWaylandDisplay,
            token.as_ptr(),
        );
    }
    gtk_window.present_with_time(gtk::gdk::ffi::GDK_CURRENT_TIME as u32);
    true
}
