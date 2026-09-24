// Precision-touchpad pan and pinch through DirectManipulation (#706).
//
// Without it, Windows hands a desktop app its touchpad as the legacy emulation:
// a two-finger pan becomes WM_MOUSEWHEEL ticks and a pinch Ctrl-flagged ones,
// with no phases, no ratio, no inertia and no way to tell a pinch from a real
// Ctrl+wheel. A DirectManipulation viewport bound to the HWND gets the real
// manipulation instead — what Chromium (`DirectManipulationHelper`), Firefox
// (`DirectManipulationOwner`) and Flutter (`DirectManipulationOwner`) do.
//
// Division of labour:
//   - this file owns the COM objects, claims touchpad contacts on
//     DM_POINTERHITTEST, ticks the update manager, and forwards the viewport's
//     raw status changes and content transforms to the JVM
//     (`EventCallback.onDirectManipulation`), with the focal point;
//   - `TaoDirectManipulationGesture` (Kotlin) turns that stream into the
//     scroll-gesture and magnify phases the macOS host already consumes, so
//     the classification (pan vs pinch, fling) is unit-tested on every OS.
//
// Configuration: translation + scaling + translation inertia + rails, the
// Chromium set. A viewport configured for scaling only would still own a
// claimed contact's pan and drop it (DirectManipulation does not hand a
// contact back to the wheel emulation), so pan and pinch are claimed together
// and both leave through the gesture wire — pan as the macOS-shaped scroll
// gesture (#654), pinch as magnify with real phases (#660).
//
// Only touchpad contacts are claimed. A touchscreen contact keeps the
// WM_POINTER path (real multi-touch pointers for Compose) — and could not be
// claimed here anyway: without a DirectComposition compositor behind the
// viewport, DirectManipulation refuses touch (`SetContact` answers
// ERROR_NOT_FOUND), touchpads being hit-tested through DM_POINTERHITTEST
// instead.
//
// Threading: everything here runs on the event-loop thread. COM callbacks
// fire inside Update / SetContact / ZoomToRect or from the window's message
// queue, and only queue; the queue reaches the JVM from `pump`, which the loop
// closure calls — never from the window procedure, which a modal loop nested
// inside a JVM callback can re-enter while `dispatch` holds the callback lock.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::time::{Duration, Instant};

use jni::objects::{JClass, JFloatArray};
use jni::sys::{jboolean, jint, jintArray, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use windows::core::{implement, Ref};
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, RECT, RPC_E_CHANGED_MODE, WPARAM};
use windows::Win32::Graphics::DirectManipulation::{
    DirectManipulationManager, IDirectManipulationContent, IDirectManipulationFrameInfoProvider,
    IDirectManipulationManager, IDirectManipulationPrimaryContent,
    IDirectManipulationUpdateManager, IDirectManipulationViewport,
    IDirectManipulationViewportEventHandler, IDirectManipulationViewportEventHandler_Impl,
    DIRECTMANIPULATION_CONFIGURATION_INTERACTION, DIRECTMANIPULATION_CONFIGURATION_RAILS_X,
    DIRECTMANIPULATION_CONFIGURATION_RAILS_Y, DIRECTMANIPULATION_CONFIGURATION_SCALING,
    DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_INERTIA,
    DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_X, DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_Y,
    DIRECTMANIPULATION_INERTIA, DIRECTMANIPULATION_MOUSEFOCUS, DIRECTMANIPULATION_READY,
    DIRECTMANIPULATION_RUNNING, DIRECTMANIPULATION_STATUS,
    DIRECTMANIPULATION_VIEWPORT_OPTIONS_MANUALUPDATE,
};
use windows::Win32::Graphics::Gdi::ScreenToClient;
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED,
};
use windows::Win32::UI::Input::Pointer::GetPointerType;
use windows::Win32::UI::Shell::{DefSubclassProc, RemoveWindowSubclass, SetWindowSubclass};
use windows::Win32::UI::WindowsAndMessaging::{
    GetClientRect, GetCursorPos, KillTimer, SetTimer, DM_POINTERHITTEST, MSG, POINTER_INPUT_TYPE,
    PT_TOUCHPAD, SIZE_MINIMIZED, WM_MOUSEWHEEL, WM_NCDESTROY, WM_SIZE, WM_TIMER,
};

