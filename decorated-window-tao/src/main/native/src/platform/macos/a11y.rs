// VoiceOver bridge.
//
// The JVM side builds an immutable snapshot of the Compose semantics tree,
// serialises it to the wire format documented in `macos/a11y.m`, and pushes it
// here once per Compose tick. We forward the bytes verbatim to the ObjC
// projection, which rebuilds its NSAccessibilityElement tree and posts the
// appropriate notifications to AppKit.
//
// Action callbacks travel back the other direction: VoiceOver invokes
// `accessibilityPerformPress` on a NucleusA11yElement, which calls
// `nucleus_tao_a11y_invoke_action`, which turns into a JNI upcall to the
// Kotlin-side controller (`TaoAccessibilityBridge.invokeAction`).
//
// All a11y JNI exports take the NSView pointer directly (cached on the JVM
// side at attach time) rather than the window handle. This is critical
// because `EVENT_DESTROYED` is dispatched from inside `WINDOWS.lock()` —
// any reentrant lock attempt on the same thread (e.g. from
// `nativeA11yDetach`) would deadlock the Tao event loop.

use jni::objects::{JByteArray, JClass, JString, JValue};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::platform::macos::ffi::{
    nucleus_tao_a11y_apply_snapshot, nucleus_tao_a11y_attach,
    nucleus_tao_a11y_consume_resync, nucleus_tao_a11y_detach, nucleus_tao_a11y_is_active,
    nucleus_tao_a11y_is_voiceover_running, nucleus_tao_a11y_note_pushed,
    nucleus_tao_a11y_post_focus_changed,
};
use crate::state::JAVA_VM;

// ── JNI exports ───────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_attach(ns_view) };
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_detach(ns_view) };
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
    bytes: JByteArray,
) -> jboolean {
    if ns_view == 0 { return JNI_FALSE; }
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    let ok = unsafe {
        nucleus_tao_a11y_apply_snapshot(ns_view, buf.as_ptr() as *const u8, len)
    };
    if ok != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_is_voiceover_running() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_is_active() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let r = unsafe { nucleus_tao_a11y_consume_resync() };
    if r != 0 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { nucleus_tao_a11y_note_pushed() };
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    ns_view: jlong,
    node_id: jlong,
) {
    if ns_view == 0 { return; }
    unsafe { nucleus_tao_a11y_post_focus_changed(ns_view, node_id as u64) };
}

/// No-op on macOS — VoiceOver reads `CFBundleName` for the app name.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11ySetAppName(
    _env: JNIEnv,
    _class: JClass,
    _name: JString,
) {
}

/// No-op stub: the partial wire format is Linux-only at v7; the macOS parser
/// is still at v4 and rejects anything else. Returning `JNI_FALSE` keeps the
/// JVM-side controller from believing a partial succeeded.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yApplyPartialSnapshot(
    _env: JNIEnv,
    _class: JClass,
    _ns_view: jlong,
    _bytes: JByteArray,
) -> jboolean {
    JNI_FALSE
}

// ── Callbacks invoked from `macos/a11y.m` ─────────────────────────────────

/// Called when VoiceOver edits a text field via `setAccessibilityValue:`.
/// Forwards the new string (UTF-8) to Kotlin so it can invoke
/// `SemanticsActions.SetText`.
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_set_text(
    ns_view_handle: i64,
    node_id: u64,
    utf8: *const u8,
    len: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if utf8.is_null() || len < 0 { return };
    let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
    let Ok(text) = std::str::from_utf8(slice) else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(text) else { return };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetText",
            "(JJLjava/lang/String;)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Object(&jstr.into()),
            ],
        );
    }
}

/// Called when VoiceOver invokes a Compose-defined custom accessibility
/// action (VO+Cmd+. menu). `action_index` is the position of the action in
/// the per-node list pushed via the wire format.
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_invoke_custom_action(
    ns_view_handle: i64,
    node_id: u64,
    action_index: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yCustomAction",
            "(JJI)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(action_index),
            ],
        );
    }
}

/// Called when VoiceOver moves a scroll bar to an absolute position via
/// `setAccessibilityValue:`. Delivers the precise (dx, dy) delta to Compose's
/// `SemanticsActions.ScrollBy`.
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_scroll_by(
    ns_view_handle: i64,
    node_id: u64,
    dx: f32,
    dy: f32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yScrollBy",
            "(JJFF)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Float(dx),
                JValue::Float(dy),
            ],
        );
    }
}

/// Called when VoiceOver places the caret / extends the selection inside a
/// text field (`setAccessibilitySelectedTextRange:`). Routed to Compose's
/// `SemanticsActions.SetSelection`.
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_set_selection(
    ns_view_handle: i64,
    node_id: u64,
    start: i32,
    end: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetSelection",
            "(JJII)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(start),
                JValue::Int(end),
            ],
        );
    }
}

/// Called when VoiceOver triggers an accessibility action. Passes the NSView
/// pointer through unchanged — the JVM-side registry is indexed by NSView,
/// so we can avoid acquiring `WINDOWS.lock()` here. That keeps this callback
/// safe to invoke from any AppKit context, including nested ones where the
/// Tao loop happens to hold the lock.
#[no_mangle]
pub extern "C" fn nucleus_tao_a11y_invoke_action(
    ns_view_handle: i64,
    node_id: u64,
    action: u16,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class("io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yActionByNsView",
            "(JJI)V",
            &[
                JValue::Long(ns_view_handle),
                JValue::Long(node_id as i64),
                JValue::Int(action as i32),
            ],
        );
    }
}
