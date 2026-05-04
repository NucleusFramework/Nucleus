# Accessibility — known issues

Audit verified against the current code. Each item cites the file/line where the issue lives.

## Wire format

- [x] **Length fields use signed `Short` and can overflow** — fixed in
  `TaoA11ySnapshotSerializer.encode` by clamping each UTF-8 payload to
  65 535 bytes at a codepoint boundary (`clampUtf8`). Wire format kept at v6
  to avoid churning the macOS / Windows readers; pathologically long
  `EditableText` values are surfaced through the dedicated AT-SPI Text
  interface anyway, so the snapshot fallback truncation is benign.

- [x] **`F_HIDDEN` documented as reserved** — observer prunes invisible
  nodes upstream of serialisation, so the bit is intentionally never set.
  Documented as forward-compat reserved (`TaoAccessibility.kt`).

## Performance / gating

- [x] **Identical-snapshot push elided** — `TaoAccessibilityController.pushSnapshot`
  now caches the previously-pushed bytes and skips the JNI / Rust-decode /
  `update_if_active` round-trip when the freshly encoded buffer is byte-identical
  (`TaoAccessibility.kt`). Compose fires `onLayoutChange` / `onSemanticsChange`
  liberally for sub-pixel jitter and animation tweens — most resolve to no
  observable change in the projection. Forced pushes and explicit resync
  requests bypass the skip so AT clients always get a fresh tree on
  (re)connection. Verified: bare-toggle clicks still flip `STATE_CHECKED`
  through. A full per-node `TreeUpdate` diff (only sending changed nodes
  to AccessKit) remains a follow-up for very large trees, but the equality
  short-circuit covers the common idle-frame case.

- [x] **Linux gating respects AT_ACTIVE** — `nativeA11yIsActive`
  (`a11y_linux.rs`) now returns the real AT-connected state. The Kotlin
  observer combines it with `pendingForcedPush` so the first snapshot still
  seeds the cache; subsequent ticks skip the BFS+JNI cost when no AT is
  listening.

- [x] **`maybeForceResync` removed** — empty function and its call site
  were dropped (`TaoAccessibility.kt`, `TaoSemanticsObserver.kt`).
  `pushSnapshot` consumes the native resync flag directly.

## Threading / safety

- [x] **`nativeA11yApplySnapshot` race fixed** — single `WINDOWS.lock()`
  acquisition with `get_mut` now serialises Apply vs Detach; the previous
  drop-and-reacquire pattern allowed a concurrent detach to silently drop
  the snapshot.

## Semantics coverage

- [x] **Toggleable without explicit `Role` promoted to checkable** —
  `TaoSemanticsObserver.describe()` now maps any node carrying
  `ToggleableState` (regardless of `Role`) to `TaoA11yRole.Checkbox`,
  before the onClick→Button branch. Verified via `bare-toggle` testTag in
  `A11yTab` — Orca / AT-SPI now expose `STATE_CHECKABLE`.

- [x] **`SemanticsProperties.Error` mapped** — wired through `EF_INVALID`
  (extra-flag bit 1). Surfaces as `STATE_INVALID_ENTRY` on AT-SPI via the
  vendored `accesskit_atspi_common` `state()` patch. Verified via
  `email-input` testTag in `A11yTab`.

- [ ] **No `Text` interface on non-editable `Text` composables** — partially addressed for inputs: text-input nodes ship a value-based `org.a11y.atspi.Text` via the vendored `SimpleTextInterface` (character_count, get_text, caret_offset, get_selection, +text-changed/caret-moved events). Static `Text` / `Label` composables still expose `name` only — no `character_lengths`, no `character_positions`, no text runs. Orca/VoiceOver can read a paragraph but cannot navigate it by word/character. Adding text-runs would lift this for static text too.

- [ ] **IME composing range not exposed** — `a11y.m:850-853` stubs the marked-text protocol (returns `NSNotFound`). IME input itself works (`TaoComposeSceneHost.kt:790`) but the composing range is never reported to NSAccessibility, and there is no AT-SPI preedit equivalent on Linux. Screen readers cannot announce in-progress composition.

## Vendored AccessKit fork

The Linux backend ships local copies of `accesskit_atspi_common 0.14.2`
and `accesskit_unix 0.17.2` under
`decorated-window-tao/src/main/native/vendor/`, redirected via
`[patch.crates-io]` in the native `Cargo.toml`. Patches and additions are
listed in each crate's `VENDORED.md`. Maintenance debt this introduces:

- [ ] **Patches must be rebased on every AccessKit upgrade** — the fork
  carries 10 patches (Modal/level/EditableText/SimpleText/container-live/
  disabled-state-fix/text-changed/caret-moved/Cache stub/InvalidEntry)
  plus 3 new files (`editable_text.rs`, `simple_text.rs`, `cache.rs`).
  Each is marked with a `// Vendored-fork addition:` or `// Vendored-fork
  fix:` comment so a `git grep` finds them. When AccessKit ≥ 0.18 lands
  with EditableText upstream, drop the local impl and re-evaluate the rest.

- [ ] **Upstream-tracking is manual** — there is no script that diffs our
  vendored sources against a published crate. Any drift (e.g. a CVE in
  the upstream we missed) has to be caught by hand. A periodic
  `cargo download accesskit_unix==0.17.2 -x` + `diff -r` against
  `vendor/accesskit_unix/` would surface anything we modified.

- [x] **Wire-format version mismatches log loudly** —
  `parse_snapshot` now prints to stderr on magic / version skew, naming
  the seen vs expected version and reminding the operator to rebuild
  both sides (`a11y_linux.rs`).

- [ ] **`accesskit_unix::set_app_name` is OnceLock-only** — calling it a
  second time (e.g. when a host embeds our backend twice) silently
  ignores the new value (`vendor/accesskit_unix/src/context.rs:56`).
  Acceptable today (Nucleus only attaches one window before the first
  AT-SPI call) but worth documenting.

## Naming / hygiene

- [x] **`linuxXid` renamed to `nativeViewHandle`** — historical X11 name
  retired in `TaoAccessibility.kt`, all call sites updated
  (`DecoratedWindow.kt`).

- [ ] **`nsView` naming leaks across platforms** — every JNI export uses `nsView` even on Windows (HWND) and Linux (Tao handle). Cosmetic; mostly noted here as a documentation hazard for new contributors.
