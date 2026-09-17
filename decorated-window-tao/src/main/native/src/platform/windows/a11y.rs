// Windows accessibility provider for the Tao backend.
//
// Architecture mirrors the Linux AccessKit path:
//   - The JVM owns the source-of-truth Compose semantic tree and pushes
//     compact byte-array snapshots through `nativeA11yApplySnapshot`.
//   - Each snapshot is decoded into an `accesskit::TreeUpdate` (shared
//     `crate::a11y::wire` decoder) and handed to an `accesskit_windows::Adapter`.
//   - The adapter is installed via Win32 subclassing so `WM_GETOBJECT` is
//     answered with a UIA provider. Narrator, NVDA and Inspect see the tree
//     like any native UIA application.
//   - Actions come back on a UIA dispatch thread; we forward them to the JVM
//     via the existing `dispatchA11y*` upcalls. The Kotlin registry is keyed
//     by HWND (same opaque handle role as NSView on macOS / XID on Linux).
//
// Replaces the hand-rolled `nucleus_tao_a11y.dll` COM projector.

use std::collections::HashMap;
use std::ffi::c_void;
use std::mem::transmute;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};

use accesskit::{
    Action, ActionHandler, ActionRequest, ActivationHandler, NodeId, TreeId, TreeUpdate,
};
use accesskit_windows::{Adapter, HWND, LPARAM, LRESULT, QueuedEvents, WPARAM};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use windows::core::PCWSTR;
use windows::Win32::Foundation::{HANDLE, HWND as WinHwnd, LPARAM as WinLParam, WPARAM as WinWParam};
use windows::Win32::UI::Input::KeyboardAndMouse::SetFocus;
use windows::Win32::UI::WindowsAndMessaging::{
    CallWindowProcW, GetPropW, IsWindowVisible, PostMessageW, RemovePropW, SetPropW,
    SetWindowLongPtrW, GWLP_WNDPROC, WM_ENTERMENULOOP, WM_ENTERSIZEMOVE, WM_EXITMENULOOP,
    WM_EXITSIZEMOVE, WM_GETOBJECT, WM_KILLFOCUS, WM_NCDESTROY, WM_SETFOCUS, WM_USER, WNDPROC,
};

use crate::a11y::jvm::forward_action_to_jvm;
use crate::a11y::tree::reachable_nodes;
use crate::a11y::wire::{parse_snapshot, NodeMeta, ParsedSnapshot};

// ── State ─────────────────────────────────────────────────────────────────

struct WindowState {
    /// Last full snapshot for `request_initial_tree`.
    last_tree: Option<TreeUpdate>,
    root: Option<NodeId>,
    nodes: HashMap<NodeId, NodeMeta>,
    child_map: HashMap<NodeId, Vec<NodeId>>,
    /// HWND as i64 — opaque key passed back to Kotlin on every upcall.
    handle: i64,
}

struct WindowEntry {
    /// Subclass owns the AccessKit Adapter + WndProc chain. Drop uninstalls.
    subclass: SubclassHost,
    state: Arc<Mutex<WindowState>>,
}

static WINDOWS: Lazy<Mutex<HashMap<i64, Box<WindowEntry>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

static AT_ACTIVE: AtomicBool = AtomicBool::new(false);
static RESYNC: AtomicBool = AtomicBool::new(false);

// Property name used to stash the subclass pointer on the HWND.
// Wide string for SetPropW / GetPropW.
const PROP_NAME: PCWSTR = windows::core::w!("NucleusAccessKitAdapter");

// ── AccessKit handlers ────────────────────────────────────────────────────

struct ActivationProxy {
    state: Arc<Mutex<WindowState>>,
}

