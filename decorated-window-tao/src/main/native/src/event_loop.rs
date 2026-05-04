// winit event loop: builds the loop, owns the platform-main-thread dance on
// macOS, and routes winit events back to Kotlin via `events::dispatch*`.
//
// Migrated from tao 0.35; see decorated-window-tao/MIGRATION.md.

use std::collections::HashMap;

use jni::sys::jint;

use winit::application::ApplicationHandler;
use winit::dpi::{LogicalPosition, LogicalSize};
use winit::event::{ElementState, Ime, MouseScrollDelta, StartCause, WindowEvent};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::keyboard::PhysicalKey;
use winit::window::{Fullscreen, Window, WindowAttributes, WindowId, WindowLevel};

use crate::events::{
    current_modifier_bits, dispatch, dispatch_key, handle_for, mouse_button_code,
    pack_modifiers, UserEvent, CURSOR_FIXED_SCALE, EVENT_CLOSE_REQUESTED, EVENT_CURSOR_LEFT,
    EVENT_CURSOR_MOVED, EVENT_DESTROYED, EVENT_FOCUSED, EVENT_KEY_DOWN, EVENT_KEY_TYPED,
    EVENT_KEY_UP, EVENT_LAUNCHED, EVENT_MAIN_EVENTS_CLEARED, EVENT_MOUSE_DOWN, EVENT_MOUSE_UP,
    EVENT_MOVED, EVENT_REDRAW_REQUESTED, EVENT_RESIZED, EVENT_SCALE_FACTOR_CHANGED,
    EVENT_SCROLL_LINE, EVENT_SCROLL_PIXEL, EVENT_UNFOCUSED, EVENT_WINDOW_READY,
    SCROLL_FIXED_SCALE,
};
use crate::keymap;
use crate::state::{CURRENT_MODIFIERS, EVENT_LOOP_PROXY, WINDOWS};

#[cfg(target_os = "linux")]
use crate::platform::linux::cursor::reapply_stored_cursor;

/// Locks `WINDOWS`, looks up the window for `handle` and runs `f` on it.
/// Returns `None` if the map is uninitialized or the handle is unknown.
fn with_window<R>(handle: u64, f: impl FnOnce(&Window) -> R) -> Option<R> {
    let guard = WINDOWS.lock().ok()?;
    let map = guard.as_ref()?;
    let window = map.get(&handle)?;
    Some(f(window))
}

/// `ApplicationHandler` impl that mirrors the previous tao closure-based loop.
/// All state lives in the global `WINDOWS` / `EVENT_LOOP_PROXY` mutexes — the
/// struct itself is a zero-sized dispatcher.
struct NucleusApp;

impl ApplicationHandler<UserEvent> for NucleusApp {
    fn new_events(&mut self, _event_loop: &ActiveEventLoop, cause: StartCause) {
        if matches!(cause, StartCause::Init) {
            dispatch(0, EVENT_LAUNCHED, 0, 0);
        }
    }

    fn resumed(&mut self, _event_loop: &ActiveEventLoop) {
        // No-op: window creation is driven by `UserEvent::CreateWindow` from the
        // JVM side, not by the resume lifecycle event.
    }

