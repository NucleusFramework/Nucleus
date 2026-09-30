pub(crate) mod a11y;
pub(crate) mod decoration;
pub(crate) mod dnd;
mod drag_data;
pub(crate) mod handles;
pub(crate) mod ime;
pub(crate) mod monitor;
pub(crate) mod scroll;
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
