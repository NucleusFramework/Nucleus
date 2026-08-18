// IMM32 caret-rect plumbing (#558) so the native IME candidate window follows
// the caret instead of sitting at the client area's top-left corner.
//
// Tao's own `Window::set_ime_position` only pushes a `COMPOSITIONFORM`, which
// positions the *inline composition* window; the candidate list of MS-IME /
// Pinyin / Hangul is placed from the `CANDIDATEFORM`. We set both, mirroring
// what winit's `ImeContext::set_ime_cursor_area` does: the caret rect becomes
// an exclusion zone (`CFS_EXCLUDE`) so the candidate list never covers the
// text being edited.

use std::ffi::c_void;

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use tao::platform::windows::WindowExtWindows;

use windows::Win32::Foundation::{HWND, POINT, RECT};
use windows::Win32::UI::Input::Ime::{
    ImmGetContext, ImmNotifyIME, ImmReleaseContext, ImmSetCandidateWindow,
    ImmSetCompositionWindow, CANDIDATEFORM, CFS_EXCLUDE, CFS_POINT, COMPOSITIONFORM, CPS_CANCEL,
    NI_COMPOSITIONSTR,
};
use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_IMMENABLED};

use crate::state::WINDOWS;

/// Copies the HWND out of the window map so the IMM calls run without holding
/// the global lock.
fn hwnd_for(handle: jlong) -> Option<HWND> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&(handle as u64))?;
    Some(HWND(window.hwnd() as *mut c_void))
}

fn ime_enabled() -> bool {
    unsafe { GetSystemMetrics(SM_IMMENABLED) != 0 }
}

/// Anchors the IME UI at the given window-local rect in *physical pixels*
/// (top-left origin). The Compose scene covers the whole client area on this
/// backend — the custom title bar is drawn inside it and reports no platform
/// inset — so root coordinates are already client coordinates.
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
    if !ime_enabled() {
        return;
    }
    let Some(hwnd) = hwnd_for(handle) else { return };
    let area = RECT {
        left: x_px,
        top: y_px,
        right: x_px + w_px.max(1),
        bottom: y_px + h_px.max(1),
    };
    // Top-left of the caret, for both forms — same anchor winit and Tao's own
    // `set_ime_position_physical` use. `CFS_POINT` documents `ptCurrentPos` as
    // the upper-left corner of the composition window, so the OS-drawn
    // composition string stays on the caret's line; pushing the candidate list
    // *below* the caret is the job of the `CFS_EXCLUDE` area, not of a
    // bottom-anchored point.
    let spot = POINT {
        x: area.left,
        y: area.top,
    };
    unsafe {
        let himc = ImmGetContext(hwnd);
        if himc.is_invalid() {
            return;
        }
        let candidate = CANDIDATEFORM {
            dwIndex: 0,
            dwStyle: CFS_EXCLUDE,
            ptCurrentPos: spot,
            rcArea: area,
        };
        let _ = ImmSetCandidateWindow(himc, &candidate);
        let composition = COMPOSITIONFORM {
            dwStyle: CFS_POINT,
            ptCurrentPos: spot,
            rcArea: area,
        };
        let _ = ImmSetCompositionWindow(himc, &composition);
        let _ = ImmReleaseContext(hwnd, himc);
    }
}

/// Drops any in-flight composition when the focused field's input session ends.
/// Without this the candidate window stays up over unfocused content, and the
/// pending composition would commit into whatever gains focus next — same
/// blur behaviour as Chromium's `ImeInput::CancelIME`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeCancelImeComposition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if !ime_enabled() {
        return;
    }
    let Some(hwnd) = hwnd_for(handle) else { return };
    unsafe {
        let himc = ImmGetContext(hwnd);
        if himc.is_invalid() {
            return;
        }
        let _ = ImmNotifyIME(himc, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
        let _ = ImmReleaseContext(hwnd, himc);
    }
}
