//! Puts dictated words into a text field.
//!
//! A recognizer hands you transcripts, but a text field wants "replace these
//! bytes with this text". [`Dictation`] does the bookkeeping in between: it
//! remembers where the words go, revises the current utterance in place as
//! partial transcripts arrive, keeps a space between speech and whatever the
//! user typed, and copes with the user editing the field mid-sentence without
//! losing or repeating a word. It knows nothing about any UI toolkit; you apply
//! the [`Replacement`]s it returns to your own text field.

use std::ops::Range;

/// One edit to make to the field: replace `range` (byte offsets) with `text`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Replacement {
    pub range: Range<usize>,
    pub text: String,
    /// Whether this revises what an earlier replacement wrote, rather than
    /// starting to write somewhere new. Handy for grouping undo: a whole run of
    /// revisions of one phrase reads best as a single undo step.
    pub continues: bool,
}

/// Where dictated words go in a text field, and what is there now.
///
/// The cycle is: feed each transcript to [`transcript`](Self::transcript),
/// apply the replacement it returns, then call [`applied`](Self::applied).
/// When the user is about to change the field themselves (a keystroke, a click
/// that moves the caret), call [`interrupt`](Self::interrupt) first so nothing
/// is written underneath their edit, and once it has landed call
/// [`settle`](Self::settle) with the field's new text and selection. Dictation
/// then carries on from wherever the caret is, adding only the words that
/// aren't on screen yet. Calling `settle` after every event is fine; it also
/// notices changes you didn't see coming.
#[derive(Clone, Debug)]
pub struct Dictation {
    /// The field's text when we last anchored, and the range of it we write over.
    draft: String,
    start: usize,
    end: usize,
    /// Utterances the recognizer has finished, and the one it is still revising.
    committed: String,
    pending: String,
    /// What has actually been written over `start..end`; `None` until the first
    /// replacement lands.
    shown: Option<String>,
    /// A replacement handed out but not yet confirmed by `applied`.
    offered: Option<String>,
    /// Set while the user is editing. `true` means an utterance was mid-flight
    /// when they started, so we wait for it to finish before re-anchoring.
    interrupted: Option<bool>,
}

impl Dictation {
    /// Speech replaces `selection` (byte offsets into `text`) and carries on from there.
    pub fn new(text: &str, selection: Range<usize>) -> Self {
        let Range { start, end } = ordered(selection);
        let start = floor_char_boundary(text, start);
        let end = floor_char_boundary(text, end);
        Self {
            draft: text.to_owned(),
            start,
            end,
            committed: String::new(),
            pending: String::new(),
            shown: None,
            offered: None,
            interrupted: None,
        }
    }

    /// A transcript from the recognizer. A partial revises the current utterance,
    /// a final commits it. Returns the edit that brings the field up to date, if
    /// there is one; apply it and call [`applied`](Self::applied).
    pub fn transcript(&mut self, text: &str, is_final: bool) -> Option<Replacement> {
        // Never lose words the recognizer already gave us: an empty transcript changes nothing,
        // one that starts over commits what it replaces, and one that stops short keeps the rest.
        let text = text.trim();
        if !text.is_empty() {
            let previous = std::mem::take(&mut self.pending);
            self.pending = text.to_owned();
            if starts_over(&previous, text) {
                append_words(&mut self.committed, &previous);
            } else {
                append_words(&mut self.pending, &words_beyond(text, &previous));
            }
        }
        if is_final {
            let pending = std::mem::take(&mut self.pending);
            append_words(&mut self.committed, &pending);
        }
        if let Some(awaiting_final) = self.interrupted.as_mut() {
            if is_final {
                *awaiting_final = false;
            }
            return None;
        }
        self.offer()
    }

    /// The replacement handed out last has been applied to the field.
    pub fn applied(&mut self) {
        if let Some(offered) = self.offered.take() {
            self.shown = Some(offered);
        }
    }

