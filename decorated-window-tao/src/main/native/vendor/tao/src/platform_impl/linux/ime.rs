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

use std::collections::{HashMap, HashSet};

use crate::keyboard::ModifiersState;

/// What the input method's `commit` signal should turn into.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Commit {
  /// Confirmed a composition: replaces the preedit Compose is showing.
  Ime,
  /// Ordinary character insert with no composition behind it — an input
  /// method still delivers plain typing this way once it is in the path.
  Text,
  /// Ordinary character insert produced by the key press being filtered.
  /// Nothing to send yet: [`ImeState::key_pressed`] hands the text back, to
  /// follow the press.
  Typed,
}

/// What [`ImeState::key_pressed`] decided for a key press.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum KeyPress {
  /// The input method consumed it; Compose sees neither it nor its release.
  Withheld,
  /// It goes to Compose, and `typed` — the text it produced, empty for a key
  /// that types nothing — follows it.
  Delivered { typed: String },
}

/// What [`ImeState`] remembers about a key that is physically held.
#[derive(Debug, Clone, Copy, Default)]
struct HeldKey {
  /// Whether its latest press was an auto-repeat.
  repeat: bool,
  /// Whether that press was withheld from Compose. An input method that
  /// answers asynchronously (ibus without its key snooper) claims a
  /// press, then hands the same event back once the engine has declined it;
  /// that second sighting is the press itself, not a repeat of it.
  withheld: bool,
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
  /// Releases the input method claimed while their press was paired, by
  /// keycode, with the time GDK stamped on the event.
  ///
  /// An input method that answers asynchronously claims a release, then hands
  /// the same event back once the engine has declined it, and that second
  /// sighting is the one Compose gets. One that answers on the spot keeps the
  /// release for good. The two look alike when the release is claimed, so the
  /// pairing waits here instead of in `pressed`: only the same event coming
  /// back takes it, and a later release of that key with no press behind it
  /// stays unpaired. The event time is what tells the same event from a new
  /// one. It could not do that for a press (a Wayland auto-repeat carries the
  /// time of the press it repeats), but a release has no repeats.
  claimed_releases: HashMap<u16, u32>,
  /// Text the input method committed while it was filtering the current key
  /// press, or `None` when no press is being filtered or a composition is
  /// open (the press began inside one, or opened one).
  ///
  /// With no engine composing, `gtk_im_context_filter_keypress` turns a
  /// printable key into text on the spot: it emits `commit` and claims the
  /// press. That key was typed, not consumed. Holding the text here is what
  /// lets [`Self::key_pressed`] tell the two apart, and lets the press reach
  /// Compose ahead of the text it produced — AWT's KEY_PRESSED, KEY_TYPED.
  typed: Option<String>,
  /// Hardware keycodes physically held, filtered or not — what tells a held
  /// key's auto-repeat from a new press. GDK reports neither flag nor count:
  /// a held key arrives as a run of presses and one release, on X11 (GDK turns
  /// detectable auto-repeat on, or drops the paired releases itself) and on
  /// Wayland (GDK's own repeat timer only emits presses). Cleared when the
  /// window loses the focus, since a key released elsewhere never reports
  /// its release here.
  held: HashMap<u16, HeldKey>,
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

  /// Returns text the press being filtered committed before it opened this
  /// composition, to be sent now, ahead of the preedit.
  ///
  /// That press is the input method's, not typed: it is withheld like any
  /// press that opens a composition, and its earlier text has to reach
  /// Compose before the preedit rather than land inside it.
  pub(crate) fn preedit_started(&mut self) -> Option<String> {
    self.composing = true;
    self.typed.take().filter(|typed| !typed.is_empty())
  }

  pub(crate) fn preedit_ended(&mut self) {
    self.composing = false;
  }

  /// Which event the `commit` signal carrying `text` should produce.
  pub(crate) fn commit(&mut self, text: &str) -> Commit {
    if self.composing {
      return Commit::Ime;
    }
    match self.typed.as_mut() {
      Some(typed) => {
        typed.push_str(text);
        Commit::Typed
      }
      None => Commit::Text,
    }
  }

  /// Call before handing a key press to `gtk_im_context_filter_keypress`:
  /// text committed until [`Self::key_pressed`] belongs to that press.
  ///
  /// Not so for a press made while a composition is open. That press is the
  /// input method's whatever it commits: GTK closes its own sequences
  /// (`preedit-end`) just before the `commit` they produce, so the Return
  /// that confirms a Ctrl+Shift+U code point would otherwise look typed and
  /// reach Compose as a newline.
  pub(crate) fn filter_started(&mut self) {
    self.typed = (!self.composing).then(String::new);
  }

