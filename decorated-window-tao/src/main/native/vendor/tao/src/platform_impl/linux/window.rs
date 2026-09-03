// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

use std::{
  cell::RefCell,
  collections::VecDeque,
  rc::Rc,
  sync::atomic::{AtomicBool, AtomicI32, Ordering},
};

use gtk::{
  gdk::WindowState,
  glib::{self, translate::ToGlibPtr},
  prelude::*,
  CssProvider, Settings,
};

use crate::{
  dpi::{LogicalPosition, LogicalSize, PhysicalPosition, PhysicalSize, Position, Size},
  error::{ExternalError, NotSupportedError, OsError as RootOsError},
  icon::Icon,
  monitor::MonitorHandle as RootMonitorHandle,
  platform_impl::wayland::header::WlHeader,
  window::{
    CursorIcon, Fullscreen, ProgressBarState, ResizeDirection, Theme, UserAttentionType,
    WindowAttributes, WindowSizeConstraints, RGBA,
  },
};

use super::{
  event_loop::EventLoopWindowTarget,
  monitor::{self, MonitorHandle},
  util, Parent, PlatformSpecificWindowBuilderAttributes,
};

#[derive(Debug, Copy, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct WindowId(pub(crate) u32);

impl WindowId {
  pub fn dummy() -> Self {
    WindowId(u32::MAX)
  }
}

pub struct Window {
  /// Window id.
  pub(crate) window_id: WindowId,
  /// Gtk window. A `GtkApplicationWindow` for regular windows, a plain
  /// `GTK_WINDOW_POPUP` for popup overlays (see `popup_transient_for`).
  pub(crate) window: gtk::Window,
  pub(crate) default_vbox: Option<gtk::Box>,
  /// Window requests sender
  pub(crate) window_requests_tx: glib::Sender<(WindowId, WindowRequest)>,
  scale_factor: Rc<AtomicI32>,
  inner_position: Rc<(AtomicI32, AtomicI32)>,
  outer_position: Rc<(AtomicI32, AtomicI32)>,
  outer_size: Rc<(AtomicI32, AtomicI32)>,
  inner_size: Rc<(AtomicI32, AtomicI32)>,
  maximized: Rc<AtomicBool>,
  is_always_on_top: Rc<AtomicBool>,
  minimized: Rc<AtomicBool>,
  // PATCH(nucleus): tracks compositor tiling (Aero Snap / half-screen). Set from
  // the GTK window-state-event so it can be read off-thread via JNI like
  // `maximized`. Used to drop the Compose-drawn rounded corners when snapped.
  tiled: Rc<AtomicBool>,
  fullscreen: RefCell<Option<Fullscreen>>,
  inner_size_constraints: RefCell<WindowSizeConstraints>,
  /// Draw event Sender
  draw_tx: crossbeam_channel::Sender<WindowId>,
  preferred_theme: RefCell<Option<Theme>>,
  css_provider: CssProvider,
}

/// PATCH(nucleus): true when this toplevel runs yaru.dart-style hidden-titlebar
/// CSD (decorated GtkWindow + hidden GtkHeaderBar installed via
/// `gtk_window_set_titlebar`). Set as gobject data at creation so the
/// event-loop signal handlers (which only hold the GtkWindow) can read it.
pub(crate) fn is_csd_hidden_titlebar<W: IsA<gtk::Window>>(window: &W) -> bool {
  unsafe {
    window
      .as_ref()
      .data::<bool>("nucleus_csd_hidden_titlebar")
      .map(|ptr| *ptr.as_ref())
      .unwrap_or(false)
  }
}

/// PATCH(nucleus): content-area geometry of a toplevel, in toplevel-widget
/// logical coordinates: `(x, y, w, h)` of the child (default vbox) GTK
/// allocated inside the client-side decorations. For a hidden-titlebar CSD
/// window this excludes the theme's shadow margins (and the 0-height hidden
/// header bar); for a plain undecorated window it is `(0, 0, window size)`.
/// The embedder positions its EGL subsurface at `(x, y)` and sizes its buffer
/// to `(w, h)`, and pointer coordinates are translated by `(-x, -y)` so the
/// content keeps a (0,0) origin — the same net effect as a Flutter view being
/// a regular GTK child inside the decorated window.
pub(crate) fn content_geometry<W: IsA<gtk::Window>>(window: &W) -> (i32, i32, i32, i32) {
  let window = window.as_ref();
  if let Some(child) = window.child() {
    let alloc = child.allocation();
    // Before the first size-allocate pass the child reports a 1×1 dummy
    // allocation at (0,0) — fall back to the window size so early callers
    // (initial inner_size snapshot) don't see garbage.
    if alloc.width() > 1 || alloc.height() > 1 {
      return (alloc.x(), alloc.y(), alloc.width(), alloc.height());
    }
  }
  let size = window.size();
  (0, 0, size.0, size.1)
}

/// PATCH(nucleus): translates event coordinates into toplevel-GdkWindow
/// coordinates. GTK3 CSD creates input-only child GdkWindows for the resize
/// border strips (`GtkWindow.border_window[8]`); pointer events landing in
/// the shadow ring are delivered on those children with STRIP-LOCAL
/// coordinates (small values), while events over the content arrive on the
/// toplevel GdkWindow. Walk the GdkWindow chain up to the widget's own
/// window, accumulating each child's position, so callers always reason in
/// one coordinate space. Identity when the event window IS the toplevel.
pub(crate) fn event_coords_to_toplevel<W: IsA<gtk::Window>>(
  window: &W,
  event_window: Option<gtk::gdk::Window>,
  x: f64,
  y: f64,
) -> (f64, f64) {
  let Some(toplevel) = window.as_ref().window() else {
    return (x, y);
  };
  let Some(mut w) = event_window else {
    return (x, y);
  };
  let (mut ex, mut ey) = (x, y);
  let mut hops = 0;
  while w != toplevel && hops < 8 {
    let (px, py) = w.position();
    ex += px as f64;
    ey += py as f64;
    match w.parent() {
      Some(parent) => w = parent,
      None => break,
    }
    hops += 1;
  }
  (ex, ey)
}