use crate::events::{dispatch_direct_manipulation, UserEvent};
use crate::state::send_user_event;

// Wire kinds of `EventCallback.onDirectManipulation`; mirror Kotlin
// `TaoDirectManipulationEvent`.
const EVENT_STATUS: jint = 0;
const EVENT_CONTENT: jint = 1;

const SUBCLASS_ID: usize = 0x4E44_4D31; // "NDM1"
const TIMER_ID: usize = 0x4E44_4D32;
// Fallback pump while a manipulation runs and nothing repaints. Frames that
// do repaint tick the viewport themselves (`pump` before every redraw), so
// this only bounds the latency of a gesture over content that does not move.
const TIMER_MILLIS: u32 = 8;
// How long a freshly claimed contact keeps the pump ticking with nothing to
// report yet: fingers that rest before they move.
const CLAIM_GRACE: Duration = Duration::from_millis(1_000);

// DirectManipulation rejects a zoom minimum below 0.1. The maximum only has to
// outlast one gesture: the content transform is reset after every one.
const ZOOM_MINIMUM: f32 = 0.1;
const ZOOM_MAXIMUM: f32 = 100.0;
// Longest a content reset may take to report its end (see `reset_content`).
const RESET_TIMEOUT: Duration = Duration::from_millis(500);
// How far past the window the content reaches — see `content_rect`.
const CONTENT_MARGIN: i32 = 100_000;

/// `nucleus.tao.directManipulation` — set by the JVM before the loop starts.
static ENABLED: AtomicBool = AtomicBool::new(true);

/// Test hook (`NUCLEUS_TAO_INPUT_INJECTION=1` only): mouse wheel notches are
/// also handed to the viewport, the one input a test can inject that
/// DirectManipulation itself animates — see
/// `nativeDiagDirectManipulationWheelFocus`.
static WHEEL_FOCUS: AtomicBool = AtomicBool::new(false);

/// DM_POINTERHITTEST messages seen by any viewport, for diagnostics.
static HIT_TESTS: AtomicU32 = AtomicU32::new(0);

#[derive(Clone, Copy)]
struct Event {
    kind: jint,
    current: jint,
    previous: jint,
    transform: Transform,
    focal: (f32, f32),
}

#[derive(Clone, Copy, PartialEq, Debug)]
struct Transform {
    scale: f32,
    x: f32,
    y: f32,
}

impl Transform {
    const IDENTITY: Transform = Transform {
        scale: 1.0,
        x: 0.0,
        y: 0.0,
    };
}

/// What the event handler and the pump share. Borrowed only for short, non
/// re-entrant stretches: never across a DirectManipulation call, which may
/// fire the handler.
struct Shared {
    handle: u64,
    hwnd: HWND,
    queue: Vec<Event>,
    status: DIRECTMANIPULATION_STATUS,
    /// The viewport's own reset is running: its transitions are not the user's.
    /// It ends when the reset settles back on READY, when a contact comes
    /// down, or at [Shared::reset_deadline] — a reset that moved nothing
    /// reports no transition at all.
    resetting: bool,
    /// The reset has left READY (its RUNNING arrived), so its READY is final.
    reset_moved: bool,
    reset_deadline: Option<Instant>,
    /// A manipulation ended; reset the content transform once the queue is out.
    reset_pending: bool,
}

impl Shared {
    fn end_reset(&mut self) {
        self.resetting = false;
        self.reset_moved = false;
        self.reset_deadline = None;
    }

