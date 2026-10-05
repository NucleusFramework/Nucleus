// Logoff / restart / shutdown / Restart Manager close (#751).
//
// Windows asks every top-level window with `WM_QUERYENDSESSION` before it ends
// the session (or, with `ENDSESSION_CLOSEAPP`, before an installer replaces the
// app's files). Every tao window hands the message here (see the vendored
// patch) and gives the app's answer — one window answering TRUE on its own is
// enough for the Restart Manager to start ending the process. The answer is
// synchronous, so the app's windows get their cancelable close requests right
// here, on the event-loop thread inside the window procedure — the same
// `requestQuit` as a macOS Cmd+Q (#696). The block reason always goes on the
// thread target window, which outlives every app window:
//
// - AGREE: every window agreed at once (`exitApplication()` from its close
//   request, or no app window at all) — TRUE;
// - HOLD: the quit is still in flight (a window closing through recomposition)
//   — FALSE plus `ShutdownBlockReasonCreate`, so Windows lists the app on its
//   "This app is preventing shutdown" screen. If the quit completes the process
//   exits and Windows proceeds; if a window stays open the Kotlin side releases
//   the reason (`nativeReleaseShutdownBlock`);
// - REFUSE: a window already kept itself open — FALSE, no reason.
//
// `WM_ENDSESSION(TRUE)` is the app's last moment: Windows may terminate the
// process as soon as it returns, so the Kotlin side tears the app down (and
// exits) synchronously, before returning.
//
// Neither is delivered into Kotlin while the event handler is already on this
// thread's stack (a sent message reaching the window procedure inside a nested
// pump): the query is then asked again from the loop and the session held
// meanwhile, and a session end is left to Windows. AWT's toolkit window gets
// the same answers (`awt_session`).

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::Instant;

use jni::objects::{JObject, JValue};
use jni::sys::jint;
use jni::JNIEnv;
use windows::core::w;
use windows::core::BOOL;
use windows::Win32::Foundation::{HWND, LPARAM};
use windows::Win32::System::Shutdown::{ShutdownBlockReasonCreate, ShutdownBlockReasonDestroy};
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::WindowsAndMessaging::{EnumThreadWindows, IsWindow};

use crate::events::UserEvent;
use crate::state::{send_user_event, EVENT_CALLBACK, JAVA_VM};

// Mirrors `TaoApplication.END_SESSION_*`.
const AGREE: jint = 0;
const HOLD: jint = 1;

const THREAD_TARGET_CLASS: &str = "Tao Thread Event Target";

/// Tao's thread target window, found once the loop exists.
static THREAD_TARGET: AtomicIsize = AtomicIsize::new(0);

/// The window carrying the shutdown block reason, or 0. Event-loop thread only:
/// `ShutdownBlockReasonDestroy` must run on the thread that created the window.
static BLOCKED_HWND: AtomicIsize = AtomicIsize::new(0);

/// A deferred query is queued and the session it belongs to is still open.
static DEFERRED_QUERY: AtomicBool = AtomicBool::new(false);

/// Windows sends the query to each top-level window in turn — tao's, and AWT's
/// on its own thread — and the quit may resolve between two of them: an answer
/// is reused for the queries that follow within this, so one session end asks
/// the app once and every window gives the same answer. `WM_ENDSESSION` forgets it.
const ANSWER_REUSE_MS: u64 = 1_000;

/// The last answer (`1` may end, `0` not, `-1` none) and when it was given, in
/// ms since the first use. Read from the AWT thread too.
static LAST_ANSWER: AtomicIsize = AtomicIsize::new(-1);
static LAST_ANSWER_AT: AtomicU64 = AtomicU64::new(0);

fn now_ms() -> u64 {
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    EPOCH.get_or_init(Instant::now).elapsed().as_millis() as u64
}

/// The answer given within [ANSWER_REUSE_MS], if any; reusing it extends it.
pub(crate) fn recent_answer() -> Option<bool> {
    let answer = LAST_ANSWER.load(Ordering::Relaxed);
    let now = now_ms();
    if answer < 0 || now.saturating_sub(LAST_ANSWER_AT.load(Ordering::Relaxed)) >= ANSWER_REUSE_MS {
        return None;
    }
    LAST_ANSWER_AT.store(now, Ordering::Relaxed);
    Some(answer == 1)
}

fn remember(may_end: bool) -> bool {
    LAST_ANSWER_AT.store(now_ms(), Ordering::Relaxed);
    LAST_ANSWER.store(may_end as isize, Ordering::Relaxed);
    may_end
}