/// PATCH(nucleus): client-area size for a configure event on a (possibly)
/// hidden-titlebar CSD window. The configure reports the FULL surface size
/// (theme shadow margins included); `gtk_window_get_size` would subtract the
/// GTK-drawn decorations but reads the PREVIOUS GdkWindow size — one configure
/// stale during an interactive resize. Instead subtract the decoration insets
/// (full allocation − content-child allocation, stable between state changes)
/// from the fresh configure size. Identity when CSD is off (insets are zero).
pub(crate) fn configure_client_size<W: IsA<gtk::Window>>(
  window: &W,
  event_w: u32,
  event_h: u32,
) -> (u32, u32) {
  if !is_csd_hidden_titlebar(window) {
    return (event_w, event_h);
  }
  let widget = window.as_ref();
  let walloc = widget.allocation();
  let (_, _, cw, ch) = content_geometry(window);
  let inset_w = (walloc.width() - cw).max(0);
  let inset_h = (walloc.height() - ch).max(0);
  (
    event_w.saturating_sub(inset_w as u32).max(1),
    event_h.saturating_sub(inset_h as u32).max(1),
  )
}

/// Synthesized ids for popup windows, disjoint from GtkApplicationWindow ids
/// (which start at 1 and grow slowly). High bit set to make collisions
/// impossible in practice.
fn next_popup_window_id() -> u32 {
  use std::sync::atomic::{AtomicU32, Ordering};
  static NEXT: AtomicU32 = AtomicU32::new(0x8000_0000);
  NEXT.fetch_add(1, Ordering::Relaxed)
}

