// Copyright 2022 The AccessKit Authors. All rights reserved.
// Licensed under the Apache License, Version 2.0 (found in
// the LICENSE-APACHE file) or the MIT license (found in
// the LICENSE-MIT file), at your option.
//
// Vendored fork addition: minimal AT-SPI Text interface for nodes that have a
// value but no inline-text-box data.
//
// Upstream `TextInterface` routes every method through PlatformNode helpers
// that walk text-runs (inline_text_boxes / character positions). Compose's
// SemanticsNode tree doesn't expose that data, so the upstream impl returns
// `UnsupportedInterface` and screen readers can't read field contents.
//
// This shim exposes the bare minimum methods Orca/AT-SPI clients use to
// announce a TextField's contents:
//   - CharacterCount / GetText / GetStringAtOffset (so contents are speakable)
//   - CaretOffset / GetSelection (so caret announcement works)
//   - GetAttributes / GetDefaultAttributes (return empty maps)
//
// Mutating methods (SetCaretOffset, AddSelection…) return `false` — Orca will
// fall back to keyboard-event injection, which already works.

use accesskit_atspi_common::{PlatformNode, Rect};
use atspi::{CoordType, Granularity, ScrollType};
use std::collections::HashMap;
use zbus::{fdo, interface};

pub(crate) struct SimpleTextInterface {
    node: PlatformNode,
}

impl SimpleTextInterface {
    pub fn new(node: PlatformNode) -> Self {
        Self { node }
    }

    fn map_error(&self) -> impl '_ + FnOnce(accesskit_atspi_common::Error) -> fdo::Error {
        |error| crate::util::map_error_from_node(&self.node, error)
    }

    fn value(&self) -> fdo::Result<String> {
        self.node.simple_text_value().map_err(self.map_error())
    }
}

#[interface(name = "org.a11y.atspi.Text")]
impl SimpleTextInterface {
    #[zbus(property)]
    fn character_count(&self) -> fdo::Result<i32> {
        Ok(self.value()?.chars().count() as i32)
    }

    /// Reports the current focus index from the host's TextSelection (set by
    /// the Tao bridge from Compose's TextSelectionRange semantic). Falls back
    /// to end-of-string when no selection is set.
    #[zbus(property)]
    fn caret_offset(&self) -> fdo::Result<i32> {
        Ok(self.node.simple_text_selection().map_err(self.map_error())?.1)
    }

    fn get_text(&self, start_offset: i32, end_offset: i32) -> fdo::Result<String> {
        let v = self.value()?;
        let chars: Vec<char> = v.chars().collect();
        let len = chars.len() as i32;
        let s = start_offset.clamp(0, len) as usize;
        // -1 means "end of string" in AT-SPI.
        let e = if end_offset < 0 { len } else { end_offset.clamp(0, len) } as usize;
        if e <= s {
            return Ok(String::new());
        }
        Ok(chars[s..e].iter().collect())
    }

    /// Granularity-aware lookup. We collapse everything to the whole field —
    /// a simple-text node has no notion of word/line/sentence boundaries.
    fn get_string_at_offset(
        &self,
        _offset: i32,
        _granularity: Granularity,
    ) -> fdo::Result<(String, i32, i32)> {
        let v = self.value()?;
        let len = v.chars().count() as i32;
        Ok((v, 0, len))
    }

    fn set_caret_offset(&self, _offset: i32) -> fdo::Result<bool> {
        Ok(false)
    }

    fn get_attribute_value(&self, _offset: i32, _attribute_name: &str) -> fdo::Result<String> {
        Ok(String::new())
    }

    fn get_attributes(
        &self,
        _offset: i32,
    ) -> fdo::Result<(HashMap<String, String>, i32, i32)> {
        let len = self.value()?.chars().count() as i32;
        Ok((HashMap::new(), 0, len))
    }

    fn get_default_attributes(&self) -> fdo::Result<HashMap<String, String>> {
        Ok(HashMap::new())
    }

    fn get_character_extents(
        &self,
        _offset: i32,
        _coord_type: CoordType,
    ) -> fdo::Result<Rect> {
        Ok(Rect {
            x: 0,
            y: 0,
            width: 0,
            height: 0,
        })
    }

    fn get_offset_at_point(
        &self,
        _x: i32,
        _y: i32,
        _coord_type: CoordType,
    ) -> fdo::Result<i32> {
        Ok(-1)
    }

    fn get_n_selections(&self) -> fdo::Result<i32> {
        // 1 if anchor != focus (a non-degenerate selection), 0 otherwise.
        let (a, f) = self.node.simple_text_selection().map_err(self.map_error())?;
        Ok(if a != f { 1 } else { 0 })
    }

    fn get_selection(&self, _selection_num: i32) -> fdo::Result<(i32, i32)> {
        let (a, f) = self.node.simple_text_selection().map_err(self.map_error())?;
        // AT-SPI expects (start, end) ordered.
        Ok(if a <= f { (a, f) } else { (f, a) })
    }

    fn add_selection(&self, _start: i32, _end: i32) -> fdo::Result<bool> {
        Ok(false)
    }

    fn remove_selection(&self, _selection_num: i32) -> fdo::Result<bool> {
        Ok(false)
    }

    fn set_selection(
        &self,
        _selection_num: i32,
        _start: i32,
        _end: i32,
    ) -> fdo::Result<bool> {
        Ok(false)
    }

    fn get_range_extents(
        &self,
        _start: i32,
        _end: i32,
        _coord_type: CoordType,
    ) -> fdo::Result<Rect> {
        Ok(Rect {
            x: 0,
            y: 0,
            width: 0,
            height: 0,
        })
    }

    fn get_attribute_run(
        &self,
        _offset: i32,
        _include_defaults: bool,
    ) -> fdo::Result<(HashMap<String, String>, i32, i32)> {
        let len = self.value()?.chars().count() as i32;
        Ok((HashMap::new(), 0, len))
    }

    fn scroll_substring_to(
        &self,
        _start: i32,
        _end: i32,
        _scroll_type: ScrollType,
    ) -> fdo::Result<bool> {
        Ok(false)
    }

    fn scroll_substring_to_point(
        &self,
        _start: i32,
        _end: i32,
        _coord_type: CoordType,
        _x: i32,
        _y: i32,
    ) -> fdo::Result<bool> {
        Ok(false)
    }
}