    fn active(&self) -> bool {
        self.status == DIRECTMANIPULATION_RUNNING || self.status == DIRECTMANIPULATION_INERTIA
    }

    /// Queues one event and makes sure the loop comes to deliver it: status
    /// changes also arrive through the window's message queue, not only from
    /// inside an `Update` the pump is already running.
    fn push(&mut self, event: Event) {
        if self.queue.is_empty() {
            send_user_event(UserEvent::DirectManipulationTick {
                handle: self.handle,
            });
        }
        self.queue.push(event);
    }

    /// Where the gesture is, client-area physical pixels. A touchpad has no
    /// screen position of its own: the gesture happens where the cursor is —
    /// Chromium, Firefox and Flutter all read `GetCursorPos`, and AppKit
    /// reports the same for a magnify.
    fn focal(&self) -> (f32, f32) {
        let mut point = POINT::default();
        // SAFETY: valid out-pointers on the calling thread.
        unsafe {
            if GetCursorPos(&mut point).is_err() {
                return (0.0, 0.0);
            }
            let _ = ScreenToClient(self.hwnd, &mut point);
        }
        (point.x as f32, point.y as f32)
    }
}

#[implement(IDirectManipulationViewportEventHandler)]
struct Handler {
    shared: Rc<RefCell<Shared>>,
}

fn read_transform(content: &IDirectManipulationContent) -> Transform {
    let mut matrix = [0f32; 6];
    // SAFETY: the matrix is the 6-float 2D affine transform DirectManipulation fills.
    match unsafe { content.GetContentTransform(&mut matrix) } {
        // [scale, 0, 0, scale, x, y]: translation and scaling only, no rotation.
        Ok(()) => Transform {
            scale: matrix[0],
            x: matrix[4],
            y: matrix[5],
        },
        Err(_) => Transform::IDENTITY,
    }
}

fn primary_transform(viewport: &IDirectManipulationViewport) -> Transform {
    // SAFETY: plain COM getter on the event-loop thread.
    match unsafe { viewport.GetPrimaryContent::<IDirectManipulationContent>() } {
        Ok(content) => read_transform(&content),
        Err(_) => Transform::IDENTITY,
    }
}

impl IDirectManipulationViewportEventHandler_Impl for Handler_Impl {
    fn OnViewportStatusChanged(
        &self,
        viewport: Ref<IDirectManipulationViewport>,
        current: DIRECTMANIPULATION_STATUS,
        previous: DIRECTMANIPULATION_STATUS,
    ) -> windows::core::Result<()> {
        let transform = viewport.ok().map(primary_transform).unwrap_or(Transform::IDENTITY);
        let Ok(mut shared) = self.shared.try_borrow_mut() else {
            return Ok(());
        };
        shared.status = current;
        if shared.resetting {
            // The synthetic reset runs READY → RUNNING → READY, partly after the
            // `Update` that started it; nothing it does is a gesture.
            if current != DIRECTMANIPULATION_READY {
                shared.reset_moved = true;
            } else if shared.reset_moved {
                shared.end_reset();
            }
            return Ok(());
        }
        if current == previous {
            return Ok(());
        }
        let focal = shared.focal();
        shared.push(Event {
            kind: EVENT_STATUS,
            current: current.0,
            previous: previous.0,
            transform,
            focal,
        });
        if current == DIRECTMANIPULATION_READY {
            shared.reset_pending = true;
        }
        Ok(())
    }

    fn OnViewportUpdated(
        &self,
        _viewport: Ref<IDirectManipulationViewport>,
    ) -> windows::core::Result<()> {
        Ok(())
    }

