// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

use std::{
  cell::RefCell,
  collections::{HashSet, VecDeque},
  error::Error,
  process,
  rc::Rc,
  sync::atomic::{AtomicBool, Ordering},
  time::Instant,
};

use cairo::Region;
use crossbeam_channel::SendError;
use gdk::{Cursor, CursorType, EventKey, EventMask, ScrollDirection, WindowEdge, WindowState};
use gio::Cancellable;
use glib::{source::Priority, MainContext};
use gtk::{
  cairo, gdk, gio,
  glib::{self},
  prelude::*,
  Settings,
};

#[cfg(feature = "x11")]
use crate::platform_impl::platform::device;
use crate::platform_impl::platform::ime::{Commit, ImeState};
use crate::{
  dpi::{LogicalPosition, LogicalSize, PhysicalPosition},
  error::ExternalError,
  event::{
    ElementState, Event, MouseButton, MouseScrollDelta, StartCause, TouchPhase, WindowEvent,
  },
  event_loop::{ControlFlow, EventLoopClosed, EventLoopWindowTarget as RootELW},
  keyboard::ModifiersState,
  monitor::MonitorHandle as RootMonitorHandle,
  platform_impl::platform::DEVICE_ID,
  window::{
    CursorIcon, Fullscreen, ProgressBarState, ResizeDirection, Theme, WindowId as RootWindowId,
  },
};

use super::{
  keyboard,
  monitor::{self, MonitorHandle},
  taskbar, util,
  window::{
    content_geometry, event_coords_to_toplevel, is_csd_hidden_titlebar, WindowId, WindowRequest,
  },
};

use taskbar::TaskbarIndicator;

/// Whether GTK focus sits on a widget Nucleus did not create — an embedded
/// native view (`NativeView`), which the widget bridge never marks with
/// `nucleus_tao_input_box` the way it marks its own capture boxes. Keys then
/// belong to the embed: no IME filtering on its behalf, no delivery to
/// Compose, plain GTK propagation to the focus widget. Without this the
/// toplevel's `GtkIMContext` consumed every printable key and the handler
/// stopped propagation, so a `WebKitWebView` or a `GtkEntry` the user had
/// clicked into never received a single character.
fn embed_owns_keyboard(window: &gtk::Window) -> bool {
  let Some(focus) = window.focused_widget() else {
    return false;
  };
  // SAFETY: only the presence of the key is read; the pointer stored under it
  // (a non-null marker set by the widget bridge) is never dereferenced.
  unsafe { glib::prelude::ObjectExt::data::<()>(&focus, "nucleus_tao_input_box").is_none() }
}

#[derive(Clone)]
pub struct EventLoopWindowTarget<T> {
  /// Gdk display
  pub(crate) display: gdk::Display,
  /// Gtk application
  pub(crate) app: gtk::Application,
  /// Window Ids of the application
  pub(crate) windows: Rc<RefCell<HashSet<WindowId>>>,
  /// Nucleus patch: popup overlay windows (GTK_WINDOW_POPUP) keyed by their
  /// synthesized ids. Not GtkApplicationWindows, so they can't be resolved
  /// through `gtk_application_get_window_by_id` — the request router falls
  /// back to this map.
  pub(crate) popup_windows: Rc<RefCell<std::collections::HashMap<u32, gtk::Window>>>,
  /// Window requests sender
  pub(crate) window_requests_tx: glib::Sender<(WindowId, WindowRequest)>,
  /// Draw event sender
  pub(crate) draw_tx: crossbeam_channel::Sender<WindowId>,
  _marker: std::marker::PhantomData<T>,
}

impl<T> EventLoopWindowTarget<T> {
  #[inline]
  pub fn monitor_from_point(&self, x: f64, y: f64) -> Option<MonitorHandle> {
    monitor::from_point(&self.display, x, y)
  }
  #[inline]
  pub fn available_monitors(&self) -> VecDeque<MonitorHandle> {
    let mut handles = VecDeque::new();
    let display = &self.display;
    let numbers = display.n_monitors();

    for i in 0..numbers {
      let monitor = MonitorHandle::new(display, i);
      handles.push_back(monitor);
    }

    handles
  }

  #[inline]
  pub fn primary_monitor(&self) -> Option<RootMonitorHandle> {
    let monitor = self.display.primary_monitor();
    monitor.map(|monitor| RootMonitorHandle {
      inner: MonitorHandle { monitor },
    })
  }

  #[cfg(feature = "rwh_05")]
  pub fn raw_display_handle_rwh_05(&self) -> rwh_05::RawDisplayHandle {
    if self.is_wayland() {
      let mut display_handle = rwh_05::WaylandDisplayHandle::empty();
      display_handle.display = unsafe {
        gdk_wayland_sys::gdk_wayland_display_get_wl_display(self.display.as_ptr() as *mut _)
      };
      rwh_05::RawDisplayHandle::Wayland(display_handle)
    } else {
      let mut display_handle = rwh_05::XlibDisplayHandle::empty();
      unsafe {
        if let Ok(xlib) = x11_dl::xlib::Xlib::open() {
          let display = (xlib.XOpenDisplay)(std::ptr::null());
          display_handle.display = display as _;
          display_handle.screen = (xlib.XDefaultScreen)(display) as _;
        }
      }

      rwh_05::RawDisplayHandle::Xlib(display_handle)
    }
  }

  #[cfg(feature = "rwh_06")]
  pub fn raw_display_handle_rwh_06(&self) -> Result<rwh_06::RawDisplayHandle, rwh_06::HandleError> {
    if self.is_wayland() {
      let display = unsafe {
        gdk_wayland_sys::gdk_wayland_display_get_wl_display(self.display.as_ptr() as *mut _)
      };
      let display = unsafe { std::ptr::NonNull::new_unchecked(display) };
      let display_handle = rwh_06::WaylandDisplayHandle::new(display);
      Ok(rwh_06::RawDisplayHandle::Wayland(display_handle))
    } else {
      #[cfg(feature = "x11")]
      unsafe {
        if let Ok(xlib) = x11_dl::xlib::Xlib::open() {
          let display = (xlib.XOpenDisplay)(std::ptr::null());
          let screen = (xlib.XDefaultScreen)(display) as _;
          let display = std::ptr::NonNull::new_unchecked(display as _);
          let display_handle = rwh_06::XlibDisplayHandle::new(Some(display), screen);
          Ok(rwh_06::RawDisplayHandle::Xlib(display_handle))
        } else {
          Err(rwh_06::HandleError::Unavailable)
        }
      }
      #[cfg(not(feature = "x11"))]
      Err(rwh_06::HandleError::Unavailable)
    }
  }

  pub fn is_wayland(&self) -> bool {
    self.display.backend().is_wayland()
  }

  #[cfg(feature = "x11")]
  pub fn is_x11(&self) -> bool {
    self.display.backend().is_x11()
  }

  #[inline]
  pub fn cursor_position(&self) -> Result<PhysicalPosition<f64>, ExternalError> {
    util::cursor_position(self.is_wayland())
  }

  #[inline]
  pub fn set_progress_bar(&self, progress: ProgressBarState) {
    if let Err(e) = self
      .window_requests_tx
      .send((WindowId::dummy(), WindowRequest::ProgressBarState(progress)))
    {
      log::warn!("Fail to send update progress bar request: {e}");
    }
  }

  #[inline]
  pub fn set_badge_count(&self, count: Option<i64>, desktop_filename: Option<String>) {
    if let Err(e) = self.window_requests_tx.send((
      WindowId::dummy(),
      WindowRequest::BadgeCount(count, desktop_filename),
    )) {
      log::warn!("Fail to send update progress bar request: {e}");
    }
  }

