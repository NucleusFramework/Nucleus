// Tao event loop: builds the loop, owns the platform-main-thread dance on
// macOS, and routes Tao events back to Kotlin via `events::dispatch*`.

use std::collections::HashMap;

use jni::sys::jint;

use tao::dpi::LogicalSize;
use tao::event::{ElementState, Event, MouseScrollDelta, StartCause, TouchPhase, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;

use crate::events::{
    current_modifier_bits, dispatch, dispatch_ime_commit, dispatch_ime_preedit,
    dispatch_ime_replace_commit, dispatch_key, dispatch_scroll_gesture, dispatch_touch_input,
    handle_for, mouse_button_code, pack_modifiers, UserEvent, CURSOR_FIXED_SCALE,
    EVENT_CLOSE_REQUESTED, EVENT_CURSOR_LEFT, EVENT_CURSOR_MOVED, EVENT_DESTROYED, EVENT_FOCUSED,
    EVENT_KEY_DOWN, EVENT_KEY_TYPED, EVENT_KEY_UP, EVENT_LAUNCHED, EVENT_MAIN_EVENTS_CLEARED,
    EVENT_MODIFIERS_CHANGED, EVENT_MOUSE_DOWN, EVENT_MOUSE_UP, EVENT_MOVED, EVENT_REDRAW_REQUESTED,
    EVENT_RESIZED, EVENT_SCALE_FACTOR_CHANGED, EVENT_SCROLL_LINE, EVENT_SCROLL_PIXEL,
    EVENT_UNFOCUSED, EVENT_WINDOW_READY, SCROLL_FIXED_SCALE, SCROLL_GESTURE_BEGAN,
    SCROLL_GESTURE_CANCELLED, SCROLL_GESTURE_CHANGED, SCROLL_GESTURE_ENDED,
    SCROLL_GESTURE_MAY_BEGIN, SCROLL_GESTURE_MOMENTUM_BEGAN, SCROLL_GESTURE_MOMENTUM_CHANGED,
    SCROLL_GESTURE_MOMENTUM_ENDED, TOUCH_EVENT_CANCEL, TOUCH_EVENT_MOVE, TOUCH_EVENT_PRESS,
    TOUCH_EVENT_RELEASE, TOUCH_FORCE_FIXED_SCALE, TOUCH_FORCE_UNKNOWN,
};
#[cfg(target_os = "windows")]
use crate::events::{
    dispatch_trackpad_gesture, EVENT_SIZE_MOVE, TRACKPAD_GESTURE_MAGNIFY, TRACKPAD_PHASE_CHANGED,
    TRACKPAD_VALUE_FIXED_SCALE,
};
use crate::keymap;
use crate::state::{set_event_loop_proxy, CURRENT_MODIFIERS, WINDOWS};

// Tao has no dedicated "minimized" event, and our vendored WM_SIZE patch
// swallows the SIZE_MINIMIZED `Resized(0,0)` (it would collapse Compose's
// scene). Instead the patch calls `MINIMIZED_HOOK` on the SIZE_MINIMIZED /
// SIZE_RESTORED transition — a deterministic, event-driven signal (no polling).
//
// CRITICAL: the hook runs INSIDE a native callback that may be re-entered
// *synchronously* by our own calls — on Windows the WM_SIZE WndProc fired by
// set_inner_size / set_minimized / set_maximized; on macOS the AppKit window
// delegate fired by miniaturize / deminiaturize; on Linux the GTK
// `window-state-event` signal fired by set_minimized / set_maximized. Those
// calls originate from UserEvent handlers that already hold the `WINDOWS` lock
// and may have `dispatch`/EVENT_CALLBACK on the stack. So the hook must do
// NOTHING re-entrant here: it only posts a `UserEvent` back to the loop. The
// resolve-handle + dedup + JVM dispatch all happen later, in the closure, at a
// safe point where no native lock is held.
#[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
fn on_tao_minimized(window_id: tao::window::WindowId, minimized: bool) {
    crate::state::send_user_event(crate::events::UserEvent::MinimizedChanged {
        window_id,
        minimized,
    });
}

// Ctrl-flagged WM_MOUSEWHEEL (precision-touchpad pinch or real Ctrl+wheel),
// forwarded by the vendored Tao MAGNIFY_HOOK patch. Each call is one discrete
// wheel tick; the JVM host accumulates the stream into a continuous two-finger
// pinch (debounced end), so we only emit a CHANGED magnify delta. Unlike the
// minimize hook this is never re-entered by our own calls (we never synthesize
// a wheel), so we dispatch straight to the JVM here — same as the macOS NSEvent
// trackpad callback. x/y are 0: the host centres the synthesised gesture on the
// last cursor position (the zoom focal point), exactly like a real pinch.
#[cfg(target_os = "windows")]
fn on_tao_magnify(window_id: tao::window::WindowId, value: f32) {
    let Some(handle) = handle_for(window_id) else {
        return;
    };
    dispatch_trackpad_gesture(
        handle,
        TRACKPAD_GESTURE_MAGNIFY,
        TRACKPAD_PHASE_CHANGED,
        0,
        0,
        (value as f64 * TRACKPAD_VALUE_FIXED_SCALE) as jint,
    );
}

// Modal resize/move loop begin/end, forwarded by the vendored Tao
// SIZE_MOVE_HOOK patch (WM_ENTERSIZEMOVE / WM_EXITSIZEMOVE). The embedder drops
// VSync while `active` so the synchronous per-WM_SIZE present doesn't block on
// the display refresh during a border drag. Like the magnify hook this is never
// re-entered by our own calls (we never programmatically enter a size/move
// loop) and holds no Tao lock at call time, so we dispatch straight to the JVM.
#[cfg(target_os = "windows")]
fn on_tao_size_move(window_id: tao::window::WindowId, active: bool) {
    let Some(handle) = handle_for(window_id) else {
        return;
    };
    dispatch(handle, EVENT_SIZE_MOVE, if active { 1 } else { 0 }, 0);
}

/// Moves a freshly built window onto an X11 screen while the rest of the
/// process keeps talking native Wayland.
///
/// GTK supports several `GdkDisplay`s in one process and one main loop, so we
/// open the X server named by `DISPLAY` (XWayland on a Wayland session) once
/// and re-home the window's `GdkWindow` there. That buys back everything
/// xdg-shell has no protocol for — stacking (`alwaysOnTop`), programmatic
/// positioning and workspace stickiness — for overlays that need it, without
/// forcing the whole app onto XWayland.
///
/// `gtk_window_set_screen` unrealizes the widget, so we realize it again to
/// restore the invariant tao patch 0003 establishes: the `GdkWindow` is valid
/// when window creation returns, before `WINDOW_READY` reaches the JVM and the
/// renderer attaches to it.
#[cfg(target_os = "linux")]
fn move_window_to_x11(window: &tao::window::Window) {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    if !gtk_window.display().backend().is_wayland() {
        return; // already an X11 / XWayland client — nothing to do.
    }
    // No X server to fall back on (DISPLAY unset, no XWayland): keep the
    // Wayland surface. The Kotlin side notices — the window still reports a
    // Wayland surface kind — and logs it there, where the framework's JUL
    // facade lives.
    let Some(x11) = x11_display() else {
        return;
    };
    gtk_window.set_screen(&x11.default_screen());
    gtk_window.realize();
}

/// The X11 `GdkDisplay`, opened on first use and kept for the process. Lives in
/// a thread-local because `GdkDisplay` is neither `Send` nor `Sync` and every
/// caller runs on the event-loop thread.
#[cfg(target_os = "linux")]
fn x11_display() -> Option<gtk::gdk::Display> {
    use std::cell::RefCell;
    thread_local! {
        static X11_DISPLAY: RefCell<Option<Option<gtk::gdk::Display>>> = const { RefCell::new(None) };
    }
    X11_DISPLAY.with(|cell| {
        cell.borrow_mut()
            .get_or_insert_with(|| {
                let name = std::env::var("DISPLAY").ok()?;
                // GDK tries its backends in order for the given name; the
                // Wayland backend cannot parse an X11 display name, so this
                // resolves to the X11 backend even on a Wayland session.
                gtk::gdk::Display::open(&name)
            })
            .clone()
    })
}

pub(crate) fn run_event_loop_blocking() {
    // GTK backend selection. Default: let GDK auto-pick (= native Wayland on
    // a Wayland session, X11 elsewhere). The Wayland-native path goes through
    // a wl_subsurface child of GTK's wl_surface — see `nativeAttachWayland`
    // in nucleus_tao_egl.c.
    //
    // Escape hatch for apps that need X11-specific features Wayland doesn't
    // expose (always-on-top, programmatic window positioning, global pointer
    // queries, …): set `NUCLEUS_TAO_LINUX_RENDERER=x11` to force XWayland.
    // Setting `GDK_BACKEND` directly is also honored — we don't override an
    // explicit user choice.
    #[cfg(target_os = "linux")]
    {
        let force_x11 = std::env::var_os("NUCLEUS_TAO_LINUX_RENDERER")
            .map(|v| v.to_string_lossy().eq_ignore_ascii_case("x11"))
            .unwrap_or(false);
        if force_x11 && std::env::var_os("GDK_BACKEND").is_none() {
            std::env::set_var("GDK_BACKEND", "x11");
        }
    }

    let mut builder = EventLoopBuilder::<UserEvent>::with_user_event();
    // GTK enforces that gtk_main_init be called from the OS process main
    // thread (= tid == pid). On a regular JVM the Java "main" thread is *not*
    // process thread 0 — javaw / java spawn a worker for it — so Tao's stock
    // assertion would panic at startup. `with_any_thread(true)` opts into the
    // documented escape hatch (`EventLoopBuilderExtUnix`), letting us drive
    // the GTK loop from whichever thread the JVM hands us. The caveat noted
    // in the Tao docs (windows die with the thread) doesn't bite us: the
    // event-loop thread is the process's main Java thread, which lives until
    // the JVM exits.
    #[cfg(target_os = "linux")]
    {
        use tao::platform::unix::EventLoopBuilderExtUnix;
        builder.with_any_thread(true);
    }
    let mut event_loop = builder.build();
    set_event_loop_proxy(event_loop.create_proxy());

    // Event-driven minimize/restore detection (see on_tao_minimized).
    #[cfg(target_os = "windows")]
    tao::platform::windows::set_minimized_hook(on_tao_minimized);
    // Trackpad pinch / Ctrl+wheel → magnify gesture (see on_tao_magnify).
    #[cfg(target_os = "windows")]
    tao::platform::windows::set_magnify_hook(on_tao_magnify);
    // Modal resize/move loop → VSync toggle (see on_tao_size_move).
    #[cfg(target_os = "windows")]
    tao::platform::windows::set_size_move_hook(on_tao_size_move);
    #[cfg(target_os = "macos")]
    tao::platform::macos::set_minimized_hook(on_tao_minimized);
    #[cfg(target_os = "linux")]
    tao::platform::linux::set_minimized_hook(on_tao_minimized);

    // Install the Cmd-Q interceptor once we're on the main thread (NSEvent
    // local monitors must be added there). The drag-event latch lives
    // alongside it. `ApplePressAndHoldEnabled` is deliberately not touched:
    // like Chromium, Nucleus lets the OS/user default decide whether a held
    // letter repeats or opens the accent picker (#612).
    #[cfg(target_os = "macos")]
    unsafe {
        crate::platform::macos::ffi::nucleus_tao_install_cmd_q_handler();
        crate::platform::macos::ffi::nucleus_tao_install_drag_monitor();
        crate::platform::macos::ffi::nucleus_tao_register_trackpad_gesture_callback(
            crate::platform::macos::trackpad_gesture_callback,
        );
        crate::platform::macos::ffi::nucleus_tao_install_trackpad_gesture_monitor();
    }

    // Use run_return() so this function returns to the JNI layer after the
    // event loop exits. run() calls process::exit() directly, which bypasses
    // JVM shutdown hooks (e.g. JDK 25 AOT cache writer, user shutdown hooks).
    use tao::platform::run_return::EventLoopExtRunReturn;
    // Needed for the Wayland-only minimize hack below (`target.is_wayland()`).
    #[cfg(target_os = "linux")]
    use tao::platform::unix::EventLoopWindowTargetExtUnix;
    // Last reported minimized state per window handle — dedups the hook. On
    // Windows it fires on every non-minimized WM_SIZE (plain resizes included);
    // on macOS the delegate and on Linux the gated GTK signal fire only on real
    // transitions, but the dedup keeps every platform on one code path and
    // guards against duplicate callbacks.
    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
    let mut last_minimized: HashMap<u64, bool> = HashMap::new();
    event_loop.run_return(move |event, target, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::NewEvents(StartCause::Init) => {
                dispatch(0, EVENT_LAUNCHED, 0, 0);
            }
            Event::UserEvent(user) => match user {
                UserEvent::Wake => {
                    // No-op: the side-effect we want is the loop returning from
                    // its `Wait` to dispatch this event, which guarantees a
                    // following `MainEventsCleared` tick that drains
                    // `TaoMainDispatcher`.
                }
                UserEvent::CreateWindow {
                    handle,
                    title,
                    width,
                    height,
                    decorations,
                    resizable,
                    visible,
                    maximized,
                    popup_of,
                    skip_taskbar,
                    transparent,
                    undecorated_shadow,
                    force_x11,
                } => {
                    #[allow(unused_mut)]
                    let mut builder = WindowBuilder::new()
                        .with_title(&title)
                        .with_inner_size(LogicalSize::new(width, height))
                        .with_decorations(decorations)
                        .with_resizable(resizable)
                        .with_visible(visible)
                        .with_maximized(maximized);
                    // Windows: taskbar/Alt+Tab exclusion must be a builder
                    // attribute — tao re-derives GWL_EXSTYLE from its
                    // WindowFlags on every state change, so a post-creation
                    // style poke is clobbered on the next activation.
                    // Also wire undecorated DWM drop-shadow (tao default true;
                    // borderless overlays pass false so no soft contour).
                    #[cfg(target_os = "windows")]
                    {
                        use tao::platform::windows::WindowBuilderExtWindows;
                        builder = builder
                            .with_skip_taskbar(skip_taskbar)
                            .with_undecorated_shadow(undecorated_shadow);
                    }
                    // macOS: NSWindow.hasShadow — same intent as Windows
                    // undecorated_shadow. Borderless transparent overlays
                    // pass false so AppKit does not draw a soft contour.
                    #[cfg(target_os = "macos")]
                    {
                        use tao::platform::macos::WindowBuilderExtMacOS;
                        builder = builder.with_has_shadow(undecorated_shadow);
                        let _ = skip_taskbar;
                    }
                    // Linux: GTK skip-taskbar + skip-pager hints
                    // (_NET_WM_STATE_SKIP_TASKBAR). Effective on X11 and
                    // XWayland; silently ignored on native Wayland, which has
                    // no client-side taskbar opt-out protocol.
                    // `undecorated_shadow` maps to the yaru.dart-style
                    // hidden-titlebar CSD: the toplevel stays decorated with a
                    // hidden GtkHeaderBar installed via set_titlebar(), so GTK
                    // draws the native theme drop shadow / rounded corners /
                    // resize border around the embedder's own chrome. Wayland
                    // only (ignored by tao on X11).
                    #[cfg(target_os = "linux")]
                    {
                        use tao::platform::unix::WindowBuilderExtUnix;
                        builder = builder
                            .with_skip_taskbar(skip_taskbar)
                            .with_csd_hidden_titlebar(undecorated_shadow);
                    }
                    // Linux: build cursor-following overlays as GTK_WINDOW_POPUP
                    // transient children — on Wayland GDK maps them as
                    // `wl_subsurface`s, the only client-positionable window
                    // kind under xdg-shell. Silently ignored if the parent
                    // handle is unknown (falls back to a regular toplevel).
                    #[cfg(target_os = "linux")]
                    if popup_of != 0 {
                        use tao::platform::unix::{WindowBuilderExtUnix, WindowExtUnix};
                        let parent_gtk = {
                            let guard = WINDOWS.lock().unwrap();
                            guard
                                .as_ref()
                                .and_then(|map| map.get(&popup_of).map(|w| w.gtk_window().clone()))
                        };
                        if let Some(parent_gtk) = parent_gtk {
                            builder = builder.with_popup_transient_for(&parent_gtk);
                        }
                    }
                    #[cfg(not(target_os = "linux"))]
                    let _ = popup_of;
                    // Full-window transparency (#416): tao sets NSWindow.opaque=NO
                    // (macOS), DWM blur-behind empty region (Windows), ARGB visual
                    // (Linux). Linux always needs with_transparent for the EGL
                    // canonical visual even when the app did not ask for a
                    // see-through window — without it Mesa fails eglCreateWindowSurface.
                    // The app-level flag still drives the Kotlin clear path via
                    // host `fullyTransparent`; builder just needs the ARGB path.
                    if transparent || cfg!(target_os = "linux") {
                        builder = builder.with_transparent(true);
                    }
                    let window = builder.build(target);
                    if let Ok(window) = window {
                        #[cfg(target_os = "linux")]
                        if force_x11 {
                            move_window_to_x11(&window);
                        }
                        #[cfg(not(target_os = "linux"))]
                        let _ = force_x11;
                        let logical_w = width as jint;
                        let logical_h = height as jint;

                        {
                            let mut guard = WINDOWS.lock().unwrap();
                            if let Some(map) = guard.as_mut() {
                                map.insert(handle, window);
                            }
                        }
                        dispatch(handle, EVENT_WINDOW_READY, logical_w, logical_h);
                    }
                }
                UserEvent::SetVisible { handle, visible } => {
                    // Linux: let the JVM suspend its EGL rendering BEFORE GTK
                    // unmaps the window. On Wayland `gtk_widget_hide` destroys
                    // the parent `wl_surface`; a swap racing that destruction
                    // trips a fatal protocol error (GDK "Error 71"). Dispatched
                    // outside the WINDOWS lock — the JVM handler re-enters
                    // native code that takes the same lock.
                    #[cfg(target_os = "linux")]
                    if !visible {
                        dispatch(handle, crate::events::EVENT_WILL_HIDE, 0, 0);
                    }
                    {
                        let guard = WINDOWS.lock().unwrap();
                        if let Some(map) = guard.as_ref() {
                            if let Some(w) = map.get(&handle) {
                                w.set_visible(visible);
                                if visible {
                                    // Linux: `set_visible` only queues the GTK
                                    // show through tao's request channel. Show
                                    // synchronously so the GDK surface already
                                    // exists when EVENT_SHOWN (below) lets the
                                    // JVM re-attach EGL. Idempotent with the
                                    // queued request.
                                    #[cfg(target_os = "linux")]
                                    {
                                        use gtk::prelude::WidgetExt;
                                        use tao::platform::unix::WindowExtUnix;
                                        w.gtk_window().show_all();
                                    }
                                    // Force a fresh frame into the now-composited surface.
                                    // The first frame is rendered (SwapBuffers) while the
                                    // HWND is still hidden, but WGL/DWM does not reliably
                                    // retain that pre-show swap once ShowWindow composites
                                    // the window — it can reveal an undefined/black front
                                    // buffer until the next redraw. Re-presenting here
                                    // guarantees the content paints the instant the window
                                    // appears.
                                    w.request_redraw();
                                }
                            }
                        }
                    }
                    #[cfg(target_os = "linux")]
                    if visible {
                        dispatch(handle, crate::events::EVENT_SHOWN, 0, 0);
                    }
                }
                UserEvent::SetTitle { handle, title } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_title(&title);
                        }
                    }
                }
                UserEvent::RequestRedraw { handle } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.request_redraw();
                        }
                    }
                }
                UserEvent::RequestClose { handle } => {
                    // Check the window still exists WITHOUT removing it yet.
                    let present = {
                        let guard = WINDOWS.lock().unwrap();
                        guard
                            .as_ref()
                            .map(|map| map.contains_key(&handle))
                            .unwrap_or(false)
                    };
                    if present {
                        // Tear down the JVM-side GL/Skia resources FIRST, while
                        // the native window — and the WGL HDC captured at attach
                        // time — is still alive. `dispatch` invokes the Kotlin
                        // callback synchronously on this thread, so `host.detach()`
                        // (which makes the window's own WGL context current and
                        // closes its Skia DirectContext) completes before we drop
                        // the Window below.
                        //
                        // Dropping the Window first would call DestroyWindow,
                        // invalidating that HDC: `wglMakeCurrent` in detach() would
                        // then fail and Skia would free its GPU objects against
                        // whatever context happens to be current — a sibling
                        // window's (e.g. the main window opened during the
                        // onboarding -> app handoff) — faulting inside the GL
                        // driver (0xC0000005). Single-window closes never hit this
                        // because no sibling context exists, which is why a relaunch
                        // (onboarding already done) doesn't crash.
                        dispatch(handle, EVENT_DESTROYED, 0, 0);
                        let mut guard = WINDOWS.lock().unwrap();
                        if let Some(map) = guard.as_mut() {
                            map.remove(&handle);
                        }
                    }
                }
                UserEvent::SetMaximized { handle, maximized } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_maximized(maximized);
                        }
                    }
                }
                UserEvent::SetResizable { handle, resizable } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_resizable(resizable);
                        }
                    }
                }
                UserEvent::SetMinimized { handle, minimized } => {
                    {
                        let guard = WINDOWS.lock().unwrap();
                        if let Some(map) = guard.as_ref() {
                            if let Some(w) = map.get(&handle) {
                                w.set_minimized(minimized);
                            }
                        }
                    }
                    // WAYLAND-ONLY HACK. Wayland's xdg-shell never reports the
                    // minimized state back (no such event exists — see
                    // platform/linux.rs), so the GTK window-state-event hook
                    // never fires and EVENT_MINIMIZED would never reach the JVM.
                    // But *we* own the title-bar minimize button and this
                    // app-driven path, so synthesize the notification ourselves
                    // right after iconify/deiconify. On X11 the real
                    // window-state-event already covers this, so gate on
                    // is_wayland() (false under a forced XWayland renderer too)
                    // to avoid a double dispatch. External minimize is still
                    // unobservable on Wayland; external restore is best-effort
                    // recovered from the focus-gained event below.
                    #[cfg(target_os = "linux")]
                    if target.is_wayland()
                        && last_minimized.get(&handle).copied() != Some(minimized)
                    {
                        last_minimized.insert(handle, minimized);
                        dispatch(handle, crate::events::EVENT_MINIMIZED, minimized as jint, 0);
                    }
                }
                // Posted from the platform minimize hook (safe point — no native
                // lock held and not nested in the WndProc / AppKit delegate / GTK
                // signal). Resolve, dedup, dispatch.
                #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
                UserEvent::MinimizedChanged {
                    window_id,
                    minimized,
                } => {
                    if let Some(handle) = handle_for(window_id) {
                        if last_minimized.get(&handle).copied() != Some(minimized) {
                            last_minimized.insert(handle, minimized);
                            dispatch(handle, crate::events::EVENT_MINIMIZED, minimized as jint, 0);
                        }
                    }
                }
                UserEvent::SetAlwaysOnTop {
                    handle,
                    always_on_top,
                } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_always_on_top(always_on_top);
                        }
                    }
                }
                UserEvent::SetAlwaysOnBottom {
                    handle,
                    always_on_bottom,
                } => {
                    // Opposite stacking: HWND_BOTTOM on Windows,
                    // NSWindowLevel::BelowNormal on macOS, _NET_WM_STATE_BELOW
                    // (gtk_window_set_keep_below) on X11 — a silent no-op on
                    // native Wayland, which has no client-side stacking
                    // protocol. Mutual exclusion with always-on-top is enforced
                    // by TaoWindow: tao's setters, unlike its WindowBuilder, let
                    // both requests coexist.
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_always_on_bottom(always_on_bottom);
                        }
                    }
                }
                UserEvent::SetFocusable { handle, focusable } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_focusable(focusable);
                        }
                    }
                }
                UserEvent::SetIgnoreCursorEvents { handle, ignore } => {
                    // Click-through: WS_EX_TRANSPARENT|WS_EX_LAYERED on
                    // Windows, NSWindow.ignoresMouseEvents on macOS, an empty
                    // GDK input region on Linux.
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            let _ = w.set_ignore_cursor_events(ignore);
                            // tao only flips the ex-styles. A WS_EX_LAYERED
                            // window renders NOTHING until its layering
                            // attributes are initialised — without this the
                            // whole window disappears the moment click-through
                            // is enabled. Full alpha keeps per-pixel
                            // transparency driven by DWM blur-behind.
                            #[cfg(target_os = "windows")]
                            if ignore {
                                use tao::platform::windows::WindowExtWindows;
                                use windows::Win32::Foundation::{COLORREF, HWND};
                                use windows::Win32::UI::WindowsAndMessaging::{
                                    SetLayeredWindowAttributes, LWA_ALPHA,
                                };
                                let hwnd = HWND(w.hwnd() as *mut _);
                                unsafe {
                                    let _ = SetLayeredWindowAttributes(
                                        hwnd,
                                        COLORREF(0),
                                        255,
                                        LWA_ALPHA,
                                    );
                                }
                            }
                        }
                    }
                }
                UserEvent::SetVisibleOnAllWorkspaces { handle, visible } => {
                    // macOS: NSWindowCollectionBehaviorCanJoinAllSpaces — an
                    // NSWindow otherwise stays bound to the Space it was created
                    // in, so an overlay vanishes the moment the user switches
                    // desktop. Linux: gtk_window_stick(). Windows: tao no-op,
                    // and none is needed — a taskbar-excluded (WS_EX_TOOLWINDOW)
                    // window is not tracked by the Virtual Desktop Manager and
                    // therefore already shows on every desktop.
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_visible_on_all_workspaces(visible);
                        }
                    }
                }
                UserEvent::Focus { handle } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            // Undo a prior `set_minimized(true)` first so the
                            // window is eligible for foreground activation.
                            w.set_minimized(false);
                            w.set_focus();
                        }
                    }
                }
                UserEvent::SetMinInnerSize {
                    handle,
                    width,
                    height,
                } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if width < 0.0 || height < 0.0 {
                                w.set_min_inner_size::<LogicalSize<f64>>(None);
                            } else {
                                w.set_min_inner_size(Some(LogicalSize::new(width, height)));
                                // Tao only stores the constraint; Windows enforces it via
                                // WM_GETMINMAXINFO during user-initiated resizes. Clamp the
                                // current inner size now so the minimum is honored immediately.
                                let scale = w.scale_factor();
                                let current = w.inner_size().to_logical::<f64>(scale);
                                let new_w = current.width.max(width);
                                let new_h = current.height.max(height);
                                if new_w > current.width || new_h > current.height {
                                    w.set_inner_size(LogicalSize::new(new_w, new_h));
                                }
                            }
                        }
                    }
                }
                UserEvent::SetWindowIcon {
                    handle,
                    width,
                    height,
                    pixels,
                } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if pixels.is_empty() || width == 0 || height == 0 {
                                w.set_window_icon(None);
                            } else if let Ok(icon) =
                                tao::window::Icon::from_rgba(pixels, width, height)
                            {
                                w.set_window_icon(Some(icon));
                            }
                        }
                    }
                }
                UserEvent::SetInnerSize {
                    handle,
                    width,
                    height,
                } => {
                    let inner = {
                        let guard = WINDOWS.lock().unwrap();
                        guard.as_ref().and_then(|map| {
                            let w = map.get(&handle)?;
                            w.set_inner_size(LogicalSize::new(width, height));
                            Some(w.inner_size())
                        })
                    };
                    // Win32 `SetWindowPos(SWP_ASYNCWINDOWPOS)` and a GCD-async
                    // `setContentSize:` both update the live window rect before
                    // the matching `Resized` event is delivered. Push
                    // EVENT_RESIZED from the size we just applied so the
                    // Compose scene/present tracks the HWND/NSWindow in this
                    // turn — otherwise TitleBar + content tremble against the
                    // already-resized chrome (#576). GTK queues Size through
                    // the request channel; `inner_size()` is still the previous
                    // configure there, so the real `Resized` follows later.
                    #[cfg(any(target_os = "windows", target_os = "macos"))]
                    if let Some(size) = inner {
                        dispatch(
                            handle,
                            EVENT_RESIZED,
                            size.width as jint,
                            size.height as jint,
                        );
                    }
                    #[cfg(target_os = "linux")]
                    let _ = inner;
                }
                UserEvent::SetOuterPosition { handle, x, y } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_outer_position(tao::dpi::LogicalPosition::new(x, y));
                        }
                    }
                }
                UserEvent::SetFullscreen { handle, fullscreen } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            if fullscreen {
                                w.set_fullscreen(Some(tao::window::Fullscreen::Borderless(None)));
                            } else {
                                w.set_fullscreen(None);
                            }
                        }
                    }
                }
                UserEvent::Exit => {
                    *control_flow = ControlFlow::Exit;
                }
            },
            Event::WindowEvent {
                window_id, event, ..
            } => {
                let Some(handle) = handle_for(window_id) else {
                    return;
                };
                match event {
                    WindowEvent::CloseRequested => {
                        dispatch(handle, EVENT_CLOSE_REQUESTED, 0, 0);
                    }
                    WindowEvent::Destroyed => {
                        if let Ok(mut guard) = WINDOWS.lock() {
                            if let Some(map) = guard.as_mut() {
                                map.remove(&handle);
                            }
                        }
                        #[cfg(any(
                            target_os = "windows",
                            target_os = "macos",
                            target_os = "linux"
                        ))]
                        last_minimized.remove(&handle);
                        dispatch(handle, EVENT_DESTROYED, 0, 0);
                    }
                    WindowEvent::Resized(size) => {
                        dispatch(
                            handle,
                            EVENT_RESIZED,
                            size.width as jint,
                            size.height as jint,
                        );
                    }
                    WindowEvent::Moved(pos) => {
                        dispatch(handle, EVENT_MOVED, pos.x, pos.y);
                    }
                    WindowEvent::ScaleFactorChanged {
                        scale_factor,
                        new_inner_size,
                    } => {
                        dispatch(
                            handle,
                            EVENT_SCALE_FACTOR_CHANGED,
                            (scale_factor * 1000.0) as jint,
                            0,
                        );
                        // macOS ONLY. A display hop leaves the NSWindow frame
                        // in points untouched, so `windowDidResize:` never
                        // fires and no Resized trails the scale change — the
                        // scene and the CAMetalLayer would keep the previous
                        // display's pixel size (#418). Forward the size tao
                        // computed for the new scale as that missing resize.
                        //
                        // Do NOT lift this out of the cfg: on Windows
                        // `new_inner_size` is deliberately the *old* physical
                        // size whenever the window is maximized/fullscreen or
                        // "show window contents while dragging" is off (see
                        // `allow_resize` in tao's WM_DPICHANGED handler), so
                        // dispatching it would publish a stale size paired
                        // with the new scale — the very bug this fixes. The
                        // real WM_SIZE follows there anyway.
                        #[cfg(target_os = "macos")]
                        dispatch(
                            handle,
                            EVENT_RESIZED,
                            new_inner_size.width as jint,
                            new_inner_size.height as jint,
                        );
                        #[cfg(not(target_os = "macos"))]
                        let _ = new_inner_size;
                    }
                    WindowEvent::Focused(focused) => {
                        // WAYLAND-ONLY HACK (restore half of the one above).
                        // External restore (GNOME overview, KDE taskbar) emits
                        // no un-minimize event on Wayland. A window cannot hold
                        // keyboard focus while iconified, so a focus-gain while
                        // we still believe we're minimized means it was
                        // restored — clear the stale flag so state.isMinimized
                        // recovers. X11 gets the real window-state-event, hence
                        // the is_wayland() gate.
                        #[cfg(target_os = "linux")]
                        if focused
                            && target.is_wayland()
                            && last_minimized.get(&handle).copied() == Some(true)
                        {
                            last_minimized.insert(handle, false);
                            dispatch(handle, crate::events::EVENT_MINIMIZED, 0, 0);
                        }
                        let code = if focused {
                            EVENT_FOCUSED
                        } else {
                            EVENT_UNFOCUSED
                        };
                        dispatch(handle, code, 0, 0);
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        dispatch(
                            handle,
                            EVENT_CURSOR_MOVED,
                            (position.x * CURSOR_FIXED_SCALE) as jint,
                            (position.y * CURSOR_FIXED_SCALE) as jint,
                        );
                    }
                    WindowEvent::CursorLeft { .. } => {
                        dispatch(handle, EVENT_CURSOR_LEFT, 0, 0);
                    }
                    WindowEvent::MouseInput { state, button, .. } => {
                        let code = match state {
                            ElementState::Pressed => EVENT_MOUSE_DOWN,
                            ElementState::Released => EVENT_MOUSE_UP,
                            _ => return,
                        };
                        dispatch(handle, code, mouse_button_code(button), 0);
                    }
                    WindowEvent::MouseWheel {
                        delta,
                        scroll_phase,
                        ..
                    } => {
                        // The JVM side reshapes the deltas to AWT's
                        // `preciseWheelRotation` semantics so Compose's
                        // `MacOSCocoaConfig` can apply its standard
                        // `× 10dp × -scrollAmount` formula. AWT never scales
                        // by the display factor, so tao's physical `PixelDelta`
                        // goes back to logical points first (#653).
                        let (code, dx, dy) = match delta {
                            MouseScrollDelta::LineDelta(x, y) => {
                                (EVENT_SCROLL_LINE, x as f64, y as f64)
                            }
                            MouseScrollDelta::PixelDelta(p) => {
                                let scale = WINDOWS
                                    .lock()
                                    .ok()
                                    .and_then(|guard| {
                                        guard.as_ref().and_then(|map| {
                                            map.get(&handle).map(|w| w.scale_factor())
                                        })
                                    })
                                    .unwrap_or(1.0);
                                let logical = p.to_logical::<f64>(scale);
                                (EVENT_SCROLL_PIXEL, logical.x, logical.y)
                            }
                            _ => return,
                        };
                        let dx_fixed = (dx * SCROLL_FIXED_SCALE) as jint;
                        let dy_fixed = (dy * SCROLL_FIXED_SCALE) as jint;
                        // A precise scroll that belongs to a trackpad gesture
                        // (finger or momentum phase) is reported as a gesture
                        // so the JVM can surface Compose Pan events (#654);
                        // everything else stays an ordinary wheel scroll.
                        let gesture = match scroll_phase {
                            tao::event::ScrollPhase::None => None,
                            tao::event::ScrollPhase::MayBegin => Some(SCROLL_GESTURE_MAY_BEGIN),
                            tao::event::ScrollPhase::Began => Some(SCROLL_GESTURE_BEGAN),
                            tao::event::ScrollPhase::Changed => Some(SCROLL_GESTURE_CHANGED),
                            tao::event::ScrollPhase::Ended => Some(SCROLL_GESTURE_ENDED),
                            tao::event::ScrollPhase::Cancelled => Some(SCROLL_GESTURE_CANCELLED),
                            tao::event::ScrollPhase::MomentumBegan => {
                                Some(SCROLL_GESTURE_MOMENTUM_BEGAN)
                            }
                            tao::event::ScrollPhase::MomentumChanged => {
                                Some(SCROLL_GESTURE_MOMENTUM_CHANGED)
                            }
                            tao::event::ScrollPhase::MomentumEnded => {
                                Some(SCROLL_GESTURE_MOMENTUM_ENDED)
                            }
                        };
                        match gesture {
                            Some(phase) if code == EVENT_SCROLL_PIXEL => {
                                dispatch_scroll_gesture(handle, phase, dx_fixed, dy_fixed);
                            }
                            _ => dispatch(handle, code, dx_fixed, dy_fixed),
                        }
                    }
                    WindowEvent::ReceivedImeText(text) => {
                        let mods = current_modifier_bits();
                        for ch in text.chars() {
                            dispatch_key(
                                handle,
                                EVENT_KEY_TYPED,
                                0,
                                keymap::LOC_STANDARD,
                                mods,
                                ch as jint,
                            );
                        }
                    }
                    WindowEvent::ImePreedit(text) => {
                        dispatch_ime_preedit(handle, &text);
                    }
                    WindowEvent::ImeCommit(text) => {
                        dispatch_ime_commit(handle, &text);
                    }
                    WindowEvent::ImeReplaceCommit {
                        text,
                        start,
                        length,
                    } => {
                        dispatch_ime_replace_commit(handle, &text, start, length);
                    }
                    WindowEvent::ModifiersChanged(state) => {
                        let modifiers = pack_modifiers(state);
                        if let Ok(mut g) = CURRENT_MODIFIERS.lock() {
                            *g = modifiers;
                        }
                        dispatch(handle, EVENT_MODIFIERS_CHANGED, modifiers, 0);
                    }
                    WindowEvent::KeyboardInput {
                        event: ke,
                        is_synthetic,
                        ..
                    } => {
                        // Tao replays a KEY_DOWN for every physically-held key when a
                        // window gains focus (Windows WM_SETFOCUS / Linux), reading the
                        // global async keyboard state. If focus transfers to our window
                        // while the user is still holding a shortcut they pressed in
                        // another app — e.g. Ctrl+W in File Explorer, which closes its
                        // tab and hands focus back to us before the keys are released —
                        // that replay reaches Compose as a real Ctrl+W and fires the
                        // shortcut here too. The reference JNI/JBR backend never does
                        // this (it only refreshes modifier state on focus), and we read
                        // live modifiers via GetAsyncKeyState on the next real key, so
                        // synthetic key events are dropped entirely.
                        if is_synthetic {
                            return;
                        }
                        let type_code = match ke.state {
                            ElementState::Pressed => EVENT_KEY_DOWN,
                            ElementState::Released => EVENT_KEY_UP,
                            _ => return,
                        };
                        let (vk, location) = keymap::map(ke.physical_key);
                        // First Unicode scalar of the produced text (if any). Modifier
                        // keys, arrows, etc. emit `text = None`; printable keys emit
                        // the post-layout / post-modifiers character — exactly what
                        // AWT delivers as `KeyEvent.keyChar`.
                        let code_point = ke
                            .text
                            .and_then(|s| s.chars().next())
                            .map(|c| c as jint)
                            .unwrap_or(0);
                        dispatch_key(
                            handle,
                            type_code,
                            vk,
                            location,
                            current_modifier_bits(),
                            code_point,
                        );
                    }
                    WindowEvent::Touch(touch) => {
                        // Tao routes Windows touchscreen input through WM_POINTER.
                        // We must forward raw touches ourselves or `LazyColumn`,
                        // drag gestures, pinch-zoom, etc. don't react on tablets /
                        // 2-in-1s.
                        let phase = match touch.phase {
                            TouchPhase::Started => TOUCH_EVENT_PRESS,
                            TouchPhase::Moved => TOUCH_EVENT_MOVE,
                            TouchPhase::Ended => TOUCH_EVENT_RELEASE,
                            TouchPhase::Cancelled => TOUCH_EVENT_CANCEL,
                            _ => return,
                        };
                        let force_fixed = match touch.force {
                            Some(f) => (f.normalized() * TOUCH_FORCE_FIXED_SCALE) as jint,
                            None => TOUCH_FORCE_UNKNOWN,
                        };
                        dispatch_touch_input(
                            handle,
                            phase,
                            touch.id,
                            (touch.location.x * CURSOR_FIXED_SCALE) as jint,
                            (touch.location.y * CURSOR_FIXED_SCALE) as jint,
                            force_fixed,
                        );
                    }
                    _ => {}
                }
            }
            Event::RedrawRequested(window_id) => {
                if let Some(handle) = handle_for(window_id) {
                    dispatch(handle, EVENT_REDRAW_REQUESTED, 0, 0);
                }
            }
            Event::MainEventsCleared => {
                dispatch(0, EVENT_MAIN_EVENTS_CLEARED, 0, 0);
            }
            // macOS deep links: AppKit installs its own `kAEGetURL` handler
            // during `finishLaunching` (routing to `application:openURLs:`).
            // Tao's delegate turns `openURLs` into `Event::Opened`, so we
            // forward the URLs to the JVM here. Covers cold start (the launch
            // URL replayed after `finishLaunching`) and warm start.
            #[cfg(target_os = "macos")]
            Event::Opened { urls } => {
                for url in &urls {
                    crate::platform::macos::apple_events::dispatch_deep_link(url.as_str());
                }
            }
            _ => {}
        }
    });
}

/// Ensure the WINDOWS map exists. Called from the JNI entry point before the
/// loop starts so JNI calls posting `UserEvent`s have a place to look up
/// windows from the Tao thread.
pub(crate) fn init_windows_map() {
    let mut guard = WINDOWS.lock().unwrap();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
}