fn forget() {
    LAST_ANSWER.store(-1, Ordering::Relaxed);
}

/// Called on the event-loop thread once the loop exists.
pub(crate) fn install() {
    unsafe {
        let _ = EnumThreadWindows(GetCurrentThreadId(), Some(find_thread_target), LPARAM(0));
    }
    super::awt_session::install();
}

/// Tao's thread target window, while it exists.
pub(crate) fn thread_target() -> Option<HWND> {
    let hwnd = HWND(THREAD_TARGET.load(Ordering::Relaxed) as *mut c_void);
    (!hwnd.is_invalid() && unsafe { IsWindow(Some(hwnd)) }.as_bool()).then_some(hwnd)
}

unsafe extern "system" fn find_thread_target(hwnd: HWND, _: LPARAM) -> BOOL {
    if super::awt_session::class_is(hwnd, THREAD_TARGET_CLASS) {
        THREAD_TARGET.store(hwnd.0 as isize, Ordering::Relaxed);
        return BOOL(0);
    }
    BOOL(1)
}

/// `WM_QUERYENDSESSION`: `true` when the session may end now.
pub(crate) fn on_query_end_session(hwnd: isize, _lparam: isize, nested: bool) -> bool {
    if let Some(may_end) = recent_answer() {
        return may_end;
    }
    if !nested {
        if let Some(answer) = query_kotlin() {
            return remember(apply(answer, hwnd));
        }
    }
    DEFERRED_QUERY.store(true, Ordering::Relaxed);
    if !send_user_event(UserEvent::QueryEndSession) {
        DEFERRED_QUERY.store(false, Ordering::Relaxed);
        return true;
    }
    block(hwnd);
    remember(false)
}

/// [UserEvent::QueryEndSession]: the deferred query, from the loop. Nobody waits
/// for its answer any more — it only decides whether the hold stays. Dropped
/// when the session query it belongs to has ended meanwhile.
pub(crate) fn on_deferred_query_end_session() {
    if !DEFERRED_QUERY.swap(false, Ordering::Relaxed) {
        return;
    }
    match query_kotlin() {
        Some(HOLD) => {}
        _ => {
            release_block();
            forget();
        }
    }
}

/// `WM_ENDSESSION`: the query is over, whichever way it went. On `ending` the
/// Kotlin side tears the app down and normally does not return.
pub(crate) fn on_end_session(_hwnd: isize, ending: bool, nested: bool) {
    DEFERRED_QUERY.store(false, Ordering::Relaxed);
    forget();
    release_block();
    if !nested {
        let _ = call_kotlin(|env, cb| {
            env.call_method(cb, "onEndSession", "(Z)V", &[JValue::Bool(ending.into())])
                .map(|_| ())
        });
    }
}

fn apply(answer: jint, hwnd: isize) -> bool {
    match answer {
        AGREE => {
            release_block();
            true
        }
        HOLD => {
            block(hwnd);
            false
        }
        _ => {
            release_block();
            false
        }
    }
}

fn query_kotlin() -> Option<jint> {
    call_kotlin(|env, cb| env.call_method(cb, "onQueryEndSession", "()I", &[])?.i())
}

/// Runs [call] against the event callback; `None` when it cannot be called
/// from here or threw.
fn call_kotlin<R>(call: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<R>) -> Option<R> {
    let vm = JAVA_VM.get()?;
    // `try_lock`: held means a dispatch is further up this thread's stack. The
    // lock is not kept across the call: we are outside tao's handler, so the app
    // code may cause a tao event synchronously, whose dispatch takes it.
    let callback = EVENT_CALLBACK.try_lock().ok()?.as_ref()?.clone();
    let mut env = vm.attach_current_thread_permanently().ok()?;
    let result = call(&mut env, callback.as_obj());
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return None;
    }
    result.ok()
}

fn block(hwnd: isize) {
    if BLOCKED_HWND.load(Ordering::Relaxed) != 0 {
        return;
    }
    let ok = unsafe {
        ShutdownBlockReasonCreate(
            HWND(hwnd as *mut c_void),
            w!("Waiting for open windows to close"),
        )
    };
    if ok.is_ok() {
        BLOCKED_HWND.store(hwnd, Ordering::Relaxed);
    }
}

/// Drops the shutdown block reason, if one is set.
pub(crate) fn release_block() {
    let hwnd = BLOCKED_HWND.swap(0, Ordering::Relaxed);
    if hwnd != 0 {
        let _ = unsafe { ShutdownBlockReasonDestroy(HWND(hwnd as *mut c_void)) };
    }
}