  #[inline]
  pub fn set_theme(&self, theme: Option<Theme>) {
    if let Err(e) = self
      .window_requests_tx
      .send((WindowId::dummy(), WindowRequest::SetTheme(theme)))
    {
      log::warn!("Fail to send update theme request: {e}");
    }
  }
}

pub struct EventLoop<T: 'static> {
  /// Window target.
  window_target: RootELW<T>,
  /// User event sender for EventLoopProxy
  pub(crate) user_event_tx: crossbeam_channel::Sender<Event<'static, T>>,
  /// Event queue of EventLoop
  events: crossbeam_channel::Receiver<Event<'static, T>>,
  /// Draw queue of EventLoop
  draws: crossbeam_channel::Receiver<WindowId>,
  /// Boolean to control device event thread
  run_device_thread: Option<Rc<AtomicBool>>,
}

#[derive(Default, Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct PlatformSpecificEventLoopAttributes {
  pub(crate) any_thread: bool,
  pub(crate) app_id: Option<String>,
}

impl<T: 'static> EventLoop<T> {
  pub(crate) fn new(attrs: &PlatformSpecificEventLoopAttributes) -> EventLoop<T> {
    if !attrs.any_thread {
      assert_is_main_thread("new_any_thread");
    }

    let context = MainContext::default();
    context
      .with_thread_default(|| {
        EventLoop::new_gtk(attrs.app_id.as_deref()).expect("Failed to initialize gtk backend!")
      })
      .expect("Failed to initialize gtk backend!")
  }

  fn new_gtk(app_id: Option<&str>) -> Result<EventLoop<T>, Box<dyn Error>> {
    // This should be done by gtk::Application::new, but does not work properly
    gtk::init()?;
    let context = MainContext::default();
    let app = gtk::Application::new(app_id, gio::ApplicationFlags::empty());
    let app_ = app.clone();
    let cancellable: Option<&Cancellable> = None;
    app.register(cancellable)?;

    // Send StartCause::Init event
    let (event_tx, event_rx) = crossbeam_channel::unbounded();
    let (draw_tx, draw_rx) = crossbeam_channel::unbounded();
    let event_tx_ = event_tx.clone();
    app.connect_activate(move |_| {
      if let Err(e) = event_tx_.send(Event::NewEvents(StartCause::Init)) {
        log::warn!("Failed to send init event to event channel: {}", e);
      }
    });
    let draw_tx_ = draw_tx.clone();
    let user_event_tx = event_tx.clone();

    // Create event loop window target.
    let (window_requests_tx, window_requests_rx) = glib::MainContext::channel(Priority::default());
    let display = gdk::Display::default()
      .expect("GdkDisplay not found. This usually means `gkt_init` hasn't called yet.");
    let popup_windows: Rc<RefCell<std::collections::HashMap<u32, gtk::Window>>> =
      Rc::new(RefCell::new(std::collections::HashMap::new()));
    let window_target = EventLoopWindowTarget {
      display,
      app,
      windows: Rc::new(RefCell::new(HashSet::new())),
      window_requests_tx,
      draw_tx: draw_tx_,
      popup_windows: popup_windows.clone(),
      _marker: std::marker::PhantomData,
    };

    // Spawn x11 thread to receive Device events.
    #[cfg(feature = "x11")]
    let run_device_thread = if window_target.is_x11() {
      let (device_tx, device_rx) = glib::MainContext::channel(glib::Priority::default());
      let user_event_tx = user_event_tx.clone();
      let run_device_thread = Rc::new(AtomicBool::new(true));
      let run = run_device_thread.clone();
      device::spawn(device_tx);
      device_rx.attach(Some(&context), move |event| {
        if let Err(e) = user_event_tx.send(Event::DeviceEvent {
          device_id: DEVICE_ID,
          event,
        }) {
          log::warn!("Fail to send device event to event channel: {}", e);
        }
        if run.load(Ordering::Relaxed) {
          glib::ControlFlow::Continue
        } else {
          glib::ControlFlow::Break
        }
      });
      Some(run_device_thread)
    } else {
      None
    };
    #[cfg(not(feature = "x11"))]
    let run_device_thread = None;

    let mut taskbar = TaskbarIndicator::new();
    let is_wayland = window_target.is_wayland();

    // Receive portal events
    #[cfg(feature = "dbus")]
    {
      let tx_requests_clone = window_target.window_requests_tx.clone();
      if let Err(e) = super::portal::receive_theme_changed(tx_requests_clone) {
        log::debug!("Unable to receive theme changed events: {e}");
      }
    }

    // Window Request
    let popup_windows_ = popup_windows.clone();
    // Nucleus patch (nucleusframework#558): input contexts keyed by window id.
    // The context is created in the `WireUpEvents` arm and read back by the
    // `SetImePosition` arm, both of which live inside this closure — so it
    // needs no home on `EventLoopWindowTarget`.
    let ime_contexts: Rc<RefCell<std::collections::HashMap<u32, gtk::IMMulticontext>>> =
      Rc::new(RefCell::new(std::collections::HashMap::new()));
    window_requests_rx.attach(Some(&context), move |(id, request)| {
      // Nucleus patch: popup overlay windows are plain gtk::Windows with
      // synthesized ids — resolve them from the popup map when the
      // GtkApplication lookup misses. All request arms operate through
      // GtkWindowExt / WidgetExt, which both kinds implement.
      let resolved: Option<gtk::Window> = app_
        .window_by_id(id.0)
        .map(|w| w.upcast::<gtk::Window>())
        .or_else(|| popup_windows_.borrow().get(&id.0).cloned());
      if let Some(window) = resolved {
        match request {
          WindowRequest::Title(title) => window.set_title(&title),
          WindowRequest::Position((x, y)) => window.move_(x, y),
          WindowRequest::PopupAnchor {
            x,
            y,
            width,
            height,
            shadow,
          } => popup_anchor(&window, x, y, width, height, shadow),
          WindowRequest::Size((w, h)) => {
            // Nucleus patch: `gtk_window_resize` is a no-op on non-resizable
            // windows (GTK follows the content's natural size instead); route
            // the request through the widget size request in that case.
            if window.is_resizable() {
              window.resize(w, h);
            } else {
              window.set_size_request(w, h);
            }
          }
          WindowRequest::SizeConstraints(constraints) => {
            util::set_size_constraints(&window, constraints);
          }
          WindowRequest::Visible(visible) => {
            if visible {
              window.show_all();
            } else {
              window.hide();
            }
          }
          WindowRequest::Focus => {
            // PATCH(nucleus): stamp the activation with a real server time on
            // X11 — Mutter drops _NET_ACTIVE_WINDOW requests carrying
            // GDK_CURRENT_TIME (0) and only flags DEMANDS_ATTENTION (observed
            // on GNOME XWayland; openbox honors the 0 timestamp).
            let time = window
              .window()
              .and_then(|w| util::x11_server_time(&w))
              .unwrap_or(gdk::ffi::GDK_CURRENT_TIME as _);
            window.present_with_time(time);
          }
          WindowRequest::Resizable(resizable) => window.set_resizable(resizable),
          WindowRequest::Closable(closable) => window.set_deletable(closable),
          WindowRequest::Minimized(minimized) => {
            if minimized {
              window.iconify();
            } else {
              window.deiconify();
              // PATCH(nucleus): deiconify alone (XMapWindow per ICCCM) does
              // not leave the Iconic state under Mutter — the compositor
              // keeps the window hidden until an activation with a valid
              // timestamp arrives, so the ICONIFIED window-state-event never
              // clears and EVENT_MINIMIZED(false) never reaches the JVM on
              // GNOME X11/XWayland sessions.
              if let Some(time) = window.window().and_then(|w| util::x11_server_time(&w)) {
                window.present_with_time(time);
              }
            }
          }
          WindowRequest::Maximized(maximized, resizable) => {
            if maximized {
              let maximize_process = util::WindowMaximizeProcess::new(window.clone(), resizable);
              glib::idle_add_local_full(glib::Priority::DEFAULT_IDLE, move || {
                let mut maximize_process = maximize_process.borrow_mut();
                maximize_process.next_step()
              });
            } else {
              window.unmaximize();
            }
          }
          WindowRequest::DragWindow => {
            if let Some(cursor) = window
              .display()
              .default_seat()
              .and_then(|seat| seat.pointer())
            {
              let (_, x, y) = cursor.position();
              window.begin_move_drag(1, x, y, 0);
            }
          }
          WindowRequest::DragResizeWindow(direction) => {
            if let Some(cursor) = window
              .display()
              .default_seat()
              .and_then(|seat| seat.pointer())
            {
              let (_, x, y) = cursor.position();
              window.begin_resize_drag(
                direction.to_gtk_edge(),
                1,
                x,
                y,
                gtk::gdk::ffi::GDK_CURRENT_TIME as _,
              );
            }
          }
          WindowRequest::Fullscreen(fullscreen) => match fullscreen {
            Some(f) => {
              if let Fullscreen::Borderless(m) = f {
                if let Some(monitor) = m {
                  let display = window.display();
                  let monitor = monitor.inner;
                  let monitors = display.n_monitors();
                  for i in 0..monitors {
                    let m = display.monitor(i).unwrap();
                    if m == monitor.monitor {
                      let screen = display.default_screen();
                      window.fullscreen_on_monitor(&screen, i);
                    }
                  }
                } else {
                  window.fullscreen();
                }
              }
            }
            None => window.unfullscreen(),
          },
          WindowRequest::Decorations(decorations) => window.set_decorated(decorations),
          WindowRequest::AlwaysOnBottom(always_on_bottom) => {
            window.set_keep_below(always_on_bottom)
          }
          WindowRequest::AlwaysOnTop(always_on_top) => window.set_keep_above(always_on_top),
          WindowRequest::WindowIcon(window_icon) => {
            if let Some(icon) = window_icon {
              window.set_icon(Some(&icon.inner.into()));
            }
          }
          WindowRequest::UserAttention(request_type) => {
            window.set_urgency_hint(request_type.is_some())
          }
          WindowRequest::SetSkipTaskbar(skip) => {
            window.set_skip_taskbar_hint(skip);
            window.set_skip_pager_hint(skip)
          }
          WindowRequest::BackgroundColor(css_provider, color) => {
            unsafe { window.set_data("background_color", color) };

            let style_context = window.style_context();
            style_context.remove_provider(&css_provider);

            if let Some(color) = color {
              let theme = format!(
                r#"
                  window {{
                    background-color:  rgba({},{},{},{});
                    }}
                    "#,
                color.0,
                color.1,
                color.2,
                color.3 as f64 / 255.0
              );
              let _ = css_provider.load_from_data(theme.as_bytes());
              style_context.add_provider(&css_provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
            };
          }
          WindowRequest::SetVisibleOnAllWorkspaces(visible) => {
            if visible {
              window.stick();
            } else {
              window.unstick();
            }
          }
          WindowRequest::CursorIcon(cursor) => {
            if let Some(gdk_window) = window.window() {
              let display = window.display();
              match cursor {
                Some(cr) => {
                  gdk_window.set_cursor(Cursor::from_name(&display, cr.to_str()).as_ref())
                }
                None => gdk_window
                  .set_cursor(Cursor::for_display(&display, CursorType::BlankCursor).as_ref()),
              }
            };
          }
          WindowRequest::CursorPosition((x, y)) => {
            if let Some(cursor) = window
              .display()
              .default_seat()
              .and_then(|seat| seat.pointer())
            {
              if let Some(screen) = GtkWindowExt::screen(&window) {
                cursor.warp(&screen, x, y);
              }
            }
          }
          // Nucleus patch (nucleusframework#558): hand the input method the
          // area the caret covers so its preedit and candidate windows are
          // placed clear of the text being typed, instead of over it.
          WindowRequest::SetImeCursorArea((x, y, w, h)) => {
            // The caret arrives in client-area coordinates — the contract
            // `set_ime_position` documents. GTK wants it relative to the
            // toplevel GdkWindow, and on a client-side-decorated window that
            // window also spans the invisible resize border and drop shadow,
            // so the two origins are apart by the content widget's allocation.
            // Skipping the translation puts the caret a shadow's height too
            // high, and the input method draws its candidate list straight
            // over the composition it belongs to.
            let (dx, dy) = window
              .child()
              .map(|child| {
                let alloc = child.allocation();
                // Before the first allocation the child reports a 1x1 dummy at
                // the origin; (0, 0) is the right answer then anyway.
                if alloc.width() > 1 || alloc.height() > 1 {
                  (alloc.x(), alloc.y())
                } else {
                  (0, 0)
                }
              })
              .unwrap_or((0, 0));
            if let Some(ime) = ime_contexts.borrow().get(&id.0) {
              ime.set_cursor_location(&gdk::Rectangle::new(x + dx, y + dy, w, h));
            }
          }
          WindowRequest::CursorIgnoreEvents(ignore) => {
            // PATCH(nucleus): an *empty* region, not a 1x1 rectangle at the
            // origin — upstream leaves the top-left pixel clickable. Both
            // branches also go through the same GdkWindow: upstream cleared
            // the shape on the GtkWidget, which is a no-op when the shape was
            // installed on the GdkWindow, so click-through could never be
            // turned back off.
            if let Some(gdk_window) = window.window() {
              if ignore {
                gdk_window.input_shape_combine_region(&Region::create(), 0, 0);
              } else {
                // Only a NULL region clears the shape for good; a full-window
                // region would go stale on the next resize, and the safe
                // binding cannot express NULL.
                use glib::translate::ToGlibPtr;
                unsafe {
                  gdk::ffi::gdk_window_input_shape_combine_region(
                    gdk_window.to_glib_none().0,
                    std::ptr::null_mut(),
                    0,
                    0,
                  );
                }
              }
            }
          }
          WindowRequest::ProgressBarState(_) => unreachable!(),
          WindowRequest::BadgeCount(_, _) => unreachable!(),
          WindowRequest::SetTheme(_) => unreachable!(),
          WindowRequest::WireUpEvents {
            transparent,
            fullscreen,
            cursor_moved,
          } => {
            window.add_events(
              EventMask::POINTER_MOTION_MASK
                | EventMask::BUTTON1_MOTION_MASK
                | EventMask::BUTTON_PRESS_MASK
                | EventMask::TOUCH_MASK
                | EventMask::STRUCTURE_MASK
                | EventMask::FOCUS_CHANGE_MASK
                | EventMask::SCROLL_MASK,
            );

            let fullscreen = Rc::new(AtomicBool::new(fullscreen));
            let fullscreen_ = fullscreen.clone();
            window.connect_window_state_event(move |_window, event| {
              let state = event.changed_mask();
              if state.contains(WindowState::FULLSCREEN) {
                fullscreen_.store(
                  event.new_window_state().contains(WindowState::FULLSCREEN),
                  Ordering::Relaxed,
                );
              }
              glib::Propagation::Proceed
            });

            // Resize hit-test + cursor + begin_resize_drag have moved out of
            // Tao into the embedder (nucleus_tao Kotlin side). The decoration
            // helper runs in `TaoComposeSceneHostLinux.onPointerMove` /
            // `onPointerButton` BEFORE forwarding the event to Compose, so it
            // can claim clicks even on top of a Compose scrollbar — mirroring
            // the JBR `WLDecoratedPeer.postMouseEvent` + `FrameDecoration`
            // architecture. The embedder calls `Window::drag_resize_window`
            // via the `nativeBeginResizeDrag` JNI entry point in `nucleus_tao`.
            let _ = fullscreen;
            let _ = is_wayland;

            // PATCH(nucleus): hidden-titlebar CSD — keep the embedder the
            // SINGLE resize authority over the shadow ring. GtkWindow's own
            // frame regions treat only a thin outer band of the ring as a
            // resize edge and the rest as a TITLE region, so a press there
            // starts a compositor MOVE grab (the window slides with the
            // pointer instead of resizing — which side "wins" depended on
            // exactly where the cursor was). The generic `::event` signal is
            // RUN_LAST: this user handler runs BEFORE GtkWindow's class
            // handler, so returning Stop for ring presses suppresses GTK's
            // title-move/edge machinery entirely. The press/release/motion is
            // re-forwarded to the embedder channel here (Stop also suppresses
            // the specific button/motion signals connected below), translated
            // to content coordinates, where the Compose edge band resolves the
            // ring to the nearest edge and drives `drag_resize_window`.
            let tx_clone = event_tx.clone();
            window.connect_event(move |window, ev| {
              if !is_csd_hidden_titlebar(window) {
                return glib::Propagation::Proceed;
              }
              let ring_pos = |window: &gtk::Window, x: f64, y: f64| {
                let (cx, cy, cw, ch) = content_geometry(window);
                let inside = x >= cx as f64
                  && y >= cy as f64
                  && x < (cx + cw) as f64
                  && y < (cy + ch) as f64;
                if inside {
                  None
                } else {
                  Some((x - cx as f64, y - cy as f64))
                }
              };
              match ev.event_type() {
                gdk::EventType::ButtonPress | gdk::EventType::ButtonRelease => {
                  let (Some((x, y)), Some(button)) = (ev.coords(), ev.button()) else {
                    return glib::Propagation::Proceed;
                  };
                  // Ring events are delivered on GTK's input-only border-strip
                  // GdkWindows with strip-LOCAL coordinates — normalize to the
                  // toplevel space before the ring test.
                  let (x, y) = event_coords_to_toplevel(window, ev.window(), x, y);
                  let Some((tx_x, tx_y)) = ring_pos(window, x, y) else {
                    return glib::Propagation::Proceed;
                  };
                  let scale_factor = window.scale_factor();
                  let pressed = ev.event_type() == gdk::EventType::ButtonPress;
                  // Ship the position first so the embedder's press-time edge
                  // hit-test sees the ring coordinates it is about to act on.
                  let _ = tx_clone.send(Event::WindowEvent {
                    window_id: RootWindowId(id),
                    event: WindowEvent::CursorMoved {
                      position: LogicalPosition::new(tx_x, tx_y)
                        .to_physical(scale_factor as f64),
                      device_id: DEVICE_ID,
                      modifiers: ModifiersState::empty(),
                    },
                  });
                  let _ = tx_clone.send(Event::WindowEvent {
                    window_id: RootWindowId(id),
                    event: WindowEvent::MouseInput {
                      button: match button {
                        1 => MouseButton::Left,
                        2 => MouseButton::Middle,
                        3 => MouseButton::Right,
                        _ => MouseButton::Other(button as u16),
                      },
                      state: if pressed {
                        ElementState::Pressed
                      } else {
                        ElementState::Released
                      },
                      device_id: DEVICE_ID,
                      modifiers: ModifiersState::empty(),
                    },
                  });
                  glib::Propagation::Stop
                }
                gdk::EventType::MotionNotify => {
                  let Some((x, y)) = ev.coords() else {
                    return glib::Propagation::Proceed;
                  };
                  let (x, y) = event_coords_to_toplevel(window, ev.window(), x, y);
                  let Some((tx_x, tx_y)) = ring_pos(window, x, y) else {
                    return glib::Propagation::Proceed;
                  };
                  let scale_factor = window.scale_factor();
                  let _ = tx_clone.send(Event::WindowEvent {
                    window_id: RootWindowId(id),
                    event: WindowEvent::CursorMoved {
                      position: LogicalPosition::new(tx_x, tx_y)
                        .to_physical(scale_factor as f64),
                      device_id: DEVICE_ID,
                      modifiers: ModifiersState::empty(),
                    },
                  });
                  glib::Propagation::Stop
                }
                _ => glib::Propagation::Proceed,
              }
            });

            let tx_clone = event_tx.clone();
            window.connect_delete_event(move |_, _| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::CloseRequested,
              }) {
                log::warn!("Failed to send window close event to event channel: {}", e);
              }
              glib::Propagation::Stop
            });

            let tx_clone = event_tx.clone();
            window.connect_configure_event(move |window, event| {
              let scale_factor = window.scale_factor();

              let (x, y) = window
                .window()
                .map(|w| w.root_origin())
                .unwrap_or_else(|| event.position());
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::Moved(
                  LogicalPosition::new(x, y).to_physical(scale_factor as f64),
                ),
              }) {
                log::warn!("Failed to send window moved event to event channel: {}", e);
              }

              // PATCH(nucleus): with hidden-titlebar CSD the configure event
              // reports the full decorated surface (theme shadow margins
              // included) — subtract the decoration insets for the client-area
              // size the embedder actually renders (fresh, unlike
              // `gtk_window_get_size` which reads the previous GdkWindow size
              // and lags one configure during interactive resizes). Identity
              // when CSD is off.
              let (ew, eh) = event.size();
              let (w, h) = super::window::configure_client_size(window, ew, eh);
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::Resized(
                  LogicalSize::new(w, h).to_physical(scale_factor as f64),
                ),
              }) {
                log::warn!(
                  "Failed to send window resized event to event channel: {}",
                  e
                );
              }
              false
            });

            let tx_clone = event_tx.clone();
            window.connect_focus_in_event(move |_, _| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::Focused(true),
              }) {
                log::warn!(
                  "Failed to send window focus-in event to event channel: {}",
                  e
                );
              }
              glib::Propagation::Proceed
            });

            let tx_clone = event_tx.clone();
            window.connect_focus_out_event(move |_, _| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::Focused(false),
              }) {
                log::warn!(
                  "Failed to send window focus-out event to event channel: {}",
                  e
                );
              }
              glib::Propagation::Proceed
            });

            let tx_clone = event_tx.clone();
            window.connect_destroy(move |_| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::Destroyed,
              }) {
                log::warn!(
                  "Failed to send window destroyed event to event channel: {}",
                  e
                );
              }
            });

            let tx_clone = event_tx.clone();
            window.connect_enter_notify_event(move |window, crossing| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::CursorEntered {
                  device_id: DEVICE_ID,
                },
              }) {
                log::warn!(
                  "Failed to send cursor entered event to event channel: {}",
                  e
                );
              }
              // Nucleus patch: follow the enter with a CursorMoved carrying the
              // entry coordinates. Crossing events are the ONLY position signal
              // a window gets when the pointer lands on it without moving —
              // e.g. the compositor's focus re-evaluation right after a
              // button-held drag ends on Wayland (used for cross-window drop
              // targeting, where global coordinates don't exist).
              let scale_factor = window.scale_factor();
              let (x, y) = crossing.position();
              // PATCH(nucleus): hidden-titlebar CSD — normalize border-strip
              // GdkWindow coordinates to the toplevel, then shift into
              // content-area coordinates so the embedder's (0,0) stays the
              // visible window corner.
              let (x, y) = if is_csd_hidden_titlebar(window) {
                let (x, y) = event_coords_to_toplevel(window, crossing.window(), x, y);
                let (cx, cy, _, _) = content_geometry(window);
                (x - cx as f64, y - cy as f64)
              } else {
                (x, y)
              };
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::CursorMoved {
                  position: LogicalPosition::new(x, y).to_physical(scale_factor as f64),
                  device_id: DEVICE_ID,
                  modifiers: ModifiersState::empty(),
                },
              }) {
                log::warn!("Failed to send cursor moved event to event channel: {}", e);
              }
              glib::Propagation::Proceed
            });

            let tx_clone = event_tx.clone();
            window.connect_motion_notify_event(move |window, motion| {
              if cursor_moved {
                // Nucleus patch: report the event's own coordinates instead of
                // `Device::window_at_position()`. The latter resolves the window
                // currently UNDER the pointer, so the moment a button-held drag
                // crosses the window edge it stops tracking (breaks cross-window
                // drag ghosts). Event coordinates keep flowing for the whole
                // implicit grab — including negative / out-of-bounds values — on
                // X11 and Wayland alike. macOS (mouseDragged) and Windows
                // (SetCapture) already behave this way.
                let scale_factor = window.scale_factor();
                let (x, y) = motion.position();
                // PATCH(nucleus): hidden-titlebar CSD — translate from
                // decorated-surface to content-area coordinates (see the
                // crossing handler above).
                let (x, y) = if is_csd_hidden_titlebar(window) {
                  let (x, y) = event_coords_to_toplevel(window, motion.window(), x, y);
                  let (cx, cy, _, _) = content_geometry(window);
                  (x - cx as f64, y - cy as f64)
                } else {
                  (x, y)
                };
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::CursorMoved {
                    position: LogicalPosition::new(x, y).to_physical(scale_factor as f64),
                    device_id: DEVICE_ID,
                    // this field is depracted so it is fine to pass empty state
                    modifiers: ModifiersState::empty(),
                  },
                }) {
                  log::warn!("Failed to send cursor moved event to event channel: {}", e);
                }
              }
              glib::Propagation::Stop
            });

            let tx_clone = event_tx.clone();
            window.connect_leave_notify_event(move |_, _| {
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::CursorLeft {
                  device_id: DEVICE_ID,
                },
              }) {
                log::warn!("Failed to send cursor left event to event channel: {}", e);
              }
              glib::Propagation::Proceed
            });

            let tx_clone = event_tx.clone();
            window.connect_button_press_event(move |_, event| {
              let button = event.button();
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::MouseInput {
                  button: match button {
                    1 => MouseButton::Left,
                    2 => MouseButton::Middle,
                    3 => MouseButton::Right,
                    _ => MouseButton::Other(button as u16),
                  },
                  state: ElementState::Pressed,
                  device_id: DEVICE_ID,
                  // this field is depracted so it is fine to pass empty state
                  modifiers: ModifiersState::empty(),
                },
              }) {
                log::warn!(
                  "Failed to send mouse input pressed event to event channel: {}",
                  e
                );
              }
              glib::Propagation::Stop
            });

            let tx_clone = event_tx.clone();
            window.connect_button_release_event(move |_, event| {
              let button = event.button();
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::MouseInput {
                  button: match button {
                    1 => MouseButton::Left,
                    2 => MouseButton::Middle,
                    3 => MouseButton::Right,
                    _ => MouseButton::Other(button as u16),
                  },
                  state: ElementState::Released,
                  device_id: DEVICE_ID,
                  // this field is depracted so it is fine to pass empty state
                  modifiers: ModifiersState::empty(),
                },
              }) {
                log::warn!(
                  "Failed to send mouse input released event to event channel: {}",
                  e
                );
              }
              glib::Propagation::Stop
            });

            let tx_clone = event_tx.clone();
            window.connect_scroll_event(move |_, event| {
              // GDK only fills `delta_x`/`delta_y` for GDK_SCROLL_SMOOTH
              // (trackpads). A discrete mouse wheel arrives with
              // `direction = UP/DOWN/LEFT/RIGHT` and a zero delta, so the
              // upstream `event.delta()` mapping drops the event entirely and
              // the wheel never scrolls. Map the direction back onto a unit
              // delta so discrete and smooth scrolls share one sign convention.
              let (x, y) = match event.direction() {
                ScrollDirection::Smooth => event.delta(),
                ScrollDirection::Up => (0.0, -1.0),
                ScrollDirection::Down => (0.0, 1.0),
                ScrollDirection::Left => (-1.0, 0.0),
                ScrollDirection::Right => (1.0, 0.0),
                _ => (0.0, 0.0),
              };
              if let Err(e) = tx_clone.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::MouseWheel {
                  device_id: DEVICE_ID,
                  delta: MouseScrollDelta::LineDelta(-x as f32, -y as f32),
                  phase: match event.direction() {
                    ScrollDirection::Smooth => TouchPhase::Moved,
                    _ => TouchPhase::Ended,
                  },
                  modifiers: ModifiersState::empty(),
                },
              }) {
                log::warn!("Failed to send scroll event to event channel: {}", e);
              }
              glib::Propagation::Proceed
            });

            let tx_clone = event_tx.clone();
            let modifier_state = Rc::new(RefCell::new(ImeState::new()));
            let keyboard_handler = Rc::new(move |event_key: EventKey, element_state| {
              // Nucleus patch: emit the FULL modifier state, not just the
              // pressed key's own bit — upstream sent `{SHIFT}` when Shift
              // was pressed while Ctrl was held, dropping Ctrl from the
              // state and breaking every Ctrl+Shift+<key> shortcut.
              //
              // Nucleus patch (nucleusframework#558): recompute it on *every*
              // key, not just on modifier keys, and publish it whenever it
              // changed. GDK reports the live modifier mask on every event, so
              // deriving the state from the event instead of from press/release
              // bookkeeping self-heals when a modifier's release goes missing.
              // That is not hypothetical: on X11 an input method sits in the
              // event path and re-injects what it forwards (ibus marks those
              // events with its own reserved bits), and modifier releases are
              // dropped along the way. The old code only ever revisited the
              // state on a modifier key, so a lost Control release left Compose
              // believing Ctrl was held — and a plain Return then read as
              // Ctrl+Return for the rest of the session.
              let mods =
                keyboard::get_modifier_state(&event_key, ElementState::Pressed == element_state);
              if let Some(mods) = modifier_state.borrow_mut().modifiers_changed(mods) {
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::ModifiersChanged(mods),
                }) {
                  log::warn!(
                    "Failed to send modifiers changed event to event channel: {}",
                    e
                  );
                }
              }
              // Nucleus patch: fall through and *also* emit `KeyboardInput`
              // for modifier-only keypresses so the JVM side can observe Alt
              // / Ctrl / Shift / Super press/release as plain Compose key
              // events (needed by app-level handlers like
              // `(ev.key == Key.AltLeft) && ev.type == KeyEventType.KeyUp`).
              // Upstream tao stops here, which makes those handlers dead on
              // the Linux backend.

              // todo: implement repeat?
              let event = keyboard::make_key_event(&event_key, false, None, element_state);

              if let Some(event) = event {
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::KeyboardInput {
                    device_id: DEVICE_ID,
                    event,
                    is_synthetic: false,
                  },
                }) {
                  log::warn!("Failed to send keyboard event to event channel: {}", e);
                }
              }
              glib::ControlFlow::Continue
            });

            // Nucleus patch (nucleusframework#558): the stock backend pinned
            // `IMContextSimple`, GTK's built-in fallback that only knows
            // Compose sequences and Ctrl+Shift+U — it never reaches the system
            // input method, so CJK input was impossible. `IMMulticontext`
            // resolves the platform module the same way GTK's own text widgets
            // do (ibus / fcitx5 through the GTK immodule on X11, the
            // text-input-v3 client on Wayland).
            let ime = gtk::IMMulticontext::new();
            ime.set_client_window(window.window().as_ref());

            // Everything about this window's input method that is state
            // rather than plumbing — composition flag, the press/release
            // pairing gate, and the last published modifier state. Split out
            // so the behaviour can be unit-tested without a display; see
            // `platform_impl::linux::ime`.
            let ime_state = Rc::new(RefCell::new(ImeState::new()));

            {
              let ime_state = ime_state.clone();
              ime.connect_preedit_start(move |_| ime_state.borrow_mut().preedit_started());
            }

            {
              let tx_clone = event_tx.clone();
              ime.connect_preedit_changed(move |ime| {
                let (text, _, _) = ime.preedit_string();
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::ImePreedit(text.to_string()),
                }) {
                  log::warn!("Failed to send IME preedit event to event channel: {}", e);
                }
              });
            }

            {
              let ime_state = ime_state.clone();
              let tx_clone = event_tx.clone();
              ime.connect_preedit_end(move |_| {
                ime_state.borrow_mut().preedit_ended();
                // Empty preedit = "drop the marked text". A commit, when there
                // is one, has already been delivered by `commit` above.
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::ImePreedit(String::new()),
                }) {
                  log::warn!("Failed to send IME preedit end event to event channel: {}", e);
                }
              });
            }

            {
              let ime_state = ime_state.clone();
              let tx_clone = event_tx.clone();
              ime.connect_commit(move |_, s| {
                let event = match ime_state.borrow().commit() {
                  Commit::Ime => WindowEvent::ImeCommit(s.to_string()),
                  Commit::Text => WindowEvent::ReceivedImeText(s.to_string()),
                };
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event,
                }) {
                  log::warn!(
                    "Failed to send received IME text event to event channel: {}",
                    e
                  );
                }
              });
            }

            // Follow the window's focus instead of latching `focus_in` once at
            // construction: an input context that still believes it is focused
            // keeps receiving key events meant for another window.
            {
              let ime = ime.clone();
              window.connect_focus_in_event(move |_, _| {
                ime.focus_in();
                glib::Propagation::Proceed
              });
            }
            {
              let ime = ime.clone();
              window.connect_focus_out_event(move |_, _| {
                ime.focus_out();
                glib::Propagation::Proceed
              });
            }
            if window.is_active() {
              ime.focus_in();
            }

            // Published so `WindowRequest::SetImePosition` can move the
            // candidate window to the caret; dropped with the window.
            {
              let ime_contexts = ime_contexts.clone();
              window.connect_destroy(move |_| {
                ime_contexts.borrow_mut().remove(&id.0);
              });
            }
            ime_contexts.borrow_mut().insert(id.0, ime.clone());

            let handler = keyboard_handler.clone();
            let ime_ = ime.clone();
            let ime_state_press = ime_state.clone();
            window.connect_key_press_event(move |window, event_key| {
              if embed_owns_keyboard(window) {
                return glib::Propagation::Proceed;
              }
              // The IME gets first refusal, and a key it consumed must not also
              // reach Compose — otherwise the Enter that confirms a conversion
              // also inserts a newline, and the BackSpace that edits the
              // composition also deletes committed text (the Linux twin of the
              // VK_PROCESSKEY leak fixed for Windows in nucleusframework#558).
              let filtered = ime_.filter_keypress(event_key);
              if !ime_state_press
                .borrow_mut()
                .key_pressed(event_key.hardware_keycode(), filtered)
              {
                return glib::Propagation::Stop;
              }
              handler(event_key.to_owned(), ElementState::Pressed);

              // Compose owns the keyboard and has the key: stop here so GtkWindow's
              // own bindings do not run on it too — an arrow or a Tab would
              // otherwise `move-focus` into an embedded native view, which then
              // steals every following keystroke from the Compose text field.
              glib::Propagation::Stop
            });

            let handler = keyboard_handler.clone();
            let ime_state_release = ime_state;
            window.connect_key_release_event(move |window, event_key| {
              if embed_owns_keyboard(window) {
                return glib::Propagation::Proceed;
              }
              let filtered = ime.filter_keypress(event_key);
              if !ime_state_release
                .borrow_mut()
                .key_released(event_key.hardware_keycode(), filtered)
              {
                return glib::Propagation::Stop;
              }
              handler(event_key.to_owned(), ElementState::Released);
              glib::Propagation::Stop
            });

            let tx_clone = event_tx.clone();
            window.connect_window_state_event(move |window, event| {
              let state = event.changed_mask();
              if state.contains(WindowState::ICONIFIED) || state.contains(WindowState::MAXIMIZED) {
                let scale_factor = window.scale_factor();

                let (x, y) = window.position();
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::Moved(
                    LogicalPosition::new(x, y).to_physical(scale_factor as f64),
                  ),
                }) {
                  log::warn!("Failed to send window moved event to event channel: {}", e);
                }

                let (w, h) = window.size();
                if let Err(e) = tx_clone.send(Event::WindowEvent {
                  window_id: RootWindowId(id),
                  event: WindowEvent::Resized(
                    LogicalSize::new(w, h).to_physical(scale_factor as f64),
                  ),
                }) {
                  log::warn!(
                    "Failed to send window resized event to event channel: {}",
                    e
                  );
                }
              }
              glib::Propagation::Proceed
            });

            // Receive draw events of the window.
            let draw_clone = draw_tx.clone();
            window.connect_draw(move |window, cr| {
              if let Err(e) = draw_clone.send(id) {
                log::warn!("Failed to send redraw event to event channel: {}", e);
              }

              if transparent {
                let background_color = unsafe {
                  window
                    .data::<Option<crate::window::RGBA>>("background_color")
                    .and_then(|c| *c.as_ref())
                };

                let rgba = background_color
                  .map(|(r, g, b, a)| (r as f64, g as f64, b as f64, a as f64 / 255.0))
                  .unwrap_or((0., 0., 0., 0.));

                let rect = window
                  .child()
                  .map(|c| c.allocation())
                  .unwrap_or_else(|| window.allocation());

                cr.rectangle(
                  rect.x() as _,
                  rect.y() as _,
                  rect.width() as _,
                  rect.height() as _,
                );
                cr.set_source_rgba(rgba.0, rgba.1, rgba.2, rgba.3);
                cr.set_operator(cairo::Operator::Source);
                let _ = cr.fill();
                cr.set_operator(cairo::Operator::Over);
              }

              glib::Propagation::Proceed
            });
          }
        }
      } else if id == WindowId::dummy() {
        match request {
          WindowRequest::ProgressBarState(state) => {
            taskbar.update(state);
          }
          WindowRequest::BadgeCount(count, desktop_filename) => {
            taskbar.update_count(count, desktop_filename);
          }
          WindowRequest::SetTheme(theme) => {
            if let Some(settings) = Settings::default() {
              settings.set_gtk_application_prefer_dark_theme(theme == Some(Theme::Dark));
              if let Err(e) = event_tx.send(Event::WindowEvent {
                window_id: RootWindowId(id),
                event: WindowEvent::ThemeChanged(theme.unwrap_or_default()),
              }) {
                log::warn!(
                  "Failed to send window theme changed event to event channel: {}",
                  e
                );
              }
            }
          }
          _ => unreachable!(),
        }
      }
      glib::ControlFlow::Continue
    });

    // Create event loop itself.
    let event_loop = Self {
      window_target: RootELW {
        p: window_target,
        _marker: std::marker::PhantomData,
      },
      user_event_tx,
      events: event_rx,
      draws: draw_rx,
      run_device_thread,
    };

    Ok(event_loop)
  }

  #[inline]
  pub fn run<F>(mut self, callback: F) -> !
  where
    F: FnMut(Event<'_, T>, &RootELW<T>, &mut ControlFlow) + 'static,
  {
    let exit_code = self.run_return(callback);
    process::exit(exit_code)
  }

  /// This is the core event loop logic. It basically loops on `gtk_main_iteration` and processes one
  /// event along with that iteration. Depends on current control flow and what it should do, an
  /// event state is defined. The whole state flow chart runs like following:
  ///
  /// ```ignore
  ///                                   Poll/Wait/WaitUntil
  ///       +-------------------------------------------------------------------------+
  ///       |                                                                         |
  ///       |                   Receiving event from event channel                    |   Receiving event from draw channel
  ///       |                               +-------+                                 |   +---+
  ///       v                               v       |                                 |   v   |
  /// +----------+  Poll/Wait/WaitUntil   +------------+  Poll/Wait/WaitUntil   +-----------+ |
  /// | NewStart | ---------------------> | EventQueue | ---------------------> | DrawQueue | |
  /// +----------+                        +------------+                        +-----------+ |
  ///       |ExitWithCode                        |ExitWithCode            ExitWithCode|   |   |
  ///       +------------------------------------+------------------------------------+   +---+
  ///                                            |
  ///                                            v
  ///                                    +---------------+
  ///                                    | LoopDestroyed |
  ///                                    +---------------+
  /// ```
  ///
  /// There are a dew notibale event will sent to callback when state is transisted:
  /// - On any state moves to `LoopDestroyed`, a `LoopDestroyed` event is sent.
  /// - On `NewStart` to `EventQueue`, a `NewEvents` with corresponding `StartCause` depends on
  ///   current control flow is sent.
  /// - On `EventQueue` to `DrawQueue`, a `MainEventsCleared` event is sent.
  /// - On `DrawQueue` back to `NewStart`, a `RedrawEventsCleared` event is sent.
  pub(crate) fn run_return<F>(&mut self, mut callback: F) -> i32
  where
    F: FnMut(Event<'_, T>, &RootELW<T>, &mut ControlFlow),
  {
    enum EventState {
      NewStart,
      EventQueue,
      DrawQueue,
    }

    let context = MainContext::default();
    let run_device_thread = self.run_device_thread.clone();

    context
      .with_thread_default(|| {
        let mut control_flow = ControlFlow::default();
        let window_target = &self.window_target;
        let events = &self.events;
        let draws = &self.draws;

        window_target.p.app.activate();

        // If this is a secondary (remote) GIO instance, the activate signal
        // was forwarded to the primary instance via D-Bus. Exit immediately so
        // the primary can handle focus (e.g. bring its window to front).
        // Without this, the secondary hangs forever waiting for a StartCause::Init
        // event that never arrives (connect_activate only fires on the primary).
        if window_target.p.app.is_remote() {
          return 0;
        }

        let mut state = EventState::NewStart;
        let exit_code = loop {
          let mut blocking = false;
          match state {
            EventState::NewStart => match control_flow {
              ControlFlow::ExitWithCode(code) => {
                callback(Event::LoopDestroyed, window_target, &mut control_flow);
                break code;
              }
              ControlFlow::Wait => {
                // Pending redraws must prevent the blocking gtk iteration just
                // like pending events: `request_redraw` only pushes into the
                // draw channel without waking the glib main context, so a
                // redraw queued while this loop is parked in
                // `gtk::main_iteration_do(true)` would otherwise stall until
                // an unrelated GTK event arrives. With several windows
                // animating, the DrawQueue state below serves the queue one
                // wakeup at a time and every other window's frame starves.
                if !events.is_empty() || !draws.is_empty() {
                  callback(
                    Event::NewEvents(StartCause::WaitCancelled {
                      start: Instant::now(),
                      requested_resume: None,
                    }),
                    window_target,
                    &mut control_flow,
                  );
                  state = EventState::EventQueue;
                } else {
                  blocking = true;
                }
              }
              ControlFlow::WaitUntil(requested_resume) => {
                let start = Instant::now();
                if start >= requested_resume {
                  callback(
                    Event::NewEvents(StartCause::ResumeTimeReached {
                      start,
                      requested_resume,
                    }),
                    window_target,
                    &mut control_flow,
                  );
                  state = EventState::EventQueue;
                } else if !events.is_empty() || !draws.is_empty() {
                  callback(
                    Event::NewEvents(StartCause::WaitCancelled {
                      start,
                      requested_resume: Some(requested_resume),
                    }),
                    window_target,
                    &mut control_flow,
                  );
                  state = EventState::EventQueue;
                } else {
                  blocking = true;
                }
              }
              _ => {
                callback(
                  Event::NewEvents(StartCause::Poll),
                  window_target,
                  &mut control_flow,
                );
                state = EventState::EventQueue;
              }
            },
            EventState::EventQueue => match control_flow {
              ControlFlow::ExitWithCode(code) => {
                callback(Event::LoopDestroyed, window_target, &mut control_flow);
                break code;
              }
              _ => match events.try_recv() {
                Ok(event) => match event {
                  Event::LoopDestroyed => control_flow = ControlFlow::ExitWithCode(1),
                  _ => callback(event, window_target, &mut control_flow),
                },
                Err(_) => {
                  callback(Event::MainEventsCleared, window_target, &mut control_flow);
                  state = EventState::DrawQueue;
                }
              },
            },
            EventState::DrawQueue => match control_flow {
              ControlFlow::ExitWithCode(code) => {
                callback(Event::LoopDestroyed, window_target, &mut control_flow);
                break code;
              }
              _ => {
                // Drain ALL pending redraws in one pass so N windows are
                // served within the same loop cycle instead of one window
                // per wakeup.
                while let Ok(id) = draws.try_recv() {
                  callback(
                    Event::RedrawRequested(RootWindowId(id)),
                    window_target,
                    &mut control_flow,
                  );
                }
                callback(Event::RedrawEventsCleared, window_target, &mut control_flow);
                state = EventState::NewStart;
              }
            },
          }
          gtk::main_iteration_do(blocking);
        };
        if let Some(run_device_thread) = run_device_thread {
          run_device_thread.store(false, Ordering::Relaxed);
        }
        exit_code
      })
      .unwrap_or(1)
  }

  #[inline]
  pub fn window_target(&self) -> &RootELW<T> {
    &self.window_target
  }

  /// Creates an `EventLoopProxy` that can be used to dispatch user events to the main event loop.
  pub fn create_proxy(&self) -> EventLoopProxy<T> {
    EventLoopProxy {
      user_event_tx: self.user_event_tx.clone(),
    }
  }
}

/// Used to send custom events to `EventLoop`.
#[derive(Debug)]
pub struct EventLoopProxy<T: 'static> {
  user_event_tx: crossbeam_channel::Sender<Event<'static, T>>,
}

