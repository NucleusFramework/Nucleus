// The AWT toolkit window and session end (#751).
//
// Compose initializes AWT even under Tao (RectManager's `postDelayed` goes
// through skiko's Swing dispatcher), and AWT's hidden `SunAwtToolkit` window is
// a top-level window of its own thread, which Windows asks separately. AWT
// answers `WM_QUERYENDSESSION` with TRUE whatever the app says — and its handler
// starts the JVM's shutdown right there (hooks, exit), before any
// `WM_ENDSESSION`, while the app's windows are still asking theirs or have
// refused. Checked on JDK 17, 21, 25 (HotSpot and OpenJ9), 26 and 27-ea: same
// class name, same behaviour.
//
// So the toolkit window is subclassed as soon as AWT creates it (a WinEvent hook
// on window creation in this process, delivered to the event-loop thread):
//
// - `WM_QUERYENDSESSION` gets the app's answer, never AWT's: the one just given
//   to tao's windows if there is one, else the thread target's, asked
//   synchronously with a timeout (an app that cannot answer must not be ended
//   for it); TRUE once the app is leaving on its own;
// - `WM_ENDSESSION(FALSE)` is forwarded to the thread target asynchronously;
// - `WM_ENDSESSION(TRUE)` too — the app's teardown runs on the event-loop thread
//   and exits the process, and AWT's `ToolkitShutdown` hook waits for this very
//   thread, so blocking it here until the loop answers would deadlock the exit —
//   and then, once the JVM exit has begun, AWT gets the session after all: a
//   real `WM_ENDSESSION(TRUE)` this thread returned from left AWT's own
//   `Dispose` hanging, so AWT's end handler (which disposes inside the message
//   and never returns) has to run it.
//
// The WinEvent hook is out of context, i.e. asynchronous: a session query that
// reaches the toolkit window between its creation and its adoption (a few ms at
// startup) is still AWT's. The hook is removed once the window is adopted.

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::time::{Duration, Instant};

use windows::core::BOOL;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::UI::Accessibility::{SetWinEventHook, UnhookWinEvent, HWINEVENTHOOK};
use windows::Win32::UI::WindowsAndMessaging::{
    CallWindowProcW, EnumWindows, GetClassNameW, GetWindowLongPtrW, GetWindowThreadProcessId,
    PeekMessageW, PostQuitMessage, SendMessageTimeoutW, SendNotifyMessageW, SetWindowLongPtrW,
    CHILDID_SELF, EVENT_OBJECT_CREATE, GWLP_WNDPROC, MSG, OBJID_WINDOW, PM_NOREMOVE,
    SMTO_ABORTIFHUNG, WINEVENT_OUTOFCONTEXT, WINEVENT_SKIPOWNTHREAD, WM_ENDSESSION,
    WM_QUERYENDSESSION, WM_QUIT, WNDPROC,
};

const AWT_TOOLKIT_CLASS: &str = "SunAwtToolkit";

/// Upper bound on the wait for the event loop's answer to a query forwarded from
/// AWT: the app's close requests run inside it, and a slow one that agrees must
/// not be overridden by AWT refusing first. `SMTO_ABORTIFHUNG` skips the wait
/// when the loop is already hung (~5 s without pumping) as the query arrives.
/// Either way AWT then refuses — an app that cannot answer must not be ended
/// for it. Never unbounded: AWT's `ToolkitShutdown` hook waits for this thread
/// at exit.
const QUERY_FORWARD_TIMEOUT_MS: u32 = 30_000;

/// How long a real session end waits for the JVM exit to begin before AWT takes
/// its own path anyway (its SIGTERM then starts the exit). Windows gives a
/// `WM_ENDSESSION` handler about as long before it ends the process itself.
const JVM_EXIT_WAIT_MS: u64 = 5_000;

/// AWT's `WM_AWT_INVOKE_METHOD` (`WM_APP + 0x1821`): how its `ToolkitShutdown`
/// hook runs its quit request on this thread.
const WM_AWT_INVOKE_METHOD: u32 = 0x9821;

/// `AwtToolkit::EXIT_ALL_ENCLOSING_LOOPS`: a `WM_QUIT` carrying it ends every
/// nested AWT loop, then lets AWT's `Dispose` finish.
const EXIT_ALL_ENCLOSING_LOOPS: i32 = -1;

