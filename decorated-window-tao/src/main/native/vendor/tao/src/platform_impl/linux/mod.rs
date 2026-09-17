// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

#[cfg(feature = "x11")]
mod device;
mod event_loop;
mod icon;
mod ime;
mod keyboard;
mod keycode;
mod monitor;
#[cfg(feature = "dbus")]
mod portal;
mod util;
mod window;

pub mod taskbar;
pub mod wayland;
#[cfg(feature = "x11")]
pub mod x11;

pub use self::keycode::{keycode_from_scancode, keycode_to_scancode};
pub(crate) use event_loop::PlatformSpecificEventLoopAttributes;
pub use event_loop::{EventLoop, EventLoopProxy, EventLoopWindowTarget};
pub use icon::PlatformIcon;
pub use monitor::{MonitorHandle, VideoMode};
pub use window::{Window, WindowId};

use crate::{event::DeviceId as RootDeviceId, keyboard::Key};

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub struct KeyEventExtra {
  pub text_with_all_modifiers: Option<&'static str>,
  pub key_without_modifiers: Key<'static>,
}

#[non_exhaustive]
#[derive(Clone, Default)]
pub enum Parent {
  #[default]
  None,
  ChildOf(gtk::Window),
}

#[derive(Clone)]
pub struct PlatformSpecificWindowBuilderAttributes {
  pub parent: Parent,
  pub skip_taskbar: bool,
  pub auto_transparent: bool,
  pub double_buffered: bool,
  pub app_paintable: bool,
  pub rgba_visual: bool,
  pub cursor_moved: bool,
  pub default_vbox: bool,
  /// Nucleus patch: build a `GTK_WINDOW_POPUP` transient for this window
  /// instead of a `GtkApplicationWindow`. On Wayland GDK maps it as a
  /// `wl_subsurface` of the parent — the only client-positionable window
  /// kind under xdg-shell. For cursor-following overlays (drag ghosts).
  pub popup_transient_for: Option<gtk::Window>,
  /// Nucleus patch: yaru.dart-style hidden-titlebar client-side decorations.
  /// Keeps the toplevel `decorated`, installs a real `GtkHeaderBar` via
  /// `gtk_window_set_titlebar()` (which latches GTK3's `client_decorated`
  /// flag, so GTK draws the theme drop shadow, rounded corners and invisible
  /// resize border), then hides the header bar widget — it takes no space but
  /// CSD stays latched. Exactly the pattern yaru.dart / the Flutter Linux
  /// runner use (`gtk_window_set_titlebar` + `gtk_widget_hide`). Wayland only:
  /// on X11 the EGL embedder renders straight into the toplevel, which is
  /// incompatible with GTK-drawn frame pixels, so the flag is ignored there.
  pub csd_hidden_titlebar: bool,
}

impl Default for PlatformSpecificWindowBuilderAttributes {
  fn default() -> Self {
    Self {
      parent: Default::default(),
      skip_taskbar: Default::default(),
      auto_transparent: true,
      double_buffered: true,
      app_paintable: false,
      rgba_visual: false,
      cursor_moved: true,
      default_vbox: true,
      popup_transient_for: None,
      csd_hidden_titlebar: false,
    }
  }
}

unsafe impl Send for PlatformSpecificWindowBuilderAttributes {}
unsafe impl Sync for PlatformSpecificWindowBuilderAttributes {}

#[derive(Debug, Clone)]
pub struct OsError;

impl std::fmt::Display for OsError {
  fn fmt(&self, _f: &mut std::fmt::Formatter<'_>) -> Result<(), std::fmt::Error> {
    Ok(())
  }
}

#[derive(Debug, Copy, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DeviceId(usize);

impl DeviceId {
  pub unsafe fn dummy() -> Self {
    Self(0)
  }
}

// FIXME: currently we use a dummy device id, find if we can get device id from gtk
pub(crate) const DEVICE_ID: RootDeviceId = RootDeviceId(DeviceId(0));
