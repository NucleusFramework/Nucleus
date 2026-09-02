// Linux drag-and-drop bridge for the Tao backend.
//
// Implements the recommendations from `DND_LINUX_RESEARCH_REQUEST.md` (specialist
// review of the inbound-DnD failure mode):
//
//   1. The drop target lives on the `GtkWindow` toplevel — not on the EGL
//      child surface, which `nucleus_tao_egl.c` already makes input-transparent
//      via `XShape ShapeInput=empty` (X11) and `wl_surface.set_input_region(empty)`
//      (Wayland). With those input regions in place, all pointer focus and
//      DnD events route to the GTK parent surface, where GDK's data-device
//      listener picks them up.
//
//   2. `gtk_drag_dest_set` is called with `DestDefaults::empty()` so we keep
//      full control over accept/reject (the decision lives in Compose), plus
//      `gtk_drag_dest_set_track_motion(TRUE)` so `drag-motion`/`drag-leave`
//      fire even when the target list match would otherwise suppress them.
//
//   3. `drag-motion` coordinates are widget-local logical pixels relative to
//      the GtkWindow's allocation, which on Linux includes the GTK headerbar
//      (Tao uses native GTK CSD on Linux). We translate to the bin-child's
//      coordinate system via `gtk_widget_translate_coordinates`, then multiply
//      by `gdk_window_get_scale_factor` to land in physical pixels — the same
//      space the Compose scene already operates in (Tao pointer events arrive
//      pre-scaled via `aFixed/1024`).
//
//   4. GTK 3 has no separate `drag-enter` signal: every motion event since
//      the last `drag-leave` is reported as a `drag-motion`. We synthesise
//      `onDragEnter` on the first motion of a session and `onDragOver` on
//      subsequent motions.
//
//   5. CRITICAL: `drag-leave` does NOT immediately tear down the Compose
//      session. GTK 3 fires spurious leave/motion pairs when the cursor
//      crosses *any* internal boundary (e.g. the EGL child / subsurface
//      edges, even when those are input-transparent and pointer focus
//      stays on the parent). Compose's session model treats `onExited` +
//      `onEnded` as terminal — calling them between every motion destroys
//      the active drop targets and the subsequent `onDrop` lands on
//      nothing. Instead we *defer* the leave dispatch through a long-ish
//      `glib::timeout_add_local_once` (250ms per the report) and cancel
//      the pending source if a real motion arrives in the meantime.
//
//   6. JNI exports mirror the Windows / macOS bridges 1:1 (same callback
//      shape, same `DROP_EFFECT_*` constants). The implementation lives
//      inside `libnucleus_tao.so` — JNI exports of a Rust cdylib survive
//      `strip = "symbols"`, so no sibling library is required (unlike
//      Windows / macOS where the C compiler's stripping behaviour forced
//      the split).
//
// Threading: every entry point runs on the GTK main thread (= Tao event
// loop = Compose dispatcher thread). All GTK signal callbacks fire on the
// same thread. No cross-thread synchronisation needed.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use jni::objects::{GlobalRef, JClass, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use gtk::gdk::DragAction;
use gtk::glib;
use gtk::prelude::*;
use gtk::{DestDefaults, TargetEntry, TargetFlags, TargetList};
use tao::platform::unix::WindowExtUnix;

use crate::state::{JAVA_VM, WINDOWS};

// ── Drop-effect constants (must match Kotlin DROP_EFFECT_*) ────────────────

const DROP_EFFECT_NONE: jint = 0;
const DROP_EFFECT_COPY: jint = 1;
const DROP_EFFECT_MOVE: jint = 2;
const DROP_EFFECT_LINK: jint = 4;

/// Target for data that never leaves the process: the JVM's cross-window
/// gestures (satellite docking, tab tear-off) ride the platform DnD session on
/// native Wayland, where it is the only pointer grab that crosses windows with
/// coordinates. Advertised and accepted `SAME_APP` only, so a foreign drop
/// target never sees it and a foreign source can never spoof it. Must match
/// Kotlin `TaoPrivateTransfer.MIME`.
const PRIVATE_TARGET: &str = "application/x-nucleus-private";
const PRIVATE_TARGET_INFO: u32 = 6;

