pub(crate) mod a11y;
pub(crate) mod decoration;
pub(crate) mod dnd;
pub(crate) mod handles;
pub(crate) mod ime;
pub(crate) mod monitor;
pub(crate) mod scroll;
pub(crate) mod server_decoration;
pub(crate) mod touch;

/// Makes Xlib thread-safe before GTK opens its X display. Call it before anything that may
/// initialise GTK.
///
/// Rendering, input and GTK's main loop reach the same X connection from different threads.
/// Xlib only locks a connection opened *after* `XInitThreads()`; without it, concurrent use
/// corrupts the request queue and Xlib aborts ("[xcb] Unknown sequence number while processing
/// queue … XInitThreads has not been called") or the process dies on an X I/O error. libX11 1.8+
/// calls it itself when it loads, which is why this only shows on older distributions, such as
/// Ubuntu 22.04 (libX11 1.7.5) and Debian 11. Harmless on a Wayland session or a newer libX11.
pub(crate) fn init_xlib_threads() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        if let Ok(xlib) = x11_dl::xlib::Xlib::open() {
            unsafe { (xlib.XInitThreads)() };
            // Dropping the handle would dlclose libX11; keep it loaded for the process lifetime.
            std::mem::forget(xlib);
        }
    });
}

/// The `xdg_toplevel` app_id to send on a Wayland display, set from the JVM before the loop
/// starts (`nativeSetWaylandAppId`). `None` keeps GTK's default.
pub(crate) static WAYLAND_APP_ID: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

/// Makes GTK send [WAYLAND_APP_ID] as each window's app_id. Call it after GTK is initialised and
/// before the first window is realized.
///
/// GTK 3 (no `GApplication` id, as with Tao) sends `g_get_prgname()` as the app_id, which is
/// the launcher binary's name; compositors pair the window with the `.desktop` entry of that
/// name. Only applied on Wayland: on X11 the prgname is also the window's `WM_CLASS`, which must
/// stay as it is.
pub(crate) fn apply_wayland_app_id() {
    use gtk::prelude::*;

    let Some(app_id) = WAYLAND_APP_ID.lock().ok().and_then(|id| id.clone()) else {
        return;
    };
    let wayland =
        gtk::gdk::Display::default().is_some_and(|display| display.backend().is_wayland());
    if wayland {
        glib::set_prgname(Some(app_id.as_str()));
    }
}
