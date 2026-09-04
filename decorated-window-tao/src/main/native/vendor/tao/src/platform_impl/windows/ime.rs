//! IMM32-based IME support (nucleusframework#558).
//!
//! Replaces the previous `minimal_ime` handler, which processed only
//! `WM_IME_ENDCOMPOSITION` + `WM_CHAR`: the committed text reached the app,
//! but the preedit was left entirely to the IME's own floating window and the
//! composition was invisible to the embedder. Modelled on winit's current
//! Windows IME implementation, adapted to the `ImePreedit` / `ImeCommit`
//! vocabulary tao gained for macOS in nucleusframework#595.

use std::{
  collections::VecDeque,
  ffi::{c_void, OsString},
  mem::MaybeUninit,
  os::windows::ffi::OsStringExt,
};

use windows::Win32::{
  Foundation::{HWND, LPARAM, LRESULT, WPARAM},
  UI::{
    Input::Ime::{
      ImmGetCompositionStringW, ImmGetContext, ImmReleaseContext, GCS_COMPSTR, GCS_RESULTSTR, HIMC,
      IME_COMPOSITION_STRING, ISC_SHOWUICOMPOSITIONWINDOW,
    },
    WindowsAndMessaging::{self as win32wm, DefWindowProcW, PeekMessageW, PM_NOREMOVE},
  },
};

use crate::platform_impl::platform::event_loop::ProcResult;

/// High surrogates occupy `0xD800..=0xDBFF`; a UTF-16 code unit in that range
/// is only half a character and has to be joined with the unit that follows.
const HIGH_SURROGATE_RANGE: std::ops::Range<u16> = 0xD800..0xDC00;

/// Everything [`ImeHandler`] needs from the input context and the message
/// queue, behind a seam.
///
/// The state machine is the part with the interesting behaviour — the order
/// the Korean IME uses, matching the replayed commit, what an empty `lparam`
/// means — and none of it is reachable from a test while the reads go
/// straight to IMM32. The production implementation is [`Imm32Source`]; tests
/// drive the same sequences through a stub.
pub(crate) trait ImeSource {
  /// The composition in progress (`GCS_COMPSTR`).
  fn composing_text(&self) -> Option<String>;

  /// The text the composition committed (`GCS_RESULTSTR`).
  fn composed_text(&self) -> Option<String>;

  /// True when a `WM_IME_COMPOSITION` carrying the committed string is
  /// already queued for this window.
  ///
  /// The Korean IME confirms with Space by sending `WM_IME_ENDCOMPOSITION`
  /// *before* the `WM_IME_COMPOSITION` that carries `GCS_RESULTSTR`, the
  /// reverse of the Chinese and Japanese order. Ending the composition on the
  /// first of those two would drop the text the second one is about to
  /// deliver, so the queue is checked and the end deferred to it. Chromium
  /// does the same (crrev 504d51fc, after Firefox).
  fn commit_is_queued(&self) -> bool;
}

/// Reads the live input context of `hwnd`.
///
/// `commit_queued` is passed in rather than peeked on demand: the peek must
/// happen before the caller takes the window-state lock — see
/// [`peek_commit_queued`].
struct Imm32Source {
  hwnd: HWND,
  commit_queued: bool,
}

impl ImeSource for Imm32Source {
  fn composing_text(&self) -> Option<String> {
    let context = unsafe { ImeContext::current(self.hwnd) };
    unsafe { context.composition_string(GCS_COMPSTR) }
  }

  fn composed_text(&self) -> Option<String> {
    let context = unsafe { ImeContext::current(self.hwnd) };
    unsafe { context.composition_string(GCS_RESULTSTR) }
  }

  fn commit_is_queued(&self) -> bool {
    self.commit_queued
  }
}

