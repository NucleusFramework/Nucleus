//! Input-method state for the Linux backend (nucleusframework#558).
//!
//! The GTK signal handlers in [`event_loop`](super::event_loop) own one
//! [`ImeState`] per window and do nothing but translate callbacks into calls on
//! it. Everything that is actually a *decision* — whether a key event reaches
//! Compose, whether committed text replaces a preedit or stands on its own,
//! whether the modifier state needs republishing — lives here, in plain data
//! with no GdkWindow, no input method and no main loop behind it.
//!
//! That split is what makes the behaviour testable. The interesting cases are
//! all sequences (a press withheld but its release delivered, a modifier whose
//! release never arrives, a commit that lands with no composition open), and
//! none of them can be reproduced from a unit test while the state is spread
//! across GTK closures. The Windows backend draws the same line with its
//! `ImeSource` trait; the Linux one needs no trait, because there is nothing to
//! read back — GTK pushes everything.

use std::collections::HashSet;

use crate::keyboard::ModifiersState;

/// What the input method's `commit` signal should turn into.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Commit {
  /// Confirmed a composition: replaces the preedit Compose is showing.
  Ime,
  /// Ordinary character insert with no composition behind it — an input
  /// method still delivers plain typing this way once it is in the path.
  Text,
}

/// Per-window input-method state. See the module docs.
#[derive(Debug, Default)]
pub(crate) struct ImeState {
  /// Whether a composition is in flight (`preedit-start` .. `preedit-end`).
  composing: bool,
  /// Hardware keycodes whose *press* was forwarded to Compose.
  ///
  /// An input method withholds the keys it consumes, but only the press: on
  /// Wayland the compositor filters them out of the stream before the client
  /// sees them (text-input-v3 cannot say "filtered"), and on X11
  /// `gtk_im_context_filter_keypress` does the same in-process. The matching
  /// release arrives either way. Compose fires `clickable`'s onClick on KeyUp,
  /// so a release with no press behind it activates whatever holds focus —
  /// the Return that merely confirmed a conversion would press a button.
  pressed: HashSet<u16>,
  /// Last modifier state published to Compose.
  modifiers: ModifiersState,
}

impl ImeState {
  pub(crate) fn new() -> Self {
    Self::default()
  }

  pub(crate) fn composing(&self) -> bool {
    self.composing
  }

  pub(crate) fn preedit_started(&mut self) {
    self.composing = true;
  }

  pub(crate) fn preedit_ended(&mut self) {
    self.composing = false;
  }

  /// Which event the `commit` signal should produce.
  pub(crate) fn commit(&self) -> Commit {
    if self.composing {
      Commit::Ime
    } else {
      Commit::Text
    }
  }

  /// Whether a key press should reach Compose. `filtered` is what
  /// `gtk_im_context_filter_keypress` returned.
  pub(crate) fn key_pressed(&mut self, keycode: u16, filtered: bool) -> bool {
    if filtered {
      return false;
    }
    self.pressed.insert(keycode);
    true
  }

  /// Whether a key release should reach Compose. Releases whose press was
  /// withheld are dropped, so Compose never sees a KeyUp without its KeyDown.
  pub(crate) fn key_released(&mut self, keycode: u16, filtered: bool) -> bool {
    let paired = self.pressed.remove(&keycode);
    paired && !filtered
  }

