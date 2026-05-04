// Cross-platform cursor JNI export.
//
// macOS / Windows go directly through Tao's `set_cursor_icon`; Linux delegates
// to a dedicated GDK/XInput2 helper (see `platform::linux::cursor`) because
// GTK 3's per-device cursor table beats legacy `XDefineCursor`.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

#[cfg(not(target_os = "linux"))]
use tao::window::CursorIcon;

#[cfg(not(target_os = "linux"))]
use crate::state::WINDOWS;

/// Mirrors `TaoCursorIcon` on the JVM side. Numeric codes only, so the JNI
/// signature stays `(JI)V`. Subset chosen to cover what Compose Desktop's
/// `PointerIcon` constants surface — additional shapes can be added later.
#[cfg(not(target_os = "linux"))]
fn cursor_from_code(code: jint) -> CursorIcon {
    match code {
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
        _ => CursorIcon::Default,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetCursorIcon(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    #[cfg(target_os = "linux")]
    {
        crate::platform::linux::cursor::set_cursor(handle as u64, code);
    }
    #[cfg(not(target_os = "linux"))]
    {
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            window.set_cursor_icon(cursor_from_code(code));
        }
    }
}