/// Answers [`ImeSource::commit_is_queued`] for a real window, by peeking the
/// message queue.
///
/// **Must be called before the window-state lock is taken.** `PeekMessageW`
/// delivers pending cross-thread *sent* messages inline — the kernel re-enters
/// the window procedure through `KiUserCallbackDispatcher` before the peek
/// returns — and nearly every window-procedure arm locks the window state.
/// Peeking while that lock is held therefore deadlocks the event-loop thread
/// against itself: it parks in `WaitOnAddress` and never pumps a message
/// again. Same bug class as the keyboard one in
/// NucleusFramework/Nucleus#640, fixed upstream in tauri-apps/tao#1215.
///
/// Only `WM_IME_ENDCOMPOSITION` consults the queue (see the matching arm in
/// [`ImeHandler::process_message_with`]), so every other message skips the
/// syscall — and the inline message delivery it would trigger.
pub(crate) fn peek_commit_queued(hwnd: HWND, msg_kind: u32) -> bool {
  if msg_kind != win32wm::WM_IME_ENDCOMPOSITION {
    return false;
  }
  let mut msg = MaybeUninit::uninit();
  let has_message = unsafe {
    PeekMessageW(
      msg.as_mut_ptr(),
      Some(hwnd),
      win32wm::WM_IME_COMPOSITION,
      win32wm::WM_IME_COMPOSITION,
      PM_NOREMOVE,
    )
  };
  if !has_message.as_bool() {
    return false;
  }
  let msg = unsafe { msg.assume_init() };
  msg.lParam.0 as u32 & GCS_RESULTSTR.0 != 0
}

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
#[derive(Default)]
pub struct ImeHandler {
  /// True between the first preedit and the commit (or cancellation).
  composing: bool,
  /// The UTF-16 code units the IME is expected to replay as `WM_IME_CHAR` /
  /// `WM_CHAR` right after a commit. That text already reached the app
  /// through [`ImeEvent::Commit`], so the replay has to be swallowed or every
  /// committed character is inserted twice.
  ///
  /// The units are matched rather than merely counted: an IME that does not
  /// replay at all (nothing obliges one to, since the composition message is
  /// answered with 0) would otherwise leave a counter standing, and the next
  /// ordinary keystrokes would be eaten by it. A mismatch means the replay
  /// isn't coming, so the expectation is dropped and the key handled
  /// normally.
  expected_commit_replay: VecDeque<u16>,
  /// Half of a surrogate pair waiting for the `WM_CHAR` carrying its other
  /// half.
  pending_high_surrogate: Option<u16>,
}

impl ImeHandler {
  /// Swallows one replayed code unit, or reports that this one is a genuine
  /// keystroke and clears the stale expectation.
  fn consume_replayed(&mut self, unit: u16) -> bool {
    match self.expected_commit_replay.front() {
      Some(&expected) if expected == unit => {
        self.expected_commit_replay.pop_front();
        true
      }
      Some(_) => {
        self.expected_commit_replay.clear();
        false
      }
      None => false,
    }
  }

  fn expect_commit_replay(&mut self, text: &str) {
    self.expected_commit_replay.clear();
    self.expected_commit_replay.extend(text.encode_utf16());
  }
}

impl ImeHandler {
  /// `commit_queued` must come from [`peek_commit_queued`], called *before*
  /// the caller locked the window state.
  pub(crate) fn process_message(
    &mut self,
    hwnd: HWND,
    msg_kind: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    commit_queued: bool,
    result: &mut ProcResult,
  ) -> Vec<ImeEvent> {
    // `WM_IME_SETCONTEXT` is the one message whose handling *is* the Win32
    // call, so it stays out of the state machine.
    if msg_kind == win32wm::WM_IME_SETCONTEXT {
      // Clear the flag that asks the IME to draw its own composition window:
      // the preedit is rendered inline by the embedder, and both at once
      // shows the text twice. The candidate list keeps its own window
      // (positioned through `Window::set_ime_position`).
      let lparam = LPARAM(lparam.0 & !(ISC_SHOWUICOMPOSITIONWINDOW as isize));
      *result = ProcResult::Value(unsafe { DefWindowProcW(hwnd, msg_kind, wparam, lparam) });
      return Vec::new();
    }

    let source = Imm32Source {
      hwnd,
      commit_queued,
    };
    self.process_message_with(&source, msg_kind, wparam, lparam, result)
  }

