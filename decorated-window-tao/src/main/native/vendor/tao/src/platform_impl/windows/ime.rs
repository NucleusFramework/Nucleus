//! IMM32-based IME support (nucleusframework#558).
//!
//! Replaces the previous `minimal_ime` handler, which processed only
//! `WM_IME_ENDCOMPOSITION` + `WM_CHAR`: the committed text reached the app,
//! but the preedit was left entirely to the IME's own floating window and the
//! composition was invisible to the embedder. Modelled on winit's current
//! Windows IME implementation, adapted to the `ImePreedit` / `ImeCommit`
//! vocabulary tao gained for macOS in nucleusframework#595.

use std::{
  ffi::{c_void, OsString},
  os::windows::ffi::OsStringExt,
};

use windows::Win32::{
  Foundation::{HWND, LPARAM, LRESULT, WPARAM},
  UI::{
    Input::Ime::{
      ImmGetCompositionStringW, ImmGetContext, ImmReleaseContext, GCS_COMPSTR, GCS_RESULTSTR, HIMC,
      IME_COMPOSITION_STRING, ISC_SHOWUICOMPOSITIONWINDOW,
    },
    WindowsAndMessaging::{self as win32wm, DefWindowProcW},
  },
};

use crate::platform_impl::platform::event_loop::ProcResult;

/// High surrogates occupy `0xD800..=0xDBFF`; a UTF-16 code unit in that range
/// is only half a character and has to be joined with the unit that follows.
const HIGH_SURROGATE_RANGE: std::ops::Range<u16> = 0xD800..0xDC00;

pub fn is_msg_ime_related(msg_kind: u32) -> bool {
  matches!(
    msg_kind,
    win32wm::WM_IME_COMPOSITION
      | win32wm::WM_IME_COMPOSITIONFULL
      | win32wm::WM_IME_STARTCOMPOSITION
      | win32wm::WM_IME_ENDCOMPOSITION
      | win32wm::WM_IME_SETCONTEXT
      | win32wm::WM_IME_CHAR
      | win32wm::WM_CHAR
      | win32wm::WM_SYSCHAR
  )
}

/// What one window message produced. A single `WM_IME_COMPOSITION` can yield
/// both a commit and a fresh preedit, hence the `Vec` in
/// [`ImeHandler::process_message`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImeEvent {
  /// The composition changed. An empty string ends it without committing
  /// (matches the macOS `unmarkText` semantics of `WindowEvent::ImePreedit`).
  Preedit(String),
  /// The composition was committed and replaces the preedit.
  Commit(String),
  /// An ordinary character typed outside any composition.
  Text(String),
}

/// A borrowed input context for a window, released on drop.
struct ImeContext {
  hwnd: HWND,
  himc: HIMC,
}

impl ImeContext {
  unsafe fn current(hwnd: HWND) -> Self {
    let himc = unsafe { ImmGetContext(hwnd) };
    ImeContext { hwnd, himc }
  }

  unsafe fn composing_text(&self) -> Option<String> {
    unsafe { self.composition_string(GCS_COMPSTR) }
  }

  unsafe fn composed_text(&self) -> Option<String> {
    unsafe { self.composition_string(GCS_RESULTSTR) }
  }

  unsafe fn composition_string(&self, gcs_mode: IME_COMPOSITION_STRING) -> Option<String> {
    let data = unsafe { self.composition_data(gcs_mode) }?;
    if data.is_empty() {
      return Some(String::new());
    }
    // `ImmGetCompositionStringW` writes UTF-16, but the API is byte-oriented.
    let (prefix, units, suffix) = unsafe { data.align_to::<u16>() };
    if prefix.is_empty() && suffix.is_empty() {
      OsString::from_wide(units).into_string().ok()
    } else {
      None
    }
  }

  unsafe fn composition_data(&self, gcs_mode: IME_COMPOSITION_STRING) -> Option<Vec<u8>> {
    let size = match unsafe { ImmGetCompositionStringW(self.himc, gcs_mode, None, 0) } {
      0 => return Some(Vec::new()),
      size if size < 0 => return None,
      size => size,
    };

    let mut buf = Vec::<u8>::with_capacity(size as usize);
    let size = unsafe {
      ImmGetCompositionStringW(
        self.himc,
        gcs_mode,
        Some(buf.as_mut_ptr() as *mut c_void),
        size as u32,
      )
    };

    if size < 0 {
      None
    } else {
      // SAFETY: the call above wrote exactly `size` bytes into the buffer.
      unsafe { buf.set_len(size as usize) };
      Some(buf)
    }
  }
}

impl Drop for ImeContext {
  fn drop(&mut self) {
    unsafe {
      let _ = ImmReleaseContext(self.hwnd, self.himc);
    }
  }
}