  /// What a key press turns into. `filtered` is what
  /// `gtk_im_context_filter_keypress` returned for it.
  ///
  /// A press the input method claimed is withheld, unless all it did was
  /// type: then the press is delivered like any other, followed by its text.
  pub(crate) fn key_pressed(&mut self, keycode: u16, filtered: bool) -> KeyPress {
    let typed = self.typed.take().unwrap_or_default();
    if filtered && typed.is_empty() {
      if let Some(held) = self.held.get_mut(&keycode) {
        held.withheld = true;
      }
      return KeyPress::Withheld;
    }
    self.pressed.insert(keycode);
    KeyPress::Delivered { typed }
  }

  /// Records a key press and answers whether it is an auto-repeat, i.e. the
  /// key was already held. Called for every press, before the input method.
  ///
  /// A press that follows a withheld one is that same press handed back by
  /// the input method (see [`HeldKey::withheld`]), and keeps its answer.
  pub(crate) fn press_is_repeat(&mut self, keycode: u16) -> bool {
    match self.held.get_mut(&keycode) {
      Some(held) if held.withheld => held.withheld = false,
      Some(held) => held.repeat = true,
      None => {
        self.held.insert(keycode, HeldKey::default());
      }
    }
    self.held[&keycode].repeat
  }

  /// Records a key release; see [`Self::press_is_repeat`].
  pub(crate) fn release_held(&mut self, keycode: u16) {
    self.held.remove(&keycode);
  }

  /// Forgets every held key: their releases go to whichever window has the focus.
  pub(crate) fn focus_lost(&mut self) {
    self.held.clear();
  }

  /// Whether a key release should reach Compose. Releases whose press was
  /// withheld are dropped, so Compose never sees a KeyUp without its KeyDown.
  ///
  /// A release the input method claimed is dropped too, but its pairing is
  /// kept for that same event (same `time`, GDK's timestamp) in case the
  /// input method hands it back; see `claimed_releases`.
  pub(crate) fn key_released(&mut self, keycode: u16, time: u32, filtered: bool) -> bool {
    if filtered {
      if self.pressed.remove(&keycode) {
        self.claimed_releases.insert(keycode, time);
      }
      return false;
    }
    // A handed-back release answers for the press it was claimed from. A
    // newer press of the key, delivered meanwhile, keeps its own pairing.
    if self.claimed_releases.get(&keycode) == Some(&time) {
      self.claimed_releases.remove(&keycode);
      return true;
    }
    self.pressed.remove(&keycode)
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
  /// Event times, in the order the events happened.
  const T0: u32 = 1_000;
  const T1: u32 = 1_100;
  const T2: u32 = 1_200;

  fn delivered(typed: &str) -> KeyPress {
    KeyPress::Delivered {
      typed: typed.to_string(),
    }
  }

  #[test]
  fn plain_typing_round_trips() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(s.key_released(KEY_A, T0, false));
  }

  /// With no engine composing, GTK's input method turns a printable key into
  /// text itself: `commit` fires inside `filter_keypress`, which then claims
  /// the press. Compose must still get the press, then the text, then the
  /// release — a press that only ever showed up as text left "focus the field
  /// on key-down" handlers dead for every printable key.
  #[test]
  fn typed_key_is_delivered_ahead_of_its_text() {
    let mut s = ImeState::new();
    s.filter_started();
    assert_eq!(s.commit("q"), Commit::Typed);
    assert_eq!(s.key_pressed(KEY_A, true), delivered("q"));
    assert!(s.key_released(KEY_A, T0, false));
  }

  /// A key the engine consumed without typing anything (a dead key, a key
  /// that only extended a composition) stays withheld.
  #[test]
  fn filtered_press_that_types_nothing_is_withheld() {
    let mut s = ImeState::new();
    s.filter_started();
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    assert!(!s.key_released(KEY_A, T0, false));
  }

  /// GTK's own sequences end just before they commit: the Return that
  /// confirms a Ctrl+Shift+U code point arrives with the composition open,
  /// then `preedit-end`, then `commit`. It was not typed, and must not reach
  /// Compose as a newline after the character.
  #[test]
  fn key_that_ends_a_sequence_is_withheld() {
    let mut s = ImeState::new();
    s.preedit_started();
    s.filter_started();
    s.preedit_ended();
    assert_eq!(s.commit("\u{e9}"), Commit::Text);
    assert_eq!(s.key_pressed(KEY_RETURN, true), KeyPress::Withheld);
    assert!(!s.key_released(KEY_RETURN, T0, false));
  }