  /// The modifier state to publish, or `None` when it did not change.
  ///
  /// Callers pass the state derived from the event's own modifier mask rather
  /// than one accumulated from press/release pairs. GDK reports the live mask
  /// on every event, so a lost release — routine once an input method
  /// re-injects events on X11 — is corrected by the next keystroke instead of
  /// leaving Compose convinced that Ctrl is still down.
  pub(crate) fn modifiers_changed(&mut self, mods: ModifiersState) -> Option<ModifiersState> {
    if mods == self.modifiers {
      return None;
    }
    self.modifiers = mods;
    Some(mods)
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  /// Keycodes are opaque here; these just have to be distinct.
  const KEY_A: u16 = 38;
  const KEY_RETURN: u16 = 36;
  const KEY_BACKSPACE: u16 = 22;
  const KEY_CTRL: u16 = 37;

  #[test]
  fn plain_typing_round_trips() {
    let mut s = ImeState::new();
    assert!(s.key_pressed(KEY_A, false));
    assert!(s.key_released(KEY_A, false));
  }

  /// Wayland: the compositor withholds the press of a key the input method
  /// consumed and delivers the release anyway. Compose must see neither.
  #[test]
  fn release_without_a_press_is_dropped() {
    let mut s = ImeState::new();
    assert!(!s.key_released(KEY_RETURN, false));
  }

  /// X11: the press reaches the client but `filter_keypress` claims it. The
  /// release that follows must not leak either.
  #[test]
  fn filtered_press_withholds_its_release() {
    let mut s = ImeState::new();
    assert!(!s.key_pressed(KEY_BACKSPACE, true));
    assert!(!s.key_released(KEY_BACKSPACE, false));
  }

  /// The observed failure: confirming a conversion with Return delivered only
  /// the release, which Compose read as a click on the focused control.
  #[test]
  fn confirming_return_does_not_reach_compose() {
    let mut s = ImeState::new();
    s.preedit_started();
    assert_eq!(s.commit(), Commit::Ime);
    s.preedit_ended();
    // Only the release arrives; its press was consumed by the input method.
    assert!(!s.key_released(KEY_RETURN, false));
    // The next Return is a real one and must go through.
    assert!(s.key_pressed(KEY_RETURN, false));
    assert!(s.key_released(KEY_RETURN, false));
  }

  #[test]
  fn autorepeat_press_stays_paired() {
    let mut s = ImeState::new();
    assert!(s.key_pressed(KEY_A, false));
    assert!(s.key_pressed(KEY_A, false));
    assert!(s.key_released(KEY_A, false));
    // The repeat collapsed into one entry, so a second release is unpaired.
    assert!(!s.key_released(KEY_A, false));
  }

  #[test]
  fn commit_routes_on_composition_state() {
    let mut s = ImeState::new();
    // Plain typing through the input method, no composition open.
    assert_eq!(s.commit(), Commit::Text);
    s.preedit_started();
    assert_eq!(s.commit(), Commit::Ime);
    s.preedit_ended();
    assert_eq!(s.commit(), Commit::Text);
  }

  #[test]
  fn modifiers_publish_only_on_change() {
    let mut s = ImeState::new();
    assert_eq!(
      s.modifiers_changed(ModifiersState::CONTROL),
      Some(ModifiersState::CONTROL)
    );
    assert_eq!(s.modifiers_changed(ModifiersState::CONTROL), None);
    assert_eq!(
      s.modifiers_changed(ModifiersState::empty()),
      Some(ModifiersState::empty())
    );
  }

  /// A Control release swallowed on the way through the input method used to
  /// leave Compose reading every later Return as Ctrl+Return. Deriving the
  /// state from each event's mask heals it on the next key.
  #[test]
  fn lost_modifier_release_recovers_on_the_next_key() {
    let mut s = ImeState::new();
    assert_eq!(
      s.modifiers_changed(ModifiersState::CONTROL),
      Some(ModifiersState::CONTROL)
    );
    // The release never arrives. The next key carries an empty mask, which is
    // the truth GDK reports.
    assert_eq!(
      s.modifiers_changed(ModifiersState::empty()),
      Some(ModifiersState::empty())
    );
    assert_eq!(s.modifiers_changed(ModifiersState::empty()), None);
  }

  /// Traces from ibus on XWayland answer a `Meta_L` press with an `Alt_L`
  /// release. Pair-based bookkeeping cannot survive that; mask-based state can.
  #[test]
  fn asymmetric_modifier_reports_settle() {
    let mut s = ImeState::new();
    assert_eq!(
      s.modifiers_changed(ModifiersState::SHIFT),
      Some(ModifiersState::SHIFT)
    );
    assert_eq!(
      s.modifiers_changed(ModifiersState::SHIFT | ModifiersState::ALT),
      Some(ModifiersState::SHIFT | ModifiersState::ALT)
    );
    assert_eq!(
      s.modifiers_changed(ModifiersState::empty()),
      Some(ModifiersState::empty())
    );
  }

  /// Shortcuts must survive the gate: the input method claims neither the
  /// modifier nor the letter, so both halves reach Compose.
  #[test]
  fn shortcuts_pass_through() {
    let mut s = ImeState::new();
    assert!(s.key_pressed(KEY_CTRL, false));
    assert_eq!(
      s.modifiers_changed(ModifiersState::CONTROL),
      Some(ModifiersState::CONTROL)
    );
    assert!(s.key_pressed(KEY_A, false));
    assert!(s.key_released(KEY_A, false));
    assert!(s.key_released(KEY_CTRL, false));
    assert_eq!(
      s.modifiers_changed(ModifiersState::empty()),
      Some(ModifiersState::empty())
    );
  }
}