/// AWT's quit request went through after the JVM exit had begun.
static AWT_QUIT_SERVED: AtomicBool = AtomicBool::new(false);

/// The subclassed AWT toolkit window and its original window procedure.
static AWT_HWND: AtomicIsize = AtomicIsize::new(0);
static AWT_PROC: AtomicIsize = AtomicIsize::new(0);
/// The WinEvent hook watching for the toolkit window, until it is adopted.
static CREATE_HOOK: AtomicIsize = AtomicIsize::new(0);

/// Called on the event-loop thread once the loop exists: adopts an AWT toolkit
/// window already there and watches for one.
pub(crate) fn install() {
    unsafe {
        let _ = EnumWindows(Some(find_awt_toolkit), LPARAM(0));
        if AWT_HWND.load(Ordering::Relaxed) != 0 {
            return;
        }
        // Out of context: the callback runs on this thread, from its message loop.
        let hook = SetWinEventHook(
            EVENT_OBJECT_CREATE,
            EVENT_OBJECT_CREATE,
            None,
            Some(on_object_created),
            GetCurrentProcessId(),
            0,
            WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNTHREAD,
        );
        if hook.is_invalid() {
            eprintln!("[nucleus-tao] SetWinEventHook failed: AWT session-end routing unavailable");
        } else {
            CREATE_HOOK.store(hook.0 as isize, Ordering::Relaxed);
        }
    }
}

pub(crate) fn class_is(hwnd: HWND, class: &str) -> bool {
    let mut buf = [0u16; 64];
    let len = unsafe { GetClassNameW(hwnd, &mut buf) };
    len > 0 && String::from_utf16_lossy(&buf[..len as usize]) == class
}

unsafe extern "system" fn find_awt_toolkit(hwnd: HWND, _: LPARAM) -> BOOL {
    let mut pid = 0u32;
    GetWindowThreadProcessId(hwnd, Some(&mut pid));
    if pid == GetCurrentProcessId() && class_is(hwnd, AWT_TOOLKIT_CLASS) {
        adopt(hwnd);
    }
    BOOL(1)
}

unsafe extern "system" fn on_object_created(
    _: HWINEVENTHOOK,
    _: u32,
    hwnd: HWND,
    id_object: i32,
    id_child: i32,
    _: u32,
    _: u32,
) {
    if id_object == OBJID_WINDOW.0 && id_child == CHILDID_SELF as i32 && !hwnd.is_invalid() {
        if class_is(hwnd, AWT_TOOLKIT_CLASS) {
            adopt(hwnd);
        }
    }
}

/// Subclasses [hwnd] (another thread's window of this process — allowed for
/// `GWLP_WNDPROC`). The original procedure is stored before the swap, so a
/// message arriving in between still finds it.
unsafe fn adopt(hwnd: HWND) {
    if AWT_HWND.load(Ordering::Relaxed) == hwnd.0 as isize {
        return;
    }
    let original = GetWindowLongPtrW(hwnd, GWLP_WNDPROC);
    if original == 0 || original == awt_proc as usize as isize {
        return;
    }
    AWT_PROC.store(original, Ordering::Relaxed);
    AWT_HWND.store(hwnd.0 as isize, Ordering::Relaxed);
    SetWindowLongPtrW(hwnd, GWLP_WNDPROC, awt_proc as usize as isize);
    let hook = CREATE_HOOK.swap(0, Ordering::Relaxed);
    if hook != 0 {
        let _ = UnhookWinEvent(HWINEVENTHOOK(hook as *mut c_void));
    }
}

/// Runs on the AWT toolkit thread.
unsafe extern "system" fn awt_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    let original: WNDPROC = std::mem::transmute(AWT_PROC.load(Ordering::Relaxed));
    match msg {
        WM_QUERYENDSESSION => LRESULT(query_end_session(wparam, lparam) as isize),
        WM_ENDSESSION if wparam.0 != 0 => session_ending(original, hwnd, wparam, lparam),
        WM_ENDSESSION => {
            // Called off: the app hears it, unless it is leaving anyway.
            if !super::end_session::exiting() {
                if let Some(target) = super::end_session::thread_target() {
                    let _ = SendNotifyMessageW(target, msg, wparam, lparam);
                }
            }
            LRESULT(0)
        }
        _ => {
            if msg == WM_AWT_INVOKE_METHOD && super::end_session::jvm_exiting() {
                AWT_QUIT_SERVED.store(true, Ordering::Relaxed);
            }
            CallWindowProcW(original, hwnd, msg, wparam, lparam)
        }
    }
}

