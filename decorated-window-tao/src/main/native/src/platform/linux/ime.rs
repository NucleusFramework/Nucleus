// Caret-rect plumbing for the Linux IME (#558).
//
// The three backends split like this: macOS is pull-based, so
// `platform/macos/ime.rs` swizzles `firstRectForCharacterRange:` because
// AppKit asks the view where the caret is. Windows and Linux are push-based —
// the app tells the input context — and tao already owns that call, so both
// only have to convert Compose's caret rect into the anchor point and hand it
// over. This file is therefore the twin of `platform/windows/ime.rs`; the
// GTK-specific part (turning the point back into a `GdkRectangle` and calling
// `gtk_im_context_set_cursor_location`) lives in tao's Linux event loop,
// which is where the input context is owned.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::dpi::PhysicalPosition;

use crate::state::WINDOWS;

/// Anchors the IME candidate window to the caret, in *window-local physical
/// pixels* with a top-left origin — the same contract as the macOS and Windows
/// implementations.
///
/// The anchor is the caret's **bottom** edge (`y + height`): GTK places the
/// candidate list below the point it is given, so passing the caret's top
/// would draw the list over the line being typed.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetImeRect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x_px: jint,
    y_px: jint,
    _w_px: jint,
    h_px: jint,
) {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(map) = guard.as_ref() else { return };
    if let Some(window) = map.get(&(handle as u64)) {
        window.set_ime_position(PhysicalPosition::new(x_px, y_px + h_px));
    }
}