impl<T: 'static> Clone for EventLoopProxy<T> {
  fn clone(&self) -> Self {
    Self {
      user_event_tx: self.user_event_tx.clone(),
    }
  }
}

impl<T: 'static> EventLoopProxy<T> {
  /// Send an event to the `EventLoop` from which this proxy was created. This emits a
  /// `UserEvent(event)` event in the event loop, where `event` is the value passed to this
  /// function.
  ///
  /// Returns an `Err` if the associated `EventLoop` no longer exists.
  pub fn send_event(&self, event: T) -> Result<(), EventLoopClosed<T>> {
    self
      .user_event_tx
      .send(Event::UserEvent(event))
      .map_err(|SendError(event)| {
        if let Event::UserEvent(error) = event {
          EventLoopClosed(error)
        } else {
          unreachable!();
        }
      })?;

    let context = MainContext::default();
    context.wakeup();

    Ok(())
  }
}

fn assert_is_main_thread(suggested_method: &str) {
  assert!(
    is_main_thread(),
    "Initializing the event loop outside of the main thread is a significant \
             cross-platform compatibility hazard. If you really, absolutely need to create an \
             EventLoop on a different thread, please use the `EventLoopExtUnix::{suggested_method}` function."
  );
}

#[cfg(target_os = "linux")]
fn is_main_thread() -> bool {
  use libc::{c_long, getpid, syscall, SYS_gettid};

  unsafe { syscall(SYS_gettid) == getpid() as c_long }
}

