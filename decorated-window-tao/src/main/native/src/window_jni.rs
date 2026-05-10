// Cross-platform JNI exports for window lifecycle and properties.
//
// All of these are thin "post a UserEvent on the loop proxy" or "look up the
// Window in WINDOWS and read state" wrappers. Symbol naming follows the
// package `io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge`.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::events::UserEvent;
use crate::state::{clear_event_loop_proxy, send_user_event, JAVA_VM, WINDOWS};

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRunBlocking(
    env: JNIEnv,
    _class: JClass,
    callback: jni::objects::JObject,
) {
    if let Ok(vm) = env.get_java_vm() {
        let _ = JAVA_VM.set(vm);
    }
    if let Ok(global) = env.new_global_ref(&callback) {
        if let Ok(mut guard) = crate::state::EVENT_CALLBACK.lock() {
            *guard = Some(global);
        }
    }
    crate::event_loop::init_windows_map();

    // Tao's NSApplication-backed event loop must run on the macOS main thread.
    // GraalVM native-image binaries packaged via jpackage / Compose Desktop
    // sometimes invoke main() on a JVM worker thread, so we sync-dispatch
    // ourselves onto the main queue when needed. On Windows there's no such
    // constraint — Tao installs its WndProc on whatever thread runs the event
    // loop and the OS message pump works on any thread.
    #[cfg(target_os = "macos")]
    {
        if crate::platform::macos::is_main_thread() {
            crate::event_loop::run_event_loop_blocking();
        } else {
            crate::platform::macos::dispatch_run_event_loop_on_main();
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        crate::event_loop::run_event_loop_blocking();
    }

    // Event loop has exited (UserEvent::Exit). Drop the Kotlin callback ref so
    // DeleteGlobalRef runs and the JVM can collect it. JAVA_VM stays in its
    // OnceCell — JavaVM is just a pointer wrapper, not a JVM-side resource.
    if let Ok(mut guard) = crate::state::EVENT_CALLBACK.lock() {
        guard.take();
    }
    clear_event_loop_proxy();
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeCreateWindow(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    title: JString,
    width: jdouble,
    height: jdouble,
    decorations: jboolean,
    resizable: jboolean,
    visible: jboolean,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    send_user_event(UserEvent::CreateWindow {
        handle: handle as u64,
        title,
        width,
        height,
        decorations: decorations != JNI_FALSE,
        resizable: resizable != JNI_FALSE,
        visible: visible != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetVisible(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    visible: jboolean,
) {
    send_user_event(UserEvent::SetVisible {
        handle: handle as u64,
        visible: visible != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetTitle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    title: JString,
) {
    let title: String = match env.get_string(&title) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    send_user_event(UserEvent::SetTitle {
        handle: handle as u64,
        title,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRequestRedraw(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    send_user_event(UserEvent::RequestRedraw {
        handle: handle as u64,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeRequestClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    send_user_event(UserEvent::RequestClose {
        handle: handle as u64,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeExit(
    _env: JNIEnv,
    _class: JClass,
) {
    send_user_event(UserEvent::Exit);
}

/// Wakes the Tao event loop so a queued `TaoMainDispatcher` block runs on the
/// next tick. Cheap no-op when the loop is already busy.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeWake(
    _env: JNIEnv,
    _class: JClass,
) {
    send_user_event(UserEvent::Wake);
}

/// Brings the window to the foreground and gives it keyboard focus. On Win32
/// this also de-minimizes the window so the foreground activation actually
/// takes effect (a minimized HWND ignores `SetForegroundWindow`).
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeFocus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    send_user_event(UserEvent::Focus {
        handle: handle as u64,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

/// Starts a native window-drag session.
///
/// On macOS we go through our own ObjC helper rather than `tao::Window::
/// drag_window()` for two reasons (mirrors the JNI backend):
///   1. The helper uses the latched NSLeftMouseDown event, not whichever event
///      AppKit happens to be processing (Tao calls `[NSApp currentEvent]`,
///      which is a NSLeftMouseDragged when this is invoked from a Compose
///      Move handler — `performWindowDragWithEvent:` documents a mouseDown).
///   2. The helper posts the call via `dispatch_async(dispatch_get_main_queue())`
///      so AppKit's modal event-tracking loop only starts on the next runloop
///      iteration. This gives Compose's child gesture detectors (e.g.
///      reorderable's drag handle) a chance to consume the in-flight gesture
///      before the modal loop steals it.
///
/// On Windows / Linux we keep the synchronous Tao call — those backends
/// don't drive a modal AppKit-style loop and the Compose-side hit-testing is
/// reliable enough.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeDragWindow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    #[cfg(target_os = "macos")]
    {
        use tao::platform::macos::WindowExtMacOS;
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            let ns_window = window.ns_window() as i64;
            unsafe { crate::platform::macos::ffi::nucleus_tao_start_window_drag(ns_window) };
        }
        return;
    }

    #[cfg(not(target_os = "macos"))]
    {
        let guard = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(map) = guard.as_ref() else { return };
        if let Some(window) = map.get(&(handle as u64)) {
            let _ = window.drag_window();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsMaximized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(map) = guard.as_ref() else {
        return JNI_FALSE;
    };
    if let Some(window) = map.get(&(handle as u64)) {
        if window.is_maximized() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMaximized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    maximized: jboolean,
) {
    send_user_event(UserEvent::SetMaximized {
        handle: handle as u64,
        maximized: maximized != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMinimized(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    minimized: jboolean,
) {
    send_user_event(UserEvent::SetMinimized {
        handle: handle as u64,
        minimized: minimized != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetAlwaysOnTop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    always_on_top: jboolean,
) {
    send_user_event(UserEvent::SetAlwaysOnTop {
        handle: handle as u64,
        always_on_top: always_on_top != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetFocusable(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    focusable: jboolean,
) {
    send_user_event(UserEvent::SetFocusable {
        handle: handle as u64,
        focusable: focusable != JNI_FALSE,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetMinInnerSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jdouble,
    height: jdouble,
) {
    send_user_event(UserEvent::SetMinInnerSize {
        handle: handle as u64,
        width,
        height,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetWindowIcon(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
    pixels: jni::objects::JByteArray,
) {
    let buf = if pixels.is_null() || width <= 0 || height <= 0 {
        Vec::new()
    } else {
        match env.convert_byte_array(&pixels) {
            Ok(b) => b,
            Err(_) => return,
        }
    };
    send_user_event(UserEvent::SetWindowIcon {
        handle: handle as u64,
        width: width.max(0) as u32,
        height: height.max(0) as u32,
        pixels: buf,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetInnerSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jdouble,
    height: jdouble,
) {
    send_user_event(UserEvent::SetInnerSize {
        handle: handle as u64,
        width,
        height,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetOuterPosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jdouble,
    y: jdouble,
) {
    send_user_event(UserEvent::SetOuterPosition {
        handle: handle as u64,
        x,
        y,
    });
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeIsFullscreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(map) = guard.as_ref() else {
        return JNI_FALSE;
    };
    if let Some(w) = map.get(&(handle as u64)) {
        if w.fullscreen().is_some() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeSetFullscreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fullscreen: jboolean,
) {
    send_user_event(UserEvent::SetFullscreen {
        handle: handle as u64,
        fullscreen: fullscreen != JNI_FALSE,
    });
}

/// Returns the current scale factor of the window (Retina = 2.0, 3.0…).
/// Encoded as `(scale * 1000) as i32` to keep a single JNI signature.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeScaleFactor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let guard = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return 1000,
    };
    let Some(map) = guard.as_ref() else {
        return 1000;
    };
    let Some(window) = map.get(&(handle as u64)) else {
        return 1000;
    };
    (window.scale_factor() * 1000.0) as jint
}