    fn OnContentUpdated(
        &self,
        _viewport: Ref<IDirectManipulationViewport>,
        content: Ref<IDirectManipulationContent>,
    ) -> windows::core::Result<()> {
        let transform = content.ok().map(read_transform).unwrap_or(Transform::IDENTITY);
        let Ok(mut shared) = self.shared.try_borrow_mut() else {
            return Ok(());
        };
        if shared.resetting || transform.scale == 0.0 {
            return Ok(());
        }
        let status = shared.status.0;
        let focal = shared.focal();
        shared.push(Event {
            kind: EVENT_CONTENT,
            current: status,
            previous: status,
            transform,
            focal,
        });
        Ok(())
    }
}

/// One viewport per Tao window.
struct Viewport {
    handle: u64,
    hwnd: HWND,
    manager: IDirectManipulationManager,
    updater: IDirectManipulationUpdateManager,
    viewport: IDirectManipulationViewport,
    cookie: u32,
    shared: Rc<RefCell<Shared>>,
    timer: Cell<bool>,
    /// The pump keeps ticking until then even while the viewport reports
    /// nothing: a contact that rests before it moves is still being claimed.
    claim_grace_until: Cell<Option<Instant>>,
    detached: Cell<bool>,
    hit_tests: Cell<u32>,
    claimed: Cell<u32>,
    delivered: Cell<u32>,
}

thread_local! {
    static VIEWPORTS: RefCell<HashMap<u64, Rc<Viewport>>> = RefCell::new(HashMap::new());
}

fn lookup(handle: u64) -> Option<Rc<Viewport>> {
    VIEWPORTS.with(|map| map.borrow().get(&handle).cloned())
}

/// The primary content: the viewport grown by [CONTENT_MARGIN] on every side.
/// The content's edges bound the translation (past them the viewport would
/// rubber-band and chain), so it reaches far past the window — one gesture's
/// worth, since the transform is reset after every one.
fn content_rect(viewport: &RECT) -> RECT {
    RECT {
        left: viewport.left - CONTENT_MARGIN,
        top: viewport.top - CONTENT_MARGIN,
        right: viewport.right + CONTENT_MARGIN,
        bottom: viewport.bottom + CONTENT_MARGIN,
    }
}

fn client_rect(hwnd: HWND) -> RECT {
    let mut rect = RECT::default();
    // SAFETY: valid out-pointer; a failure leaves the empty rect clamped below.
    let _ = unsafe { GetClientRect(hwnd, &mut rect) };
    RECT {
        left: 0,
        top: 0,
        right: rect.right.max(1),
        bottom: rect.bottom.max(1),
    }
}

/// Binds a viewport to a freshly created window. Silently leaves the window on
/// the legacy wheel emulation when DirectManipulation is disabled or refused.
pub(crate) fn attach(handle: u64, hwnd: HWND) {
    if !ENABLED.load(Ordering::Relaxed) || lookup(handle).is_some() {
        return;
    }
    // The loop thread is already an STA (tao's OleInitialize for drag-and-drop);
    // this only balances a thread that is not. An MTA thread would get the
    // manager through a proxy, its callbacks on another thread: stay legacy.
    // SAFETY: COM initialisation of the calling thread.
    if unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) } == RPC_E_CHANGED_MODE {
        return;
    }
    // SAFETY: every call below is a COM method on objects created here, on
    // the thread that owns them; `hwnd` is the live window being registered.
    let Ok(viewport) = (unsafe { create(handle, hwnd) }) else {
        return;
    };
    let viewport = Rc::new(viewport);
    // SAFETY: the subclass is removed on WM_NCDESTROY or in `release`, before
    // the viewport it looks up goes away; `handle` is plain data.
    let installed =
        unsafe { SetWindowSubclass(hwnd, Some(subclass_proc), SUBCLASS_ID, handle as usize) };
    if !installed.as_bool() {
        release(&viewport);
        return;
    }
    VIEWPORTS.with(|map| map.borrow_mut().insert(handle, viewport));
}

