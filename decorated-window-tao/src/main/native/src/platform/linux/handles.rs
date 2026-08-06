// X11 / Wayland window-handle JNI export. The JVM-side EGL helper picks the
// right `EGLNativeWindowType` (Window / wl_egl_window) based on the backend
// tag returned in slot 0.

use jni::objects::JClass;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;

use raw_window_handle::{HasDisplayHandle, HasWindowHandle, RawDisplayHandle, RawWindowHandle};
use tao::window::Window;

use crate::state::WINDOWS;

/// Returns the underlying X11 / Wayland window handles so the JVM can attach
/// an EGL context via the `nucleus_tao_egl` helper.
///
/// The returned `long[]` has length 3 with one of the following shapes:
///   `[0, 0, 0]`            → handle unavailable (window not yet realised).
///   `[1, display, xid]`    → Xlib backend; `display` is `Display*`,
///                            `xid` is the X11 `Window`.
///   `[2, display, surface]` → Wayland backend; `display` is `wl_display*`,
///                             `surface` is `wl_surface*`.
///
/// Tao's GTK-based Linux windowing layer wraps both X11 and Wayland — the
/// concrete backend is decided at GDK init time. We mirror its
/// `raw_window_handle_rwh_06`/`raw_display_handle_rwh_06` impls and expose the
/// underlying pointers directly.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxHandles(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    // Resolve handles inside the WINDOWS lock so the Tao Window can't be
    // dropped between the `window_handle()` and `display_handle()` calls.
    let mut out = [0i64; 3];
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                fill_linux_handles(window, &mut out);
            }
        }
    }
    match env.new_long_array(3) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &out);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn fill_linux_handles(window: &Window, out: &mut [jlong; 3]) {
    let Ok(wh) = window.window_handle() else { return };
    let Ok(dh) = window.display_handle() else { return };
    match (wh.as_raw(), dh.as_raw()) {
        (RawWindowHandle::Xlib(w), RawDisplayHandle::Xlib(_)) => {
            // Tao's `raw_display_handle_rwh_06` calls `XOpenDisplay(NULL)`
            // and returns a *fresh* X11 connection. GLX requires the context,
            // drawable and display to all share the same connection — using
            // tao's display with a GDK-owned XID makes `glXMakeCurrent` fail
            // silently. Pull GDK's actual `Display*` via `gdk_x11_*`.
            out[0] = 1;
            out[1] = gdk_x11_display_for_window(window).unwrap_or(0);
            out[2] = w.window as jlong;
        }
        (RawWindowHandle::Wayland(w), RawDisplayHandle::Wayland(d)) => {
            out[0] = 2;
            out[1] = d.display.as_ptr() as jlong;
            out[2] = w.surface.as_ptr() as jlong;
        }
        _ => {}
    }
}

/// Returns the underlying `GtkApplicationWindow*` (cast to `jlong`)
/// for the given Tao window, or 0 if the window is unavailable. The
/// pointer is the raw GObject* used by `libgtk-3`, suitable for
/// passing to the C-side widget embedding helpers in
/// `nucleus_tao_linux_widget.c`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxGtkWindow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    use tao::platform::unix::WindowExtUnix;

    let mut out: jlong = 0;
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                let gtk_window = window.gtk_window();
                let raw: *mut gtk::ffi::GtkWindow =
                    glib::translate::ToGlibPtr::<*mut gtk::ffi::GtkWindow>::to_glib_none(
                        gtk_window,
                    ).0;
                out = raw as jlong;
            }
        }
    }
    out
}

/// Returns the origin of the content area — the child (default vbox) GTK
/// allocated inside any client-side decorations — in logical toplevel
/// coordinates, packed as `(x << 32) | (y & 0xffff_ffff)`. `(0, 0)` for plain
/// undecorated windows; equal to the theme's shadow margins when the
/// yaru.dart-style hidden-titlebar CSD is active. The EGL host positions the
/// content `wl_subsurface` at this offset so it fills exactly the visible
/// window area, leaving the margin ring to GTK's own shadow rendering.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxContentOrigin(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    let mut x: i32 = 0;
    let mut y: i32 = 0;
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                if let Some(child) = window.gtk_window().child() {
                    let alloc = child.allocation();
                    // Pre-first-allocate the child reports a 1×1 dummy at
                    // (0,0) — the fallthrough (0,0) is the correct answer.
                    if alloc.width() > 1 || alloc.height() > 1 {
                        x = alloc.x();
                        y = alloc.y();
                    }
                }
            }
        }
    }
    ((x as i64) << 32) | ((y as i64) & 0xffff_ffff)
}

