// Tao event loop: builds the loop, owns the platform-main-thread dance on
// macOS, and routes Tao events back to Kotlin via `events::dispatch*`.

use std::collections::HashMap;

use jni::sys::jint;

use tao::dpi::LogicalSize;
use tao::event::{ElementState, Event, MouseScrollDelta, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;

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
    let event_loop = builder.build();
    let _ = EVENT_LOOP_PROXY.set(event_loop.create_proxy());

    // Install the Cmd-Q interceptor once we're on the main thread (NSEvent
    // local monitors must be added there). Press-and-hold accent picker and
    // the drag-event latch live alongside it.
    #[cfg(target_os = "macos")]
    unsafe {
        crate::platform::macos::ffi::nucleus_tao_install_cmd_q_handler();
        crate::platform::macos::ffi::nucleus_tao_enable_press_and_hold();
        crate::platform::macos::ffi::nucleus_tao_install_drag_monitor();
    }

    event_loop.run(move |event, target, control_flow| {
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
                } => {
                    #[allow(unused_mut)]
                    let mut builder = WindowBuilder::new()
                        .with_title(&title)
                        .with_inner_size(LogicalSize::new(width, height))
                        .with_decorations(decorations)
                        .with_resizable(resizable)
                        .with_visible(visible);
                    // Linux: request an ARGB visual so the GTK window's X
                    // visual matches the canonical visual that Mesa's EGL
                    // exposes through its EGLConfigs. Without this, GDK
                    // assigns a non-canonical 24-bit RGB visual and
                    // `eglCreateWindowSurface` fails with EGL_BAD_CONFIG
                    // because no EGLConfig advertises that visual ID.
                    // The GLX path is unaffected — its `glXChooseVisual`
                    // already requests ALPHA_SIZE=8, and ARGB GTK lets the
                    // helper render directly into the parent without the
                    // child-window fallback.
                    #[cfg(target_os = "linux")]
                    {
                        builder = builder.with_transparent(true);
                    }
                    let window = builder.build(target);
                    if let Ok(window) = window {
                        let logical_w = width as jint;
                        let logical_h = height as jint;

                        // GTK realizes its widgets lazily, so the underlying
                        // `GdkWindow` (= source of the X11 XID / Wayland
                        // wl_surface that the EGL helper needs) doesn't
                        // exist yet right after `build()`. Force realization
                        // here so `nativeLinuxHandles` returns a valid handle
                        // synchronously when the JVM-side WINDOW_READY
                        // callback runs. macOS / Windows do this implicitly.
                        #[cfg(target_os = "linux")]
                        {
                            use gtk::prelude::WidgetExt;
                            use tao::platform::unix::WindowExtUnix;
                            window.gtk_window().realize();
                        }

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
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_visible(visible);
                        }
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
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_maximized(maximized);
                        }
                    }
                }
                UserEvent::SetMinimized { handle, minimized } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_minimized(minimized);
                        }
                    }
                }
                UserEvent::SetAlwaysOnTop { handle, always_on_top } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_always_on_top(always_on_top);
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
                UserEvent::SetMinInnerSize { handle, width, height } => {
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
                UserEvent::SetWindowIcon { handle, width, height, pixels } => {
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
                UserEvent::SetInnerSize { handle, width, height } => {
                    let guard = WINDOWS.lock().unwrap();
                    if let Some(map) = guard.as_ref() {
                        if let Some(w) = map.get(&handle) {
                            w.set_inner_size(LogicalSize::new(width, height));
                        }
                    }
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
            Event::WindowEvent { window_id, event, .. } => {
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
                        dispatch(handle, EVENT_SCALE_FACTOR_CHANGED, (scale_factor * 1000.0) as jint, 0);
                    }
                    WindowEvent::Focused(focused) => {
                        let code = if focused { EVENT_FOCUSED } else { EVENT_UNFOCUSED };
                        dispatch(handle, code, 0, 0);
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        // Re-apply our XI2 device cursor BEFORE dispatching the
                        // event to the JVM. tao's GTK signal handler ran first
                        // and reset `gdk_window_set_cursor("default")` on the
                        // parent for resize-edge detection — without this
                        // re-apply our hover icon would only flash for a
                        // single pixel of motion before being overwritten.
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
                            _ => return,
                        };
                        dispatch(handle, code, mouse_button_code(button), 0);
                    }
                    WindowEvent::MouseWheel { delta, .. } => {
                        // Pass the raw NSEvent values straight through; the JVM
                        // side reshapes them to match AWT's `preciseWheelRotation`
                        // semantics so Compose's `MacOSCocoaConfig` can apply its
                        // standard `× 10dp × -scrollAmount` formula.
                        let (code, dx, dy) = match delta {
                            MouseScrollDelta::LineDelta(x, y) => {
                                (EVENT_SCROLL_LINE, x as f64, y as f64)
                            }
                            MouseScrollDelta::PixelDelta(p) => {
                                (EVENT_SCROLL_PIXEL, p.x, p.y)
                            }
                            _ => return,
                        };
                        dispatch(
                            handle,
                            code,
                            (dx * SCROLL_FIXED_SCALE) as jint,
                            (dy * SCROLL_FIXED_SCALE) as jint,
                        );
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
                    WindowEvent::ModifiersChanged(state) => {
                        if let Ok(mut g) = CURRENT_MODIFIERS.lock() {
                            *g = pack_modifiers(state);
                        }
                    }
                    WindowEvent::KeyboardInput { event: ke, .. } => {
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