#[cfg(any(target_os = "dragonfly", target_os = "freebsd", target_os = "openbsd"))]
fn is_main_thread() -> bool {
  use libc::pthread_main_np;

  unsafe { pthread_main_np() == 1 }
}

#[cfg(target_os = "netbsd")]
fn is_main_thread() -> bool {
  std::thread::current().name() == Some("main")
}

impl CursorIcon {
  fn to_str(&self) -> &str {
    match self {
      CursorIcon::Crosshair => "crosshair",
      CursorIcon::Hand => "pointer",
      CursorIcon::Arrow => "arrow",
      CursorIcon::Move => "move",
      CursorIcon::Text => "text",
      CursorIcon::Wait => "wait",
      CursorIcon::Help => "help",
      CursorIcon::Progress => "progress",
      CursorIcon::NotAllowed => "not-allowed",
      CursorIcon::ContextMenu => "context-menu",
      CursorIcon::Cell => "cell",
      CursorIcon::VerticalText => "vertical-text",
      CursorIcon::Alias => "alias",
      CursorIcon::Copy => "copy",
      CursorIcon::NoDrop => "no-drop",
      CursorIcon::Grab => "grab",
      CursorIcon::Grabbing => "grabbing",
      CursorIcon::AllScroll => "all-scroll",
      CursorIcon::ZoomIn => "zoom-in",
      CursorIcon::ZoomOut => "zoom-out",
      CursorIcon::EResize => "e-resize",
      CursorIcon::NResize => "n-resize",
      CursorIcon::NeResize => "ne-resize",
      CursorIcon::NwResize => "nw-resize",
      CursorIcon::SResize => "s-resize",
      CursorIcon::SeResize => "se-resize",
      CursorIcon::SwResize => "sw-resize",
      CursorIcon::WResize => "w-resize",
      CursorIcon::EwResize => "ew-resize",
      CursorIcon::NsResize => "ns-resize",
      CursorIcon::NeswResize => "nesw-resize",
      CursorIcon::NwseResize => "nwse-resize",
      CursorIcon::ColResize => "col-resize",
      CursorIcon::RowResize => "row-resize",
      CursorIcon::Default => "default",
    }
  }
}