/// Styles the GTK-drawn client-side decorations to match the embedder's
/// chrome: rounds the decoration node (shadow frame outline) and the window
/// background to `radius` px on all four corners, so the native frame and the
/// Compose-carved content corners coincide exactly. Same mechanism
/// yaru_window_linux uses for `setBackground` (a `GtkCssProvider` on the
/// window's style context). No-op if the CSS fails to parse.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxSetCsdCornerRadius(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    radius: jint,
) {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                let r = radius.max(0);
                let provider = gtk::CssProvider::new();
                let css = format!(
                    "decoration {{ border-radius: {r}px; }} window.csd {{ border-radius: {r}px; }}",
                );
                if provider.load_from_data(css.as_bytes()).is_ok() {
                    window
                        .gtk_window()
                        .style_context()
                        .add_provider(&provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
                }
            }
        }
    }
}

fn gdk_x11_display_for_window(window: &Window) -> Option<jlong> {
    use gtk::prelude::WidgetExt;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    let gdk_display = WidgetExt::display(gtk_window);
    // `gdk_display.to_glib_none().0` returns `*mut gdk_sys::GdkDisplay`. Tao's
    // transitive `gtk` crate doesn't expose the `gdk` crate publicly, so we
    // erase to `*mut c_void` and re-cast — `GdkX11Display` is a
    // newtype over `GdkDisplay` at the C level.
    let raw_display_ptr: *mut std::ffi::c_void =
        glib::translate::ToGlibPtr::<*mut gtk::gdk::ffi::GdkDisplay>::to_glib_none(&gdk_display).0
            as *mut std::ffi::c_void;
    if raw_display_ptr.is_null() {
        return None;
    }
    let xdisplay = unsafe {
        gdk_x11_sys::gdk_x11_display_get_xdisplay(raw_display_ptr as *mut gdk_x11_sys::GdkX11Display)
    };
    if xdisplay.is_null() {
        None
    } else {
        Some(xdisplay as jlong)
    }
}

// ── xdg_foreign export for XDG Desktop Portal parenting ─────────────────────
//
// FileKit / xdg-desktop-portal expect a Wayland parent as `wayland:<token>`,
// where `<token>` is the opaque handle from `xdg_foreign` — NOT a raw
// `wl_surface*`. GDK already owns the exporter (`gdk_wayland_window_export_handle`);
// we surface that string to the JVM and require the caller to keep the export
// alive until portal dialogs complete (`nativeLinuxUnexportXdgForeignHandle`).

use std::ffi::CStr;
use std::os::raw::{c_char, c_void};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Holds the oneshot sender until the compositor delivers the exported handle
/// (or until the export is torn down via [export_destroy]).
struct ExportUserData {
    tx: Mutex<Option<mpsc::Sender<Option<String>>>>,
}

unsafe extern "C" fn export_callback(
    _window: *mut gdk_wayland_sys::GdkWaylandWindow,
    handle: *const c_char,
    user_data: *mut c_void,
) {
    let data = &*(user_data as *const ExportUserData);
    let value = if handle.is_null() {
        None
    } else {
        Some(CStr::from_ptr(handle).to_string_lossy().into_owned())
    };
    if let Ok(mut guard) = data.tx.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(value);
        }
    }
}

unsafe extern "C" fn export_destroy(user_data: *mut c_void) {
    // Drop any leftover sender so a blocked waiter unblocks with RecvError.
    drop(Box::from_raw(user_data as *mut ExportUserData));
}

/// Raw `GdkWindow*` for the Tao window, or null if unavailable / not realized.
fn gdk_window_ptr_for_handle(handle: jlong) -> *mut gtk::gdk::ffi::GdkWindow {
    use gtk::prelude::{DisplayExtManual, WidgetExt};
    use tao::platform::unix::WindowExtUnix;

    let Ok(guard) = WINDOWS.lock() else {
        return std::ptr::null_mut();
    };
    let Some(map) = guard.as_ref() else {
        return std::ptr::null_mut();
    };
    let Some(window) = map.get(&(handle as u64)) else {
        return std::ptr::null_mut();
    };
    let gtk_window = window.gtk_window();
    // Only Wayland has xdg_foreign; X11 uses the XID path instead.
    if !gtk_window.display().backend().is_wayland() {
        return std::ptr::null_mut();
    }
    match WidgetExt::window(gtk_window) {
        Some(gw) => {
            glib::translate::ToGlibPtr::<*mut gtk::gdk::ffi::GdkWindow>::to_glib_none(&gw).0
        }
        None => std::ptr::null_mut(),
    }
}

/// Starts `gdk_wayland_window_export_handle` on [gdk_ptr]. Returns whether GDK
/// accepted the export request. On `false`, [user_data_ptr] is still owned by
/// the caller and must be freed; on `true`, ownership moved to GDK (freed by
/// [export_destroy] on unexport / window destroy).
unsafe fn start_export(
    gdk_ptr: *mut gtk::gdk::ffi::GdkWindow,
    user_data_ptr: *mut ExportUserData,
) -> bool {
    if gdk_ptr.is_null() {
        return false;
    }
    let ok = gdk_wayland_sys::gdk_wayland_window_export_handle(
        gdk_ptr as *mut gdk_wayland_sys::GdkWaylandWindow,
        Some(export_callback),
        user_data_ptr as *mut c_void,
        Some(export_destroy),
    );
    ok != glib::ffi::GFALSE
}