/// Anti-rebound delay for `drag-leave` → `onExited`/`onEnded` dispatch. The
/// specialist report cites 250 ms as a safe upper bound on GTK 3's spurious
/// leave/motion pair latency. Any incoming `drag-motion` cancels the timer.
const LEAVE_DEBOUNCE_MS: u32 = 250;

/// Pump interval while an outbound drag session owns the GTK main thread.
/// ~120 Hz, the same order as the macOS run-loop timer: at or above every
/// display's refresh rate, and anything the swap thread cannot absorb is
/// coalesced by the host's owed-render gate.
const DRAG_PUMP_INTERVAL_MS: u64 = 8;

/// How many queued GTK events to drain after `drag-end` so the toolkit can
/// finish releasing the drag's pointer grab. A handful of iterations: the
/// teardown is a few events, and the loop stops as soon as none are pending.
const DRAG_TEARDOWN_ITERATIONS: usize = 64;

// ── Per-window registration ────────────────────────────────────────────────

#[allow(dead_code)]
struct Registration {
    callback: GlobalRef,
    motion_id: glib::SignalHandlerId,
    leave_id: glib::SignalHandlerId,
    drop_id: glib::SignalHandlerId,
    received_id: glib::SignalHandlerId,
    /// Tracks whether the active session has surfaced a target Compose is
    /// willing to accept. Updated in drag-motion, consulted in drag-drop.
    accepting: Rc<Cell<bool>>,
    /// Whether we've dispatched `onDragEnter` since the last finalised leave
    /// or drop. Reset by the deferred leave handler and by drop completion.
    entered: Rc<Cell<bool>>,
    /// Pending deferred-leave timer. Cancelled on motion / drop.
    pending_leave: Rc<RefCell<Option<glib::SourceId>>>,
}

thread_local! {
    static INBOUND: RefCell<HashMap<u64, Registration>> = RefCell::new(HashMap::new());
}

#[allow(dead_code)]
struct OutboundSession {
    files: Vec<String>,
    text: Option<String>,
    private_data: Option<String>,
    result: Rc<Cell<jint>>,
    done: Rc<Cell<bool>>,
}

/// The drag icon a session shows under the pointer: premultiplied ARGB32 in
/// native endianness (cairo's own layout), `width × height` device pixels
/// rendered at `scale` px per logical pixel, with the pointer at
/// (`hot_x`, `hot_y`) device pixels.
pub(crate) struct DragIcon {
    pub argb: Vec<u32>,
    pub width: i32,
    pub height: i32,
    pub scale: f64,
    pub hot_x: i32,
    pub hot_y: i32,
}

thread_local! {
    static OUTBOUND: RefCell<HashMap<u64, OutboundSession>> = RefCell::new(HashMap::new());
}

// ── Helpers ────────────────────────────────────────────────────────────────

fn target_entries() -> [TargetEntry; 6] {
    // Info codes are forwarded to drag-data-get verbatim; we use them to pick
    // the right serialiser. text/uri-list is the primary inbound target for
    // file drops on Linux (Nautilus, Files, Konqueror, Firefox bookmarks…).
    [
        TargetEntry::new("text/uri-list", TargetFlags::OTHER_APP, 1),
        TargetEntry::new("text/plain;charset=utf-8", TargetFlags::OTHER_APP, 2),
        TargetEntry::new("UTF8_STRING", TargetFlags::OTHER_APP, 4),
        TargetEntry::new("STRING", TargetFlags::OTHER_APP, 5),
        TargetEntry::new("text/plain", TargetFlags::OTHER_APP, 3),
        TargetEntry::new(PRIVATE_TARGET, TargetFlags::SAME_APP, PRIVATE_TARGET_INFO),
    ]
}

