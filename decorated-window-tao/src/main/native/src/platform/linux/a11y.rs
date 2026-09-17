// Linux accessibility provider for the Tao backend.
//
// Architecture mirrors the macOS / Windows pipeline:
//   - The JVM owns the source-of-truth Compose semantic tree and pushes
//     compact byte-array snapshots through `nativeA11yApplySnapshot` whenever
//     the SemanticsOwnerListener fires.
//   - Each snapshot is decoded into an `accesskit::TreeUpdate` and handed
//     to an `accesskit_unix::Adapter`, which projects it to AT-SPI2 over
//     D-Bus (zbus). Orca, accerciser and other ATs see the tree like any
//     other native AT-SPI2 application.
//   - Accessibility actions (Click, Focus, Set value, Custom action…) come
//     back from AccessKit on a zbus dispatch thread; we forward them to the
//     JVM via the existing `dispatchA11yActionByNsView` upcalls. The Kotlin
//     registry is keyed by an opaque "view handle" that on Linux is the
//     X11 Window XID — same role as NSView on macOS or HWND on Windows.
//
// Wire format v7 is decoded by the shared `crate::a11y::wire` module
// (same path as Windows AccessKit).
//
// IMPORTANT: AT-SPI's `org.a11y.Status.IsEnabled` flag must be true for any
// of this to be observable. accesskit_unix's `run_event_loop` only constructs
// its AT-SPI bus connection (and registers per-node interfaces) when an AT
// has flipped that property. Orca, accerciser, GNOME Settings → Accessibility
// all set it. Headless tests can force it via:
//   `busctl --user set-property org.a11y.Bus /org/a11y/bus org.a11y.Status \
//      IsEnabled b true`
// Without it, `Message::RegisterInterfaces` payloads are silently dropped on
// the executor thread (`accesskit_unix-0.17.2/src/context.rs:220`).

use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};

use accesskit::{
    ActionHandler, ActionRequest, ActivationHandler, DeactivationHandler, NodeId, Rect, TreeId,
    TreeUpdate,
};
use accesskit_unix::Adapter;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::a11y::jvm::forward_action_to_jvm;
use crate::a11y::tree::reachable_nodes;
use crate::a11y::wire::{parse_snapshot, NodeMeta, ParsedSnapshot};

// ── State management ──────────────────────────────────────────────────────

struct WindowState {
    /// Last full snapshot. Returned to AccessKit on `request_initial_tree`.
    /// Partial updates do NOT update this — they only mutate AccessKit's
    /// internal cache. On AT (re)connection the JVM's RESYNC flag forces
    /// the next observer tick to send a full snapshot, which refreshes
    /// `last_tree` for any subsequent activation request.
    last_tree: Option<TreeUpdate>,
    /// Root node id, captured on the first full push. Partial updates never
    /// re-emit the root, so we cache it here as the focus fallback when a
    /// partial reports "nothing focused" — the root is the only node we can
    /// guarantee is still live in AccessKit's tree.
    root: Option<NodeId>,
    /// NodeId → metadata used to interpret AccessKit-side action requests.
    /// Custom-action dispatch uses the index inside `custom_action_count` to
    /// look up the Kotlin-side handler position. Full snapshots replace
    /// this map; partial snapshots merge into it.
    nodes: HashMap<NodeId, NodeMeta>,
    /// NodeId → its direct children, mirroring AccessKit's own tree topology.
    /// Full snapshots reset it; partial snapshots merge the re-emitted nodes.
    /// We replay the consumer's reachability over this map after every update
    /// so we know the exact set of nodes AccessKit currently holds — and can
    /// clamp `TreeUpdate.focus` to a live node before dispatch. A focus that
    /// points at a pruned node makes accesskit_consumer panic, and with
    /// `panic = "abort"` that takes down the whole JVM.
    child_map: HashMap<NodeId, Vec<NodeId>>,
    /// X11 Window XID — opaque "view handle" passed back to Kotlin on every
    /// upcall. Mirrors NSView on macOS / HWND on Windows.
    handle: i64,
    /// Tracks whether at least one AT has connected to the bus. Set on the
    /// first activation request; we use it only as a hint — AccessKit's
    /// `update_if_active` is the actual gate.
    has_been_activated: bool,
}