/// `WM_QUERYENDSESSION`: the app's answer, never AWT's.
unsafe fn query_end_session(wparam: WPARAM, lparam: LPARAM) -> bool {
    // The app is leaving on its own (its loop has ended, the thread target may be
    // gone): it agrees, and AWT is not asked — its handler would start a JVM exit.
    let target = match super::end_session::thread_target() {
        Some(target) if !super::end_session::exiting() => target,
        _ => return true,
    };
    if let Some(may_end) = super::end_session::recent_answer() {
        return may_end;
    }
    if !super::end_session::begin_forward() {
        return true;
    }
    let mut answer = 0usize;
    let sent = SendMessageTimeoutW(
        target,
        WM_QUERYENDSESSION,
        wparam,
        lparam,
        SMTO_ABORTIFHUNG,
        QUERY_FORWARD_TIMEOUT_MS,
        Some(&mut answer),
    );
    super::end_session::end_forward();
    if sent.0 == 0 {
        // The loop answers later, to nobody: tell it Windows has a FALSE.
        super::end_session::refused_unasked();
    }
    sent.0 != 0 && answer != 0
}

/// `WM_ENDSESSION(TRUE)`: the session really ends. The app hears it (its teardown
/// exits the JVM), then AWT takes its own path: its query handler marks the JVM
/// signalled and its end handler disposes the toolkit inside the message and never
/// returns. Returning from a real `WM_ENDSESSION(TRUE)` instead left AWT's
/// `Dispose` waiting for a `WM_QUIT` that never came, and its `ToolkitShutdown`
/// hook — so the exit — with it (E2E: a Restart Manager close reaching this window
/// as the app quit on its own). AWT goes only once the JVM exit has begun, so the
/// SIGTERM its query handler raises joins that exit instead of starting one of
/// its own (code 143) ahead of the app's teardown.
unsafe fn session_ending(original: WNDPROC, hwnd: HWND, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if !super::end_session::exiting() {
        if let Some(target) = super::end_session::thread_target() {
            let _ = SendNotifyMessageW(target, WM_ENDSESSION, wparam, lparam);
        }
    }
    wait_for_jvm_exit();
    // AWT's own loop has already ended (its `WM_QUIT` is pending): this is its
    // `Dispose` pumping. Its end handler would dispose again — a no-op on an
    // inactive toolkit — and then never return, so `Dispose` would never finish.
    let mut msg = MSG::default();
    if PeekMessageW(&mut msg, None, WM_QUIT, WM_QUIT, PM_NOREMOVE).as_bool() {
        return LRESULT(0);
    }
    let _ = CallWindowProcW(original, hwnd, WM_QUERYENDSESSION, WPARAM(0), lparam);
    // The end handler runs AWT's loop until `ToolkitShutdown`'s quit request, then
    // disposes. A request already served (the same pump dispatched it just before
    // this message) set the loop's break flag: the loop then quits at once without
    // posting the `WM_QUIT` that `Dispose` waits for, so post it here.
    if AWT_QUIT_SERVED.load(Ordering::Relaxed) {
        PostQuitMessage(EXIT_ALL_ENCLOSING_LOOPS);
    }
    CallWindowProcW(original, hwnd, WM_ENDSESSION, wparam, lparam)
}

/// Waits (bounded) for the JVM exit to begin — without pumping: AWT's
/// `ToolkitShutdown` hook sends this thread its quit request, which must reach
/// AWT's end handler loop. Served here, it set the loop's break flag before the
/// loop started, and the loop then quit without the `WM_QUIT` that AWT's
/// `Dispose` waits for.
fn wait_for_jvm_exit() {
    let deadline = Instant::now() + Duration::from_millis(JVM_EXIT_WAIT_MS);
    while !super::end_session::jvm_exiting() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(1));
    }
}
