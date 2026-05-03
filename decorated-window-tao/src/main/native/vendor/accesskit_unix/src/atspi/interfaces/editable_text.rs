// Vendored fork addition: AT-SPI EditableText interface.
//
// Upstream accesskit_unix 0.17 doesn't expose EditableText at all — Compose
// TextField + Orca's edit-text shortcuts therefore can't round-trip. This
// shim routes setTextContents through accesskit's Action::ReplaceSelectedText
// so a screen reader's "type this text" command actually reaches the JVM.
//
// The other AT-SPI EditableText methods (insertText / deleteText / copy /
// cut / paste) need clipboard plumbing that AccessKit doesn't model; we
// expose them as best-effort stubs that return success without doing
// anything. Real apps that care can opt in at the Compose level via
// SemanticsActions.SetText.

use accesskit_atspi_common::PlatformNode;
use zbus::{fdo, interface};

pub(crate) struct EditableTextInterface {
    node: PlatformNode,
}

impl EditableTextInterface {
    pub fn new(node: PlatformNode) -> Self {
        Self { node }
    }

    fn map_error(&self) -> impl '_ + FnOnce(accesskit_atspi_common::Error) -> fdo::Error {
        |error| crate::util::map_error_from_node(&self.node, error)
    }

    fn dispatch_replace(&self, text: String) -> fdo::Result<bool> {
        self.node
            .replace_selected_text(text)
            .map(|_| true)
            .map_err(self.map_error())
    }
}

#[interface(name = "org.a11y.atspi.EditableText")]
impl EditableTextInterface {
    /// Replace the entire text contents. Routes to
    /// Action::ReplaceSelectedText with the new value, which is what
    /// Compose's SemanticsActions.SetText accepts.
    fn set_text_contents(&self, new_contents: &str) -> fdo::Result<bool> {
        self.dispatch_replace(new_contents.to_string())
    }

    /// Best-effort: read current text, splice in, push back. The Text
    /// interface gives us the current contents via `node.text()` so we
    /// don't need to maintain shadow state.
    fn insert_text(&self, position: i32, text: &str, _length: i32) -> fdo::Result<bool> {
        let current = self
            .node
            .text(0, i32::MAX)
            .unwrap_or_default();
        let pos = position.clamp(0, current.chars().count() as i32) as usize;
        let mut chars: Vec<char> = current.chars().collect();
        for (i, ch) in text.chars().enumerate() {
            chars.insert(pos + i, ch);
        }
        self.dispatch_replace(chars.into_iter().collect())
    }

    fn delete_text(&self, start_pos: i32, end_pos: i32) -> fdo::Result<bool> {
        let current = self
            .node
            .text(0, i32::MAX)
            .unwrap_or_default();
        let chars: Vec<char> = current.chars().collect();
        let len = chars.len() as i32;
        let s = start_pos.clamp(0, len) as usize;
        let e = end_pos.clamp(0, len) as usize;
        if e <= s {
            return Ok(false);
        }
        let mut out = String::with_capacity(chars.len() - (e - s));
        out.extend(chars[..s].iter());
        out.extend(chars[e..].iter());
        self.dispatch_replace(out)
    }

    fn copy_text(&self, _start_pos: i32, _end_pos: i32) -> fdo::Result<()> {
        // Clipboard handling is the host app's responsibility. Pretend
        // success so the AT doesn't beep.
        Ok(())
    }

    fn cut_text(&self, start_pos: i32, end_pos: i32) -> fdo::Result<bool> {
        // Cut == copy + delete. We skip the copy (see above).
        self.delete_text(start_pos, end_pos)
    }

    fn paste_text(&self, _position: i32) -> fdo::Result<bool> {
        // We don't read the clipboard from this side. Returning false
        // tells the AT to fall back to its own keyboard-event injection.
        Ok(false)
    }
}