struct WindowEntry {
    /// Adapter is the live AT-SPI projection. Drop-on-detach is intentional —
    /// the destructor unregisters the application from the bus.
    adapter: Adapter,
    state: Arc<Mutex<WindowState>>,
}

static WINDOWS: Lazy<Mutex<HashMap<i64, Box<WindowEntry>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

// Global flags consumed by the Kotlin observer. AccessKit's adapter takes
// care of suppressing work when no AT is connected, so we keep these simple
// and let the JVM push at the natural Compose recomposition cadence.
static AT_ACTIVE: AtomicBool = AtomicBool::new(false);
static RESYNC: AtomicBool = AtomicBool::new(false);

// ── AccessKit handlers ────────────────────────────────────────────────────

/// Invoked the first time an AT connects, then again whenever the platform
/// adapter wants a full re-broadcast (typically when the AT reconnects).
struct ActivationProxy {
    state: Arc<Mutex<WindowState>>,
}

impl ActivationHandler for ActivationProxy {
    fn request_initial_tree(&mut self) -> Option<TreeUpdate> {
        AT_ACTIVE.store(true, Ordering::Relaxed);
        RESYNC.store(true, Ordering::Relaxed);
        let mut st = self.state.lock().ok()?;
        st.has_been_activated = true;
        // Clone the cached tree if we have one. A `None` here is fine —
        // AccessKit will retry; the Kotlin observer's `pendingForcedPush`
        // ensures it pushes a fresh snapshot on the next event-loop tick.
        st.last_tree.clone()
    }
}

struct DeactivationProxy {
    state: Arc<Mutex<WindowState>>,
}

impl DeactivationHandler for DeactivationProxy {
    fn deactivate_accessibility(&mut self) {
        AT_ACTIVE.store(false, Ordering::Relaxed);
        // Keep `last_tree` so a quick AT reconnect can answer
        // `request_initial_tree` immediately. RESYNC still forces the JVM to
        // push a fresh full snapshot on the next tick, which refreshes the
        // cache. Clearing the cache here was the main cause of empty trees
        // when an AT reconnected before the next Compose semantics walk.
        RESYNC.store(true, Ordering::Relaxed);
    }
}

struct ActionProxy {
    state: Arc<Mutex<WindowState>>,
}

impl ActionHandler for ActionProxy {
    fn do_action(&mut self, request: ActionRequest) {
        let (handle, meta_actions, custom_count) = {
            let st = match self.state.lock() {
                Ok(s) => s,
                Err(_) => return,
            };
            let Some(meta) = st.nodes.get(&request.target_node) else {
                return;
            };
            (st.handle, meta.actions, meta.custom_action_count)
        };
        forward_action_to_jvm(handle, request, meta_actions, custom_count);
    }
}

// ── JNI entry points ──────────────────────────────────────────────────────
//
// Names mirror the macOS/Windows JNI exports declared on
// `NativeTaoBridge.kt`. The JVM caches the X11 Window XID at attach time and
// uses it as the opaque `nsView` handle on every subsequent call, so we
// don't have to lock back into the Tao `WINDOWS` map from the AT-SPI side.

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if map.contains_key(&handle) {
        return;
    }
    let state = Arc::new(Mutex::new(WindowState {
        last_tree: None,
        root: None,
        nodes: HashMap::new(),
        child_map: HashMap::new(),
        handle,
        has_been_activated: false,
    }));
    let activation = ActivationProxy {
        state: Arc::clone(&state),
    };
    let action = ActionProxy {
        state: Arc::clone(&state),
    };
    let deactivation = DeactivationProxy {
        state: Arc::clone(&state),
    };
    let adapter = Adapter::new(activation, action, deactivation);
    map.insert(
        handle,
        Box::new(WindowEntry {
            adapter,
            state,
        }),
    );
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let _ = map.remove(&handle);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bytes: JByteArray,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    // Reinterpret as &[u8] — JNI gives us i8 (Java byte is signed).
    let bytes_u8: &[u8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u8, len) };

    let parsed = match parse_snapshot(bytes_u8) {
        Some(p) => p,
        None => return JNI_FALSE,
    };
    if parsed.is_partial {
        // The full-snapshot entry point should never receive a partial
        // payload; reject loudly so the JVM's gating bug surfaces in dev.
        eprintln!("[a11y] full apply rejected: buffer carries FLAG_PARTIAL");
        return JNI_FALSE;
    }

    apply_parsed(handle, parsed, /* partial = */ false)
}

