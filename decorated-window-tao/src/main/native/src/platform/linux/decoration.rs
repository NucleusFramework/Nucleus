// Linux equivalent of `windows/nucleus_tao_windows_deco.c` and
// `macos/decoration.m` for the slice consumed by `DecoratedDialog`:
// owner relationship + outer geometry query.
//
// On Linux/GTK the analogue of Win32 `SetWindowLongPtrW(GWLP_HWNDPARENT)` /
// AppKit `[NSWindow addChildWindow:]` is `gtk_window_set_transient_for`. It
// gives us:
//   - child stays above owner in z-order
//   - child follows the owner across minimisation / workspace switches
//   - focus returns to the owner when the child is closed
//   - parent stays interactive (we don't call `set_modal`)
// Combined with `set_skip_taskbar_hint(true)` and
// `set_destroy_with_parent(true)` this matches the non-modal `JDialog`
// semantics used by `decorated-window-jni`'s `DecoratedDialog`.
//
// All entry points run on the GTK main thread (= Tao event-loop thread =
// Compose dispatcher thread). They look up Tao windows in the shared
// `WINDOWS` map, exactly like `monitor.rs` / `handles.rs`.

use jni::objects::JClass;
use jni::sys::{jlong, jlongArray};
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

/// Wires `child` as a transient of `owner`. Pass `0` for `owner_handle` to
/// clear the relationship.
///
/// Tao keeps the GTK window alive for the lifetime of the entry in the
/// `WINDOWS` map, so the references obtained from `gtk_window()` are valid
/// for the duration of this call.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeLinuxSetDialogOwner(
    _env: JNIEnv,
    _class: JClass,
    child_handle: jlong,
    owner_handle: jlong,
) {
    use gtk::prelude::GtkWindowExt;

    let Ok(guard) = WINDOWS.lock() else { return };
    let Some(map) = guard.as_ref() else { return };
    let Some(child) = map.get(&(child_handle as u64)) else { return };
    let child_gtk = child.gtk_window();

    if owner_handle == 0 {
        GtkWindowExt::set_transient_for(child_gtk, None::<&gtk::Window>);
        return;
    }

    let Some(owner) = map.get(&(owner_handle as u64)) else { return };
    let owner_gtk = owner.gtk_window();

    GtkWindowExt::set_transient_for(child_gtk, Some(owner_gtk));
    // Mirrors the Win32 `WS_EX_TOOLWINDOW`-ish behaviour of an owned
    // dialog: keep the dialog out of the taskbar — the owner already
    // represents the app there.
    GtkWindowExt::set_skip_taskbar_hint(child_gtk, true);
    // If the owner closes (or is destroyed), bring the dialog down with it
    // so the user can never end up with an orphan transient.
    GtkWindowExt::set_destroy_with_parent(child_gtk, true);
}

/// Returns `[x, y, width, height]` of the window's outer (decoration-inclusive)
/// bounds in physical pixels with a top-left origin. Matches the shape
/// returned by the Windows / macOS counterparts so the centring math in
/// `DecoratedDialog.kt` stays portable. Returns `null` when the geometry
/// isn't yet resolvable (window not realised / hidden on Wayland).
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeLinuxGetWindowRect(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let values = with_window(handle, |window| {
        let pos = window.outer_position().ok()?;
        let size = window.outer_size();
        Some([
            pos.x as i64,
            pos.y as i64,
            size.width as i64,
            size.height as i64,
        ])
    });
    let Some(values) = values else {
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
