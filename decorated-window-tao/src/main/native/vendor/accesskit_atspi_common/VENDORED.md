# Vendored fork of `accesskit_atspi_common` 0.14.2

Source: https://github.com/AccessKit/accesskit (crates.io 0.14.2)
License: MIT OR Apache-2.0 (see LICENSE-MIT and LICENSE-APACHE)

Local patched copy of upstream `accesskit_atspi_common` 0.14.2, redirected
via `[patch.crates-io]`.

## Local patches

- `src/node.rs`
  - `State::Modal` exposed when `is_modal()` (Compose `IsDialog`).
  - `level: N` heading attribute via `Accessible.GetAttributes()`.
  - `container-live` attribute mirrors aria-live (Polite / Assertive).
  - `Interface::EditableText` advertised for non-read-only text inputs.
  - `Interface::Text` advertised for text inputs even when `supports_text_ranges()`
    is false (handled by `SimpleTextInterface` in `accesskit_unix`).
  - `State::Enabled | State::Sensitive` only added when `!is_disabled()`
    (upstream unconditionally added them, hiding disabled buttons).
  - `replace_selected_text(text)`, `simple_text_value()`,
    `simple_text_selection()`, `is_simple_text_input()` public helpers
    used by the sibling `accesskit_unix` interfaces.

- `src/adapter.rs`
  - `emit_simple_text_change_if_needed` — char-level diff for text-input
    nodes without text-runs (TextField typing → text-changed events).
  - `emit_simple_caret_change_if_needed` — caret-moved + selection-changed
    events from `raw_text_selection()` for the same node class.

All upstream copyright notices are preserved. Patches are marked with
`// Vendored-fork addition:` or `// Vendored-fork fix:` comments.