/// Shared apply path for full and partial wire-format buffers. Holds the
/// `WINDOWS` lock across `update_if_active` so concurrent Detach can't drop
/// the snapshot between mutex regions.
fn apply_parsed(handle: i64, parsed: ParsedSnapshot, partial: bool) -> jboolean {
    let ParsedSnapshot {
        mut update,
        metas,
        root_id,
        ..
    } = parsed;

    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return JNI_FALSE;
    };
    {
        let mut st = match entry.state.lock() {
            Ok(s) => s,
            Err(_) => return JNI_FALSE,
        };
        if partial {
            // Merge incoming metadata into the existing map so action
            // dispatch keeps working for un-emitted nodes.
            for (id, m) in metas {
                st.nodes.insert(id, m);
            }
        } else {
            st.nodes = metas;
            st.root = root_id;
            // A full snapshot is the complete tree — drop the previous
            // topology before remirroring it below.
            st.child_map.clear();
        }

        // Mirror AccessKit's tree topology, then clamp the focus so we never
        // hand the consumer a `TreeUpdate.focus` pointing at a node it doesn't
        // hold — that panics inside accesskit_consumer, and `panic = "abort"`
        // turns it into a JVM-wide crash. Both update kinds funnel through
        // here so full snapshots, partials and the cached `last_tree` (replayed
        // on AT reconnection) are all protected.
        for (id, node) in &update.nodes {
            st.child_map.insert(*id, node.children().to_vec());
        }
        let live = reachable_nodes(st.root, &st.child_map);
        // Prune nodes AccessKit will have dropped as unreachable, keeping our
        // mirror in lockstep with the consumer's node set.
        st.child_map.retain(|id, _| live.contains(id));
        if !live.contains(&update.focus) {
            if let Some(root) = st.root {
                update.focus = root;
            }
        }
        // Cache the (clamped) tree for the eventual `request_initial_tree`.
        if !partial {
            st.last_tree = Some(update.clone());
        }
    }
    entry.adapter.update_if_active(|| update);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplyPartialSnapshot(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bytes: JByteArray,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    let bytes_u8: &[u8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u8, len) };

    let parsed = match parse_snapshot(bytes_u8) {
        Some(p) => p,
        None => return JNI_FALSE,
    };
    if !parsed.is_partial {
        eprintln!("[a11y] partial apply rejected: buffer is not flagged FLAG_PARTIAL");
        return JNI_FALSE;
    }
    apply_parsed(handle, parsed, /* partial = */ true)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // Report the *real* AT-connected state. The Kotlin observer combines
    // this with its `pendingForcedPush` flag (true on first attach) so the
    // first snapshot still lands and populates `state.last_tree` for the
    // eventual `request_initial_tree`. Returning true unconditionally was
    // a leftover heuristic that defeated the JVM-side skip path — every
    // recomposition paid a full BFS + UTF-8 encode + JNI copy even with no
    // AT connected. Now the cost is amortised to one push per AT
    // (de)activation transition plus the genuine semantic-change ticks
    // while an AT is listening.
    if AT_ACTIVE.load(Ordering::Relaxed) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if RESYNC.swap(false, Ordering::Relaxed) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
    // No bookkeeping needed — `AT_ACTIVE` and `RESYNC` are managed inside
    // the activation/deactivation handlers. Implemented for parity with
    // the macOS export so the Kotlin controller's call doesn't fail with
    // UnsatisfiedLinkError.
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // No screen-reader-detect API on Linux; report false. ATs that talk
    // AT-SPI are detected through `AT_ACTIVE` instead, which the activation
    // handler flips on first connect.
    JNI_FALSE
}

