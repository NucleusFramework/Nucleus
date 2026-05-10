// Linux primary-monitor geometry exposed for `WindowPosition.Aligned`
// resolution on the JVM side. Mirrors the slice of
// `NativeTaoWindowsDecoBridge` / `NativeTaoMacOsDecoBridge` used by
// `applyAlignedPosition`.
//
// We go through GDK (via the `gtk` crate already pulled in for cursor / handle
// helpers) so the work area accounts for the panel / dock — Tao's
// `MonitorHandle::size()` returns the full screen size which would centre the
// window slightly off on desktops with a bottom panel.
//
// All entry points are scoped to a Tao window handle: the WINDOWS map gives
// us a `tao::window::Window` from which we can reach the underlying GTK widget
// → GdkDisplay → primary `GdkMonitor`. The JNI calls are invoked from the
// Compose dispatcher which is pinned to the Tao / GTK main thread, so the
// GDK API contract (main thread only) is satisfied.

use jni::objects::JClass;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;

use tao::platform::unix::WindowExtUnix;
use tao::window::Window;

use crate::state::WINDOWS;

fn with_window<R>(handle: jlong, f: impl FnOnce(&Window) -> Option<R>) -> Option<R> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&(handle as u64))?;
    f(window)
}

fn primary_monitor(window: &Window) -> Option<gtk::gdk::Monitor> {
    use gtk::prelude::WidgetExt;
    let gtk_window = window.gtk_window();
    let display = WidgetExt::display(gtk_window);
    display.primary_monitor().or_else(|| display.monitor(0))
}

/// Returns `[x, y, width, height]` of the primary monitor's work area
/// (full screen minus panels / docks) in physical pixels with a top-left
/// origin. Falls back to the full monitor geometry when GDK can't report
/// a work area (e.g. some Wayland compositors).
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeLinuxPrimaryMonitorWorkArea(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let rect = with_window(handle, |w| {
        use gtk::prelude::MonitorExt;
        let monitor = primary_monitor(w)?;
        let scale = monitor.scale_factor().max(1);
        // GDK reports work area in *logical* pixels on HiDPI; convert to
        // physical to match the Win32 / NSScreen conventions used by the
        // shared centring math in DecoratedWindowComposable.kt.
        let area = monitor.workarea();
        let (x, y, w_, h_) = if area.width() > 0 && area.height() > 0 {
            (area.x(), area.y(), area.width(), area.height())
        } else {
            let g = monitor.geometry();
            (g.x(), g.y(), g.width(), g.height())
        };
        Some([
            (x as i64) * scale as i64,
            (y as i64) * scale as i64,
            (w_ as i64) * scale as i64,
            (h_ as i64) * scale as i64,
        ])
    });
    let Some(values) = rect else {
        return std::ptr::null_mut();
    };
    let arr = match env.new_long_array(4) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_long_array_region(&arr, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Returns the primary monitor's scale factor encoded as `(scale * 1000)`.
/// Used as a scale source for the centring math when the window's own
/// scale factor is not yet resolvable.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeLinuxPrimaryMonitorScaleMilli(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_window(handle, |w| {
        use gtk::prelude::MonitorExt;
        let monitor = primary_monitor(w)?;
        Some(monitor.scale_factor().max(1) as jint * 1000)
    })
    .unwrap_or(1000)
}