/// Blocks until the compositor returns an xdg_foreign handle (or [timeout_ms]
/// elapses). Safe from the Tao/GTK main thread (nested main-context iteration)
/// and from worker threads (`MainContext::invoke` + channel wait).
///
/// Returns a JVM string with the **unprefixed** opaque handle, or null on
/// failure / timeout / non-Wayland.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxExportXdgForeignHandle(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    timeout_ms: jint,
) -> jni::sys::jstring {
    let timeout = Duration::from_millis(timeout_ms.max(1) as u64);
    let (tx, rx) = mpsc::channel::<Option<String>>();

    let started_ok = Arc::new(AtomicBool::new(false));
    let invoke_done = Arc::new(AtomicBool::new(false));

    // Allocate ExportUserData inside the main-context closure so we never need
    // to Send a raw pointer across threads (MainContext::invoke requires Send).
    let run_export = {
        let started_ok = started_ok.clone();
        let invoke_done = invoke_done.clone();
        move || {
            let user_data = Box::new(ExportUserData {
                tx: Mutex::new(Some(tx)),
            });
            let user_data_ptr = Box::into_raw(user_data);
            let gdk_ptr = gdk_window_ptr_for_handle(handle);
            let ok = unsafe { start_export(gdk_ptr, user_data_ptr) };
            if !ok {
                // GDK rejected the export — reclaim user_data and unblock.
                let data = unsafe { Box::from_raw(user_data_ptr) };
                if let Ok(mut guard) = data.tx.lock() {
                    if let Some(tx) = guard.take() {
                        let _ = tx.send(None);
                    }
                }
                drop(data);
            }
            started_ok.store(ok, Ordering::SeqCst);
            invoke_done.store(true, Ordering::SeqCst);
        }
    };

    let ctx = glib::MainContext::default();
    if ctx.is_owner() {
        // Already on the GTK thread: run inline, then nest-iterate until the
        // wayland handle event arrives (callback) or we time out.
        run_export();
    } else {
        ctx.invoke(run_export);
    }

    let deadline = Instant::now() + timeout;
    loop {
        // Nested iteration when we own the main context so the export's
        // wayland round-trip can complete without deadlocking.
        if ctx.is_owner() {
            while ctx.iteration(false) {}
        }
        match rx.try_recv() {
            Ok(Some(token)) if !token.is_empty() => {
                return match env.new_string(token) {
                    Ok(js) => js.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
            Ok(_) => {
                // Empty / explicit failure.
                return std::ptr::null_mut();
            }
            Err(mpsc::TryRecvError::Disconnected) => {
                return std::ptr::null_mut();
            }
            Err(mpsc::TryRecvError::Empty) => {
                if Instant::now() >= deadline {
                    // Timed out: drop a pending export so we don't leave the
                    // surface exported without a Kotlin owner.
                    if started_ok.load(Ordering::SeqCst) {
                        let unexport = move || {
                            let gdk_ptr = gdk_window_ptr_for_handle(handle);
                            if !gdk_ptr.is_null() {
                                unsafe {
                                    gdk_wayland_sys::gdk_wayland_window_unexport_handle(
                                        gdk_ptr as *mut gdk_wayland_sys::GdkWaylandWindow,
                                    );
                                }
                            }
                        };
                        if ctx.is_owner() {
                            unexport();
                        } else {
                            ctx.invoke(unexport);
                        }
                    }
                    return std::ptr::null_mut();
                }
                // Worker thread: yield so the main loop can run. Main thread:
                // iteration(false) above already drained; brief sleep avoids
                // a hot spin when no events are pending yet.
                if !ctx.is_owner() {
                    std::thread::sleep(Duration::from_millis(2));
                } else {
                    // Block briefly for the next glib source (wayland fd).
                    let _ = ctx.iteration(true);
                }
            }
        }
    }
}

/// Drops the xdg_foreign export for [handle]. No-op when the window is gone,
/// not on Wayland, or was never exported. Safe from any thread.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxUnexportXdgForeignHandle(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ctx = glib::MainContext::default();
    let do_unexport = move || {
        let gdk_ptr = gdk_window_ptr_for_handle(handle);
        if gdk_ptr.is_null() {
            return;
        }
        unsafe {
            gdk_wayland_sys::gdk_wayland_window_unexport_handle(
                gdk_ptr as *mut gdk_wayland_sys::GdkWaylandWindow,
            );
        }
    };
    if ctx.is_owner() {
        do_unexport();
    } else {
        // Fire-and-forget: unexport does not need a reply. Using `invoke` keeps
        // GDK calls on the GTK thread.
        ctx.invoke(do_unexport);
    }
}