unsafe fn create(handle: u64, hwnd: HWND) -> windows::core::Result<Viewport> {
    let manager: IDirectManipulationManager =
        CoCreateInstance(&DirectManipulationManager, None, CLSCTX_INPROC_SERVER)?;
    let updater: IDirectManipulationUpdateManager = manager.GetUpdateManager()?;
    let viewport: IDirectManipulationViewport =
        manager.CreateViewport(None::<&IDirectManipulationFrameInfoProvider>, hwnd)?;
    viewport.ActivateConfiguration(
        DIRECTMANIPULATION_CONFIGURATION_INTERACTION
            | DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_X
            | DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_Y
            | DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_INERTIA
            | DIRECTMANIPULATION_CONFIGURATION_RAILS_X
            | DIRECTMANIPULATION_CONFIGURATION_RAILS_Y
            | DIRECTMANIPULATION_CONFIGURATION_SCALING,
    )?;
    viewport.SetViewportOptions(DIRECTMANIPULATION_VIEWPORT_OPTIONS_MANUALUPDATE)?;
    let shared = Rc::new(RefCell::new(Shared {
        handle,
        hwnd,
        queue: Vec::new(),
        status: DIRECTMANIPULATION_READY,
        resetting: false,
        reset_moved: false,
        reset_deadline: None,
        reset_pending: false,
    }));
    let handler: IDirectManipulationViewportEventHandler = Handler {
        shared: shared.clone(),
    }
    .into();
    let cookie = viewport.AddEventHandler(Some(hwnd), &handler)?;
    let bound = (|| {
        set_rects(&viewport, hwnd)?;
        if let Ok(content) = viewport.GetPrimaryContent::<IDirectManipulationPrimaryContent>() {
            let _ = content.SetZoomBoundaries(ZOOM_MINIMUM, ZOOM_MAXIMUM);
        }
        manager.Activate(hwnd)?;
        viewport.Enable()?;
        updater.Update(None)
    })();
    if let Err(error) = bound {
        let _ = viewport.RemoveEventHandler(cookie);
        let _ = viewport.Abandon();
        let _ = manager.Deactivate(hwnd);
        return Err(error);
    }
    Ok(Viewport {
        handle,
        hwnd,
        manager,
        updater,
        viewport,
        cookie,
        shared,
        timer: Cell::new(false),
        claim_grace_until: Cell::new(None),
        detached: Cell::new(false),
        hit_tests: Cell::new(0),
        claimed: Cell::new(0),
        delivered: Cell::new(0),
    })
}

/// Viewport = the client area; content = far past it (see [content_rect]).
unsafe fn set_rects(viewport: &IDirectManipulationViewport, hwnd: HWND) -> windows::core::Result<()> {
    let rect = client_rect(hwnd);
    viewport.SetViewportRect(&rect)?;
    if let Ok(content) = viewport.GetPrimaryContent::<IDirectManipulationContent>() {
        content.SetContentRect(&content_rect(&rect))?;
    }
    Ok(())
}

fn release(viewport: &Viewport) {
    if viewport.detached.replace(true) {
        return;
    }
    // SAFETY: COM teardown on the owning thread; the window is still valid
    // (WM_NCDESTROY runs before the HWND is freed).
    unsafe {
        if viewport.timer.replace(false) {
            let _ = KillTimer(Some(viewport.hwnd), TIMER_ID);
        }
        let _ = RemoveWindowSubclass(viewport.hwnd, Some(subclass_proc), SUBCLASS_ID);
        let _ = viewport.viewport.Stop();
        let _ = viewport.viewport.RemoveEventHandler(viewport.cookie);
        let _ = viewport.viewport.Abandon();
        let _ = viewport.manager.Deactivate(viewport.hwnd);
    }
    if let Ok(mut shared) = viewport.shared.try_borrow_mut() {
        shared.queue.clear();
    }
}

/// Drops the window's viewport. WM_NCDESTROY calls it for every way a window
/// can go.
fn detach(handle: u64) {
    if let Some(viewport) = VIEWPORTS.with(|map| map.borrow_mut().remove(&handle)) {
        release(&viewport);
    }
}