impl Window {
  pub(crate) fn new<T>(
    event_loop_window_target: &EventLoopWindowTarget<T>,
    attributes: WindowAttributes,
    pl_attribs: PlatformSpecificWindowBuilderAttributes,
  ) -> Result<Self, RootOsError> {
    let app = &event_loop_window_target.app;
    let window_requests_tx = event_loop_window_target.window_requests_tx.clone();
    let draw_tx = event_loop_window_target.draw_tx.clone();
    let is_wayland = event_loop_window_target.is_wayland();

    // Nucleus patch: `popup_transient_for` creates a `GTK_WINDOW_POPUP` window
    // instead of a `GtkApplicationWindow`. On Wayland GDK maps such a window as
    // a `wl_subsurface` of the transient parent ("wayland: prefer subsurface
    // when possible", GTK 3.20+), which is the ONLY window kind a client can
    // freely position under xdg-shell — `gtk_window_move` becomes
    // `wl_subsurface.set_position` and the surface is not clipped to the
    // parent. Used for cursor-following overlays (drag ghosts). On X11 it is
    // an override-redirect window, positionable as usual.
    let (window, window_id) = if let Some(popup_parent) = pl_attribs.popup_transient_for.clone() {
      let window = gtk::Window::new(gtk::WindowType::Popup);
      window.set_transient_for(Some(&popup_parent));
      window.set_accept_focus(attributes.focusable && attributes.focused);
      // Deliberately NOT added to the GtkApplication: popup overlays must not
      // keep the app alive nor appear in `gtk_application_get_window_by_id`
      // (their ids are synthesized below and routed via `popup_windows`).
      let window_id = WindowId(next_popup_window_id());
      event_loop_window_target
        .popup_windows
        .borrow_mut()
        .insert(window_id.0, window.clone());
      (window, window_id)
    } else {
      let mut window_builder = gtk::ApplicationWindow::builder()
        .application(app)
        .accept_focus(attributes.focusable && attributes.focused);
      if let Parent::ChildOf(parent) = pl_attribs.parent {
        window_builder = window_builder.transient_for(&parent);
      }

      let window = window_builder.build();

      // Nucleus patch: yaru.dart-style hidden-titlebar CSD. The exact sequence
      // the Flutter Linux runner + yaru_window_linux use: create a real
      // GtkHeaderBar, show it, install it with gtk_window_set_titlebar()
      // (this latches GTK3's internal `client_decorated` flag — GTK now draws
      // the theme's `decoration` node: drop shadow, rounded corners and the
      // invisible resize border), then gtk_widget_hide() it. Hiding the widget
      // removes its height but does NOT un-latch CSD, so the window keeps the
      // native shadow with zero visible titlebar. Wayland only — on X11 the
      // embedder renders into the toplevel XID and would paint over GTK's
      // frame pixels.
      let csd_hidden_titlebar = pl_attribs.csd_hidden_titlebar && is_wayland;
      if csd_hidden_titlebar {
        let header = gtk::HeaderBar::new();
        header.show();
        window.set_titlebar(Some(&header));
        header.hide();
        // yaru.dart hides the header bar AFTER the window is mapped (from
        // Dart), so it never meets gtk_widget_show_all. Tao shows windows
        // with `show_all()`, which recursively re-shows hidden children —
        // opt the header bar out so the hide sticks (44px of native
        // titlebar would otherwise reappear above the embedder's chrome).
        header.set_no_show_all(true);
        unsafe {
          // Read back by the event-loop handlers (Resized source, pointer
          // translation) and by the content-geometry accessors below.
          window.set_data("nucleus_csd_hidden_titlebar", true);
        }
      } else if is_wayland {
        WlHeader::setup(&window, &attributes.title);
      }

      let window_id = WindowId(window.id());
      (window.upcast::<gtk::Window>(), window_id)
    };
    event_loop_window_target
      .windows
      .borrow_mut()
      .insert(window_id);

    // Set Width/Height & Resizable
    let win_scale_factor = window.scale_factor();
    let (width, height) = attributes
      .inner_size
      .map(|size| size.to_logical::<f64>(win_scale_factor as f64).into())
      .unwrap_or((800, 600));
    window.set_default_size(1, 1);
    window.resize(width, height);
    // Nucleus patch: `gtk_window_resize` has no effect on a non-resizable
    // window — GTK sizes it to the content's natural size instead, so a
    // 200×40 overlay came up 200×200. Pin the requested size through the
    // widget size request (the only sizing channel GTK honours when
    // `resizable == false`). Applies to both X11 and Wayland.
    if !attributes.resizable && !attributes.maximized {
      window.set_size_request(width, height);
    }

    if attributes.maximized {
      // Nucleus patch: apply `maximize()` synchronously, before the GTK window
      // is ever realized/mapped. GTK 3 explicitly supports this — its docs:
      //   "If the window isn't yet visible on screen, this function modifies
      //    its internal state ahead of time, so that the window will be
      //    maximized when it is mapped to the screen."
      // Upstream tao queues the maximize through a `glib::idle_add_local_full`
      // tick which fires AFTER `window.show()` — on Wayland the first
      // xdg_toplevel.configure handshake therefore reports the inner_size,
      // the surface is mapped at the requested logical size for one frame,
      // and only the next idle round trip snaps it to maximized. Synchronous
      // application avoids the visible normal→maximized glitch.
      //
      // GTK 3 refuses to maximize a non-resizable window, so we temporarily
      // flip `set_resizable(true)` around the call and restore the requested
      // value immediately after (this matches `WindowMaximizeProcess`).
      window.set_resizable(true);
      window.maximize();
      if !attributes.resizable {
        window.set_resizable(false);
      }
    } else {
      window.set_resizable(attributes.resizable);
    }

    window.set_deletable(attributes.closable);

    // Set Min/Max Size
    util::set_size_constraints(&window, attributes.inner_size_constraints);

    // Set Position
    if let Some(position) = attributes.position {
      let (x, y): (i32, i32) = position.to_logical::<i32>(win_scale_factor as f64).into();
      window.move_(x, y);
    }

    // Set GDK Visual
    if pl_attribs.rgba_visual || attributes.transparent {
      if let Some(screen) = GtkWindowExt::screen(&window) {
        if let Some(visual) = screen.rgba_visual() {
          window.set_visual(Some(&visual));
        }
      }
    }

    if pl_attribs.app_paintable || attributes.transparent {
      // Set a few attributes to make the window can be painted.
      // See Gtk drawing model for more info:
      // https://docs.gtk.org/gtk3/drawing-model.html
      window.set_app_paintable(true);
    }

    if !pl_attribs.double_buffered {
      let widget = window.upcast_ref::<gtk::Widget>();
      if !event_loop_window_target.is_wayland() {
        unsafe {
          gtk::ffi::gtk_widget_set_double_buffered(widget.to_glib_none().0, 0);
        }
      }
    }

    let default_vbox = if pl_attribs.default_vbox {
      let box_ = gtk::Box::new(gtk::Orientation::Vertical, 0);
      window.add(&box_);
      Some(box_)
    } else {
      None
    };

    // Rest attributes
    window.set_title(&attributes.title);
    if let Some(Fullscreen::Borderless(m)) = &attributes.fullscreen {
      if let Some(monitor) = m {
        let display = window.display();
        let monitor = &monitor.inner;
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
    window.set_visible(attributes.visible);
    // Nucleus patch: hidden-titlebar CSD requires the window to STAY decorated
    // (gtk_window_set_decorated(FALSE) would stop GTK from drawing the
    // decoration node — shadow included — even though CSD is latched). The
    // embedder's own chrome replaces the (hidden) titlebar, exactly like a
    // yaru.dart app drawing YaruWindowTitleBar in-view.
    window.set_decorated(attributes.decorations || is_csd_hidden_titlebar(&window));

    if attributes.always_on_bottom {
      window.set_keep_below(attributes.always_on_bottom);
    }

    if attributes.always_on_top {
      window.set_keep_above(attributes.always_on_top);
    }

    if attributes.visible_on_all_workspaces {
      window.stick();
    }

    // Set initial `preferred_theme` value to current portal color-scheme
    #[cfg(feature = "dbus")]
    let preferred_theme = super::portal::theme().ok();
    #[cfg(not(feature = "dbus"))]
    let preferred_theme = None;

    if let Some(theme) = preferred_theme {
      if let Some(settings) = Settings::default() {
        settings.set_gtk_application_prefer_dark_theme(theme == Theme::Dark);
      }
    }

    if attributes.visible {
      window.show_all();
    } else {
      window.hide();
    }

    // restore accept-focus after the window has been drawn
    // if the window was initially created without focus and is supposed to be focusable
    if attributes.focusable && !attributes.focused {
      let signal_id = Rc::new(RefCell::new(None));
      let signal_id_ = signal_id.clone();
      let id = window.connect_draw(move |window, _| {
        if let Some(id) = signal_id_.take() {
          window.set_accept_focus(true);
          window.disconnect(id);
        }
        glib::Propagation::Proceed
      });
      signal_id.borrow_mut().replace(id);
    }

    // Check if we should paint the transparent background ourselves.
    let mut transparent = false;
    if attributes.transparent && pl_attribs.auto_transparent {
      transparent = true;
    }
    let cursor_moved = pl_attribs.cursor_moved;
    if let Err(e) = window_requests_tx.send((
      window_id,
      WindowRequest::WireUpEvents {
        transparent,
        fullscreen: attributes.fullscreen.is_some(),
        cursor_moved,
      },
    )) {
      log::warn!("Fail to send wire up events request: {}", e);
    }

    let (
      scale_factor,
      outer_position,
      inner_position,
      outer_size,
      inner_size,
      maximized,
      minimized,
      is_always_on_top,
      tiled,
    ) = Self::setup_signals(&window, window_id, Some(&attributes));

    if let Some(icon) = attributes.window_icon {
      window.set_icon(Some(&icon.inner.into()));
    }

    let win = Self {
      window_id,
      window,
      default_vbox,
      window_requests_tx,
      draw_tx,
      scale_factor,
      outer_position,
      inner_position,
      outer_size,
      inner_size,
      maximized,
      minimized,
      is_always_on_top,
      tiled,
      fullscreen: RefCell::new(attributes.fullscreen),
      inner_size_constraints: RefCell::new(attributes.inner_size_constraints),
      preferred_theme: RefCell::new(preferred_theme),
      css_provider: CssProvider::new(),
    };

    let _ = win.set_skip_taskbar(pl_attribs.skip_taskbar);
    win.set_background_color(attributes.background_color);

    // Force the underlying GdkWindow to exist before returning. GTK realises
    // widgets lazily (on the first `show`/`map`), so without this call the
    // X11 XID / Wayland wl_surface obtained from the window are not valid
    // until later in the event loop. Embedders that need those handles
    // synchronously (e.g. to attach an EGL surface in a WINDOW_READY
    // callback) would otherwise see a zero handle on first frame.
    win.window.realize();

    Ok(win)
  }

  fn setup_signals(
    window: &gtk::Window,
    window_id: WindowId,
    attributes: Option<&WindowAttributes>,
  ) -> (
    Rc<AtomicI32>,
    Rc<(AtomicI32, AtomicI32)>,
    Rc<(AtomicI32, AtomicI32)>,
    Rc<(AtomicI32, AtomicI32)>,
    Rc<(AtomicI32, AtomicI32)>,
    Rc<AtomicBool>,
    Rc<AtomicBool>,
    Rc<AtomicBool>,
    Rc<AtomicBool>,
  ) {
    let win_scale_factor = window.scale_factor();

    let w_pos = window.position();
    let inner_position: Rc<(AtomicI32, AtomicI32)> = Rc::new((w_pos.0.into(), w_pos.1.into()));
    let inner_position_clone = inner_position.clone();

    let o_pos = window.window().map(|w| w.root_origin()).unwrap_or(w_pos);
    let outer_position: Rc<(AtomicI32, AtomicI32)> = Rc::new((o_pos.0.into(), o_pos.1.into()));
    let outer_position_clone = outer_position.clone();

    let w_size = window.size();
    let inner_size: Rc<(AtomicI32, AtomicI32)> = Rc::new((w_size.0.into(), w_size.1.into()));
    let inner_size_clone = inner_size.clone();

    let o_size = window.window().map(|w| w.root_origin()).unwrap_or(w_pos);
    let outer_size: Rc<(AtomicI32, AtomicI32)> = Rc::new((o_size.0.into(), o_size.1.into()));
    let outer_size_clone = outer_size.clone();

    window.connect_configure_event(move |window, event| {
      let (x, y) = event.position();
      inner_position_clone.0.store(x, Ordering::Release);
      inner_position_clone.1.store(y, Ordering::Release);

      // PATCH(nucleus): with hidden-titlebar CSD the configure event reports
      // the whole surface INCLUDING the theme's shadow margins — subtract the
      // decoration insets to get the client-area size (see
      // `configure_client_size`). Identity when CSD is off.
      let (ew, eh) = event.size();
      let (w, h) = configure_client_size(window, ew, eh);
      inner_size_clone.0.store(w as i32, Ordering::Release);
      inner_size_clone.1.store(h as i32, Ordering::Release);

      let (x, y, w, h) = window
        .window()
        .map(|w| {
          let rect = w.frame_extents();
          (rect.x(), rect.y(), rect.width(), rect.height())
        })
        // PATCH(nucleus): `gdk_window_get_frame_extents` answers with its
        // (0, 0, 1, 1) placeholder until the window is mapped and — under a
        // reparenting WM — framed. A configure that lands inside that window
        // latches the placeholder into `outer_*`, where it stays until the
        // *next* configure: on a software-rendered X server under a
        // lightweight WM (Xvfb + openbox) that is seconds away, or never.
        // Every consumer of `outer_position` / `outer_size` then reads a 1x1
        // window at the screen origin.
        //
        // Fall back to the window's own frame origin plus its client size.
        // NOT to `event.position()`: for a window a reparenting WM has framed,
        // the configure event carries coordinates relative to that frame, so
        // using it publishes a window at (0, 0). `root_origin` is the frame's
        // top-left in root coordinates, which is what `frame_extents` would
        // have said.
        .filter(|(_, _, w, h)| *w > 1 && *h > 1)
        .unwrap_or_else(|| {
          let (rx, ry) = window
            .window()
            .map(|w| w.root_origin())
            .unwrap_or((x, y));
          (rx, ry, w as i32, h as i32)
        });

      outer_position_clone.0.store(x, Ordering::Release);
      outer_position_clone.1.store(y, Ordering::Release);

      outer_size_clone.0.store(w, Ordering::Release);
      outer_size_clone.1.store(h, Ordering::Release);

      false
    });

    let w_max = window.is_maximized();
    let maximized: Rc<AtomicBool> = Rc::new(w_max.into());
    let max_clone = maximized.clone();
    let minimized = Rc::new(AtomicBool::new(false));
    let minimized_clone = minimized.clone();
    let is_always_on_top = Rc::new(AtomicBool::new(
      attributes.map(|a| a.always_on_top).unwrap_or(false),
    ));
    let is_always_on_top_clone = is_always_on_top.clone();
    // PATCH(nucleus): tiling (Aero Snap) state, mirroring `maximized`.
    let tiled = Rc::new(AtomicBool::new(false));
    let tiled_clone = tiled.clone();

    window.connect_window_state_event(move |window, event| {
      let state = event.new_window_state();
      max_clone.store(state.contains(WindowState::MAXIMIZED), Ordering::Release);
      let iconified = state.contains(WindowState::ICONIFIED);
      minimized_clone.store(iconified, Ordering::Release);
      is_always_on_top_clone.store(state.contains(WindowState::ABOVE), Ordering::Release);
      // PATCH(nucleus): a window snapped to a screen edge (GNOME/KDE Aero Snap)
      // reports tiled state without the MAXIMIZED bit. Treat any tiled edge as
      // tiled so the embedder can square off the rounded corners, matching
      // native client-side decorations.
      tiled_clone.store(
        state.intersects(
          WindowState::TILED
            | WindowState::TOP_TILED
            | WindowState::RIGHT_TILED
            | WindowState::BOTTOM_TILED
            | WindowState::LEFT_TILED,
        ),
        Ordering::Release,
      );
      // PATCH(nucleus): deterministic minimize/restore signal. This signal fires
      // for every state change (focus, maximize, …), so gate on the ICONIFIED bit
      // actually transitioning to avoid spamming the embedder hook.
      if event.changed_mask().contains(WindowState::ICONIFIED) {
        if let Some(hook) = crate::platform::linux::MINIMIZED_HOOK.get() {
          hook(crate::window::WindowId(window_id), iconified);
        }
      }
      glib::Propagation::Proceed
    });

    let scale_factor: Rc<AtomicI32> = Rc::new(win_scale_factor.into());
    let scale_factor_clone = scale_factor.clone();
    window.connect_scale_factor_notify(move |window| {
      scale_factor_clone.store(window.scale_factor(), Ordering::Release);
    });

    (
      scale_factor,
      outer_position,
      inner_position,
      outer_size,
      inner_size,
      maximized,
      minimized,
      is_always_on_top,
      tiled,
    )
  }

  pub(crate) fn new_from_gtk_window<T>(
    event_loop_window_target: &EventLoopWindowTarget<T>,
    window: gtk::ApplicationWindow,
  ) -> Result<Self, RootOsError> {
    let window_requests_tx = event_loop_window_target.window_requests_tx.clone();
    let draw_tx = event_loop_window_target.draw_tx.clone();

    let window_id = WindowId(window.id());
    let window = window.upcast::<gtk::Window>();
    event_loop_window_target
      .windows
      .borrow_mut()
      .insert(window_id);

    let (
      scale_factor,
      outer_position,
      inner_position,
      outer_size,
      inner_size,
      maximized,
      minimized,
      is_always_on_top,
      tiled,
    ) = Self::setup_signals(&window, window_id, None);

    let win = Self {
      window_id,
      window,
      default_vbox: None,
      window_requests_tx,
      draw_tx,
      scale_factor,
      outer_position,
      inner_position,
      outer_size,
      inner_size,
      maximized,
      minimized,
      is_always_on_top,
      tiled,
      fullscreen: RefCell::new(None),
      inner_size_constraints: RefCell::new(WindowSizeConstraints::default()),
      preferred_theme: RefCell::new(None),
      css_provider: CssProvider::new(),
    };

    Ok(win)
  }

  pub fn id(&self) -> WindowId {
    self.window_id
  }

  pub fn scale_factor(&self) -> f64 {
    self.scale_factor.load(Ordering::Acquire) as f64
  }

  pub fn request_redraw(&self) {
    if let Err(e) = self.draw_tx.send(self.window_id) {
      log::warn!("Failed to send redraw event to event channel: {}", e);
    }
  }

  pub fn inner_position(&self) -> Result<PhysicalPosition<i32>, NotSupportedError> {
    let (x, y) = &*self.inner_position;
    Ok(
      LogicalPosition::new(x.load(Ordering::Acquire), y.load(Ordering::Acquire))
        .to_physical(self.scale_factor.load(Ordering::Acquire) as f64),
    )
  }

  pub fn outer_position(&self) -> Result<PhysicalPosition<i32>, NotSupportedError> {
    let (x, y) = &*self.outer_position;
    Ok(
      LogicalPosition::new(x.load(Ordering::Acquire), y.load(Ordering::Acquire))
        .to_physical(self.scale_factor.load(Ordering::Acquire) as f64),
    )
  }

  pub fn set_outer_position<P: Into<Position>>(&self, position: P) {
    let (x, y): (i32, i32) = position
      .into()
      .to_logical::<i32>(self.scale_factor())
      .into();

    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Position((x, y))))
    {
      log::warn!("Fail to send position request: {}", e);
    }
  }

