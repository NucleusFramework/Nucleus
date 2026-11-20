use crate::{
  dpi::{LogicalPosition, LogicalSize, PhysicalPosition},
  error::ExternalError,
  window::WindowSizeConstraints,
};
use gtk::{
  gdk::{
    self,
    prelude::{DeviceExt, SeatExt},
    Display,
  },
  glib::{self},
  prelude::BinExt,
  traits::{GtkWindowExt, WidgetExt},
};
use std::{cell::RefCell, rc::Rc};

#[inline]
pub fn cursor_position(is_wayland: bool) -> Result<PhysicalPosition<f64>, ExternalError> {
  if is_wayland {
    Ok((0, 0).into())
  } else {
    Display::default()
      .map(|d| {
        (
          d.default_seat().and_then(|s| s.pointer()),
          d.default_group(),
        )
      })
      .map(|(p, g)| {
        p.map(|p| {
          let (_, x, y) = p.position_double();
          LogicalPosition::new(x, y).to_physical(g.scale_factor() as _)
        })
      })
      .map(|p| p.ok_or(ExternalError::Os(os_error!(super::OsError))))
      .ok_or(ExternalError::Os(os_error!(super::OsError)))?
  }
}

pub fn set_size_constraints<W: GtkWindowExt + WidgetExt>(
  window: &W,
  constraints: WindowSizeConstraints,
) {
  let mut geom_mask = gdk::WindowHints::empty();
  if constraints.has_min() {
    geom_mask |= gdk::WindowHints::MIN_SIZE;
  }
  if constraints.has_max() {
    geom_mask |= gdk::WindowHints::MAX_SIZE;
  }

  let scale_factor = window.scale_factor() as f64;

  let min_size: LogicalSize<i32> = constraints.min_size_logical(scale_factor);
  let max_size: LogicalSize<i32> = constraints.max_size_logical(scale_factor);

  let picky_none: Option<&gtk::Window> = None;
  window.set_geometry_hints(
    picky_none,
    Some(&gdk::Geometry::new(
      min_size.width,
      min_size.height,
      max_size.width,
      max_size.height,
      0,
      0,
      0,
      0,
      0f64,
      0f64,
      gdk::Gravity::Center,
    )),
    geom_mask,
  )
}

// PATCH(nucleus): pin the size of a non-resizable window. GTK sizes such a
// window to what its widgets request, so a size request is the channel that
// counts — but on the window itself it covers the client-side decorations
// too: a CSD toplevel on Wayland lost its theme shadow margins (52 px per
// axis on Adwaita/Yaru) off the requested content size. Requesting it on the
// content child leaves GTK to add the decorations, whatever they are (none on
// X11 or on an undecorated window).
pub fn set_content_size_request(window: &gtk::Window, width: i32, height: i32) {
  match window.child() {
    Some(content) => {
      content.set_size_request(width, height);
      // GTK measures a hidden child as nothing, and the content stays hidden
      // until the window's own `show_all`: a window sized before it is shown
      // (wrap content, #532) would otherwise map at a stale size.
      content.show();
      // GTK still applies the last `gtk_window_resize` when a non-resizable
      // window maps: keep it in step, or the creation size wins.
      window.resize(width, height);
    }
    None => window.set_size_request(width, height),
  }
}

// PATCH(nucleus): fetch a real X server timestamp for activation requests.
// gtk_window_present with GDK_CURRENT_TIME (0) is dropped by Mutter's
// focus-stealing prevention (X11 and XWayland): the window is neither raised,
// restored, nor focused — it only gets _NET_WM_STATE_DEMANDS_ATTENTION.
// gdk_x11_get_server_time round-trips a zero-length property change to obtain
// a fresh timestamp, which Mutter accepts. Returns None on Wayland (GTK
// handles activation there) or when the window is not yet realized.
#[cfg(feature = "x11")]
pub fn x11_server_time(gdk_window: &gdk::Window) -> Option<u32> {
  use gtk::prelude::{DisplayExtManual, ObjectType};
  if !gdk_window.display().backend().is_x11() {
    return None;
  }
  Some(unsafe { gdk_x11_sys::gdk_x11_get_server_time(gdk_window.as_ptr() as *mut _) })
}

#[cfg(not(feature = "x11"))]
pub fn x11_server_time(_gdk_window: &gdk::Window) -> Option<u32> {
  None
}

pub struct WindowMaximizeProcess<W: GtkWindowExt + WidgetExt> {
  window: W,
  resizable: bool,
  step: u8,
}

impl<W: GtkWindowExt + WidgetExt> WindowMaximizeProcess<W> {
  pub fn new(window: W, resizable: bool) -> Rc<RefCell<Self>> {
    Rc::new(RefCell::new(Self {
      window,
      resizable,
      step: 0,
    }))
  }

  pub fn next_step(&mut self) -> glib::ControlFlow {
    match self.step {
      0 => {
        self.window.set_resizable(true);
        self.step += 1;
        glib::ControlFlow::Continue
      }
      1 => {
        self.window.maximize();
        self.step += 1;
        glib::ControlFlow::Continue
      }
      2 => {
        self.window.set_resizable(self.resizable);
        glib::ControlFlow::Break
      }
      _ => glib::ControlFlow::Break,
    }
  }
}