fn arm_timer(viewport: &Viewport) {
    if viewport.timer.replace(true) {
        return;
    }
    // SAFETY: a thread timer on a live window owned by this thread.
    unsafe {
        SetTimer(Some(viewport.hwnd), TIMER_ID, TIMER_MILLIS, None);
    }
}

fn disarm_timer(viewport: &Viewport) {
    if viewport.timer.replace(false) {
        // SAFETY: as in `arm_timer`.
        let _ = unsafe { KillTimer(Some(viewport.hwnd), TIMER_ID) };
    }
}

/// DM_POINTERHITTEST: the OS offers a new precision-touchpad contact — the
/// only way a touchpad reaches a viewport, since a desktop app never sees its
/// contacts as WM_POINTER messages.
fn on_hit_test(handle: u64, wparam: WPARAM) {
    let Some(viewport) = lookup(handle) else {
        return;
    };
    viewport.hit_tests.set(viewport.hit_tests.get().wrapping_add(1));
    HIT_TESTS.fetch_add(1, Ordering::Relaxed);
    // GET_POINTERID_WPARAM: the low word.
    let pointer_id = (wparam.0 & 0xFFFF) as u32;
    let mut kind = POINTER_INPUT_TYPE::default();
    // SAFETY: plain query into a stack value.
    if unsafe { GetPointerType(pointer_id, &mut kind) }.is_err() || kind != PT_TOUCHPAD {
        return;
    }
    claim(&viewport, pointer_id);
}

/// Hands [contact] to the viewport; `false` when it refused it.
fn claim(viewport: &Viewport, contact: u32) -> bool {
    // A contact coming down is the user's: whatever the synthetic reset still
    // waits for is over.
    if let Ok(mut shared) = viewport.shared.try_borrow_mut() {
        shared.end_reset();
    }
    // SAFETY: COM call on the owning thread; a stale id is refused, not fatal.
    if unsafe { viewport.viewport.SetContact(contact) }.is_err() {
        return false;
    }
    viewport.claimed.set(viewport.claimed.get().wrapping_add(1));
    viewport.claim_grace_until.set(Some(Instant::now() + CLAIM_GRACE));
    arm_timer(viewport);
    // Serve the first update from the loop at once rather than a timer tick
    // later: a flick is short enough for that wait to show.
    send_user_event(UserEvent::DirectManipulationTick {
        handle: viewport.handle,
    });
    true
}

/// Test hook: a wheel notch handed to the viewport the way Microsoft's
/// DirectManipulation sample does it — the mouse-focus contact plus
/// `ProcessInput` — so DirectManipulation animates it itself. The notch still
/// reaches Tao afterwards.
fn on_wheel_focus(handle: u64, hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) {
    let Some(viewport) = lookup(handle) else {
        return;
    };
    let message = MSG {
        hwnd,
        message: msg,
        wParam: wparam,
        lParam: lparam,
        ..Default::default()
    };
    // SAFETY: COM calls on the owning thread with a message that outlives them.
    unsafe {
        if claim(&viewport, DIRECTMANIPULATION_MOUSEFOCUS) {
            let _ = viewport.manager.ProcessInput(&message);
            let _ = viewport.viewport.ReleaseContact(DIRECTMANIPULATION_MOUSEFOCUS);
        }
    }
}