    fn user_event(&mut self, event_loop: &ActiveEventLoop, user: UserEvent) {
        match user {
            UserEvent::Wake => {
                // No-op: the side-effect we want is the loop returning from
                // its `Wait` so a following `about_to_wait` tick drains
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
            } => {
                #[allow(unused_mut)]
                let mut attrs = WindowAttributes::default()
                    .with_title(&title)
                    .with_inner_size(LogicalSize::new(width, height))
                    .with_decorations(decorations)
                    .with_resizable(resizable)
                    .with_visible(visible);
                // Linux: request an ARGB visual so the X visual matches the
                // canonical visual that Mesa's EGL exposes through its
                // EGLConfigs. Without this, a non-canonical 24-bit RGB visual
                // is assigned and `eglCreateWindowSurface` fails with
                // EGL_BAD_CONFIG. The GLX path is unaffected — its
                // `glXChooseVisual` already requests ALPHA_SIZE=8.
                #[cfg(target_os = "linux")]
                {
                    attrs = attrs.with_transparent(true);
                }

                if let Ok(window) = event_loop.create_window(attrs) {
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
                with_window(handle, |w| w.set_visible(visible));
            }
            UserEvent::SetTitle { handle, title } => {
                with_window(handle, |w| w.set_title(&title));
            }
            UserEvent::RequestRedraw { handle } => {
                with_window(handle, |w| w.request_redraw());
            }
            UserEvent::RequestClose { handle } => {
                let mut guard = WINDOWS.lock().unwrap();
                if let Some(map) = guard.as_mut() {
                    if map.remove(&handle).is_some() {
                        #[cfg(target_os = "linux")]
                        crate::platform::linux::cursor::forget_cursor(handle);
                        dispatch(handle, EVENT_DESTROYED, 0, 0);
                    }
                }
            }
            UserEvent::SetMaximized { handle, maximized } => {
                with_window(handle, |w| w.set_maximized(maximized));
            }
            UserEvent::SetMinimized { handle, minimized } => {
                with_window(handle, |w| w.set_minimized(minimized));
            }
            UserEvent::SetAlwaysOnTop { handle, always_on_top } => {
                let level = if always_on_top {
                    WindowLevel::AlwaysOnTop
                } else {
                    WindowLevel::Normal
                };
                with_window(handle, |w| w.set_window_level(level));
            }
            UserEvent::SetFocusable { handle, focusable } => {
                // winit 0.30 doesn't expose `set_focusable` cross-platform.
                // The per-platform `WindowExt*::with_skip_taskbar` covers the
                // taskbar-skip side of focusability but not keyboard focus
                // gating. Left as a no-op until we wire platform-specific
                // helpers if a real need surfaces.
                let _ = (handle, focusable);
            }
            UserEvent::Focus { handle } => {
                with_window(handle, |w| {
                    // Undo a prior `set_minimized(true)` first so the window is
                    // eligible for foreground activation.
                    w.set_minimized(false);
                    w.focus_window();
                });
            }
            UserEvent::SetMinInnerSize { handle, width, height } => {
                with_window(handle, |w| {
                    if width < 0.0 || height < 0.0 {
                        w.set_min_inner_size(None::<LogicalSize<f64>>);
                    } else {
                        w.set_min_inner_size(Some(LogicalSize::new(width, height)));
                        // winit only stores the constraint; Windows enforces
                        // it via WM_GETMINMAXINFO during user-initiated
                        // resizes. Clamp the current inner size now so the
                        // minimum is honored immediately.
                        let scale = w.scale_factor();
                        let current = w.inner_size().to_logical::<f64>(scale);
                        let new_w = current.width.max(width);
                        let new_h = current.height.max(height);
                        if new_w > current.width || new_h > current.height {
                            let _ = w.request_inner_size(LogicalSize::new(new_w, new_h));
                        }
                    }
                });
            }
            UserEvent::SetWindowIcon { handle, width, height, pixels } => {
                with_window(handle, |w| {
                    if pixels.is_empty() || width == 0 || height == 0 {
                        w.set_window_icon(None);
                    } else if let Ok(icon) =
                        winit::window::Icon::from_rgba(pixels.clone(), width, height)
                    {
                        w.set_window_icon(Some(icon));
                    }
                });
            }
            UserEvent::SetInnerSize { handle, width, height } => {
                with_window(handle, |w| {
                    let _ = w.request_inner_size(LogicalSize::new(width, height));
                });
            }
            UserEvent::SetOuterPosition { handle, x, y } => {
                with_window(handle, |w| w.set_outer_position(LogicalPosition::new(x, y)));
            }
            UserEvent::SetFullscreen { handle, fullscreen } => {
                with_window(handle, |w| {
                    if fullscreen {
                        w.set_fullscreen(Some(Fullscreen::Borderless(None)));
                    } else {
                        w.set_fullscreen(None);
                    }
                });
            }
            UserEvent::Exit => {
                // winit 0.30 leaks a CFRunLoopObserver after `run_app`
                // returns; AWT's still-running NSApp drives that observer
                // one more time, where it tries to upgrade an already-dropped
                // `Weak<PanicInfo>` and panics — turning a clean close into
                // SIGABRT (exit 134). The JVM has nothing left to do at this
                // point (Kotlin has already flushed `EVENT_DESTROYED` for
                // every window), so short-circuit straight to process exit
                // instead of unwinding through winit's stale observer.
                #[cfg(target_os = "macos")]
                std::process::exit(0);
                #[cfg(not(target_os = "macos"))]
                event_loop.exit();
            }
        }
    }

    fn window_event(
        &mut self,
        _event_loop: &ActiveEventLoop,
        window_id: WindowId,
        event: WindowEvent,
    ) {
        let Some(handle) = handle_for(window_id) else { return };
        match event {
            WindowEvent::CloseRequested => {
                dispatch(handle, EVENT_CLOSE_REQUESTED, 0, 0);
            }
            WindowEvent::Destroyed => {
                dispatch(handle, EVENT_DESTROYED, 0, 0);
            }
            WindowEvent::Resized(size) => {
                dispatch(handle, EVENT_RESIZED, size.width as jint, size.height as jint);
            }
            WindowEvent::Moved(pos) => {
                dispatch(handle, EVENT_MOVED, pos.x, pos.y);
            }
            WindowEvent::ScaleFactorChanged { scale_factor, .. } => {
                dispatch(
                    handle,
                    EVENT_SCALE_FACTOR_CHANGED,
                    (scale_factor * 1000.0) as jint,
                    0,
                );
            }
            WindowEvent::Focused(focused) => {
                let code = if focused { EVENT_FOCUSED } else { EVENT_UNFOCUSED };
                dispatch(handle, code, 0, 0);
            }
            WindowEvent::CursorMoved { position, .. } => {
                // Re-apply our XI2 device cursor BEFORE dispatching the event
                // to the JVM. (Legacy GTK quirk; harmless under winit but kept
                // to preserve the per-device cursor table behavior on Linux.)
                #[cfg(target_os = "linux")]
                reapply_stored_cursor(handle);
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
                };
                dispatch(handle, code, mouse_button_code(button), 0);
            }
            WindowEvent::MouseWheel { delta, .. } => {
                // Pass the raw NSEvent values straight through; the JVM side
                // reshapes them to match AWT's `preciseWheelRotation` semantics
                // so Compose's `MacOSCocoaConfig` can apply its standard
                // `× 10dp × -scrollAmount` formula.
                let (code, dx, dy) = match delta {
                    MouseScrollDelta::LineDelta(x, y) => {
                        (EVENT_SCROLL_LINE, x as f64, y as f64)
                    }
                    MouseScrollDelta::PixelDelta(p) => (EVENT_SCROLL_PIXEL, p.x, p.y),
                };
                dispatch(
                    handle,
                    code,
                    (dx * SCROLL_FIXED_SCALE) as jint,
                    (dy * SCROLL_FIXED_SCALE) as jint,
                );
            }
            WindowEvent::Ime(Ime::Commit(text)) => {
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
            WindowEvent::ModifiersChanged(modifiers) => {
                if let Ok(mut g) = CURRENT_MODIFIERS.lock() {
                    *g = pack_modifiers(modifiers.state());
                }
            }
            WindowEvent::KeyboardInput { event: ke, .. } => {
                let type_code = match ke.state {
                    ElementState::Pressed => EVENT_KEY_DOWN,
                    ElementState::Released => EVENT_KEY_UP,
                };
                let physical = match ke.physical_key {
                    PhysicalKey::Code(code) => code,
                    PhysicalKey::Unidentified(_) => return,
                };
                let (vk, location) = keymap::map(physical);
                // First Unicode scalar of the produced text (if any). Modifier
                // keys, arrows, etc. emit `text = None`; printable keys emit
                // the post-layout / post-modifiers character — exactly what
                // AWT delivers as `KeyEvent.keyChar`.
                let code_point = ke
                    .text
                    .as_ref()
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
            WindowEvent::RedrawRequested => {
                dispatch(handle, EVENT_REDRAW_REQUESTED, 0, 0);
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, _event_loop: &ActiveEventLoop) {
        dispatch(0, EVENT_MAIN_EVENTS_CLEARED, 0, 0);
    }
}

pub(crate) fn run_event_loop_blocking() {
    // Honor the legacy `NUCLEUS_TAO_LINUX_RENDERER=x11` env var by translating
    // it to winit's `WINIT_UNIX_BACKEND=x11`, so apps that already set the old
    // variable keep working.
    #[cfg(target_os = "linux")]
    {
        let force_x11 = std::env::var_os("NUCLEUS_TAO_LINUX_RENDERER")
            .map(|v| v.to_string_lossy().eq_ignore_ascii_case("x11"))
            .unwrap_or(false);
        if force_x11 && std::env::var_os("WINIT_UNIX_BACKEND").is_none() {
            std::env::set_var("WINIT_UNIX_BACKEND", "x11");
        }
    }

    let mut builder = EventLoop::<UserEvent>::with_user_event();
    // Allow the event loop to run on a non-main OS thread on Linux. The JVM
    // typically hands us a worker thread, not process tid 0, and winit's
    // default assertion would panic at startup. The Wayland and X11 backends
    // each carry their own escape-hatch trait — we set the flag on both so
    // whichever backend winit picks at init time is happy.
    #[cfg(target_os = "linux")]
    {
        use winit::platform::wayland::EventLoopBuilderExtWayland;
        use winit::platform::x11::EventLoopBuilderExtX11;
        EventLoopBuilderExtX11::with_any_thread(&mut builder, true);
        EventLoopBuilderExtWayland::with_any_thread(&mut builder, true);
    }
    let event_loop = builder.build().expect("failed to build winit event loop");
    let _ = EVENT_LOOP_PROXY.set(event_loop.create_proxy());

    // winit 0.30 defaults to `ControlFlow::Wait`, but we set it explicitly so
    // the intent is visible to readers (and survives any future default flip).
    event_loop.set_control_flow(ControlFlow::Wait);

    // Install the Cmd-Q interceptor once we're on the main thread (NSEvent
    // local monitors must be added there). Press-and-hold accent picker and
    // the drag-event latch live alongside it.
    #[cfg(target_os = "macos")]
    unsafe {
        crate::platform::macos::ffi::nucleus_tao_install_cmd_q_handler();
        crate::platform::macos::ffi::nucleus_tao_enable_press_and_hold();
        crate::platform::macos::ffi::nucleus_tao_install_drag_monitor();
    }

    let mut app = NucleusApp;
    if let Err(err) = event_loop.run_app(&mut app) {
        eprintln!("nucleus_tao: event loop terminated with error: {err}");
    }
}

/// Ensure the WINDOWS map exists. Called from the JNI entry point before the
/// loop starts so JNI calls posting `UserEvent`s have a place to look up
/// windows from the event-loop thread.
pub(crate) fn init_windows_map() {
    let mut guard = WINDOWS.lock().unwrap();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
}