  /// An input method that answers asynchronously (ibus without its key
  /// snooper) claims the press, then hands the same event back once
  /// the engine has declined it; the release goes the same way. Compose gets
  /// one fresh press, its text and one release — not a "repeat" and no lost
  /// key-up.
  #[test]
  fn press_handed_back_by_the_input_method_is_not_a_repeat() {
    let mut s = ImeState::new();
    // First sighting: sent to the engine, claimed for now.
    assert!(!s.press_is_repeat(KEY_A));
    s.filter_started();
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    // Handed back: the fallback context types it.
    assert!(!s.press_is_repeat(KEY_A));
    s.filter_started();
    assert_eq!(s.commit("a"), Commit::Typed);
    assert_eq!(s.key_pressed(KEY_A, true), delivered("a"));
    // A real auto-repeat, which makes the same round trip.
    assert!(s.press_is_repeat(KEY_A));
    s.filter_started();
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    assert!(s.press_is_repeat(KEY_A));
    s.filter_started();
    assert_eq!(s.commit("a"), Commit::Typed);
    assert_eq!(s.key_pressed(KEY_A, true), delivered("a"));
    // The release: claimed, then handed back.
    s.release_held(KEY_A);
    assert!(!s.key_released(KEY_A, T0, true));
    s.release_held(KEY_A);
    assert!(s.key_released(KEY_A, T0, false));
    assert!(!s.press_is_repeat(KEY_A));
  }

