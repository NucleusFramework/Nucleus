// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

mod app;
mod app_delegate;
mod app_state;
mod badge;
mod dock;
mod event;
mod event_loop;
mod ffi;
mod icon;
mod keycode;
mod monitor;
mod observer;
mod progress_bar;
mod util;
mod view;
mod window;
mod window_delegate;

use std::{
  fmt,
  ops::Deref,
  sync::{
    atomic::{AtomicU8, Ordering},
    Arc,
  },
};

use objc2_app_kit::{NSEvent, NSEventSubtype, NSEventType, NSPointingDeviceType};

pub(crate) use self::event_loop::PlatformSpecificEventLoopAttributes;
pub use self::{
  app_delegate::get_aux_state_mut,
  event::KeyEventExtra,
  event_loop::{EventLoop, EventLoopWindowTarget, Proxy as EventLoopProxy},
  keycode::{keycode_from_scancode, keycode_to_scancode},
  monitor::{MonitorHandle, VideoMode},
  progress_bar::set_progress_indicator,
  window::{Id as WindowId, Parent, PlatformSpecificWindowBuilderAttributes, UnownedWindow},
};
use crate::{
  error::OsError as RootOsError, event::DeviceId as RootDeviceId, window::WindowAttributes,
};
pub(crate) use badge::set_badge_label;
pub(crate) use dock::set_dock_visibility;
pub(crate) use icon::PlatformIcon;

// PATCH(nucleus): the id carries the tool of a pointer event (0 = the mouse,
// see `PointerSource`), so the embedder can tell a tablet pen from the mouse.
#[derive(Debug, Copy, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DeviceId(u8);

impl DeviceId {
  pub unsafe fn dummy() -> Self {
    DeviceId(0)
  }
}

// Constant device ID; to be removed when if backend is updated to report real device IDs.
pub(crate) const DEVICE_ID: RootDeviceId = RootDeviceId(DeviceId(0));

// PATCH(nucleus): tablet tools get device ids of their own. AppKit reports a
// pen as ordinary mouse events whose `subtype` is `TabletPoint`; which end of
// the pen is in use is only named by the proximity event that precedes them.
const PEN_DEVICE_ID: RootDeviceId = RootDeviceId(DeviceId(1));
const ERASER_DEVICE_ID: RootDeviceId = RootDeviceId(DeviceId(2));

/// Tool of the last proximity event: the pen tip unless the eraser came in range.
static TABLET_TOOL: AtomicU8 = AtomicU8::new(1);
/// Tool of the last mouse event, for events that carry none (enter, exit, wheel).
static POINTER_TOOL: AtomicU8 = AtomicU8::new(0);

/// Records the tool a tablet proximity event names. Called for every event
/// `NSApp` dispatches, since AppKit hands proximity to no particular view.
pub(crate) fn note_tablet_proximity(event: &NSEvent) {
  let proximity = match event.r#type() {
    NSEventType::TabletProximity => true,
    t if is_mouse_event(t) => event.subtype() == NSEventSubtype::TabletProximity,
    _ => false,
  };
  if proximity && event.isEnteringProximity() {
    let tool = if event.pointingDeviceType() == NSPointingDeviceType::Eraser {
      2
    } else {
      1
    };
    TABLET_TOOL.store(tool, Ordering::Relaxed);
  }
}

/// The device id of a pointer event: its tablet tool, or the mouse.
pub(crate) fn pointer_device_id(event: &NSEvent) -> RootDeviceId {
  let event_type = event.r#type();
  if !is_mouse_event(event_type) {
    // `subtype` throws for these; they belong to the tool of the last one.
    return RootDeviceId(DeviceId(POINTER_TOOL.load(Ordering::Relaxed)));
  }
  let id = match event.subtype() {
    NSEventSubtype::TabletPoint | NSEventSubtype::TabletProximity => {
      if TABLET_TOOL.load(Ordering::Relaxed) == 2 {
        ERASER_DEVICE_ID
      } else {
        PEN_DEVICE_ID
      }
    }
    _ => DEVICE_ID,
  };
  POINTER_TOOL.store(id.0 .0, Ordering::Relaxed);
  id
}

fn is_mouse_event(event_type: NSEventType) -> bool {
  matches!(
    event_type,
    NSEventType::LeftMouseDown
      | NSEventType::LeftMouseUp
      | NSEventType::RightMouseDown
      | NSEventType::RightMouseUp
      | NSEventType::OtherMouseDown
      | NSEventType::OtherMouseUp
      | NSEventType::MouseMoved
      | NSEventType::LeftMouseDragged
      | NSEventType::RightMouseDragged
      | NSEventType::OtherMouseDragged
  )
}

/// Tool behind a pointer event; see [`pointer_device_id`].
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum PointerSource {
  /// A mouse, a trackpad, or any device that is not a tablet tool.
  Mouse,
  /// The tip of a tablet pen.
  Pen,
  /// The eraser end of a tablet pen.
  Eraser,
}

impl DeviceId {
  pub(crate) fn pointer_source(&self) -> PointerSource {
    match self.0 {
      1 => PointerSource::Pen,
      2 => PointerSource::Eraser,
      _ => PointerSource::Mouse,
    }
  }
}

pub struct Window {
  window: Arc<UnownedWindow>,
  // We keep this around so that it doesn't get dropped until the window does.
  #[allow(dead_code)]
  delegate: util::IdRef,
}

#[non_exhaustive]
#[derive(Debug)]
pub enum OsError {
  CGError(core_graphics::base::CGError),
  CreationError(&'static str),
}

unsafe impl Send for Window {}
unsafe impl Sync for Window {}

impl Deref for Window {
  type Target = UnownedWindow;
  #[inline]
  fn deref(&self) -> &Self::Target {
    &self.window
  }
}

impl Window {
  pub fn new<T: 'static>(
    _window_target: &EventLoopWindowTarget<T>,
    attributes: WindowAttributes,
    pl_attribs: PlatformSpecificWindowBuilderAttributes,
  ) -> Result<Self, RootOsError> {
    let (window, delegate) = UnownedWindow::new(attributes, pl_attribs)?;
    Ok(Window { window, delegate })
  }
}

impl fmt::Display for OsError {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      OsError::CGError(e) => f.pad(&format!("CGError {}", e)),
      OsError::CreationError(e) => f.pad(e),
    }
  }
}
