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

use jni::objects::{JClass, JObject};
use jni::sys::{jint, jlong, jlongArray, jobjectArray};
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

fn display_of(window: &Window) -> gtk::gdk::Display {
    use gtk::prelude::WidgetExt;
    WidgetExt::display(window.gtk_window())
}

fn primary_monitor(window: &Window) -> Option<gtk::gdk::Monitor> {
    let display = display_of(window);
    display.primary_monitor().or_else(|| display.monitor(0))
}

/// Returns `[x, y, width, height]` of the primary monitor's work area
/// (full screen minus panels / docks) in physical pixels with a top-left
/// origin. Falls back to the full monitor geometry when GDK can't report
/// a work area (e.g. some Wayland compositors).
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxPrimaryMonitorWorkArea(
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

/// Returns one tab-separated descriptor per monitor, in GDK enumeration order:
/// `id \t name \t x \t y \t width \t height \t workX \t workY \t workWidth \t
/// workHeight \t scaleMilli \t primary`. Geometry is physical pixels with a
/// top-left origin, matching the Win32 / NSScreen conventions of the sibling
/// bridges; `primary` is `1` or `0`.
///
/// [handle] may be `0`: monitors are a display-wide property, so the default
/// GDK display is used when no window is available (a tray-only app). Returns
/// `null` when GDK has no display at all.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxMonitors(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    let Some(rows) = collect_monitors(handle) else {
        return std::ptr::null_mut();
    };
    match build_string_array(&mut env, &rows) {
        Some(arr) => arr.into_raw(),
        None => std::ptr::null_mut(),
    }
}

fn collect_monitors(handle: jlong) -> Option<Vec<String>> {
    use gtk::gdk::prelude::DisplayExt;
    use gtk::prelude::MonitorExt;

    let display = with_window(handle, |w| Some(display_of(w)))
        .or_else(gtk::gdk::Display::default)?;
    let primary = display.primary_monitor();
    let count = display.n_monitors();
    let mut rows = Vec::with_capacity(count.max(0) as usize);
    for index in 0..count {
        let Some(monitor) = display.monitor(index) else {
            continue;
        };
        let scale = monitor.scale_factor().max(1) as i64;
        let geometry = monitor.geometry();
        let area = monitor.workarea();
        let work = if area.width() > 0 && area.height() > 0 {
            area
        } else {
            geometry
        };
        // GDK reports logical pixels on HiDPI; scale up to physical.
        let model = monitor.model().map(|s| s.to_string()).unwrap_or_default();
        let manufacturer = monitor
            .manufacturer()
            .map(|s| s.to_string())
            .unwrap_or_default();
        let id = if model.is_empty() {
            format!("monitor-{index}")
        } else {
            model.clone()
        };
        let name = match (manufacturer.as_str(), model.as_str()) {
            ("", "") => id.clone(),
            ("", m) => m.to_string(),
            (mf, "") => mf.to_string(),
            (mf, m) => format!("{mf} {m}"),
        };
        // `Monitor` has no identity comparison in gdk3, so the primary flag is
        // matched on geometry — two monitors cannot share an origin.
        let is_primary = primary
            .as_ref()
            .map(|p| p.geometry() == geometry)
            .unwrap_or(index == 0);
        rows.push(encode_monitor(
            &id,
            &name,
            [
                geometry.x() as i64 * scale,
                geometry.y() as i64 * scale,
                geometry.width() as i64 * scale,
                geometry.height() as i64 * scale,
            ],
            [
                work.x() as i64 * scale,
                work.y() as i64 * scale,
                work.width() as i64 * scale,
                work.height() as i64 * scale,
            ],
            (scale * 1000) as i64,
            is_primary,
        ));
    }
    Some(rows)
}

fn encode_monitor(
    id: &str,
    name: &str,
    bounds: [i64; 4],
    work: [i64; 4],
    scale_milli: i64,
    primary: bool,
) -> String {
    // Tabs are the separator, so they must not survive inside a display name.
    let sanitize = |s: &str| s.replace(['\t', '\n'], " ");
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
        sanitize(id),
        sanitize(name),
        bounds[0],
        bounds[1],
        bounds[2],
        bounds[3],
        work[0],
        work[1],
        work[2],
        work[3],
        scale_milli,
        if primary { 1 } else { 0 },
    )
}

fn build_string_array<'a>(env: &mut JNIEnv<'a>, items: &[String]) -> Option<JObject<'a>> {
    let cls = env.find_class("java/lang/String").ok()?;
    let arr = env
        .new_object_array(items.len() as i32, cls, JObject::null())
        .ok()?;
    for (index, item) in items.iter().enumerate() {
        let js = env.new_string(item).ok()?;
        env.set_object_array_element(&arr, index as i32, js).ok()?;
    }
    Some(arr.into())
}

/// Returns the primary monitor's scale factor encoded as `(scale * 1000)`.
/// Used as a scale source for the centring math when the window's own
/// scale factor is not yet resolvable.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxPrimaryMonitorScaleMilli(
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