impl ActivationHandler for ActivationProxy {
    fn request_initial_tree(&mut self) -> Option<TreeUpdate> {
        AT_ACTIVE.store(true, Ordering::Relaxed);
        RESYNC.store(true, Ordering::Relaxed);
        let st = self.state.lock().ok()?;
        // Clone the cached full tree if we have one. `None` is fine —
        // AccessKit installs a placeholder root and the next full push
        // (forced by RESYNC) replaces it. `pendingForcedPush` on the JVM
        // side also seeds a tree at attach time before any AT queries.
        st.last_tree.clone()
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
        // UIA SetFocus updates Compose semantic focus via RequestFocus, but
        // Win32 keyboard focus may still sit on the non-client system menu
        // (Alt/Tab chrome). Pull the client HWND into keyboard focus on the
        // UI thread so Tab/Enter/typing reach Compose — matching Chromium's
        // "focus action implies host window activation" behaviour.
        if request.action == Action::Focus {
            let win_hwnd = WinHwnd(handle as *mut c_void);
            unsafe {
                let _ = PostMessageW(
                    Some(win_hwnd),
                    WM_NUCLEUS_A11Y_ENSURE_CLIENT_FOCUS,
                    WinWParam(0),
                    WinLParam(0),
                );
            }
        }
        forward_action_to_jvm(handle, request, meta_actions, custom_count);
    }
}

// ── Win32 subclass host (Adapter + WM_GETOBJECT) ──────────────────────────
//
// Mirrors `accesskit_windows::SubclassingAdapter` but:
//   - no "window must not be visible" panic (attach can race past first paint)
//   - uses `Mutex` + `try_lock` instead of `RefCell` (nested GETOBJECT re-entry)
//   - **defers** `QueuedEvents::raise` via `PostMessage(WM_NUCLEUS_A11Y_FLUSH)`
//     so UIA events fire from a pure WndProc turn with a live message pump —
//     matching Chromium and the old nucleus_tao_a11y.dll deferred-flush path.
//     Raising inline during Compose `MAIN_EVENTS_CLEARED` / JNI can return
//     S_OK from `UiaRaise*` while out-of-process .NET clients still see zero
//     PropertyChanged deliveries (`uia-problem.txt`).
//
// Invariant: never hold `adapter_state` across `QueuedEvents::raise()`.

/// Private WndProc message: drain + raise queued AccessKit UIA events.
const WM_NUCLEUS_A11Y_FLUSH: u32 = WM_USER + 0xA11;
/// Private WndProc message: ensure the client HWND owns Win32 keyboard focus
/// after an AccessKit `Action::Focus` (posted from the UIA/COM thread).
const WM_NUCLEUS_A11Y_ENSURE_CLIENT_FOCUS: u32 = WM_USER + 0xA12;

struct SubclassState {
    adapter: Adapter,
    activation_handler: Box<dyn ActivationHandler + Send>,
}

struct SubclassImpl {
    hwnd: HWND,
    /// Guards Adapter + ActivationHandler. Taken briefly; never across raise().
    adapter_state: Mutex<SubclassState>,
    /// Events waiting for the UI-thread flush. Populated by tree apply / focus
    /// updates; drained on `WM_NUCLEUS_A11Y_FLUSH`.
    pending_events: Mutex<Vec<QueuedEvents>>,
    prev_wnd_proc: WNDPROC,
    window_destroyed: std::sync::atomic::AtomicBool,
}

// HWND is a raw pointer; the WndProc only runs on the window thread. Cross-
// thread tree applies go through `adapter_state` (Mutex) and WINDOWS.
unsafe impl Send for SubclassImpl {}
unsafe impl Sync for SubclassImpl {}

// Work around SetWindowLongPtrW pointer-width difference on 32/64-bit.
#[cfg(any(target_arch = "x86_64", target_arch = "aarch64"))]
type LongPtr = isize;
#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
type LongPtr = i32;