/// Override the AT-SPI application name. Without this, accesskit_unix uses
/// `current_exe()` which on the JVM is just "java" — so screen readers and
/// accessibility tools (Accerciser, Orca) all show the app as "java" instead
/// of its actual product name. Must be called before the first Adapter is
/// constructed; later calls are silently ignored.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11ySetAppName(
    mut env: JNIEnv,
    _class: JClass,
    name: jni::objects::JString,
) {
    let Ok(jstr) = env.get_string(&name) else { return };
    let s: String = jstr.into();
    if !s.is_empty() {
        accesskit_unix::set_app_name(s);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    node_id: jlong,
) {
    if handle == 0 {
        return;
    }
    // Build a focus-only TreeUpdate. AccessKit needs a `tree` field set on
    // the *first* update only — incremental focus changes can omit it. We
    // include the cached `Tree::new(root)` to cover the case where this
    // call runs before any snapshot has been pushed.
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    let requested = NodeId(node_id as u64);
    let (cached_tree, target) = {
        let st = match entry.state.lock() {
            Ok(s) => s,
            Err(_) => return,
        };
        let tree = st.last_tree.as_ref().and_then(|u| u.tree.clone());
        // This update carries no nodes, so `requested` must already be live in
        // AccessKit's tree — otherwise the consumer panics (JVM abort). Fall
        // back to the root when it isn't a node we currently hold.
        let target = if st.child_map.contains_key(&requested) {
            requested
        } else {
            match st.root {
                Some(root) => root,
                None => return,
            }
        };
        (tree, target)
    };
    entry.adapter.update_if_active(|| TreeUpdate {
        nodes: Vec::new(),
        tree: cached_tree,
        tree_id: TreeId::ROOT,
        focus: target,
    });
}

/// Optional: push outer/inner window geometry so AT-SPI screen-coordinate
/// queries (e.g. flat review) align with the actual on-screen position.
/// Called from Kotlin on `onResized` and `onWindowReady`. Coordinates are
/// in physical pixels relative to the X root window — i.e. raw XWayland
/// values; AccessKit applies them as-is to its Component.GetExtents
/// implementation.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11ySetRootBounds(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    outer_x: jlong,
    outer_y: jlong,
    outer_w: jlong,
    outer_h: jlong,
    inner_x: jlong,
    inner_y: jlong,
    inner_w: jlong,
    inner_h: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    let outer = Rect {
        x0: outer_x as f64,
        y0: outer_y as f64,
        x1: (outer_x + outer_w) as f64,
        y1: (outer_y + outer_h) as f64,
    };
    let inner = Rect {
        x0: inner_x as f64,
        y0: inner_y as f64,
        x1: (inner_x + inner_w) as f64,
        y1: (inner_y + inner_h) as f64,
    };
    entry.adapter.set_root_window_bounds(outer, inner);
}

/// Resolve outer/inner window geometry from the X server itself and push
/// it to AccessKit. The JVM only has window-local coordinates; we need
/// screen-space ones for AT-SPI's flat-review and screen-magnifier focus
/// tracking. Calling `XGetGeometry` + `XTranslateCoordinates` against the
/// X root gives us the true on-screen origin of the client area.
///
/// `xid` is the X11 Window ID (same value the JVM cached as the "handle"
/// at attach time on Linux). `display_ptr` is GDK's Display* (from
/// `nativeLinuxHandles`).
/// Cached Xlib symbol table — `Xlib::open` does dlopen+dlsym which is
/// expensive. We open it once and reuse. Safe to share across threads
/// because the function pointers are immutable.
static XLIB_INSTANCE: once_cell::sync::OnceCell<x11_dl::xlib::Xlib> =
    once_cell::sync::OnceCell::new();

/// Process-wide guard so two concurrent JNI calls into our resolver don't
/// race on Xlib's per-display request queue. GDK's main loop also reaches
/// into Xlib but holds GDK's own internal lock — without serialising our
/// reads, a fast resize can interleave our XGetGeometry with GDK's own
/// X traffic and corrupt the request queue, causing SIGSEGV inside
/// internal Xlib helpers like `XDefaultScreen`.
static X11_CALL_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// X11 error handler that swallows BadWindow/BadDrawable etc. without
/// killing the process. Without this, a stale XID (e.g. from an
/// already-destroyed dialog) would hard-abort the JVM.
unsafe extern "C" fn x11_error_handler(
    _display: *mut x11_dl::xlib::Display,
    _event: *mut x11_dl::xlib::XErrorEvent,
) -> i32 {
    0
}

