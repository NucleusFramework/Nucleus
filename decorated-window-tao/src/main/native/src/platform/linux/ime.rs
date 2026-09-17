// Caret-rect plumbing for the Linux IME (#558).
//
// The three backends split by how the platform asks for the caret. macOS is
// pull-based, so `platform/macos/ime.rs` swizzles
// `firstRectForCharacterRange:` because AppKit asks the view where the caret
// is. Windows and Linux are push-based — the app tells the input context — and
// tao already owns that call.
//
// Where Linux differs from Windows is the shape of the answer. IMM32 takes a
// *point* and hangs the candidate list off it, so `platform/windows/ime.rs`
// sends the caret's bottom edge and is done. GTK takes the *area* the cursor
// covers and the input method keeps its own windows off that area, so the full
// rect goes through: pass a bare point and the "Tab to select" hint sits on top
// of the composition it is describing. Hence `set_ime_cursor_area` rather than
// `set_ime_position` here, and no bottom-edge adjustment — GTK derives the
// placement from the rect itself.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::dpi::{PhysicalPosition, PhysicalSize};
use tao::platform::unix::WindowExtUnix;

use crate::state::WINDOWS;

/// Reports the caret rectangle to the input method, in *window-local physical
/// pixels* with a top-left origin — the same contract as the macOS and Windows
/// implementations, which is why the JVM side passes the same four numbers to
/// all three.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetImeRect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x_px: jint,
    y_px: jint,
    w_px: jint,
    h_px: jint,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        window.set_ime_cursor_area(
            PhysicalPosition::new(x_px, y_px),
            PhysicalSize::new(w_px.max(0), h_px.max(0)),
        );
    }
}
