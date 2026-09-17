# Vendored fork of `accesskit_unix` 0.22.1

Source: https://github.com/AccessKit/accesskit (crates.io 0.22.1)
License: MIT OR Apache-2.0 (see LICENSE-MIT and LICENSE-APACHE)

This is a **local patched copy** of upstream `accesskit_unix` 0.22.1,
redirected via a `[patch.crates-io]` entry in
`decorated-window-tao/src/main/native/Cargo.toml`. We carry the patches
locally because upstream doesn't ship a few features Compose Multiplatform
needs to talk to AT-SPI clients on Linux.

## Local additions

- `src/atspi/interfaces/editable_text.rs` — `org.a11y.atspi.EditableText`
  routing `set_text_contents` through `Action::ReplaceSelectedText`.
- `src/atspi/interfaces/simple_text.rs` — value-based `org.a11y.atspi.Text`
  for text inputs that don't populate inline text-runs.

## Local patches

- `src/atspi/bus.rs` — register the new EditableText / SimpleText
  interfaces; tolerate `InterfaceNotFound` on unregister.
- `src/context.rs` — `set_app_name` override (so JVM apps don't show as "java").
- `src/lib.rs` — re-export `set_app_name`.

## Dropped at the 0.17.2 → 0.22.1 bump

- `src/atspi/interfaces/cache.rs` — upstream implements a real
  `org.a11y.atspi.Cache` object (with `AddAccessible` / `RemoveAccessible`
  signals) since 0.22.0, superseding our empty stub.

Upstream also gained, in this range, two fixes we previously worked around by
hand: the AT-SPI `IsEnabled` property is now watched for lazy activation
(0.22.0), and recoverable D-Bus errors no longer panic (0.22.1) — the latter
matters because the crate runs inside a JVM built with `panic = "abort"`.

All upstream copyright notices are preserved. Patches are marked with
`// Vendored-fork addition:` or `// Vendored-fork fix:` comments for diffing
against upstream when we move to a newer release.