    /// The user is changing the field or moving the caret. Nothing more is
    /// written until [`settle`](Self::settle) sees their change has landed.
    pub fn interrupt(&mut self) {
        if self.interrupted.is_none() {
            self.interrupted = Some(!self.pending.is_empty());
            self.offered = None;
        }
    }

    pub fn is_interrupted(&self) -> bool {
        self.interrupted.is_some()
    }

    /// The field's text and selection now that the current event has been
    /// handled. Anything we didn't write ourselves counts as an interruption,
    /// and an interruption that turns out to have changed nothing (End with the
    /// caret already at the end, a click on the caret) is called off. Once an
    /// interrupted utterance has finished (or none was in flight), dictation
    /// re-anchors at the caret and returns whatever was said since that isn't
    /// on screen yet, if anything.
    pub fn settle(&mut self, text: &str, selection: Range<usize>) -> Option<Replacement> {
        let selection = ordered(selection);
        if self.matches(text, &selection) {
            self.interrupted = None;
            return self.offer();
        }
        self.interrupt();
        if self.interrupted != Some(false) {
            return None;
        }
        let unseen = words_beyond(self.shown.as_deref().unwrap_or(""), &self.committed);
        *self = Self {
            committed: unseen,
            pending: std::mem::take(&mut self.pending),
            ..Self::new(text, selection)
        };
        self.offer()
    }

    fn offer(&mut self) -> Option<Replacement> {
        let insertion = self.insertion();
        let same = match &self.shown {
            Some(shown) => *shown == insertion,
            None => insertion.is_empty(),
        };
        if same {
            return None;
        }
        let range = match &self.shown {
            Some(shown) => self.start..self.start + shown.len(),
            None => self.start..self.end,
        };
        self.offered = Some(insertion.clone());
        Some(Replacement { range, text: insertion, continues: self.shown.is_some() })
    }

    /// Whether the field holds exactly what we last left in it.
    fn matches(&self, text: &str, selection: &Range<usize>) -> bool {
        let Some(shown) = &self.shown else {
            return text == self.draft && *selection == (self.start..self.end);
        };
        let caret = self.start + shown.len();
        // `get`, not indexing: after an edit, our offsets may fall inside one of the field's characters.
        *selection == (caret..caret)
            && text.len() == self.draft.len() - (self.end - self.start) + shown.len()
            && text.get(..self.start) == Some(&self.draft[..self.start])
            && text.get(self.start..caret) == Some(shown.as_str())
            && text.get(caret..) == Some(&self.draft[self.end..])
    }

    /// Everything said since the anchor, spaced off the draft around it.
    fn insertion(&self) -> String {
        let mut spoken = self.committed.clone();
        append_words(&mut spoken, &self.pending);
        if spoken.is_empty() {
            return spoken;
        }
        if needs_space(&self.draft[..self.start], &spoken) {
            spoken.insert(0, ' ');
        }
        if needs_space(&spoken, &self.draft[self.end..]) {
            spoken.push(' ');
        }
        spoken
    }
}

fn ordered(range: Range<usize>) -> Range<usize> {
    range.start.min(range.end)..range.start.max(range.end)
}

fn floor_char_boundary(text: &str, index: usize) -> usize {
    let mut index = index.min(text.len());
    while !text.is_char_boundary(index) {
        index -= 1;
    }
    index
}

/// Whether `new` starts over rather than revising `old`, like a recognizer that restarted after
/// a pause: it drops most words of a long `old`, rather than reformatting a few ("300 and" to "340").
fn starts_over(old: &str, new: &str) -> bool {
    let (old, new) = (loose_words(old), loose_words(new));
    let kept = old.iter().filter(|word| new.contains(word)).count();
    old.len() >= 6 && !new.is_empty() && new.len() * 2 < old.len() && kept * 4 < old.len()
}

fn loose_words(text: &str) -> Vec<String> {
    text.split_whitespace().map(loose).filter(|word| !word.is_empty()).collect()
}

/// A word compared loosely, because a recognizer routinely re-cases or re-punctuates.
fn loose(word: &str) -> String {
    word.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase()
}