/// Builds the GTK drag icon from [`DragIcon`]: a cairo surface at the source's
/// device scale, so it stays crisp on HiDPI, with the hotspot expressed as the
/// surface's device offset (the way `gtk_drag_set_icon_surface` reads it).
fn drag_icon_surface(icon: DragIcon) -> Option<gtk::cairo::ImageSurface> {
    use gtk::cairo::{Format, ImageSurface};
    if icon.width <= 0 || icon.height <= 0 {
        return None;
    }
    let stride = Format::ARgb32.stride_for_width(icon.width as u32).ok()?;
    if stride != icon.width * 4 || icon.argb.len() != (icon.width * icon.height) as usize {
        return None;
    }
    let mut bytes = Vec::with_capacity(icon.argb.len() * 4);
    for px in icon.argb {
        bytes.extend_from_slice(&px.to_ne_bytes());
    }
    let surface =
        ImageSurface::create_for_data(bytes, Format::ARgb32, icon.width, icon.height, stride).ok()?;
    let scale = if icon.scale > 0.0 { icon.scale } else { 1.0 };
    surface.set_device_scale(scale, scale);
    surface.set_device_offset(-(icon.hot_x as f64), -(icon.hot_y as f64));
    Some(surface)
}

fn map_action_to_effect(action: DragAction) -> jint {
    if action.contains(DragAction::COPY) {
        DROP_EFFECT_COPY
    } else if action.contains(DragAction::MOVE) {
        DROP_EFFECT_MOVE
    } else if action.contains(DragAction::LINK) {
        DROP_EFFECT_LINK
    } else {
        DROP_EFFECT_NONE
    }
}

fn map_effect_to_action(effect: jint) -> DragAction {
    let mut a = DragAction::empty();
    if effect & DROP_EFFECT_COPY != 0 {
        a |= DragAction::COPY;
    }
    if effect & DROP_EFFECT_MOVE != 0 {
        a |= DragAction::MOVE;
    }
    if effect & DROP_EFFECT_LINK != 0 {
        a |= DragAction::LINK;
    }
    if a.is_empty() {
        a = DragAction::COPY;
    }
    a
}

/// Translates `drag-motion (x, y)` (widget-local logical pixels relative to
/// the GtkWindow's allocation, which includes the GTK CSD headerbar on Linux)
/// to physical pixels of the bin-child content area — the coordinate space
/// Compose's pointer pipeline operates in.
///
/// Falls back to multiplying by scale only when `translate_coordinates` fails
/// (would only happen if the bin child isn't realised — unlikely once any
/// drag is in flight).
fn translate_to_content_phys(window: &gtk::Window, x: i32, y: i32) -> (i32, i32) {
    let scale = window
        .window()
        .map(|w| w.scale_factor())
        .unwrap_or(1)
        .max(1);
    let (lx, ly) = match window.child() {
        Some(child) => window
            .translate_coordinates(&child, x, y)
            .unwrap_or((x, y)),
        None => (x, y),
    };
    (lx * scale, ly * scale)
}

fn with_window<R, F: FnOnce(&tao::window::Window) -> R>(handle: u64, f: F) -> Option<R> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    map.get(&handle).map(f)
}

// ── JNI dispatch helpers ───────────────────────────────────────────────────

fn dispatch_motion(
    handle: u64,
    callback: &GlobalRef,
    method: &str,
    x: jint,
    y: jint,
    has_files: bool,
) -> jint {
    let Some(vm) = JAVA_VM.get() else {
        return DROP_EFFECT_NONE;
    };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return DROP_EFFECT_NONE;
    };
    let res = env.call_method(
        callback.as_obj(),
        method,
        "(JIIIZ)I",
        &[
            JValue::Long(handle as jlong),
            JValue::Int(x),
            JValue::Int(y),
            JValue::Int(0),
            JValue::Bool(if has_files { JNI_TRUE } else { JNI_FALSE }),
        ],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return DROP_EFFECT_NONE;
    }
    res.and_then(|v| v.i()).unwrap_or(DROP_EFFECT_NONE)
}

