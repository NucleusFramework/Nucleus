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
use jni::sys::{jint, jlong, jstring};
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

/// Inverse of [cursor_from_code] for the icons it produces; `None` for the
/// rest, which have no `TaoCursorIcon` code.
#[cfg(target_os = "macos")]
fn code_from_cursor(icon: CursorIcon) -> Option<jint> {
    Some(match icon {
        CursorIcon::Arrow => 0,
        CursorIcon::Text => 1,
        CursorIcon::Hand => 2,
        CursorIcon::Crosshair => 3,
        CursorIcon::Wait => 4,
        CursorIcon::Move => 5,
        CursorIcon::NotAllowed => 6,
        CursorIcon::Help => 7,
        CursorIcon::Progress => 8,
        CursorIcon::EwResize => 9,
        CursorIcon::NsResize => 10,
        CursorIcon::NeswResize => 11,
        CursorIcon::NwseResize => 12,
        CursorIcon::Grab => 13,
        CursorIcon::Grabbing => 14,
        _ => return None,
    })
}

/// `tao::platform::macos::set_cursor_hook`: Tao's cursor rects resolve icons
/// through Nucleus' table (`nucleus_tao_cursors.h`), so the shape AppKit
/// re-asserts is the one `nucleus_tao_set_cursor_icon` just set (#746).
/// Icons without a code keep Tao's arrow fallback (null).
#[cfg(target_os = "macos")]
pub(crate) fn tao_cursor_hook(icon: CursorIcon) -> *mut std::ffi::c_void {
    match code_from_cursor(icon) {
        Some(code) => unsafe { crate::platform::macos::ffi::nucleus_tao_cursor_ptr(code) },
        None => std::ptr::null_mut(),
    }
}

/// Headful-suite diagnostic: what the cursor for [code] looks like (hotspot,
/// size, pixel hash), or `[NSCursor currentCursor]` when [code] is negative.
/// `null` off macOS.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeDiagCursorSignature(
    env: JNIEnv,
    _class: JClass,
    code: jint,
) -> jstring {
    #[cfg(target_os = "macos")]
    {
        let mut buffer = [0 as std::ffi::c_char; 128];
        unsafe {
            crate::platform::macos::ffi::nucleus_tao_diag_cursor_signature(code, buffer.as_mut_ptr(), buffer.len());
        }
        let signature = unsafe { std::ffi::CStr::from_ptr(buffer.as_ptr()) }.to_string_lossy();
        env.new_string(signature.as_ref()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (env, code);
        std::ptr::null_mut()
    }
}