extern "system" fn a11y_wnd_proc(
    window: WinHwnd,
    message: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    let handle = unsafe { GetPropW(window, PROP_NAME) };
    let impl_ptr = handle.0 as *const SubclassImpl;
    if impl_ptr.is_null() {
        return unsafe { CallWindowProcW(None, window, message, wparam, lparam) };
    }
    let r#impl = unsafe { &*impl_ptr };
    match message {
        WM_GETOBJECT => {
            // try_lock: if a tree apply / focus update already holds the
            // adapter, a nested GETOBJECT from raise()/UIA must not panic or
            // deadlock. Falling through to the previous WndProc is safe —
            // UIA retries; AccessKit may already be in Placeholder/Active.
            if let Ok(mut state) = r#impl.adapter_state.try_lock() {
                // Split borrows: adapter and activation_handler are distinct
                // fields of SubclassState (same pattern as accesskit_windows).
                let SubclassState {
                    ref mut adapter,
                    ref mut activation_handler,
                } = *state;
                if let Some(result) = adapter.handle_wm_getobject(
                    WPARAM(wparam.0),
                    LPARAM(lparam.0),
                    &mut **activation_handler,
                ) {
                    let lr: LRESULT = result.into();
                    drop(state);
                    return windows::Win32::Foundation::LRESULT(lr.0);
                }
            }
        }
        m if m == WM_NUCLEUS_A11Y_FLUSH => {
            r#impl.flush_pending_events();
            return windows::Win32::Foundation::LRESULT(0);
        }
        m if m == WM_NUCLEUS_A11Y_ENSURE_CLIENT_FOCUS => {
            // Must run on the window thread (SetFocus thread affinity).
            // Pulls keyboard focus off the system menu / non-client chrome
            // so subsequent Tab/Enter keystrokes land in Compose.
            unsafe {
                let _ = SetFocus(Some(window));
            }
            return windows::Win32::Foundation::LRESULT(0);
        }
        WM_SETFOCUS | WM_EXITMENULOOP | WM_EXITSIZEMOVE => {
            r#impl.update_window_focus_state(true);
        }
        WM_KILLFOCUS | WM_ENTERMENULOOP | WM_ENTERSIZEMOVE => {
            r#impl.update_window_focus_state(false);
        }
        WM_NCDESTROY => {
            r#impl
                .window_destroyed
                .store(true, std::sync::atomic::Ordering::Relaxed);
        }
        _ => (),
    }
    unsafe { CallWindowProcW(r#impl.prev_wnd_proc, window, message, wparam, lparam) }
}

impl SubclassImpl {
    fn new(
        hwnd: HWND,
        is_window_focused: bool,
        activation_handler: impl 'static + ActivationHandler + Send,
        action_handler: impl 'static + ActionHandler + Send,
    ) -> Box<Self> {
        let adapter = Adapter::new(hwnd, is_window_focused, action_handler);
        Box::new(Self {
            hwnd,
            adapter_state: Mutex::new(SubclassState {
                adapter,
                activation_handler: Box::new(activation_handler),
            }),
            pending_events: Mutex::new(Vec::new()),
            prev_wnd_proc: None,
            window_destroyed: std::sync::atomic::AtomicBool::new(false),
        })
    }

    fn install(&mut self) {
        let win_hwnd = WinHwnd(self.hwnd.0);
        // If a previous Nucleus AccessKit subclass is still hanging off this
        // HWND (attach without detach), refuse rather than corrupt the chain.
        if !unsafe { GetPropW(win_hwnd, PROP_NAME) }.0.is_null() {
            eprintln!("[a11y] AccessKit subclass already installed on HWND {:?}", self.hwnd.0);
            return;
        }
        unsafe {
            let _ = SetPropW(
                win_hwnd,
                PROP_NAME,
                Some(HANDLE(self as *const SubclassImpl as *mut c_void)),
            );
        }
        let result = unsafe {
            SetWindowLongPtrW(win_hwnd, GWLP_WNDPROC, a11y_wnd_proc as *const c_void as LongPtr)
        };
        if result == 0 {
            eprintln!("[a11y] SetWindowLongPtrW failed installing AccessKit subclass");
            let _ = unsafe { RemovePropW(win_hwnd, PROP_NAME) };
            return;
        }
        self.prev_wnd_proc = unsafe { transmute::<LongPtr, WNDPROC>(result) };
    }

    /// Queue AccessKit events and post a flush to the HWND's message pump.
    /// Safe to call from any thread that may hold JNI / Compose locks.
    fn schedule_raise(&self, events: QueuedEvents) {
        if let Ok(mut q) = self.pending_events.lock() {
            q.push(events);
        } else {
            return;
        }
        let win_hwnd = WinHwnd(self.hwnd.0);
        // Ignore PostMessage failures (window dying); events are dropped with
        // the queue on detach.
        let _ = unsafe {
            PostMessageW(Some(win_hwnd), WM_NUCLEUS_A11Y_FLUSH, WinWParam(0), WinLParam(0))
        };
    }

    fn flush_pending_events(&self) {
        let batch = match self.pending_events.lock() {
            Ok(mut q) if !q.is_empty() => std::mem::take(&mut *q),
            _ => return,
        };
        // Raise outside pending_events + adapter_state locks. raise() may
        // re-enter WM_GETOBJECT and even schedule more flushes.
        for events in batch {
            events.raise();
        }
    }

    fn update_window_focus_state(&self, is_focused: bool) {
        // try_lock: focus messages can nest under raise()/GETOBJECT.
        let events = {
            let Ok(mut state) = self.adapter_state.try_lock() else {
                return;
            };
            state.adapter.update_window_focus_state(is_focused)
        };
        if let Some(events) = events {
            // Focus updates already arrive on the UI thread; raise inline so
            // focus tracking stays tight. Tree-apply path uses schedule_raise.
            events.raise();
        }
    }

    fn uninstall(&self) {
        if self
            .window_destroyed
            .load(std::sync::atomic::Ordering::Relaxed)
        {
            return;
        }
        // Drop any unraised events — the HWND is going away.
        if let Ok(mut q) = self.pending_events.lock() {
            q.clear();
        }
        let win_hwnd = WinHwnd(self.hwnd.0);
        if self.prev_wnd_proc.is_some() {
            let _ = unsafe {
                SetWindowLongPtrW(
                    win_hwnd,
                    GWLP_WNDPROC,
                    transmute::<WNDPROC, LongPtr>(self.prev_wnd_proc),
                )
            };
        }
        let _ = unsafe { RemovePropW(win_hwnd, PROP_NAME) };
    }

    /// Apply a tree update if the AccessKit adapter is active and **schedule**
    /// any resulting UIA events for UI-thread flush (see `schedule_raise`).
    fn update_if_active(&self, update_factory: impl FnOnce() -> TreeUpdate) {
        let events = {
            let Ok(mut state) = self.adapter_state.lock() else {
                return;
            };
            state.adapter.update_if_active(update_factory)
        };
        if let Some(events) = events {
            self.schedule_raise(events);
        }
    }
}

/// Owns a boxed SubclassImpl so the pointer stashed in the HWND prop stays
/// valid until detach / drop.
struct SubclassHost(Box<SubclassImpl>);

impl SubclassHost {
    fn new(
        hwnd: HWND,
        is_window_focused: bool,
        activation_handler: impl 'static + ActivationHandler + Send,
        action_handler: impl 'static + ActionHandler + Send,
    ) -> Self {
        let mut r#impl =
            SubclassImpl::new(hwnd, is_window_focused, activation_handler, action_handler);
        r#impl.install();
        Self(r#impl)
    }

    fn update_if_active(&self, update_factory: impl FnOnce() -> TreeUpdate) {
        self.0.update_if_active(update_factory)
    }
}

impl Drop for SubclassHost {
    fn drop(&mut self) {
        self.0.uninstall();
    }
}

// ── Apply path ────────────────────────────────────────────────────────────

fn apply_parsed(handle: i64, parsed: ParsedSnapshot, partial: bool) -> jboolean {
    let ParsedSnapshot {
        mut update,
        metas,
        root_id,
        ..
    } = parsed;

    {
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
                for (id, m) in metas {
                    st.nodes.insert(id, m);
                }
            } else {
                st.nodes = metas;
                st.root = root_id;
                st.child_map.clear();
            }

            for (id, node) in &update.nodes {
                st.child_map.insert(*id, node.children().to_vec());
            }
            let live = reachable_nodes(st.root, &st.child_map);
            st.child_map.retain(|id, _| live.contains(id));
            if !live.contains(&update.focus) {
                if let Some(root) = st.root {
                    update.focus = root;
                }
            }
            // Cache full trees for AT (re)activation. Also keep partial
            // merges out of last_tree — AccessKit holds the live tree; on
            // reconnection RESYNC forces a fresh full snapshot from the JVM.
            if !partial {
                st.last_tree = Some(update.clone());
            }
        }
        // Applies under WINDOWS so detach cannot free the subclass mid-update.
        // Events are *scheduled* (PostMessage flush) — not raised inline —
        // so we can drop WINDOWS before UIA re-enters WndProc on flush.
        entry.subclass.update_if_active(|| update);
    }
    JNI_TRUE
}