impl ResizeDirection {
  fn to_cursor_str(&self) -> &str {
    match self {
      ResizeDirection::East => "e-resize",
      ResizeDirection::North => "n-resize",
      ResizeDirection::NorthEast => "ne-resize",
      ResizeDirection::NorthWest => "nw-resize",
      ResizeDirection::South => "s-resize",
      ResizeDirection::SouthEast => "se-resize",
      ResizeDirection::SouthWest => "sw-resize",
      ResizeDirection::West => "w-resize",
    }
  }

  fn to_gtk_edge(&self) -> WindowEdge {
    match self {
      ResizeDirection::East => WindowEdge::East,
      ResizeDirection::North => WindowEdge::North,
      ResizeDirection::NorthEast => WindowEdge::NorthEast,
      ResizeDirection::NorthWest => WindowEdge::NorthWest,
      ResizeDirection::South => WindowEdge::South,
      ResizeDirection::SouthEast => WindowEdge::SouthEast,
      ResizeDirection::SouthWest => WindowEdge::SouthWest,
      ResizeDirection::West => WindowEdge::West,
    }
  }
}

/// Nucleus patch: the compositor-positioned popup behind
/// `Window::popup_anchor`. `gdk_window_move_to_rect` arrived in GDK 3.24; it
/// is resolved at run time so the library still loads against 3.22, where the
/// request degrades to the plain move a subsurface popup gets.
fn popup_anchor(
  window: &gtk::Window,
  x: i32,
  y: i32,
  width: i32,
  height: i32,
  shadow: (i32, i32, i32, i32),
) {
  use glib::translate::ToGlibPtr;
  type MoveToRect = unsafe extern "C" fn(
    *mut gdk::ffi::GdkWindow,
    *const gdk::ffi::GdkRectangle,
    i32,
    i32,
    i32,
    i32,
    i32,
  );
  extern "C" {
    fn dlsym(handle: *mut std::ffi::c_void, symbol: *const std::os::raw::c_char) -> *mut std::ffi::c_void;
  }
  const GDK_GRAVITY_NORTH_WEST: i32 = 1;
  const GDK_ANCHOR_FLIP_X: i32 = 1 << 0;
  const GDK_ANCHOR_FLIP_Y: i32 = 1 << 1;
  const GDK_ANCHOR_SLIDE_X: i32 = 1 << 2;
  const GDK_ANCHOR_SLIDE_Y: i32 = 1 << 3;
  let (left, right, top, bottom) = shadow;
  // RTLD_DEFAULT: GDK is already loaded into the process.
  let symbol = unsafe { dlsym(std::ptr::null_mut(), b"gdk_window_move_to_rect\0".as_ptr() as *const _) };
  if symbol.is_null() {
    window.move_(x - left, y - top);
    return;
  }
  let move_to_rect: MoveToRect = unsafe { std::mem::transmute(symbol) };
  // A popup menu maps as an xdg_popup on Wayland even where GDK would ignore
  // the positioner; harmless on X11 (a menu-typed override-redirect window).
  window.set_type_hint(gdk::WindowTypeHint::PopupMenu);
  // The positioner GDK builds at map time takes the window's geometry as it
  // stands, so the real size must be in place *before* `move_to_rect` — hence
  // the size request, the realize and the resize pass here rather than a
  // separate `WindowRequest::Size`. Popup overlays are non-resizable, where
  // `gtk_window_resize` is a no-op and the size request is what counts.
  if width > 0 && height > 0 {
    window.set_size_request(width, height);
    window.resize(width, height);
  }
  if !window.is_realized() {
    window.realize();
  }
  window.check_resize();
  let Some(gdk_window) = window.window() else {
    return;
  };
  // GTK only manages the shadow width of client-decorated windows, so this
  // sticks: the xdg window geometry becomes the content, margins excluded.
  gdk_window.set_shadow_width(left, right, top, bottom);
  let rect = gdk::Rectangle::new(x, y, 1, 1);
  unsafe {
    move_to_rect(
      gdk_window.to_glib_none().0,
      rect.to_glib_none().0,
      GDK_GRAVITY_NORTH_WEST,
      GDK_GRAVITY_NORTH_WEST,
      GDK_ANCHOR_FLIP_X | GDK_ANCHOR_FLIP_Y | GDK_ANCHOR_SLIDE_X | GDK_ANCHOR_SLIDE_Y,
      0,
      0,
    );
  }
}