/// Tracks the composition across the window messages that make one up.
pub struct ImeHandler {
  /// True between the first preedit and the commit (or cancellation).
  composing: bool,
  /// UTF-16 code units the IME is about to replay as `WM_IME_CHAR` /
  /// `WM_CHAR` after a commit. That text already reached the app through
  /// [`ImeEvent::Commit`], so the replay has to be swallowed or every
  /// committed character is inserted twice.
  pending_commit_units: usize,
  /// Half of a surrogate pair waiting for the `WM_CHAR` carrying its other
  /// half.
  pending_high_surrogate: Option<u16>,
}

impl Default for ImeHandler {
  fn default() -> Self {
    ImeHandler {
      composing: false,
      pending_commit_units: 0,
      pending_high_surrogate: None,
    }
  }
}

impl ImeHandler {
  pub(crate) fn process_message(
    &mut self,
    hwnd: HWND,
    msg_kind: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    result: &mut ProcResult,
  ) -> Vec<ImeEvent> {
    match msg_kind {
      win32wm::WM_IME_SETCONTEXT => {
        // Clear the flag that asks the IME to draw its own composition
        // window: the preedit is rendered inline by the embedder, and both at
        // once shows the text twice. The candidate list keeps its own window
        // (positioned through `Window::set_ime_position`).
        let lparam = LPARAM(lparam.0 & !(ISC_SHOWUICOMPOSITIONWINDOW as isize));
        *result = ProcResult::Value(unsafe { DefWindowProcW(hwnd, msg_kind, wparam, lparam) });
        Vec::new()
      }
      win32wm::WM_IME_STARTCOMPOSITION => {
        self.composing = true;
        self.pending_high_surrogate = None;
        Vec::new()
      }
      win32wm::WM_IME_COMPOSITION => {
        // Returning 0 without the default handler is what keeps the IME from
        // painting the composition string itself.
        *result = ProcResult::Value(LRESULT(0));

        if lparam.0 == 0 {
          // The composition was cancelled without committing anything.
          self.composing = false;
          return vec![ImeEvent::Preedit(String::new())];
        }

        let flags = lparam.0 as u32;
        let context = unsafe { ImeContext::current(hwnd) };
        let mut events = Vec::new();

        // Google Japanese Input and ATOK set both flags on the same message,
        // so the committed text has to be taken before the new preedit.
        if flags & GCS_RESULTSTR.0 != 0 {
          if let Some(text) = unsafe { context.composed_text() } {
            if !text.is_empty() {
              self.pending_commit_units += text.encode_utf16().count();
              self.composing = false;
              events.push(ImeEvent::Commit(text));
            }
          }
        }

        if flags & GCS_COMPSTR.0 != 0 {
          if let Some(text) = unsafe { context.composing_text() } {
            self.composing = !text.is_empty();
            events.push(ImeEvent::Preedit(text));
          }
        }

        events
      }
      win32wm::WM_IME_ENDCOMPOSITION => {
        // The Hangul IME sends `WM_IME_COMPOSITION` *after* this message, so
        // a still-open composition may yet be committed. Leave `composing`
        // for that path and only clear a preedit that nothing committed.
        let context = unsafe { ImeContext::current(hwnd) };
        let mut events = Vec::new();

        if self.composing {
          if let Some(text) = unsafe { context.composed_text() } {
            if !text.is_empty() {
              self.pending_commit_units += text.encode_utf16().count();
              events.push(ImeEvent::Commit(text));
            }
          }
          if events.is_empty() {
            events.push(ImeEvent::Preedit(String::new()));
          }
        }

        self.composing = false;
        events
      }
      win32wm::WM_IME_CHAR => {
        // The committed text already travelled through `ImeEvent::Commit`.
        // Swallowing this message also stops the default handler from turning
        // it into a `WM_CHAR`.
        *result = ProcResult::Value(LRESULT(0));
        self.pending_commit_units = self.pending_commit_units.saturating_sub(1);
        Vec::new()
      }
      win32wm::WM_CHAR | win32wm::WM_SYSCHAR => {
        *result = ProcResult::Value(LRESULT(0));

        let unit = wparam.0 as u16;

        if self.pending_commit_units > 0 {
          self.pending_commit_units -= 1;
          return Vec::new();
        }

        // A character outside the BMP arrives as two messages.
        if let Some(high) = self.pending_high_surrogate.take() {
          return String::from_utf16(&[high, unit])
            .ok()
            .map(ImeEvent::Text)
            .into_iter()
            .collect();
        }
        if HIGH_SURROGATE_RANGE.contains(&unit) {
          self.pending_high_surrogate = Some(unit);
          return Vec::new();
        }

        String::from_utf16(&[unit])
          .ok()
          .map(ImeEvent::Text)
          .into_iter()
          .collect()
      }
      _ => Vec::new(),
    }
  }
}
