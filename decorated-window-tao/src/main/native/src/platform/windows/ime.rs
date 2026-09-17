// Caret-rect plumbing for the Windows IME (#558).
//
// The macOS twin (`platform/macos/ime.rs`) has to swizzle
// `firstRectForCharacterRange:` because AppKit pulls the caret rect from the
// view. IMM32 is push-based instead: the app tells the input context where the
// caret is, and tao already owns that call — `Window::set_ime_position` sets
// both the composition and the candidate window (the latter added for #558).
// So this file only has to convert Compose's caret rect into the point the IME
// should anchor to, and hand it to tao.

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::dpi::PhysicalPosition;

use crate::state::WINDOWS;

/// Anchors the IME windows to the caret, in *window-local physical pixels*
/// with a top-left origin — the same contract as the macOS implementation.
///
/// The anchor is the caret's **bottom** edge (`y + height`): IMM32 places the
/// candidate list with its top-left corner at the given point, so passing the
/// caret's top would draw the list over the line being typed.
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