  /// A release the input method kept for good must not pair with a later
  /// release of that key. On Wayland the compositor withholds the press of
  /// the Return that confirms a conversion and delivers its release anyway:
  /// that release is a new event, and Compose must not see it.
  #[test]
  fn release_kept_by_the_input_method_pairs_with_nothing_later() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_RETURN, false), delivered(""));
    assert!(!s.key_released(KEY_RETURN, T0, true));
    // No press in between: the compositor kept it.
    assert!(!s.key_released(KEY_RETURN, T1, false));
    // Nor does a withheld press bring the pairing back.
    assert_eq!(s.key_pressed(KEY_RETURN, true), KeyPress::Withheld);
    assert!(!s.key_released(KEY_RETURN, T2, false));
  }

  /// A key tapped twice faster than an asynchronous input method answers:
  /// the second press is claimed before the first release is handed back.
  /// Each press still gets its own key-up.
  #[test]
  fn handed_back_release_survives_the_next_press_of_its_key() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(!s.key_released(KEY_A, T0, true));
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    assert!(s.key_released(KEY_A, T0, false));
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(!s.key_released(KEY_A, T1, true));
    assert!(s.key_released(KEY_A, T1, false));
    // Handed back once, not twice.
    assert!(!s.key_released(KEY_A, T1, false));
  }

  /// A press delivered before the input method hands back the release of
  /// the press before it keeps its own pairing: each key-down gets its
  /// key-up, and the key does not stay held in Compose.
  #[test]
  fn handed_back_release_leaves_a_newer_press_paired() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(!s.key_released(KEY_A, T0, true));
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(s.key_released(KEY_A, T0, false));
    assert!(s.key_released(KEY_A, T1, false));
  }

  /// A key Compose has the key-down of keeps its key-up when a later repeat
  /// of it is withheld.
  #[test]
  fn withheld_repeat_keeps_the_pairing_of_the_press_before_it() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    assert!(s.key_released(KEY_A, T0, false));
  }

  /// Text that confirms a composition is the input method's, not the key's,
  /// even when it lands while a press is being filtered.
  #[test]
  fn composition_commit_during_a_filter_is_not_typed() {
    let mut s = ImeState::new();
    s.preedit_started();
    s.filter_started();
    assert_eq!(s.commit("\u{65e5}\u{672c}"), Commit::Ime);
    assert_eq!(s.key_pressed(KEY_RETURN, true), KeyPress::Withheld);
  }

  /// A press that commits text and then opens a composition was consumed,
  /// not typed: it is withheld, and its text goes out before the preedit
  /// instead of after it.
  #[test]
  fn press_that_commits_then_composes_is_withheld() {
    let mut s = ImeState::new();
    s.filter_started();
    assert_eq!(s.commit("q"), Commit::Typed);
    assert_eq!(s.preedit_started(), Some("q".to_string()));
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
    assert!(!s.key_released(KEY_A, T0, false));
  }

  /// A press that only opens a composition has no text to send ahead of it.
  #[test]
  fn press_that_only_composes_sends_nothing_ahead() {
    let mut s = ImeState::new();
    s.filter_started();
    assert_eq!(s.preedit_started(), None);
    assert_eq!(s.key_pressed(KEY_A, true), KeyPress::Withheld);
  }

  /// Text committed with no key press being filtered (an engine answering
  /// asynchronously, an on-screen keyboard) has no press to follow.
  #[test]
  fn commit_outside_a_filter_is_sent_at_once() {
    let mut s = ImeState::new();
    assert_eq!(s.commit("q"), Commit::Text);
    s.filter_started();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    // The filter window closed with the press.
    assert_eq!(s.commit("q"), Commit::Text);
  }

  /// Wayland: the compositor withholds the press of a key the input method
  /// consumed and delivers the release anyway. Compose must see neither.
  #[test]
  fn release_without_a_press_is_dropped() {
    let mut s = ImeState::new();
    assert!(!s.key_released(KEY_RETURN, T0, false));
  }

  /// X11: the press reaches the client but `filter_keypress` claims it. The
  /// release that follows must not leak either.
  #[test]
  fn filtered_press_withholds_its_release() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_BACKSPACE, true), KeyPress::Withheld);
    assert!(!s.key_released(KEY_BACKSPACE, T0, false));
  }

  /// The observed failure: confirming a conversion with Return delivered only
  /// the release, which Compose read as a click on the focused control.
  #[test]
  fn confirming_return_does_not_reach_compose() {
    let mut s = ImeState::new();
    s.preedit_started();
    assert_eq!(s.commit("x"), Commit::Ime);
    s.preedit_ended();
    // Only the release arrives; its press was consumed by the input method.
    assert!(!s.key_released(KEY_RETURN, T0, false));
    // The next Return is a real one and must go through.
    assert_eq!(s.key_pressed(KEY_RETURN, false), delivered(""));
    assert!(s.key_released(KEY_RETURN, T0, false));
  }

  #[test]
  fn autorepeat_press_stays_paired() {
    let mut s = ImeState::new();
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(s.key_released(KEY_A, T0, false));
    // The repeat collapsed into one entry, so a second release is unpaired.
    assert!(!s.key_released(KEY_A, T0, false));
  }

  #[test]
  fn held_key_presses_are_repeats() {
    let mut s = ImeState::new();
    assert!(!s.press_is_repeat(KEY_A));
    assert!(s.press_is_repeat(KEY_A));
    assert!(s.press_is_repeat(KEY_A));
    s.release_held(KEY_A);
    assert!(!s.press_is_repeat(KEY_A));
  }

  #[test]
  fn rollover_is_not_a_repeat() {
    let mut s = ImeState::new();
    assert!(!s.press_is_repeat(KEY_CTRL));
    assert!(!s.press_is_repeat(KEY_A));
    assert!(s.press_is_repeat(KEY_A));
    s.release_held(KEY_A);
    assert!(!s.press_is_repeat(KEY_A));
  }

  #[test]
  fn focus_loss_forgets_held_keys() {
    let mut s = ImeState::new();
    assert!(!s.press_is_repeat(KEY_A));
    // Released while another window had the focus: no release here.
    s.focus_lost();
    assert!(!s.press_is_repeat(KEY_A));
  }

  #[test]
  fn commit_routes_on_composition_state() {
    let mut s = ImeState::new();
    // Plain typing through the input method, no composition open.
    assert_eq!(s.commit("x"), Commit::Text);
    s.preedit_started();
    assert_eq!(s.commit("x"), Commit::Ime);
    s.preedit_ended();
    assert_eq!(s.commit("x"), Commit::Text);
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
    assert_eq!(s.key_pressed(KEY_CTRL, false), delivered(""));
    assert_eq!(
      s.modifiers_changed(ModifiersState::CONTROL),
      Some(ModifiersState::CONTROL)
    );
    assert_eq!(s.key_pressed(KEY_A, false), delivered(""));
    assert!(s.key_released(KEY_A, T0, false));
    assert!(s.key_released(KEY_CTRL, T0, false));
    assert_eq!(
      s.modifiers_changed(ModifiersState::empty()),
      Some(ModifiersState::empty())
    );
  }
}