/// The part of `spoken` that goes beyond what the user can already see in
/// `shown`. Words are compared loosely, because a recognizer routinely re-cases
/// or re-punctuates what it already sent. Returns nothing when the two share no
/// leading words at all, so nothing is ever inserted twice.
fn words_beyond(shown: &str, spoken: &str) -> String {
    if shown.trim().is_empty() {
        return spoken.trim().to_owned();
    }
    let shown_words: Vec<_> = shown.split_whitespace().map(loose).collect();
    let mut matched = 0;
    let mut rest = spoken.trim();
    for word in spoken.split_whitespace() {
        if matched >= shown_words.len() || loose(word) != shown_words[matched] {
            break;
        }
        matched += 1;
        rest = rest[word.len()..].trim_start();
    }
    // Every word the user can see must be accounted for. If the revision inserted
    // a word inside them, the tail is not safely separable, so insert nothing.
    if matched < shown_words.len() { String::new() } else { rest.to_owned() }
}

fn append_words(destination: &mut String, text: &str) {
    if needs_space(destination, text) {
        destination.push(' ');
    }
    destination.push_str(text);
}

/// Whether dictated text needs a space to keep it off whatever precedes it.
///
/// Speech joins onto a draft the user may have typed, so the default is to
/// separate them. The exceptions are all cases where jamming them together is
/// what was meant: an opening bracket the user just typed, punctuation that the
/// recognizer itself supplies, and scripts that don't space their words.
/// An apostrophe is only a joiner on the right, for endings like `'s`; a draft
/// that happens to end in one still gets its space.
fn needs_space(left: &str, right: &str) -> bool {
    let (Some(left), Some(right)) = (left.chars().last(), right.chars().next()) else { return false };
    let cjk = |c| matches!(c, '\u{3000}'..='\u{30ff}' | '\u{3400}'..='\u{9fff}' | '\u{ac00}'..='\u{d7af}' | '\u{f900}'..='\u{faff}');
    !left.is_whitespace() && !right.is_whitespace()
        && !matches!(left, '(' | '[' | '{')
        && !matches!(right, '.' | ',' | '!' | '?' | ':' | ';' | ')' | ']' | '}' | '\'' | '\u{2019}')
        && !cjk(left) && !cjk(right)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A stand-in text field: applies each replacement and reports back.
    struct Field {
        text: String,
        caret: usize,
    }

    impl Field {
        fn new(text: &str, caret: usize) -> Self {
            Self { text: text.into(), caret }
        }

        fn apply(&mut self, dictation: &mut Dictation, replacement: Option<Replacement>) {
            if let Some(replacement) = replacement {
                self.text.replace_range(replacement.range.clone(), &replacement.text);
                self.caret = replacement.range.start + replacement.text.len();
                dictation.applied();
            }
        }

        fn say(&mut self, dictation: &mut Dictation, text: &str, is_final: bool) {
            let replacement = dictation.transcript(text, is_final);
            self.apply(dictation, replacement);
        }

        /// The user types at the caret, as a toolkit event would deliver it.
        fn type_text(&mut self, dictation: &mut Dictation, text: &str) {
            dictation.interrupt();
            self.text.insert_str(self.caret, text);
            self.caret += text.len();
            self.settle(dictation);
        }

        fn settle(&mut self, dictation: &mut Dictation) {
            let replacement = dictation.settle(&self.text, self.caret..self.caret);
            self.apply(dictation, replacement);
        }
    }

    #[test]
    fn partials_replace_the_utterance_and_finals_append() {
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "I scream", false);
        assert_eq!(field.text, "I scream");
        field.say(&mut dictation, "Ice cream.", true);
        assert_eq!(field.text, "Ice cream.");
        field.say(&mut dictation, "Please", false);
        assert_eq!(field.text, "Ice cream. Please");
        field.say(&mut dictation, "Please!", true);
        assert_eq!(field.text, "Ice cream. Please!");
        assert_eq!(field.caret, field.text.len());
    }

    #[test]
    fn the_first_replacement_starts_a_run_and_later_ones_continue_it() {
        let mut dictation = Dictation::new("Hi old friend", 3..6);
        let first = dictation.transcript("new", false).unwrap();
        assert_eq!(first, Replacement { range: 3..6, text: "new".into(), continues: false });
        dictation.applied();
        let second = dictation.transcript("dear", false).unwrap();
        assert_eq!(second, Replacement { range: 3..6, text: "dear".into(), continues: true });
        // A revision that changes nothing is not an edit, nor is the final that
        // merely confirms it.
        dictation.applied();
        assert_eq!(dictation.transcript("dear", false), None);
        assert_eq!(dictation.transcript("dear", true), None);
    }

    #[test]
    fn a_replacement_that_never_landed_is_simply_offered_again() {
        let mut dictation = Dictation::new("", 0..0);
        assert_eq!(dictation.transcript("one", false).unwrap().range, 0..0);
        // Not applied, so the field still holds nothing; the next one starts over.
        let again = dictation.transcript("one two", false).unwrap();
        assert_eq!(again, Replacement { range: 0..0, text: "one two".into(), continues: false });
        dictation.applied();
        assert_eq!(dictation.transcript("one two three", true).unwrap().range, 0..7);
    }

    #[test]
    fn speech_is_spaced_off_the_draft_but_not_off_punctuation_or_brackets() {
        let after = |draft: &str, spoken: &str| {
            let mut dictation = Dictation::new(draft, draft.len()..draft.len());
            let mut field = Field::new(draft, draft.len());
            field.say(&mut dictation, spoken, true);
            field.text
        };
        assert_eq!(after("hello", "world"), "hello world");
        assert_eq!(after("hello,", "world"), "hello, world");
        assert_eq!(after("hello'", "world"), "hello' world");
        assert_eq!(after("hello ", "world"), "hello world");
        assert_eq!(after("hello\n", "world"), "hello\nworld");
        assert_eq!(after("hello(", "world"), "hello(world");
        assert_eq!(after("你好", "世界"), "你好世界");
        assert_eq!(after("", "say enter"), "say enter");

        // Recognizer punctuation joins onto the words before it.
        let mut dictation = Dictation::new("(", 1..1);
        let mut field = Field::new("(", 1);
        field.say(&mut dictation, "say enter", true);
        field.say(&mut dictation, ", then stop)", true);
        assert_eq!(field.text, "(say enter, then stop)");

        // And a caret parked mid-draft gets a space on both sides.
        let mut dictation = Dictation::new("abcdef", 3..3);
        let mut field = Field::new("abcdef", 3);
        field.say(&mut dictation, "MID", true);
        assert_eq!(field.text, "abc MID def");

        // Silence replaces nothing and adds no whitespace.
        let mut dictation = Dictation::new("keep this", 0..9);
        assert_eq!(dictation.transcript(" ", false), None);
        assert_eq!(dictation.transcript("", true), None);
    }

    #[test]
    fn selection_offsets_are_ordered_and_kept_on_char_boundaries() {
        let draft = "Hi 🦀, old text today";
        let start = draft.find("old").unwrap();
        let end = draft.find(" today").unwrap();
        let mut dictation = Dictation::new(draft, end..start);
        assert_eq!(dictation.transcript("new text", false).unwrap().range, start..end);
        // Inside the crab: floored to its start. Past the end: clamped.
        let inside = draft.find('🦀').unwrap() + 1;
        assert_eq!(Dictation::new(draft, inside..inside).start, inside - 1);
        assert_eq!(Dictation::new(draft, 999..999).start, draft.len());
    }

    #[test]
    fn typing_mid_utterance_keeps_the_words_spoken_afterwards() {
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "hello world", false);
        field.type_text(&mut dictation, "X");
        assert!(dictation.is_interrupted(), "still waiting for the utterance to finish");
        // Revisions in the meantime are not written under the user's edit.
        field.say(&mut dictation, "hello world and", false);
        assert_eq!(field.text, "hello worldX");
        field.say(&mut dictation, "hello world and more", true);
        field.settle(&mut dictation);
        assert_eq!(field.text, "hello worldX and more");
        assert!(!dictation.is_interrupted());
    }

    #[test]
    fn typing_between_utterances_re_anchors_at_once() {
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "first", true);
        field.type_text(&mut dictation, "X");
        assert!(!dictation.is_interrupted());
        field.say(&mut dictation, "second", true);
        assert_eq!(field.text, "firstX second");

        // Typing before anything was recognized at all.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.type_text(&mut dictation, "typed first");
        field.say(&mut dictation, "spoken after", true);
        assert_eq!(field.text, "typed first spoken after");
    }

    #[test]
    fn a_revision_after_an_edit_never_undoes_it_or_repeats_itself() {
        // Backspace, then the same utterance again: nothing to add.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "typo here", false);
        dictation.interrupt();
        field.text.pop();
        field.caret -= 1;
        field.settle(&mut dictation);
        field.say(&mut dictation, "typo here", false);
        field.say(&mut dictation, "typo here.", true);
        field.settle(&mut dictation);
        assert_eq!(field.text, "typo her");

        // Wiping the dictated text and typing over it.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "wipe me", false);
        dictation.interrupt();
        field.text = "fresh".into();
        field.caret = 5;
        field.settle(&mut dictation);
        field.say(&mut dictation, "wipe me", true);
        field.settle(&mut dictation);
        field.say(&mut dictation, "then more", true);
        assert_eq!(field.text, "fresh then more");

        // A wholesale revision that shares no words with what was shown is
        // dropped rather than repeated; speech resumes with the next utterance.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "keep these words", false);
        dictation.interrupt();
        field.caret = 4;
        field.settle(&mut dictation);
        field.say(&mut dictation, "stale revision", true);
        field.settle(&mut dictation);
        assert_eq!(field.text, "keep these words");
        field.say(&mut dictation, "and more", true);
        assert_eq!(field.text, "keep and more these words");
    }

    #[test]
    fn a_final_that_arrives_during_the_edit_waits_for_it() {
        // The final is fed before the edit lands, exactly as when both happen in
        // one event; the words it adds still go after the edit.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "keep these words", false);
        dictation.interrupt();
        assert_eq!(dictation.transcript("keep these words and more", true), None);
        field.text = "keep  words".into();
        field.caret = 5;
        field.settle(&mut dictation);
        assert_eq!(field.text, "keep and more words");

        // Two finals in the same gap both make it in.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "hello", false);
        dictation.interrupt();
        assert_eq!(dictation.transcript("hello world", true), None);
        assert_eq!(dictation.transcript("again", true), None);
        field.text = "hello!".into();
        field.caret = 6;
        field.settle(&mut dictation);
        assert_eq!(field.text, "hello! world again");
    }

    #[test]
    fn settle_notices_changes_that_arrived_without_warning() {
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "hello", false);
        // Nothing changed: not an interruption.
        field.settle(&mut dictation);
        assert!(!dictation.is_interrupted());
        // The caret moved: an interruption, resolved at the utterance boundary.
        field.caret = 0;
        field.settle(&mut dictation);
        assert!(dictation.is_interrupted());
        field.say(&mut dictation, "hello there", true);
        field.settle(&mut dictation);
        // The caret follows the words, including the space that keeps them apart.
        assert_eq!(field.text, "there hello");
        assert_eq!(field.caret, 6);
    }

    #[test]
    fn an_interruption_that_changed_nothing_is_called_off() {
        // End with the caret already at the end, or a click on the caret: the
        // field is untouched, so the utterance carries on, revisions included.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "I scream", false);
        dictation.interrupt();
        assert_eq!(dictation.transcript("I scream for", false), None, "held while the edit is pending");
        field.settle(&mut dictation);
        assert!(!dictation.is_interrupted());
        assert_eq!(field.text, "I scream for", "the held revision lands as soon as nothing changed");
        field.say(&mut dictation, "Ice cream please", true);
        assert_eq!(field.text, "Ice cream please");

        // The same when the final itself arrived while the interruption was pending.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "hello world", false);
        dictation.interrupt();
        assert_eq!(dictation.transcript("Hello, world.", true), None);
        field.settle(&mut dictation);
        assert_eq!(field.text, "Hello, world.");
    }

    #[test]
    fn a_partial_that_arrives_during_an_edit_is_written_after_it() {
        // Between utterances the user types, and the next utterance's first
        // partial is fed before their edit lands. Nothing was in flight, so the
        // edit re-anchors at once and the partial goes after it.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "first", true);
        dictation.interrupt();
        assert_eq!(dictation.transcript("second", false), None);
        field.text.push('X');
        field.caret += 1;
        field.settle(&mut dictation);
        assert_eq!(field.text, "firstX second");
        assert!(!dictation.is_interrupted());
        field.say(&mut dictation, "second one", true);
        assert_eq!(field.text, "firstX second one");
    }

    #[test]
    fn an_edit_that_splits_a_character_at_the_anchor_is_still_an_edit() {
        // Same length and caret as what we wrote, but the anchor now falls inside the `é`.
        let mut dictation = Dictation::new("abc", 1..1);
        let mut field = Field::new("abc", 1);
        field.say(&mut dictation, "X", false);
        assert_eq!((field.text.as_str(), field.caret), ("a X bc", 4));
        dictation.interrupt();
        field.text = "éX bc".into();
        field.settle(&mut dictation);
        assert!(dictation.is_interrupted());
        field.say(&mut dictation, "X", true);
        field.settle(&mut dictation);
        assert_eq!(field.text, "éX bc");
    }

    #[test]
    fn words_already_shown_are_never_removed() {
        // An empty transcript, partial or final, keeps what is shown.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "keep me", false);
        field.say(&mut dictation, "", false);
        field.say(&mut dictation, "", true);
        assert_eq!(field.text, "keep me");
        // A recognizer that starts over after a pause keeps the phrase before it.
        field.say(&mut dictation, "a long phrase that took thirty seconds", false);
        field.say(&mut dictation, "Short phrase.", false);
        field.say(&mut dictation, "Short phrase.", true);
        assert_eq!(field.text, "keep me a long phrase that took thirty seconds Short phrase.");

        // A final that stops short keeps the words it left out.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        field.say(&mut dictation, "hello world and more", false);
        field.say(&mut dictation, "Hello world.", true);
        assert_eq!(field.text, "Hello world. and more");

        // Reformatting a number as it's spoken is a revision, not a restart.
        let mut dictation = Dictation::new("", 0..0);
        let mut field = Field::new("", 0);
        for partial in ["Three", "300", "300 and", "340", "347 apples"] {
            field.say(&mut dictation, partial, false);
        }
        field.say(&mut dictation, "347 apples.", true);
        assert_eq!(field.text, "347 apples.");
    }

    #[test]
    fn words_beyond_only_returns_what_was_not_already_shown() {
        assert_eq!(words_beyond("hello world", "hello world and more"), "and more");
        // Re-casing and re-punctuating is not new speech.
        assert_eq!(words_beyond("hello world", "Hello, world! And more"), "And more");
        assert_eq!(words_beyond("hello world", "Hello world."), "");
        assert_eq!(words_beyond("", "all of it"), "all of it");
        assert_eq!(words_beyond("   ", "all of it"), "all of it");
        // No shared leading words: lose the tail rather than repeat the draft.
        assert_eq!(words_beyond("hello world", "goodbye everyone"), "");
        // A word inserted inside what is shown is not separable either.
        assert_eq!(words_beyond("hello world", "hello big world and more"), "");
        assert_eq!(words_beyond("the cat sat", "the cat quickly sat down"), "");
        // A shorter final is a revision, not new speech.
        assert_eq!(words_beyond("hello world and more", "hello world"), "");
    }
}