fn read_jbytes(env: &JNIEnv, bytes: &JByteArray) -> Option<Vec<u8>> {
    let len = match env.get_array_length(bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return None,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(bytes, 0, &mut buf).is_err() {
        return None;
    }
    Some(buf.into_iter().map(|b| b as u8).collect())
}

// ── JNI exports ───────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if map.contains_key(&hwnd) {
        return;
    }

    let hwnd_ak = HWND(hwnd as *mut c_void);
    let win_hwnd = WinHwnd(hwnd as *mut c_void);
    // Best-effort focus hint for the adapter; the subclass tracks focus
    // changes via WM_SETFOCUS / WM_KILLFOCUS afterwards.
    let is_focused = unsafe { IsWindowVisible(win_hwnd).as_bool() };

    let state = Arc::new(Mutex::new(WindowState {
        last_tree: None,
        root: None,
        nodes: HashMap::new(),
        child_map: HashMap::new(),
        handle: hwnd,
    }));
    let activation = ActivationProxy {
        state: Arc::clone(&state),
    };
    let action = ActionProxy {
        state: Arc::clone(&state),
    };
    let subclass = SubclassHost::new(hwnd_ak, is_focused, activation, action);
    map.insert(
        hwnd,
        Box::new(WindowEntry {
            subclass,
            state,
        }),
    );
    // Seed path: force a full push so last_tree is populated before the
    // first UIA query (same role as Linux's pendingForcedPush + RESYNC).
    RESYNC.store(true, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let _ = map.remove(&hwnd);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
    bytes: JByteArray,
) -> jboolean {
    if hwnd == 0 {
        return JNI_FALSE;
    }
    let Some(buf) = read_jbytes(&env, &bytes) else {
        return JNI_FALSE;
    };
    let parsed = match parse_snapshot(&buf) {
        Some(p) => p,
        None => return JNI_FALSE,
    };
    if parsed.is_partial {
        eprintln!("[a11y] full apply rejected: buffer carries FLAG_PARTIAL");
        return JNI_FALSE;
    }
    apply_parsed(hwnd, parsed, /* partial = */ false)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplyPartialSnapshot(
    env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
    bytes: JByteArray,
) -> jboolean {
    if hwnd == 0 {
        return JNI_FALSE;
    }
    let Some(buf) = read_jbytes(&env, &bytes) else {
        return JNI_FALSE;
    };
    let parsed = match parse_snapshot(&buf) {
        Some(p) => p,
        None => return JNI_FALSE,
    };
    if !parsed.is_partial {
        eprintln!("[a11y] partial apply rejected: buffer is not flagged FLAG_PARTIAL");
        return JNI_FALSE;
    }
    apply_parsed(hwnd, parsed, /* partial = */ true)
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // True once any AT has issued WM_GETOBJECT (ActivationHandler ran).
    // The JVM-side `pendingForcedPush` still seeds the first full snapshot at
    // attach so `request_initial_tree` can answer immediately — same model as
    // Linux AT_ACTIVE gating.
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
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
    node_id: jlong,
) {
    if hwnd == 0 {
        return;
    }
    {
        let mut map = match WINDOWS.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let Some(entry) = map.get_mut(&hwnd) else {
            return;
        };
        let requested = NodeId(node_id as u64);
        let target = {
            let st = match entry.state.lock() {
                Ok(s) => s,
                Err(_) => return,
            };
            if st.child_map.contains_key(&requested) {
                requested
            } else {
                match st.root {
                    Some(root) => root,
                    None => return,
                }
            }
        };
        // Schedules a flush; does not raise while holding WINDOWS.
        entry.subclass.update_if_active(|| TreeUpdate {
            nodes: Vec::new(),
            tree: None,
            tree_id: TreeId::ROOT,
            focus: target,
        });
    }
}

/// No-op on Windows — UIA reads the HWND title / process name for the app.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11ySetAppName(
    _env: JNIEnv,
    _class: JClass,
    _name: jni::objects::JString,
) {
}
