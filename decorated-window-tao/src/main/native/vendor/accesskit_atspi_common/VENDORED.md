# Vendored fork of `accesskit_atspi_common` 0.19.1

Source: https://github.com/AccessKit/accesskit (crates.io 0.19.1)
License: MIT OR Apache-2.0 (see LICENSE-MIT and LICENSE-APACHE)

Local patched copy of upstream `accesskit_atspi_common` 0.19.1, redirected
via `[patch.crates-io]`.

## Local patches

- `src/node.rs`
  - `State::Enabled | State::Sensitive` only added when `!is_disabled()`
    (upstream adds them unconditionally for non-read-only nodes, hiding
    disabled buttons, and withholds them for read-only ones).
  - `State::InvalidEntry` from AccessKit's `invalid()` (Compose
    `SemanticsProperties.Error`).
  - `level: N` heading attribute via `Accessible.GetAttributes()`.
  - `container-live` attribute mirrors aria-live (Polite / Assertive).
  - `Interface::EditableText` advertised for non-read-only text inputs.
  - `Interface::Text` advertised for text inputs even when `supports_text_ranges()`
    is false (handled by `SimpleTextInterface` in `accesskit_unix`).
  - Custom actions projected through the AT-SPI Action interface
    (`supports_action`, `n_actions`, `get_action_name`, `do_action`) — upstream
    only exposes the synthetic "click".
  - `replace_selected_text(text)`, `simple_text_value()`,
    `simple_text_selection()`, `is_simple_text_input()` public helpers
    used by the sibling `accesskit_unix` interfaces.

- `src/adapter.rs`
  - `emit_simple_text_change_if_needed` — char-level diff for text-input
    nodes without text-runs (TextField typing → text-changed events).
  - `emit_simple_caret_change_if_needed` — caret-moved + selection-changed
    events from `raw_text_selection()` for the same node class.

## Dropped at the 0.14.2 → 0.19.1 bump

- `State::Modal` on `is_modal()` — upstream ships it since 0.15.

All upstream copyright notices are preserved. Patches are marked with
`// Vendored-fork addition:` or `// Vendored-fork fix:` comments.