extern "system" fn subclass_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    _id: usize,
    refdata: usize,
) -> LRESULT {
    let handle = refdata as u64;
    match msg {
        DM_POINTERHITTEST => on_hit_test(handle, wparam),
        WM_MOUSEWHEEL if WHEEL_FOCUS.load(Ordering::Relaxed) => {
            on_wheel_focus(handle, hwnd, msg, wparam, lparam)
        }
        WM_TIMER if wparam.0 == TIMER_ID => {
            // Posted, not pumped here: see the threading note at the top.
            send_user_event(UserEvent::DirectManipulationTick { handle });
            return LRESULT(0);
        }
        WM_SIZE if wparam.0 as u32 != SIZE_MINIMIZED => {
            if let Some(viewport) = lookup(handle) {
                // SAFETY: COM calls on the owning thread.
                let _ = unsafe { set_rects(&viewport.viewport, hwnd) };
            }
        }
        WM_NCDESTROY => detach(handle),
        _ => {}
    }
    // SAFETY: forwards to the next procedure in this window's chain.
    unsafe { DefSubclassProc(hwnd, msg, wparam, lparam) }
}

/// Ticks the viewport and delivers what it reported. Called from the loop
/// closure only: on a `DirectManipulationTick`, and right before a window
/// paints, so every frame renders the manipulation as of that frame.
pub(crate) fn pump(handle: u64) {
    let Some(viewport) = lookup(handle) else {
        return;
    };
    if !busy(&viewport) {
        disarm_timer(&viewport);
        return;
    }
    // SAFETY: COM call on the owning thread; callbacks only queue.
    let _ = unsafe { viewport.updater.Update(None) };
    deliver(&viewport);
    if viewport.detached.get() {
        return;
    }
    if busy(&viewport) {
        arm_timer(&viewport);
    } else {
        disarm_timer(&viewport);
    }
}

/// Whether the viewport still needs ticking: a manipulation runs, something
/// waits for delivery, or a contact was claimed a moment ago.
fn busy(viewport: &Viewport) -> bool {
    let Ok(mut shared) = viewport.shared.try_borrow_mut() else {
        return true;
    };
    if shared.resetting && shared.reset_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
        shared.end_reset();
    }
    if shared.active() || shared.resetting || !shared.queue.is_empty() || shared.reset_pending {
        return true;
    }
    match viewport.claim_grace_until.get() {
        Some(until) if Instant::now() < until => true,
        Some(_) => {
            viewport.claim_grace_until.set(None);
            false
        }
        None => false,
    }
}

fn deliver(viewport: &Viewport) {
    loop {
        let events = match viewport.shared.try_borrow_mut() {
            Ok(mut shared) => std::mem::take(&mut shared.queue),
            Err(_) => return,
        };
        for event in events {
            // The JVM may close the window from inside a callback.
            if viewport.detached.get() {
                return;
            }
            viewport.delivered.set(viewport.delivered.get().wrapping_add(1));
            dispatch_direct_manipulation(
                viewport.handle,
                event.kind,
                event.current,
                event.previous,
                event.transform.scale,
                event.transform.x,
                event.transform.y,
                event.focal.0,
                event.focal.1,
            );
        }
        if viewport.detached.get() {
            return;
        }
        let reset = match viewport.shared.try_borrow_mut() {
            Ok(mut shared) => std::mem::take(&mut shared.reset_pending),
            Err(_) => return,
        };
        if !reset {
            return;
        }
        reset_content(viewport);
    }
}

/// Puts the content transform back to identity after a manipulation, so every
/// gesture starts from the same origin and one long session cannot run into
/// the content or zoom boundaries (Chromium's `ResetViewport`, Flutter's
/// synthesized reset). The JVM rebases every gesture on its first transform
/// anyway, so a reset that does not happen costs drift, never a jump.
///
/// The reset is a manipulation of its own — READY → RUNNING → READY, delivered
/// partly after `Update` returns — so the handler stays quiet until it settles
/// back on READY, the next contact comes down (`claim`) or [RESET_TIMEOUT]
/// passes.
fn reset_content(viewport: &Viewport) {
    if primary_transform(&viewport.viewport) == Transform::IDENTITY {
        return;
    }
    // SAFETY: COM calls on the owning thread.
    let rect = match unsafe { viewport.viewport.GetViewportRect() } {
        Ok(rect) => rect,
        Err(_) => return,
    };
    if let Ok(mut shared) = viewport.shared.try_borrow_mut() {
        shared.resetting = true;
        shared.reset_moved = false;
        shared.reset_deadline = Some(Instant::now() + RESET_TIMEOUT);
    }
    // SAFETY: as above; the callbacks it fires see `resetting` and stay quiet.
    unsafe {
        let _ = viewport.viewport.ZoomToRect(
            rect.left as f32,
            rect.top as f32,
            rect.right as f32,
            rect.bottom as f32,
            false,
        );
        let _ = viewport.updater.Update(None);
    }
    if let Ok(mut shared) = viewport.shared.try_borrow_mut() {
        shared.queue.clear();
    }
}

