// macOS main-thread bouncing (JWM-style).
//
// JVM launchers (with or without `-XstartOnFirstThread`) and GraalVM
// native-image binaries can land us on a worker thread on macOS. Tao's
// `EventLoop::new` panics unless invoked on the OS main thread, and
// AppKit-driven animations (e.g. fullscreen) only complete when their
// notifications are dispatched via the main thread's run loop.
//
// `dispatch_sync(main_queue)` requires the main thread to already be inside
// an active run loop in default mode — otherwise the queued block never
// runs and we deadlock. `performSelectorOnMainThread:waitUntilDone:YES`,
// in contrast, uses a CFRunLoop source that wakes the main thread the next
// time it enters any run loop. Combined with the fact that AWT
// (transitively pulled by Compose Desktop) starts `[NSApp run]` on main
// during early init, this gives us a reliable rendezvous point.
//
// Implemented in C in `macos/main_thread_dispatch.m`, compiled by build.rs.

use std::ffi::c_void;

use crate::event_loop::run_event_loop_blocking;
use crate::platform::macos::ffi::{nucleus_tao_is_main_thread, nucleus_tao_run_on_main_blocking};
use crate::state::EVENT_LOOP_PROXY;

pub(crate) fn is_main_thread() -> bool {
    unsafe { nucleus_tao_is_main_thread() != 0 }
}

extern "C" fn run_event_loop_trampoline(_ctx: *mut c_void) {
    run_event_loop_blocking();
}

pub(crate) fn dispatch_run_event_loop_on_main() {
    unsafe {
        nucleus_tao_run_on_main_blocking(run_event_loop_trampoline, std::ptr::null_mut());
    }
}

/// Called from `main_thread_dispatch.m` when the user hits Cmd-Q.
/// Posts a `UserEvent::Exit` on the running Tao event-loop proxy.
#[no_mangle]
pub extern "C" fn nucleus_tao_post_exit() {
    if let Some(proxy) = EVENT_LOOP_PROXY.get() {
        let _ = proxy.send_event(crate::events::UserEvent::Exit);
    }
}