  pub fn set_background_color(&self, color: Option<RGBA>) {
    if let Err(e) = self.window_requests_tx.send((
      self.window_id,
      WindowRequest::BackgroundColor(self.css_provider.clone(), color),
    )) {
      log::warn!("Fail to send size request: {}", e);
    }
  }

  pub fn inner_size(&self) -> PhysicalSize<u32> {
    let (width, height) = &*self.inner_size;

    LogicalSize::new(
      width.load(Ordering::Acquire) as u32,
      height.load(Ordering::Acquire) as u32,
    )
    .to_physical(self.scale_factor.load(Ordering::Acquire) as f64)
  }

  pub fn set_inner_size<S: Into<Size>>(&self, size: S) {
    let (width, height) = size.into().to_logical::<i32>(self.scale_factor()).into();

    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Size((width, height))))
    {
      log::warn!("Fail to send size request: {}", e);
    }
  }

  pub fn outer_size(&self) -> PhysicalSize<u32> {
    let (width, height) = &*self.outer_size;

    LogicalSize::new(
      width.load(Ordering::Acquire) as u32,
      height.load(Ordering::Acquire) as u32,
    )
    .to_physical(self.scale_factor.load(Ordering::Acquire) as f64)
  }

  fn set_size_constraints(&self, constraints: WindowSizeConstraints) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::SizeConstraints(constraints)))
    {
      log::warn!("Fail to send size constraint request: {}", e);
    }
  }

  pub fn set_min_inner_size(&self, size: Option<Size>) {
    let (width, height) = size.map(crate::extract_width_height).unzip();
    let mut size_constraints = self.inner_size_constraints.borrow_mut();
    size_constraints.min_width = width;
    size_constraints.min_height = height;
    self.set_size_constraints(*size_constraints)
  }

  pub fn set_max_inner_size(&self, size: Option<Size>) {
    let (width, height) = size.map(crate::extract_width_height).unzip();
    let mut size_constraints = self.inner_size_constraints.borrow_mut();
    size_constraints.max_width = width;
    size_constraints.max_height = height;
    self.set_size_constraints(*size_constraints)
  }

  pub fn set_inner_size_constraints(&self, constraints: WindowSizeConstraints) {
    *self.inner_size_constraints.borrow_mut() = constraints;
    self.set_size_constraints(constraints)
  }

  pub fn set_title(&self, title: &str) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Title(title.to_string())))
    {
      log::warn!("Fail to send title request: {}", e);
    }
  }

  pub fn title(&self) -> String {
    self
      .window
      .title()
      .map(|t| t.as_str().to_string())
      .unwrap_or_default()
  }

  pub fn set_visible(&self, visible: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Visible(visible)))
    {
      log::warn!("Fail to send visible request: {}", e);
    }
  }

  pub fn set_focus(&self) {
    if !self.minimized.load(Ordering::Acquire) && self.window.get_visible() {
      if let Err(e) = self
        .window_requests_tx
        .send((self.window_id, WindowRequest::Focus))
      {
        log::warn!("Fail to send visible request: {}", e);
      }
    }
  }

  pub fn set_focusable(&self, focusable: bool) {
    self.window.set_accept_focus(focusable);
  }

  pub fn is_focused(&self) -> bool {
    self.window.is_active()
  }

  pub fn set_resizable(&self, resizable: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Resizable(resizable)))
    {
      log::warn!("Fail to send resizable request: {}", e);
    }
  }

  pub fn set_minimizable(&self, _minimizable: bool) {}

  pub fn set_maximizable(&self, _maximizable: bool) {}

  pub fn set_closable(&self, closable: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Closable(closable)))
    {
      log::warn!("Fail to send closable request: {}", e);
    }
  }

  pub fn set_minimized(&self, minimized: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Minimized(minimized)))
    {
      log::warn!("Fail to send minimized request: {}", e);
    }
  }

  pub fn set_maximized(&self, maximized: bool) {
    let resizable = self.is_resizable();

    if let Err(e) = self.window_requests_tx.send((
      self.window_id,
      WindowRequest::Maximized(maximized, resizable),
    )) {
      log::warn!("Fail to send maximized request: {}", e);
    }
  }

  pub fn is_always_on_top(&self) -> bool {
    self.is_always_on_top.load(Ordering::Acquire)
  }

  pub fn is_maximized(&self) -> bool {
    self.maximized.load(Ordering::Acquire)
  }

  // PATCH(nucleus): true when the compositor has tiled/snapped the window to a
  // screen edge (Aero Snap). Read off-thread via JNI, like `is_maximized`.
  pub fn is_tiled(&self) -> bool {
    self.tiled.load(Ordering::Acquire)
  }

  pub fn is_minimized(&self) -> bool {
    self.minimized.load(Ordering::Acquire)
  }

  pub fn is_resizable(&self) -> bool {
    self.window.is_resizable()
  }

  pub fn is_minimizable(&self) -> bool {
    true
  }

  pub fn is_maximizable(&self) -> bool {
    true
  }
  pub fn is_closable(&self) -> bool {
    self.window.is_deletable()
  }

  pub fn is_decorated(&self) -> bool {
    self.window.is_decorated()
  }

  #[inline]
  pub fn is_visible(&self) -> bool {
    self.window.is_visible()
  }

  pub fn drag_window(&self) -> Result<(), ExternalError> {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::DragWindow))
    {
      log::warn!("Fail to send drag window request: {}", e);
    }
    Ok(())
  }

  pub fn drag_resize_window(&self, direction: ResizeDirection) -> Result<(), ExternalError> {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::DragResizeWindow(direction)))
    {
      log::warn!("Fail to send drag window request: {}", e);
    }
    Ok(())
  }

  pub fn set_fullscreen(&self, fullscreen: Option<Fullscreen>) {
    self.fullscreen.replace(fullscreen.clone());
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Fullscreen(fullscreen)))
    {
      log::warn!("Fail to send fullscreen request: {}", e);
    }
  }

  pub fn fullscreen(&self) -> Option<Fullscreen> {
    self.fullscreen.borrow().clone()
  }

  pub fn set_decorations(&self, decorations: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::Decorations(decorations)))
    {
      log::warn!("Fail to send decorations request: {}", e);
    }
  }

  pub fn set_always_on_bottom(&self, always_on_bottom: bool) {
    if let Err(e) = self.window_requests_tx.send((
      self.window_id,
      WindowRequest::AlwaysOnBottom(always_on_bottom),
    )) {
      log::warn!("Fail to send always on bottom request: {}", e);
    }
  }

  pub fn set_always_on_top(&self, always_on_top: bool) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::AlwaysOnTop(always_on_top)))
    {
      log::warn!("Fail to send always on top request: {}", e);
    }
  }

  pub fn set_window_icon(&self, window_icon: Option<Icon>) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::WindowIcon(window_icon)))
    {
      log::warn!("Fail to send window icon request: {}", e);
    }
  }

  /// Nucleus patch (nucleusframework#558): forward the caret position to the
  /// window's input context so the candidate list follows the text cursor.
  /// The context itself lives on the event-loop side (it is created when the
  /// window's events are wired up), so this goes through the request channel
  /// like every other window mutation.
  pub fn set_ime_position<P: Into<Position>>(&self, position: P) {
    self.set_ime_cursor_area(position, LogicalSize::new(0, 0));
  }

  /// Nucleus patch (nucleusframework#558): tell the input method the rectangle
  /// the caret occupies, so it can keep its own windows clear of the text.
  ///
  /// GTK is area-based where IMM32 is point-based: `set_cursor_location` takes
  /// the region the cursor covers and the input method keeps its own windows
  /// off it, which is why the caret's *size* matters here and not on Windows.
  /// GDK works in logical pixels, so the caller's physical rect is scaled down
  /// on the way in.
  pub fn set_ime_cursor_area<P: Into<Position>, S: Into<Size>>(&self, position: P, size: S) {
    let scale_factor = self.scale_factor();
    let (x, y): (i32, i32) = position.into().to_logical::<i32>(scale_factor).into();
    let (w, h): (i32, i32) = size.into().to_logical::<i32>(scale_factor).into();
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::SetImeCursorArea((x, y, w, h))))
    {
      log::warn!("Fail to send ime cursor area request: {}", e);
    }
  }

  pub fn request_user_attention(&self, request_type: Option<UserAttentionType>) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::UserAttention(request_type)))
    {
      log::warn!("Fail to send user attention request: {}", e);
    }
  }

  pub fn set_visible_on_all_workspaces(&self, visible: bool) {
    if let Err(e) = self.window_requests_tx.send((
      self.window_id,
      WindowRequest::SetVisibleOnAllWorkspaces(visible),
    )) {
      log::warn!("Fail to send visible on all workspaces request: {}", e);
    }
  }
  pub fn set_cursor_icon(&self, cursor: CursorIcon) {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::CursorIcon(Some(cursor))))
    {
      log::warn!("Fail to send cursor icon request: {}", e);
    }
  }

  pub fn set_cursor_position<P: Into<Position>>(&self, position: P) -> Result<(), ExternalError> {
    let inner_pos = self.inner_position().unwrap_or_default();
    let (x, y): (i32, i32) = position
      .into()
      .to_logical::<i32>(self.scale_factor())
      .into();

    if let Err(e) = self.window_requests_tx.send((
      self.window_id,
      WindowRequest::CursorPosition((x + inner_pos.x, y + inner_pos.y)),
    )) {
      log::warn!("Fail to send cursor position request: {}", e);
    }

    Ok(())
  }

  pub fn set_cursor_grab(&self, _grab: bool) -> Result<(), ExternalError> {
    Ok(())
  }

  pub fn set_ignore_cursor_events(&self, ignore: bool) -> Result<(), ExternalError> {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::CursorIgnoreEvents(ignore)))
    {
      log::warn!("Fail to send cursor position request: {}", e);
    }

    Ok(())
  }

  pub fn set_cursor_visible(&self, visible: bool) {
    let cursor = if visible {
      Some(CursorIcon::Default)
    } else {
      None
    };
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::CursorIcon(cursor)))
    {
      log::warn!("Fail to send cursor visibility request: {}", e);
    }
  }

  #[inline]
  pub fn cursor_position(&self) -> Result<PhysicalPosition<f64>, ExternalError> {
    util::cursor_position(self.is_wayland())
  }

  pub fn current_monitor(&self) -> Option<RootMonitorHandle> {
    let display = self.window.display();
    // `.window()` returns `None` if the window is invisible;
    // we fallback to the primary monitor
    let monitor = self
      .window
      .window()
      .and_then(|window| display.monitor_at_window(&window))
      .or_else(|| display.primary_monitor());

    monitor.map(|monitor| RootMonitorHandle {
      inner: MonitorHandle { monitor },
    })
  }

  #[inline]
  pub fn available_monitors(&self) -> VecDeque<MonitorHandle> {
    let mut handles = VecDeque::new();
    let display = self.window.display();
    let numbers = display.n_monitors();

    for i in 0..numbers {
      let monitor = MonitorHandle::new(&display, i);
      handles.push_back(monitor);
    }

    handles
  }

  pub fn primary_monitor(&self) -> Option<RootMonitorHandle> {
    let display = self.window.display();
    display.primary_monitor().map(|monitor| {
      let handle = MonitorHandle { monitor };
      RootMonitorHandle { inner: handle }
    })
  }

  #[inline]
  pub fn monitor_from_point(&self, x: f64, y: f64) -> Option<RootMonitorHandle> {
    let display = &self.window.display();
    monitor::from_point(display, x, y).map(|inner| RootMonitorHandle { inner })
  }

  fn is_wayland(&self) -> bool {
    self.window.display().backend().is_wayland()
  }

  #[cfg(feature = "rwh_04")]
  #[inline]
  pub fn raw_window_handle_rwh_04(&self) -> rwh_04::RawWindowHandle {
    if self.is_wayland() {
      let mut window_handle = rwh_04::WaylandHandle::empty();
      if let Some(window) = self.window.window() {
        window_handle.surface =
          unsafe { gdk_wayland_sys::gdk_wayland_window_get_wl_surface(window.as_ptr() as *mut _) };
      }

      rwh_04::RawWindowHandle::Wayland(window_handle)
    } else {
      let mut window_handle = rwh_04::XlibHandle::empty();
      unsafe {
        if let Some(window) = self.window.window() {
          window_handle.window = gdk_x11_sys::gdk_x11_window_get_xid(window.as_ptr() as *mut _);
        }
      }
      rwh_04::RawWindowHandle::Xlib(window_handle)
    }
  }

  #[cfg(feature = "rwh_05")]
  #[inline]
  pub fn raw_window_handle_rwh_05(&self) -> rwh_05::RawWindowHandle {
    if self.is_wayland() {
      let mut window_handle = rwh_05::WaylandWindowHandle::empty();
      if let Some(window) = self.window.window() {
        window_handle.surface =
          unsafe { gdk_wayland_sys::gdk_wayland_window_get_wl_surface(window.as_ptr() as *mut _) };
      }

      rwh_05::RawWindowHandle::Wayland(window_handle)
    } else {
      let mut window_handle = rwh_05::XlibWindowHandle::empty();
      unsafe {
        if let Some(window) = self.window.window() {
          window_handle.window = gdk_x11_sys::gdk_x11_window_get_xid(window.as_ptr() as *mut _);
        }
      }
      rwh_05::RawWindowHandle::Xlib(window_handle)
    }
  }

  #[cfg(feature = "rwh_05")]
  #[inline]
  pub fn raw_display_handle_rwh_05(&self) -> rwh_05::RawDisplayHandle {
    if self.is_wayland() {
      let mut display_handle = rwh_05::WaylandDisplayHandle::empty();
      display_handle.display = unsafe {
        gdk_wayland_sys::gdk_wayland_display_get_wl_display(self.window.display().as_ptr() as *mut _)
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
  #[inline]
  pub fn raw_window_handle_rwh_06(&self) -> Result<rwh_06::RawWindowHandle, rwh_06::HandleError> {
    if let Some(window) = self.window.window() {
      if self.is_wayland() {
        let surface =
          unsafe { gdk_wayland_sys::gdk_wayland_window_get_wl_surface(window.as_ptr() as *mut _) };
        let surface = unsafe { std::ptr::NonNull::new_unchecked(surface) };
        let window_handle = rwh_06::WaylandWindowHandle::new(surface);
        Ok(rwh_06::RawWindowHandle::Wayland(window_handle))
      } else {
        #[cfg(feature = "x11")]
        {
          let xid = unsafe { gdk_x11_sys::gdk_x11_window_get_xid(window.as_ptr() as *mut _) };
          let window_handle = rwh_06::XlibWindowHandle::new(xid);
          Ok(rwh_06::RawWindowHandle::Xlib(window_handle))
        }
        #[cfg(not(feature = "x11"))]
        Err(rwh_06::HandleError::Unavailable)
      }
    } else {
      Err(rwh_06::HandleError::Unavailable)
    }
  }

  #[cfg(feature = "rwh_06")]
  #[inline]
  pub fn raw_display_handle_rwh_06(&self) -> Result<rwh_06::RawDisplayHandle, rwh_06::HandleError> {
    if self.is_wayland() {
      let display = unsafe {
        gdk_wayland_sys::gdk_wayland_display_get_wl_display(self.window.display().as_ptr() as *mut _)
      };
      let display = unsafe { std::ptr::NonNull::new_unchecked(display) };
      let display_handle = rwh_06::WaylandDisplayHandle::new(display);
      Ok(rwh_06::RawDisplayHandle::Wayland(display_handle))
    } else {
      #[cfg(feature = "x11")]
      if let Ok(xlib) = x11_dl::xlib::Xlib::open() {
        unsafe {
          let display = (xlib.XOpenDisplay)(std::ptr::null());
          let screen = (xlib.XDefaultScreen)(display) as _;
          let display = std::ptr::NonNull::new_unchecked(display as _);
          let display_handle = rwh_06::XlibDisplayHandle::new(Some(display), screen);
          Ok(rwh_06::RawDisplayHandle::Xlib(display_handle))
        }
      } else {
        Err(rwh_06::HandleError::Unavailable)
      }
      #[cfg(not(feature = "x11"))]
      Err(rwh_06::HandleError::Unavailable)
    }
  }

  pub fn set_skip_taskbar(&self, skip: bool) -> Result<(), ExternalError> {
    if let Err(e) = self
      .window_requests_tx
      .send((self.window_id, WindowRequest::SetSkipTaskbar(skip)))
    {
      log::warn!("Fail to send skip taskbar request: {}", e);
    }

    Ok(())
  }

  pub fn set_progress_bar(&self, progress: ProgressBarState) {
    if let Err(e) = self
      .window_requests_tx
      .send((WindowId::dummy(), WindowRequest::ProgressBarState(progress)))
    {
      log::warn!("Fail to send update progress bar request: {}", e);
    }
  }

  pub fn set_badge_count(&self, count: Option<i64>, desktop_filename: Option<String>) {
    if let Err(e) = self.window_requests_tx.send((
      WindowId::dummy(),
      WindowRequest::BadgeCount(count, desktop_filename),
    )) {
      log::warn!("Fail to send update badge count request: {}", e);
    }
  }

  pub fn theme(&self) -> Theme {
    if let Some(theme) = *self.preferred_theme.borrow() {
      return theme;
    }

    #[cfg(feature = "dbus")]
    if let Ok(portal_theme) = super::portal::theme() {
      return portal_theme;
    }

    Theme::Light
  }

  pub fn set_theme(&self, theme: Option<Theme>) {
    *self.preferred_theme.borrow_mut() = theme;
    if let Err(e) = self
      .window_requests_tx
      .send((WindowId::dummy(), WindowRequest::SetTheme(theme)))
    {
      log::warn!("Fail to send set theme request: {e}");
    }
  }
}

// We need GtkWindow to initialize WebView, so we have to keep it in the field.
// It is called on any method.
unsafe impl Send for Window {}
unsafe impl Sync for Window {}

#[non_exhaustive]
pub enum WindowRequest {
  Title(String),
  Position((i32, i32)),
  Size((i32, i32)),
  SizeConstraints(WindowSizeConstraints),
  Visible(bool),
  Focus,
  Resizable(bool),
  Closable(bool),
  Minimized(bool),
  Maximized(bool, bool),
  DragWindow,
  DragResizeWindow(ResizeDirection),
  Fullscreen(Option<Fullscreen>),
  Decorations(bool),
  AlwaysOnBottom(bool),
  AlwaysOnTop(bool),
  WindowIcon(Option<Icon>),
  UserAttention(Option<UserAttentionType>),
  SetSkipTaskbar(bool),
  CursorIcon(Option<CursorIcon>),
  CursorPosition((i32, i32)),
  CursorIgnoreEvents(bool),
  /// Nucleus patch (nucleusframework#558): the rectangle the caret occupies,
  /// in window-local logical pixels, for the input method to steer clear of.
  SetImeCursorArea((i32, i32, i32, i32)),
  WireUpEvents {
    transparent: bool,
    fullscreen: bool,
    cursor_moved: bool,
  },
  SetVisibleOnAllWorkspaces(bool),
  ProgressBarState(ProgressBarState),
  BadgeCount(Option<i64>, Option<String>),
  SetTheme(Option<Theme>),
  BackgroundColor(CssProvider, Option<RGBA>),
}

impl Drop for Window {
  fn drop(&mut self) {
    unsafe {
      self.window.destroy();
    }
  }
}