// ── JNI ──────────────────────────────────────────────────────────────────

fn injection_armed() -> bool {
    std::env::var_os("NUCLEUS_TAO_INPUT_INJECTION").is_some_and(|value| value == "1")
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeSetDirectManipulationEnabled(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    ENABLED.store(enabled != JNI_FALSE, Ordering::Relaxed);
}

/// Event-loop thread only: the registry is that thread's.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeDirectManipulationAttached(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if lookup(handle as u64).is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `[attached, hitTests, claimedContacts, status, deliveredEvents, resetting,
/// processHitTests]`. Event-loop thread only.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeDiagDirectManipulationStats(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let process_hit_tests = HIT_TESTS.load(Ordering::Relaxed) as jint;
    let stats: [jint; 7] = match lookup(handle as u64) {
        Some(viewport) => {
            let (status, resetting) = viewport
                .shared
                .try_borrow()
                .map(|shared| (shared.status.0, shared.resetting as jint))
                .unwrap_or((-1, -1));
            [
                1,
                viewport.hit_tests.get() as jint,
                viewport.claimed.get() as jint,
                status,
                viewport.delivered.get() as jint,
                resetting,
                process_hit_tests,
            ]
        }
        None => [0, 0, 0, -1, 0, 0, process_hit_tests],
    };
    let Ok(array) = env.new_int_array(stats.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, &stats).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Test hook — see [WHEEL_FOCUS]. `false` (and no effect) outside an
/// injection-armed process.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeDiagDirectManipulationWheelFocus(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) -> jboolean {
    if !injection_armed() {
        return JNI_FALSE;
    }
    WHEEL_FOCUS.store(enabled != JNI_FALSE, Ordering::Relaxed);
    JNI_TRUE
}

/// Test hook: queues viewport events as if the handler had reported them —
/// `[kind, status, previousStatus, scale, x, y, focalX, focalY]` per event —
/// and lets the loop deliver them, so the pump, the wire, the host and Compose
/// run on a script a touchpad would produce (a precision-touchpad contact
/// cannot be injected). `false` outside an injection-armed process or for a
/// window without a viewport. Event-loop thread only.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeDiagDirectManipulationReplay(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    events: JFloatArray,
) -> jboolean {
    const FIELDS: usize = 8;
    if !injection_armed() {
        return JNI_FALSE;
    }
    let Some(viewport) = lookup(handle as u64) else {
        return JNI_FALSE;
    };
    let Ok(length) = env.get_array_length(&events) else {
        return JNI_FALSE;
    };
    let mut values = vec![0f32; length as usize];
    if values.len() % FIELDS != 0 || env.get_float_array_region(&events, 0, &mut values).is_err() {
        return JNI_FALSE;
    }
    let Ok(mut shared) = viewport.shared.try_borrow_mut() else {
        return JNI_FALSE;
    };
    for chunk in values.chunks_exact(FIELDS) {
        shared.push(Event {
            kind: chunk[0] as jint,
            current: chunk[1] as jint,
            previous: chunk[2] as jint,
            transform: Transform {
                scale: chunk[3],
                x: chunk[4],
                y: chunk[5],
            },
            focal: (chunk[6], chunk[7]),
        });
    }
    JNI_TRUE
}
