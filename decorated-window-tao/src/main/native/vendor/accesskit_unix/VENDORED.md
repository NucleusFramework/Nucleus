# Vendored fork of `accesskit_unix` 0.17.2

Source: https://github.com/AccessKit/accesskit (crates.io 0.17.2)
License: MIT OR Apache-2.0 (see LICENSE-MIT and LICENSE-APACHE)

This is a **local patched copy** of upstream `accesskit_unix` 0.17.2,
redirected via a `[patch.crates-io]` entry in
`decorated-window-tao/src/main/native/Cargo.toml`. We carry the patches
locally because upstream 0.14/0.17 doesn't ship a few features Compose
Multiplatform needs to talk to AT-SPI clients on Linux.

## Local additions

- `src/atspi/interfaces/editable_text.rs` — `org.a11y.atspi.EditableText`
  routing `set_text_contents` through `Action::ReplaceSelectedText`.
- `src/atspi/interfaces/simple_text.rs` — value-based `org.a11y.atspi.Text`
  for text inputs that don't populate inline text-runs.
- `src/atspi/interfaces/cache.rs` — empty `org.a11y.atspi.Cache` stub at
  `/org/a11y/atspi/cache` so AT clients stop logging "Unknown object".

## Local patches

- `src/atspi/bus.rs` — register the new EditableText / SimpleText / Cache
  interfaces; tolerate `InterfaceNotFound` on unregister.
- `src/context.rs` — `set_app_name` override (so JVM apps don't show as "java").
- `src/lib.rs` — re-export `set_app_name`.

All upstream copyright notices are preserved. Patches are marked with
`// Vendored-fork addition:` or `// Vendored-fork fix:` comments for diffing
against upstream when we eventually move to a newer release.
