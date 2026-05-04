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

- [x] **True per-node diff on partial updates** — wire format bumped to
  v7 (Linux). The header now carries a `flags: u16` (bit 0 = partial) plus an
  explicit `focusId: u64`, and each per-node entry includes its own
  children list, so partial buffers fully describe their topology without
  relying on the rest of the tree being present. `TaoAccessibilityController`
  caches the previously-pushed `Map<Long, TaoA11yNode>`, computes the
  changed-node set on each push (`TaoA11yNode` data-class equality covers
  both content and children-list changes), and emits either a full
  snapshot (first push, forced push, AT resync, or > 50 % delta) or a
  partial via the new `nativeA11yApplyPartialSnapshot` JNI export. The
  Rust side merges partials into AccessKit's existing tree via
  `update_if_active(|| TreeUpdate { tree: None, … })` and keeps a
  `last_focus` in `WindowState` so partials that don't carry a focus
  token reuse the previous one. Verified end-to-end: bare-toggle
  interactive clicks still flip `STATE_CHECKED`; `email-input`
  validation toggles `STATE_INVALID_ENTRY` on every text edit through
  the partial path.

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

- [x] **Static-text labels carry through to AT-SPI `name`** —
  `accesskit_consumer::Node::label_comes_from_value()` returns `true` for
  `Role::Label`, which means AT-SPI `Accessible.name` is read from the
  AccessKit `value` slot rather than `label` for our `ROLE_STATIC_TEXT`
  mapping. Without mirroring the wire-format `label` into the value, plain
  `BasicText` content (status counters, live-region strings, validation
  hints) reached the bus as a "label"-role node with an empty `name` —
  Orca/Accerciser would announce the role with no accompanying text. The
  parser now sets the value to the label for `ROLE_STATIC_TEXT` nodes
  whose snapshot doesn't already carry an explicit `valueString` (text
  inputs and progress surfaces still own that slot). Verified end-to-end:
  full regression goes from 32/34 → 34/34, complex stress goes from
  31/32 → 32/32, and `dump_labels.py` now reports 20 / 20 named labels.

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
