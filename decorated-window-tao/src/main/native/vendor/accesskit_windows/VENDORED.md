# Vendored fork of `accesskit_windows` 0.34.0

Source: https://github.com/AccessKit/accesskit (crates.io 0.34.0)
License: MIT OR Apache-2.0

## Local patches

- `src/node.rs` `ProviderOptions`: add `ProviderOptions_UseComThreading`
  alongside `ProviderOptions_ServerSideProvider` (Chromium/Electron parity).
- `src/adapter.rs` `QueuedEvents::raise`: do not `.unwrap()` on raise
  HRESULTs (empty queue is a no-op; raise failures must not abort the JVM).

## Dropped at the 0.29.2 → 0.34.0 bump

- `IExpandCollapseProvider` — the pattern was backported from 0.34 into the
  0.29.2 fork; upstream 0.34 ships it, together with the
  `From<ExpandCollapseState> for Variant` conversion in `src/util.rs`.
