// Cross-platform cursor JNI export.
//
// Every platform goes through Tao's `set_cursor_icon`. On Linux that call is
// channel-dispatched to the GTK main thread (`WindowRequest::CursorIcon` →
// `gdk_window_set_cursor` with a themed cursor), which is the only safe way
// in: the previous per-device GDK/XInput2 helper ran directly on the calling
// JVM thread, and GTK 3 is not thread-safe — on Wayland the call was a silent
// no-op, so the hover resize cursor never appeared even though the resize
// drag itself (channel-dispatched like this) worked fine.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::window::CursorIcon;

use crate::state::WINDOWS;

/// Mirrors `TaoCursorIcon` on the JVM side. Numeric codes only, so the JNI
/// signature stays `(JI)V`. Covers what Compose Desktop's `PointerIcon`
/// constants surface, plus the shapes Nucleus exposes itself through
/// `TaoPointerIcons` (grab / grabbing for drag handles, move, …).
/// On macOS, code 0 is an explicit arrow cursor rather than Tao's null
/// `Default`, matching Compose AWT's concrete `Cursor.DEFAULT_CURSOR`.
fn cursor_from_code(code: jint) -> CursorIcon {
    match code {
        #[cfg(target_os = "macos")]
        0 => CursorIcon::Arrow,
        1 => CursorIcon::Text,
        2 => CursorIcon::Hand,
        3 => CursorIcon::Crosshair,
        4 => CursorIcon::Wait,
        5 => CursorIcon::Move,
        6 => CursorIcon::NotAllowed,
        7 => CursorIcon::Help,
        8 => CursorIcon::Progress,
        9 => CursorIcon::EwResize,
        10 => CursorIcon::NsResize,
        11 => CursorIcon::NeswResize,
        12 => CursorIcon::NwseResize,
        13 => CursorIcon::Grab,
        14 => CursorIcon::Grabbing,
        #[cfg(target_os = "macos")]
        _ => CursorIcon::Arrow,
        #[cfg(not(target_os = "macos"))]
        _ => CursorIcon::Default,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetCursorIcon(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        window.set_cursor_icon(cursor_from_code(code));
        #[cfg(target_os = "macos")]
        unsafe {
            crate::platform::macos::ffi::nucleus_tao_set_cursor_icon(code);
        }
    }
}