fn dispatch_leave(handle: u64, callback: &GlobalRef) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return;
    };
    let _ = env.call_method(
        callback.as_obj(),
        "onDragLeave",
        "(J)V",
        &[JValue::Long(handle as jlong)],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

fn dispatch_drop(
    handle: u64,
    callback: &GlobalRef,
    x: jint,
    y: jint,
    files: &[String],
) -> jint {
    let Some(vm) = JAVA_VM.get() else {
        return DROP_EFFECT_NONE;
    };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return DROP_EFFECT_NONE;
    };
    // The String[] and its elements are JNI local refs. This runs on the Tao
    // loop thread (attached permanently, frame never popped), so scope them in
    // a local frame or every drop leaks the array + one ref per file.
    env.with_local_frame(files.len() as i32 + 6, |env| -> Result<jint, jni::errors::Error> {
        let arr = match build_string_array(env, files) {
            Some(a) => a,
            None => return Ok(DROP_EFFECT_NONE),
        };
        let res = env.call_method(
            callback.as_obj(),
            "onDrop",
            "(JIII[Ljava/lang/String;)I",
            &[
                JValue::Long(handle as jlong),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(0),
                JValue::Object(&arr),
            ],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Ok(DROP_EFFECT_NONE);
        }
        Ok(res.and_then(|v| v.i()).unwrap_or(DROP_EFFECT_NONE))
    })
    .unwrap_or(DROP_EFFECT_NONE)
}

/// Upcalls `DragPump.pump()` on the GTK main thread. Returns `false` once the
/// upcall has failed so the caller can latch it off — a broken pump must report
/// once, not ~120×/s for the whole drag.
fn dispatch_pump(pump: &GlobalRef) -> bool {
    let Some(vm) = JAVA_VM.get() else {
        return false;
    };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return false;
    };
    let res = env.call_method(pump.as_obj(), "pump", "()V", &[]);
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return false;
    }
    res.is_ok()
}

fn build_string_array<'a>(
    env: &mut JNIEnv<'a>,
    items: &[String],
) -> Option<JObject<'a>> {
    let cls = env.find_class("java/lang/String").ok()?;
    let arr = env
        .new_object_array(items.len() as i32, cls, JObject::null())
        .ok()?;
    for (i, s) in items.iter().enumerate() {
        let js = env.new_string(s).ok()?;
        env.set_object_array_element(&arr, i as i32, js).ok()?;
    }
    Some(arr.into())
}

// ── URI ↔ path encoding ────────────────────────────────────────────────────

/// Decodes a `file://` URI to a filesystem path. Non-file schemes are
/// returned verbatim so the receiver can still inspect them.
fn uri_to_path(uri: &str) -> String {
    let rest = match uri.strip_prefix("file://") {
        Some(r) => r,
        None => return uri.to_string(),
    };
    percent_decode(rest)
}

