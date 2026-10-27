pub(crate) mod a11y;
pub(crate) mod activation;
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

/// The `WM_CLASS` to give windows on an X11 display, set from the JVM before the loop starts
/// (`nativeSetX11WmClass`). `None` keeps GTK's default.
pub(crate) static X11_WM_CLASS: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

/// Makes GTK name the windows the way the desktop pairs them with their `.desktop` entry. Call it
/// after GTK is initialised (its display chosen) and before the first window is realized.
///
/// GTK 3 (no `GApplication` id, as with Tao) derives both names from `g_get_prgname()`, the
/// launcher binary's name, which the entry need not match:
///
/// - **Wayland:** the `xdg_toplevel` app_id is the prgname; compositors match it against the
///   entry's basename, so it is set to [WAYLAND_APP_ID].
/// - **X11:** `WM_CLASS` is the prgname (instance) and `gdk_get_program_class()` (class, the
///   prgname capitalised by default); desktops match either against the entry's
///   `StartupWMClass`, so both are set to [X11_WM_CLASS].
pub(crate) fn apply_window_identity() {
    use gtk::prelude::*;

    let Some(display) = gtk::gdk::Display::default() else {
        return;
    };
    let backend = display.backend();
    if backend.is_wayland() {
        if let Some(app_id) = WAYLAND_APP_ID.lock().ok().and_then(|id| id.clone()) {
            glib::set_prgname(Some(app_id.as_str()));
        }
    } else if backend.is_x11() {
        if let Some(wm_class) = X11_WM_CLASS.lock().ok().and_then(|class| class.clone()) {
            glib::set_prgname(Some(wm_class.as_str()));
            gtk::gdk::set_program_class(&wm_class);
        }
    }
}
