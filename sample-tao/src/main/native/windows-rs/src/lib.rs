//! Windows WebView bridge for the sample-tao "WebView" tab demo,
//! backed by [`wry`] (Tauri's WebView2 wrapper).
//!
//! `nativeCreate(parentHwnd, initialUrl)` creates the WebView as a
//! child of the Tao main HWND and returns the hosting HWND that
//! `NativeView` can reparent into its own bounds. `wry` doesn't object
//! to a `SetParent` because all it cares about is sizing the
//! controller via `ICoreWebView2Controller::put_Bounds`, which we drive
//! from `nativeSetBounds` (called by NativeView's resize hook).
//!
//! Builds into `sample_tao_webview.dll`. Loaded via
//! `NativeLibraryLoader.load("sample_tao_webview")`.
//!
//! Threading: every entry point must run on the Tao main thread (= the
//! thread that created `parentHwnd` and pumps its message loop).
//! WebView2's `ICoreWebView2Controller` is STA — interacting with it
//! off-thread will deadlock or crash.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use raw_window_handle::{
    HasWindowHandle, RawWindowHandle, Win32WindowHandle, WindowHandle,
};
use std::collections::HashMap;
use std::num::NonZeroIsize;
use std::sync::Mutex;
use wry::{dpi, Rect, WebView, WebViewBuilder};

/// `wry::WebView` is `!Send` because it owns an STA-bound
/// `ICoreWebView2Controller`. The bridge contract says every entry
/// point runs on the Tao main thread, so cross-thread access is
/// already forbidden — we wrap it to satisfy `Mutex<...>: Sync` for
/// the global registry. Any actual cross-thread call would be a
/// programming error caught by WebView2 at runtime.
struct StaWebView(WebView);
unsafe impl Send for StaWebView {}

struct Handles {
    by_hwnd: HashMap<isize, StaWebView>,
    last_url: HashMap<isize, String>,
}

static HANDLES: Lazy<Mutex<Handles>> = Lazy::new(|| {
    Mutex::new(Handles {
        by_hwnd: HashMap::new(),
        last_url: HashMap::new(),
    })
});

/// Wraps a raw HWND so wry's `build_as_child` accepts it as a parent
/// without requiring a full `tao::Window`. wry just stores the handle
/// and uses it as `HWND_PARENT` in the `CreateWindowEx` call it makes
/// for its hosting child window.
struct ParentHwnd(isize);

impl HasWindowHandle for ParentHwnd {
    fn window_handle(&self) -> Result<WindowHandle<'_>, raw_window_handle::HandleError> {
        let nz = NonZeroIsize::new(self.0).ok_or(raw_window_handle::HandleError::Unavailable)?;
        let mut h = Win32WindowHandle::new(nz);
        h.hinstance = None;
        // SAFETY: lifetime bounded by &self; HWND is JVM-managed (we don't own it).
        Ok(unsafe { WindowHandle::borrow_raw(RawWindowHandle::Win32(h)) })
    }
}

fn build_webview(parent_hwnd: isize, initial_url: &str) -> Option<(isize, WebView)> {
    let parent = ParentHwnd(parent_hwnd);
    // Use PhysicalPosition/PhysicalSize: NativeView passes physical
    // pixels straight from Compose's coords.size. LogicalSize would
    // make wry re-apply DPI scaling and the WebView2 controller would
    // end up 1.5–1.75× too big on hidpi screens.
    let webview = WebViewBuilder::new()
        .with_url(initial_url)
        .with_bounds(Rect {
            position: dpi::PhysicalPosition::new(0, 0).into(),
            size: dpi::PhysicalSize::new(800, 600).into(),
        })
        .build_as_child(&parent)
        .ok()?;
    // wry 0.55 doesn't expose its hosting HWND directly — walk the
    // parent's child-window list to find it. wry creates exactly one
    // child HWND under the parent at build time, so the first child
    // returned by GetWindow(GW_CHILD) is ours.
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{GetWindow, GET_WINDOW_CMD};
    let parent_h = HWND(parent_hwnd as *mut std::ffi::c_void);
    // GW_CHILD = 5
    let child = unsafe { GetWindow(parent_h, GET_WINDOW_CMD(5u32)) }.ok()?;
    Some((child.0 as isize, webview))
}

macro_rules! lock_or {
    ($default:expr) => {
        match HANDLES.lock() {
            Ok(g) => g,
            Err(_) => return $default,
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    parent_hwnd: jlong,
    initial_url: JString,
) -> jlong {
    if parent_hwnd == 0 {
        return 0;
    }
    let url: String = match env.get_string(&initial_url) {
        Ok(s) => s.into(),
        Err(_) => "about:blank".to_string(),
    };
    let (child_hwnd, webview) = match build_webview(parent_hwnd as isize, &url) {
        Some(v) => v,
        None => return 0,
    };
    let mut g = lock_or!(0);
    g.by_hwnd.insert(child_hwnd, StaWebView(webview));
    g.last_url.insert(child_hwnd, url);
    child_hwnd as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let mut g = lock_or!(());
    g.by_hwnd.remove(&(handle as isize));
    g.last_url.remove(&(handle as isize));
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeLoadUrl(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) {
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let mut g = lock_or!(());
    if let Some(wv) = g.by_hwnd.get(&(handle as isize)) {
        let _ = wv.0.load_url(&url);
        g.last_url.insert(handle as isize, url);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeGoBack(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(g) = HANDLES.lock() {
        if let Some(wv) = g.by_hwnd.get(&(handle as isize)) {
            let _ = wv.0.evaluate_script("window.history.back()");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeGoForward(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(g) = HANDLES.lock() {
        if let Some(wv) = g.by_hwnd.get(&(handle as isize)) {
            let _ = wv.0.evaluate_script("window.history.forward()");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeReload(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(g) = HANDLES.lock() {
        if let Some(wv) = g.by_hwnd.get(&(handle as isize)) {
            let _ = wv.0.reload();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCanGoBack(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> jboolean {
    // History API exposure via wry is async; defer accurate reporting.
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCanGoForward(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> jboolean {
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCurrentUrl<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    handle: jlong,
) -> jstring {
    let url = HANDLES
        .lock()
        .ok()
        .and_then(|g| g.last_url.get(&(handle as isize)).cloned())
        .unwrap_or_default();
    env.new_string(url)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeIsLoading(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> jboolean {
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeSetBounds(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width_px: jni::sys::jint,
    height_px: jni::sys::jint,
) {
    if let Ok(g) = HANDLES.lock() {
        if let Some(wv) = g.by_hwnd.get(&(handle as isize)) {
            let _ = wv.0.set_bounds(Rect {
                position: dpi::PhysicalPosition::new(0, 0).into(),
                size: dpi::PhysicalSize::new(width_px.max(1), height_px.max(1)).into(),
            });
        }
    }
}