/// Minimal RFC 3986 percent-decoding for `%XX` sequences in
/// `text/uri-list` payloads. UTF-8 paths with diacritics decode correctly
/// because we accumulate bytes and let `String::from_utf8_lossy` reassemble.
fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn percent_encode_path(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for &b in input.as_bytes() {
        let safe = b.is_ascii_alphanumeric()
            || matches!(b, b'-' | b'_' | b'.' | b'~' | b'/');
        if safe {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

// ── Inbound: gtk_drag_dest_set + signal handlers ───────────────────────────

fn install_inbound(handle: u64, callback: GlobalRef) -> Result<(), &'static str> {
    let widget: gtk::Window =
        with_window(handle, |w| w.gtk_window().clone()).ok_or("window not found")?;

    // Defensive: in case Tao or some other layer registered a target. Tao
    // 0.35 itself does not on Linux, but we don't assume.
    widget.drag_dest_unset();
    let entries = target_entries();
    widget.drag_dest_set(
        DestDefaults::empty(),
        &entries,
        DragAction::COPY | DragAction::MOVE | DragAction::LINK,
    );
    // Force `drag-motion`/`drag-leave` to fire even when the offered targets
    // don't match — gives us a chance to negotiate a target later (e.g. some
    // sources only advertise their full target list lazily).
    widget.drag_dest_set_track_motion(true);

    let accepting = Rc::new(Cell::new(false));
    let entered = Rc::new(Cell::new(false));
    let pending_leave: Rc<RefCell<Option<glib::SourceId>>> = Rc::new(RefCell::new(None));

    // ── drag-motion ─────────────────────────────────────────────────────
    let cb_motion = callback.clone();
    let acc_motion = Rc::clone(&accepting);
    let ent_motion = Rc::clone(&entered);
    let pending_motion = Rc::clone(&pending_leave);
    let widget_motion = widget.clone();
    let motion_id = widget.connect_drag_motion(move |_w, ctx, x, y, time| {
        // Cancel any deferred leave — a fresh motion proves we never left.
        if let Some(src) = pending_motion.borrow_mut().take() {
            src.remove();
        }

        let has_files = ctx
            .list_targets()
            .iter()
            .any(|atom| atom.name() == "text/uri-list");

        let (px, py) = translate_to_content_phys(&widget_motion, x, y);

        let method = if ent_motion.get() {
            "onDragOver"
        } else {
            ent_motion.set(true);
            "onDragEnter"
        };
        let effect = dispatch_motion(handle, &cb_motion, method, px, py, has_files);
        let action = map_effect_to_action(effect);
        if effect == DROP_EFFECT_NONE {
            ctx.drag_status(DragAction::empty(), time);
            acc_motion.set(false);
            false
        } else {
            ctx.drag_status(action, time);
            acc_motion.set(true);
            true
        }
    });

    // ── drag-leave ──────────────────────────────────────────────────────
    let cb_leave = callback.clone();
    let acc_leave = Rc::clone(&accepting);
    let ent_leave = Rc::clone(&entered);
    let pending_leave_for_cb = Rc::clone(&pending_leave);
    let leave_id = widget.connect_drag_leave(move |_w, _ctx, _time| {
        // GTK 3 fires drag-leave between every pair of drag-motion events
        // when the cursor crosses any internal widget boundary — we *cannot*
        // dispatch onExited+onEnded here directly without destroying the
        // active Compose session and breaking every drop. Schedule it on a
        // 250ms timer; a real motion will cancel the timer.
        if let Some(prev) = pending_leave_for_cb.borrow_mut().take() {
            prev.remove();
        }
        let acc = Rc::clone(&acc_leave);
        let ent = Rc::clone(&ent_leave);
        let cb = cb_leave.clone();
        let slot = Rc::clone(&pending_leave_for_cb);
        let id = glib::timeout_add_local_once(
            std::time::Duration::from_millis(LEAVE_DEBOUNCE_MS as u64),
            move || {
                slot.borrow_mut().take();
                acc.set(false);
                ent.set(false);
                dispatch_leave(handle, &cb);
            },
        );
        *pending_leave_for_cb.borrow_mut() = Some(id);
    });

    // ── drag-drop ───────────────────────────────────────────────────────
    let pending_drop = Rc::clone(&pending_leave);
    let drop_id = widget.connect_drag_drop(move |w, ctx, _x, _y, time| {
        if let Some(src) = pending_drop.borrow_mut().take() {
            src.remove();
        }
        // `gtk_drag_dest_find_target(ctx, NULL)` consults the destination's
        // registered target list (the one we passed to `drag_dest_set`) and
        // returns the best-matching atom — much more robust than walking
        // `ctx.list_targets()` ourselves with name comparisons.
        let target = w.drag_dest_find_target(ctx, None);
        if let Some(target) = target {
            w.drag_get_data(ctx, &target, time);
            true
        } else {
            ctx.drag_status(DragAction::empty(), time);
            false
        }
    });

    // ── drag-data-received ──────────────────────────────────────────────
    let cb_received = callback.clone();
    let ent_received = Rc::clone(&entered);
    let widget_received = widget.clone();
    let received_id =
        widget.connect_drag_data_received(move |_w, ctx, x, y, data, _info, time| {
            let mut paths: Vec<String> = Vec::new();
            let uris = data.uris();
            if !uris.is_empty() {
                paths.extend(uris.iter().map(|u| uri_to_path(u.as_str())));
            } else if let Some(text) = data.text() {
                paths.push(text.to_string());
            }
            let (px, py) = translate_to_content_phys(&widget_received, x, y);
            let effect = dispatch_drop(handle, &cb_received, px, py, &paths);
            let success = effect != DROP_EFFECT_NONE;
            ctx.drag_finish(success, false, time);
            ent_received.set(false);
        });

    let registration = Registration {
        callback,
        motion_id,
        leave_id,
        drop_id,
        received_id,
        accepting,
        entered,
        pending_leave,
    };
    INBOUND.with(|m| {
        m.borrow_mut().insert(handle, registration);
    });
    Ok(())
}

fn revoke_inbound(handle: u64) {
    let Some(reg) = INBOUND.with(|m| m.borrow_mut().remove(&handle)) else {
        return;
    };
    if let Some(src) = reg.pending_leave.borrow_mut().take() {
        src.remove();
    }
    if let Some(widget) = with_window(handle, |w| w.gtk_window().clone()) {
        widget.disconnect(reg.motion_id);
        widget.disconnect(reg.leave_id);
        widget.disconnect(reg.drop_id);
        widget.disconnect(reg.received_id);
        widget.drag_dest_unset();
    }
    drop(reg.callback); // explicit DeleteGlobalRef
}

// ── Outbound: gtk_drag_begin_with_coordinates ──────────────────────────────

fn start_outbound(
    handle: u64,
    files: Vec<String>,
    text: Option<String>,
    private_data: Option<String>,
    allowed: jint,
    icon: Option<DragIcon>,
    pump: Option<GlobalRef>,
) -> jint {
    if files.is_empty()
        && text.as_deref().map(str::is_empty).unwrap_or(true)
        && private_data.is_none()
    {
        return DROP_EFFECT_NONE;
    }
    let Some(widget) = with_window(handle, |w| w.gtk_window().clone()) else {
        return DROP_EFFECT_NONE;
    };

    let target_list = TargetList::new(&[]);
    if !files.is_empty() {
        target_list.add(&gtk::gdk::Atom::intern("text/uri-list"), 0, 1);
    }
    if text.is_some() {
        target_list.add(&gtk::gdk::Atom::intern("text/plain;charset=utf-8"), 0, 2);
        target_list.add(&gtk::gdk::Atom::intern("UTF8_STRING"), 0, 4);
    }
    if private_data.is_some() {
        target_list.add(
            &gtk::gdk::Atom::intern(PRIVATE_TARGET),
            TargetFlags::SAME_APP.bits(),
            PRIVATE_TARGET_INFO,
        );
    }

    let result = Rc::new(Cell::new(DROP_EFFECT_NONE));
    let done = Rc::new(Cell::new(false));

    let session = OutboundSession {
        files: files.clone(),
        text: text.clone(),
        private_data: private_data.clone(),
        result: Rc::clone(&result),
        done: Rc::clone(&done),
    };
    OUTBOUND.with(|m| {
        m.borrow_mut().insert(handle, session);
    });

    let data_get_id = widget.connect_drag_data_get(move |_w, _ctx, data, info, _time| {
        OUTBOUND.with(|m| {
            let map = m.borrow();
            let Some(s) = map.get(&handle) else { return };
            match info {
                1 => {
                    let uris: Vec<String> = s
                        .files
                        .iter()
                        .map(|p| {
                            if p.starts_with("file://") {
                                p.clone()
                            } else {
                                format!("file://{}", percent_encode_path(p))
                            }
                        })
                        .collect();
                    let refs: Vec<&str> = uris.iter().map(|s| s.as_str()).collect();
                    let _ = data.set_uris(&refs);
                }
                2 | 3 | 4 | 5 => {
                    if let Some(t) = s.text.as_deref() {
                        let _ = data.set_text(t);
                    } else if !s.files.is_empty() {
                        let joined = s.files.join("\n");
                        let _ = data.set_text(&joined);
                    }
                }
                PRIVATE_TARGET_INFO => {
                    if let Some(p) = s.private_data.as_deref() {
                        data.set(&gtk::gdk::Atom::intern(PRIVATE_TARGET), 8, p.as_bytes());
                    }
                }
                _ => {}
            }
        });
    });

    let res_end = Rc::clone(&result);
    let done_end = Rc::clone(&done);
    let drag_end_id = widget.connect_drag_end(move |_w, ctx| {
        res_end.set(map_action_to_effect(ctx.selected_action()));
        done_end.set(true);
    });

    let done_fail = Rc::clone(&done);
    let drag_failed_id = widget.connect_drag_failed(move |_w, _ctx, _result| {
        done_fail.set(true);
        glib::Propagation::Proceed
    });

    let actions = map_effect_to_action(allowed);
    let ctx = widget.drag_begin_with_coordinates(&target_list, actions, 1, None, -1, -1);
    if ctx.is_none() {
        widget.disconnect(data_get_id);
        widget.disconnect(drag_end_id);
        widget.disconnect(drag_failed_id);
        OUTBOUND.with(|m| {
            m.borrow_mut().remove(&handle);
        });
        return DROP_EFFECT_NONE;
    }
    if let Some(ref c) = ctx {
        match icon.and_then(drag_icon_surface) {
            Some(surface) => c.drag_set_icon_surface(&surface),
            None => c.drag_set_icon_default(),
        }
    }

    // Keep the host alive for the session, the Linux counterpart of the Windows
    // `QueryContinueDrag` upcall and the macOS run-loop timer.
    //
    // The GTK pump below is not one: it dispatches GDK events, which push into
    // tao's event channel, but nothing re-enters tao's own state machine —
    // `start_outbound` is called from inside a `callback(…)` of
    // `EventLoop::run_return`, so for the whole drag no `MainEventsCleared` is
    // emitted (`TaoMainDispatcher` never drains) and no queued
    // `RedrawRequested` is delivered (the host never paints). The window is
    // frozen exactly as it was on the other two platforms.
    //
    // A glib timeout rather than the loop body: `main_iteration_do(true)`
    // blocks until GTK has something to dispatch, so a cursor held still would
    // stop ticking — the known Windows trade-off, which the timer avoids here
    // for the same reason it does on macOS. It also wakes the loop, so the pump
    // rate is the timer's and not the input rate's.
    let pump_source = pump.map(|p| {
        let mut broken = false;
        glib::timeout_add_local(
            std::time::Duration::from_millis(DRAG_PUMP_INTERVAL_MS),
            move || {
                if !broken && !dispatch_pump(&p) {
                    broken = true;
                }
                // Never `Break`, even once latched off: the source is removed
                // below, and `SourceId::remove` on an id that already returned
                // `Break` is a GLib critical.
                glib::ControlFlow::Continue
            },
        )
    });

    // Cooperatively pump the GTK main loop until drag-end fires. The session
    // runs through the same loop we're already on; drag_begin returned
    // immediately. Mirrors Win32 `DoDragDrop`'s nested message pump.
    while !done.get() {
        gtk::main_iteration_do(true);
    }

    // `drag-end` is emitted *before* GTK has finished tearing the drag down —
    // in particular before it releases the implicit pointer grab
    // `gtk_drag_begin` took on the seat. Returning the instant our own handler
    // sets the flag (and then disconnecting GTK's handlers underneath it)
    // leaves that grab in place, and every window of the application goes
    // deaf to the pointer for good. So drain what GTK still has queued, then
    // make sure the seat is ungrabbed either way.
    for _ in 0..DRAG_TEARDOWN_ITERATIONS {
        if !gtk::events_pending() {
            break;
        }
        gtk::main_iteration_do(false);
    }
    if let Some(gdk_window) = WidgetExt::window(&widget) {
        if let Some(seat) = gdk_window.display().default_seat() {
            seat.ungrab();
        }
    }

    if let Some(src) = pump_source {
        src.remove();
    }
    widget.disconnect(data_get_id);
    widget.disconnect(drag_end_id);
    widget.disconnect(drag_failed_id);
    OUTBOUND.with(|m| {
        m.borrow_mut().remove(&handle);
    });
    result.get()
}

// ── JNI exports ────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxDndBridge_nativeRegister(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let global = match env.new_global_ref(&callback) {
        Ok(g) => g,
        Err(_) => return -2,
    };
    match install_inbound(handle as u64, global) {
        Ok(()) => 0,
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxDndBridge_nativeRevoke(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    revoke_inbound(handle as u64);
    0
}

#[no_mangle]
#[allow(unused_mut)]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxDndBridge_nativeStartDrag(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    files: JObjectArray,
    text: JString,
    private_data: JString,
    allowed_effects: jint,
    icon_argb: JIntArray,
    icon_width: jint,
    icon_height: jint,
    icon_scale: jfloat,
    icon_hot_x: jint,
    icon_hot_y: jint,
    pump: JObject,
) -> jint {
    if handle == 0 {
        return DROP_EFFECT_NONE;
    }
    let mut files_vec: Vec<String> = Vec::new();
    if !files.is_null() {
        let n = env.get_array_length(&files).unwrap_or(0);
        for i in 0..n {
            let Ok(elem) = env.get_object_array_element(&files, i) else {
                continue;
            };
            if elem.is_null() {
                continue;
            }
            let jstr = JString::from(elem);
            let captured: Option<String> = env
                .get_string(&jstr)
                .ok()
                .map(|s| s.to_str().unwrap_or("").to_string());
            if let Some(s) = captured {
                files_vec.push(s);
            }
        }
    }
    let text_opt: Option<String> = if !text.is_null() {
        env.get_string(&text)
            .ok()
            .map(|s| s.to_str().unwrap_or("").to_string())
            .filter(|s| !s.is_empty())
    } else {
        None
    };
    // GlobalRef, unlike the macOS timer's raw jobject: the timeout closure is
    // `'static`, so it cannot borrow this frame's local ref. It fires on this
    // same already-attached thread either way.
    let private_opt: Option<String> = if !private_data.is_null() {
        env.get_string(&private_data)
            .ok()
            .map(|s| s.to_str().unwrap_or("").to_string())
    } else {
        None
    };
    let icon: Option<DragIcon> = if icon_argb.is_null() || icon_width <= 0 || icon_height <= 0 {
        None
    } else {
        let len = env.get_array_length(&icon_argb).unwrap_or(0) as usize;
        let mut buf: Vec<jint> = vec![0; len];
        if env.get_int_array_region(&icon_argb, 0, &mut buf).is_ok() {
            Some(DragIcon {
                argb: buf.into_iter().map(|v| v as u32).collect(),
                width: icon_width,
                height: icon_height,
                scale: icon_scale as f64,
                hot_x: icon_hot_x,
                hot_y: icon_hot_y,
            })
        } else {
            None
        }
    };
    let pump_ref: Option<GlobalRef> = if pump.is_null() {
        None
    } else {
        env.new_global_ref(&pump).ok()
    };
    start_outbound(
        handle as u64,
        files_vec,
        text_opt,
        private_opt,
        allowed_effects,
        icon,
        pump_ref,
    )
}