/// RAII guard that restores the previous X11 error handler on Drop. We
/// must avoid leaking our own handler past this function — other parts
/// of the process (Skia/Skiko, GDK) install their own handlers and rely
/// on them.
struct X11ErrorHandlerGuard {
    xlib: &'static x11_dl::xlib::Xlib,
    prev: Option<unsafe extern "C" fn(*mut x11_dl::xlib::Display, *mut x11_dl::xlib::XErrorEvent) -> i32>,
}

impl Drop for X11ErrorHandlerGuard {
    fn drop(&mut self) {
        unsafe {
            (self.xlib.XSetErrorHandler)(self.prev);
        }
    }
}

fn scopeguard_restore(
    xlib: &'static x11_dl::xlib::Xlib,
    prev: Option<unsafe extern "C" fn(*mut x11_dl::xlib::Display, *mut x11_dl::xlib::XErrorEvent) -> i32>,
) -> X11ErrorHandlerGuard {
    X11ErrorHandlerGuard { xlib, prev }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yResolveX11Bounds(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    display_ptr: jlong,
    xid: jlong,
) {
    if handle == 0 || display_ptr == 0 || xid == 0 {
        return;
    }
    use x11_dl::xlib::{Window as XWindow, Xlib, XID};

    let xlib = match XLIB_INSTANCE.get_or_try_init(Xlib::open) {
        Ok(x) => x,
        Err(_) => return,
    };
    let display = display_ptr as *mut x11_dl::xlib::Display;
    let xid: XID = xid as XID;

    // Serialize concurrent calls — a fast user resize fires onResized
    // many times per second and racing X11 reads against GDK's main-loop
    // X traffic was crashing the JVM inside `XDefaultScreen`.
    let _guard = X11_CALL_LOCK.lock();

    // Install an error handler so a stale XID (e.g. window already
    // unmapped during shutdown) silently fails instead of aborting the
    // process. Restore the previous handler on exit.
    let prev_handler = unsafe { (xlib.XSetErrorHandler)(Some(x11_error_handler)) };
    let _restore = scopeguard_restore(xlib, prev_handler);

    // 1. Inner client geometry. Returns width/height in client area pixels.
    let mut root: XWindow = 0;
    let mut x: i32 = 0;
    let mut y: i32 = 0;
    let mut w: u32 = 0;
    let mut h: u32 = 0;
    let mut border: u32 = 0;
    let mut depth: u32 = 0;
    let geo_ok = unsafe {
        (xlib.XGetGeometry)(
            display,
            xid,
            &mut root,
            &mut x,
            &mut y,
            &mut w,
            &mut h,
            &mut border,
            &mut depth,
        ) != 0
    };
    if !geo_ok || w == 0 || h == 0 {
        return;
    }

    // 2. Translate (0, 0) client → root.
    let mut inner_root_x: i32 = 0;
    let mut inner_root_y: i32 = 0;
    let mut child: XWindow = 0;
    let trans_ok = unsafe {
        (xlib.XTranslateCoordinates)(
            display,
            xid,
            root,
            0,
            0,
            &mut inner_root_x,
            &mut inner_root_y,
            &mut child,
        ) != 0
    };
    if !trans_ok {
        return;
    }

    // 3. Push to AccessKit — outer == inner since Tao on Linux uses GTK
    //    client-side decorations (no separate WM frame to account for).
    let outer = Rect {
        x0: inner_root_x as f64,
        y0: inner_root_y as f64,
        x1: (inner_root_x + w as i32) as f64,
        y1: (inner_root_y + h as i32) as f64,
    };
    let inner = outer;

    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    entry.adapter.set_root_window_bounds(outer, inner);
}

/// Updates the window-focus state inside AccessKit. Called from Kotlin's
/// `onFocusChanged` so AT-SPI's `STATE_ACTIVE` flag on the toplevel matches
/// the actual X focus.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11ySetWindowFocus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    focused: jboolean,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    entry.adapter.update_window_focus_state(focused != JNI_FALSE);
}
