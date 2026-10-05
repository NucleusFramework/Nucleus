// The AWT toolkit window and session end (#751).
//
// Compose initializes AWT even under Tao (RectManager's `postDelayed` goes
// through skiko's Swing dispatcher), and AWT's hidden `SunAwtToolkit` window is
// a top-level window of its own thread, which Windows asks separately. AWT
// answers `WM_QUERYENDSESSION` with TRUE whatever the app says — and its handler
// starts the JVM's shutdown right there (hooks, exit), before any
// `WM_ENDSESSION`, while the app's windows are still asking theirs or have
// refused.
//
// So the toolkit window is subclassed as soon as AWT creates it (a WinEvent hook
// on window creation in this process, delivered to the event-loop thread), and
// AWT never sees a session message:
//
// - `WM_QUERYENDSESSION` gets the app's answer: the one just given to tao's
//   windows if there is one, else the thread target's, asked synchronously with
//   a timeout (an app that cannot answer must not be ended for it);
// - `WM_ENDSESSION` is forwarded to the thread target *asynchronously*: the
//   app's teardown runs on the event-loop thread and exits the process, and
//   AWT's `ToolkitShutdown` hook waits for this very thread — blocking it here
//   until the event loop answers would deadlock the exit against it.

use std::sync::atomic::{AtomicIsize, Ordering};

use windows::core::BOOL;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::UI::Accessibility::{SetWinEventHook, HWINEVENTHOOK};
use windows::Win32::UI::WindowsAndMessaging::{
    CallWindowProcW, EnumWindows, GetClassNameW, GetWindowLongPtrW, GetWindowThreadProcessId,
    SendMessageTimeoutW, SendNotifyMessageW, SetWindowLongPtrW, CHILDID_SELF, EVENT_OBJECT_CREATE,
    GWLP_WNDPROC, OBJID_WINDOW, SMTO_ABORTIFHUNG, WINEVENT_OUTOFCONTEXT, WINEVENT_SKIPOWNTHREAD,
    WM_ENDSESSION, WM_QUERYENDSESSION, WNDPROC,
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

/// The subclassed AWT toolkit window and its original window procedure.
static AWT_HWND: AtomicIsize = AtomicIsize::new(0);
static AWT_PROC: AtomicIsize = AtomicIsize::new(0);

/// Called on the event-loop thread once the loop exists: adopts an AWT toolkit
/// window already there and watches for one.
pub(crate) fn install() {
    unsafe {
        let _ = EnumWindows(Some(find_awt_toolkit), LPARAM(0));
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
}

/// Runs on the AWT toolkit thread.
unsafe extern "system" fn awt_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    let original: WNDPROC = std::mem::transmute(AWT_PROC.load(Ordering::Relaxed));
    let target = super::end_session::thread_target();
    let routed = target.is_some();
    let target = target.unwrap_or_default();
    match msg {
        WM_QUERYENDSESSION if routed => {
            if let Some(may_end) = super::end_session::recent_answer() {
                return LRESULT(may_end as isize);
            }
            let mut answer = 0usize;
            let sent = SendMessageTimeoutW(
                target,
                msg,
                wparam,
                lparam,
                SMTO_ABORTIFHUNG,
                QUERY_FORWARD_TIMEOUT_MS,
                Some(&mut answer),
            );
            LRESULT((sent.0 != 0 && answer != 0) as isize)
        }
        WM_ENDSESSION if routed => {
            let _ = SendNotifyMessageW(target, msg, wparam, lparam);
            LRESULT(0)
        }
        _ => CallWindowProcW(original, hwnd, msg, wparam, lparam),
    }
}