  fn process_message_with(
    &mut self,
    source: &dyn ImeSource,
    msg_kind: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    result: &mut ProcResult,
  ) -> Vec<ImeEvent> {
    match msg_kind {
      win32wm::WM_IME_STARTCOMPOSITION => {
        self.composing = true;
        self.pending_high_surrogate = None;
        // Nothing from an earlier composition can still be in flight once a
        // new one starts.
        self.expected_commit_replay.clear();
        Vec::new()
      }
      win32wm::WM_IME_COMPOSITION => {
        // Returning 0 without the default handler is what keeps the IME from
        // painting the composition string itself.
        *result = ProcResult::Value(LRESULT(0));

        if lparam.0 == 0 {
          // Nothing changed and the composition string is empty. Clear the
          // preedit, but leave `composing` alone: whether the composition is
          // still live is decided by START/ENDCOMPOSITION and by `GCS_COMPSTR`
          // below, and this message is not a reliable cancel signal.
          return vec![ImeEvent::Preedit(String::new())];
        }

        let flags = lparam.0 as u32;
        let mut events = Vec::new();

        // Google Japanese Input and ATOK set both flags on the same message,
        // so the committed text has to be taken before the new preedit.
        if flags & GCS_RESULTSTR.0 != 0 {
          if let Some(text) = source.composed_text() {
            if !text.is_empty() {
              self.expect_commit_replay(&text);
              self.composing = false;
              events.push(ImeEvent::Commit(text));
            }
          }
        }

        if flags & GCS_COMPSTR.0 != 0 {
          if let Some(text) = source.composing_text() {
            self.composing = !text.is_empty();
            events.push(ImeEvent::Preedit(text));
          }
        }

        events
      }
      win32wm::WM_IME_ENDCOMPOSITION => {
        // The Korean IME puts this message *before* the one carrying the
        // committed string, so ending the composition here would drop that
        // text. Hand the composition over to the queued message instead.
        if source.commit_is_queued() {
          return Vec::new();
        }

        let mut events = Vec::new();
        if self.composing {
          // Nothing committed: the composition was abandoned, so take the
          // preedit down with it.
          events.push(ImeEvent::Preedit(String::new()));
        }

        self.composing = false;
        events
      }
      win32wm::WM_IME_CHAR => {
        // The committed text already travelled through `ImeEvent::Commit`.
        // Swallowing this message also stops the default handler from turning
        // it into a `WM_CHAR`.
        *result = ProcResult::Value(LRESULT(0));
        self.consume_replayed(wparam.0 as u16);
        Vec::new()
      }
      win32wm::WM_CHAR | win32wm::WM_SYSCHAR => {
        *result = ProcResult::Value(LRESULT(0));

        let unit = wparam.0 as u16;

        if self.consume_replayed(unit) {
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

#[cfg(test)]
mod tests {
  use super::*;

  /// Stands in for the input context and the message queue: the three reads
  /// [`ImeHandler`] makes are answered from here, so the state machine can be
  /// driven through real message sequences with no IME installed.
  #[derive(Default)]
  struct StubSource {
    composing: Option<String>,
    composed: Option<String>,
    commit_queued: bool,
  }

  impl ImeSource for StubSource {
    fn composing_text(&self) -> Option<String> {
      self.composing.clone()
    }

    fn composed_text(&self) -> Option<String> {
      self.composed.clone()
    }

    fn commit_is_queued(&self) -> bool {
      self.commit_queued
    }
  }

  /// Feeds one message and drops the `ProcResult` the caller would consume.
  fn send(
    handler: &mut ImeHandler,
    source: &dyn ImeSource,
    msg: u32,
    wparam: usize,
    lparam: isize,
  ) -> Vec<ImeEvent> {
    let mut result = ProcResult::DefSubclassProc;
    handler.process_message_with(source, msg, WPARAM(wparam), LPARAM(lparam), &mut result)
  }

  fn preedit(text: &str) -> ImeEvent {
    ImeEvent::Preedit(text.to_string())
  }

  fn commit(text: &str) -> ImeEvent {
    ImeEvent::Commit(text.to_string())
  }

  fn text(text: &str) -> ImeEvent {
    ImeEvent::Text(text.to_string())
  }

  /// The Japanese/Chinese order: the composition commits and ends, in that
  /// order.
  #[test]
  fn commits_then_ends() {
    let mut handler = ImeHandler::default();
    let mut source = StubSource {
      composing: Some("に".into()),
      ..Default::default()
    };

    assert!(send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0).is_empty());
    assert_eq!(
      send(
        &mut handler,
        &source,
        win32wm::WM_IME_COMPOSITION,
        0,
        GCS_COMPSTR.0 as isize
      ),
      vec![preedit("に")]
    );

    source.composed = Some("荷".into());
    source.composing = Some(String::new());
    assert_eq!(
      send(
        &mut handler,
        &source,
        win32wm::WM_IME_COMPOSITION,
        0,
        (GCS_RESULTSTR.0 | GCS_COMPSTR.0) as isize
      ),
      vec![commit("荷"), preedit("")],
      "ATOK and Google Japanese Input set both flags on one message",
    );

    // Nothing left to commit, so ending must not emit a stray preedit.
    assert!(send(&mut handler, &source, win32wm::WM_IME_ENDCOMPOSITION, 0, 0).is_empty());
  }

  /// The Korean order: `WM_IME_ENDCOMPOSITION` arrives *before* the message
  /// carrying `GCS_RESULTSTR`, so it must not tear the composition down.
  #[test]
  fn defers_to_a_queued_commit() {
    let mut handler = ImeHandler::default();
    let mut source = StubSource {
      composing: Some("한".into()),
      ..Default::default()
    };

    send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0);
    send(
      &mut handler,
      &source,
      win32wm::WM_IME_COMPOSITION,
      0,
      GCS_COMPSTR.0 as isize,
    );

    // Space confirms: the commit is already queued behind this message.
    source.commit_queued = true;
    assert!(
      send(&mut handler, &source, win32wm::WM_IME_ENDCOMPOSITION, 0, 0).is_empty(),
      "the end must be handed to the queued commit, not clear the preedit",
    );

    source.commit_queued = false;
    source.composed = Some("한".into());
    assert_eq!(
      send(
        &mut handler,
        &source,
        win32wm::WM_IME_COMPOSITION,
        0,
        GCS_RESULTSTR.0 as isize
      ),
      vec![commit("한")]
    );
  }

  /// A composition abandoned without committing takes its preedit with it.
  #[test]
  fn ends_without_commit_clears_the_preedit() {
    let mut handler = ImeHandler::default();
    let source = StubSource {
      composing: Some("に".into()),
      ..Default::default()
    };

    send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0);
    send(
      &mut handler,
      &source,
      win32wm::WM_IME_COMPOSITION,
      0,
      GCS_COMPSTR.0 as isize,
    );

    assert_eq!(
      send(&mut handler, &source, win32wm::WM_IME_ENDCOMPOSITION, 0, 0),
      vec![preedit("")]
    );
  }

  /// The committed text is replayed as `WM_CHAR`; it already reached the app
  /// through `ImeCommit`, so it must not be inserted a second time.
  #[test]
  fn swallows_the_commit_replay() {
    let mut handler = ImeHandler::default();
    let source = StubSource {
      composed: Some("荷".into()),
      ..Default::default()
    };

    send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0);
    send(
      &mut handler,
      &source,
      win32wm::WM_IME_COMPOSITION,
      0,
      GCS_RESULTSTR.0 as isize,
    );

    let replayed = '荷' as usize;
    assert!(send(&mut handler, &source, win32wm::WM_CHAR, replayed, 0).is_empty());
    // The expectation is spent: the same character typed again is real input.
    assert_eq!(
      send(&mut handler, &source, win32wm::WM_CHAR, replayed, 0),
      vec![text("荷")]
    );
  }

  /// Nothing obliges an IME to replay — the composition message is answered
  /// with 0 — so a stale expectation must never eat a real keystroke.
  #[test]
  fn a_missing_replay_does_not_eat_the_next_key() {
    let mut handler = ImeHandler::default();
    let source = StubSource {
      composed: Some("荷".into()),
      ..Default::default()
    };

    send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0);
    send(
      &mut handler,
      &source,
      win32wm::WM_IME_COMPOSITION,
      0,
      GCS_RESULTSTR.0 as isize,
    );

    // The replay never comes; the user types something else.
    assert_eq!(
      send(&mut handler, &source, win32wm::WM_CHAR, 'a' as usize, 0),
      vec![text("a")]
    );
    // And the abandoned expectation is gone, so the next key is untouched.
    assert_eq!(
      send(&mut handler, &source, win32wm::WM_CHAR, 'b' as usize, 0),
      vec![text("b")]
    );
  }

