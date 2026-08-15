// Headful e2e: synthesize a GdkEventScroll that matches the GTK mouse-wheel
// shape (direction set, delta_x/delta_y left at 0). Stage-1 scene tests
// inject TaoPointerScrollEvent past this path and cannot see the #533 drop.

use glib::translate::{ToGlibPtr, ToGlibPtrMut};
use gtk::gdk;
use gtk::prelude::*;
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use crate::state::WINDOWS;

/// `GdkScrollDirection` values, kept in a single match so a bad JNI argument
/// cannot invent a direction GDK would never emit.
fn gdk_scroll_direction(raw: jint) -> Option<i32> {
    match raw {
        0 => Some(gdk::ffi::GDK_SCROLL_UP),
        1 => Some(gdk::ffi::GDK_SCROLL_DOWN),
        2 => Some(gdk::ffi::GDK_SCROLL_LEFT),
        3 => Some(gdk::ffi::GDK_SCROLL_RIGHT),
        4 => Some(gdk::ffi::GDK_SCROLL_SMOOTH),
        _ => None,
    }
}

fn gtk_window_for(handle: jlong) -> Option<gtk::Window> {
    use tao::platform::unix::WindowExtUnix;

    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&(handle as u64))?;
    Some(window.gtk_window().clone())
}

fn attach_pointer_device(event: &mut gdk::Event, gdk_window: &gdk::Window) {
    use gdk::prelude::*;
    if let Some(pointer) = gdk_window
        .display()
        .default_seat()
        .and_then(|seat| seat.pointer())
    {
        event.set_device(Some(&pointer));
    }
}

fn event_mut_ptr(event: &mut gdk::Event) -> *mut gdk::ffi::GdkEvent {
    event.to_glib_none_mut().0
}

unsafe fn write_scroll_fields(
    event: &mut gdk::Event,
    gdk_window: &gdk::Window,
    x: f64,
    y: f64,
    direction: i32,
    delta_x: f64,
    delta_y: f64,
) {
    let ptr = event_mut_ptr(event) as *mut gdk::ffi::GdkEventScroll;
    (*ptr).window = gdk_window.to_glib_full();
    (*ptr).send_event = 1;
    (*ptr).x = x;
    (*ptr).y = y;
    (*ptr).x_root = x;
    (*ptr).y_root = y;
    (*ptr).direction = direction;
    (*ptr).delta_x = delta_x;
    (*ptr).delta_y = delta_y;
}

/// Linux only, headful e2e: deliver a synthetic `GDK_SCROLL` to the GtkWindow
/// behind [handle], going through `connect_scroll_event` — the same path a
/// real mouse wheel uses.
///
/// [direction] is a `GdkScrollDirection` (`0=UP … 4=SMOOTH`). Discrete
/// directions force `delta_x`/`delta_y` to 0, which is the GTK 3 mouse-wheel
/// payload (`flush_discrete_scroll_event`). SMOOTH uses
/// [delta_x_milli]/[delta_y_milli] as thousandths.
///
/// Coordinates are widget-local. Compose's last pointer is positioned by
/// the caller (CURSOR_MOVED) so this function only has to deliver the
/// scroll. Returns JNI `true` when the signal was emitted on a realized
/// window.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxInjectGdkScroll(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    direction: jint,
    delta_x_milli: jint,
    delta_y_milli: jint,
    x: jint,
    y: jint,
) -> jboolean {
    let Some(direction) = gdk_scroll_direction(direction) else {
        return jboolean::from(false);
    };
    let Some(gtk_window) = gtk_window_for(handle) else {
        return jboolean::from(false);
    };
    let Some(gdk_window) = gtk_window.window() else {
        return jboolean::from(false);
    };

    let x = x as f64;
    let y = y as f64;
    let (delta_x, delta_y) = if direction == gdk::ffi::GDK_SCROLL_SMOOTH {
        (delta_x_milli as f64 / 1000.0, delta_y_milli as f64 / 1000.0)
    } else {
        // Discrete mouse wheel: GTK leaves the delta at zero.
        (0.0, 0.0)
    };

    let mut scroll = gdk::Event::new(gdk::EventType::Scroll);
    unsafe {
        write_scroll_fields(&mut scroll, &gdk_window, x, y, direction, delta_x, delta_y);
    }
    attach_pointer_device(&mut scroll, &gdk_window);

    // Emit the same `scroll-event` signal `connect_scroll_event` is wired
    // to. `gtk_widget_event` / `gtk_main_do_event` can drop a send_event on
    // Wayland (no device grab, missing SMOOTH_SCROLL_MASK); the signal is
    // the exact handler the bug lives in.
    let _handled: bool = glib::prelude::ObjectExt::emit_by_name(&gtk_window, "scroll-event", &[&scroll]);

    jboolean::from(true)
}