  /// An empty `lparam` says the composition string is empty and nothing more.
  /// It clears the preedit but must not end the composition, which is still
  /// live and can carry on.
  #[test]
  fn empty_lparam_clears_the_preedit_without_ending_the_composition() {
    let mut handler = ImeHandler::default();
    let mut source = StubSource {
      composing: Some("に".into()),
      ..Default::default()
    };

    send(&mut handler, &source, win32wm::WM_IME_STARTCOMPOSITION, 0, 0);
    send(
      &mut handler,
      &source,
      win32wm::WM_IME_COMPOSITION,
      0,
      GCS_COMPSTR.0 as isize,
    );

    assert_eq!(
      send(&mut handler, &source, win32wm::WM_IME_COMPOSITION, 0, 0),
      vec![preedit("")]
    );

    // Still composing: typing on resumes the preedit rather than starting over.
    source.composing = Some("にほ".into());
    assert_eq!(
      send(
        &mut handler,
        &source,
        win32wm::WM_IME_COMPOSITION,
        0,
        GCS_COMPSTR.0 as isize
      ),
      vec![preedit("にほ")]
    );
    assert_eq!(
      send(&mut handler, &source, win32wm::WM_IME_ENDCOMPOSITION, 0, 0),
      vec![preedit("")],
      "the composition was still live, so ending it clears the preedit",
    );
  }

  /// A character outside the BMP arrives as two `WM_CHAR` messages.
  #[test]
  fn joins_surrogate_pairs() {
    let mut handler = ImeHandler::default();
    let source = StubSource::default();

    // U+1F600 GRINNING FACE = D83D DE00
    assert!(send(&mut handler, &source, win32wm::WM_CHAR, 0xD83D, 0).is_empty());
    assert_eq!(
      send(&mut handler, &source, win32wm::WM_CHAR, 0xDE00, 0),
      vec![text("😀")]
    );
  }
}
