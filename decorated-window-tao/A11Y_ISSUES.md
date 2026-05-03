# Accessibility — known issues

Audit verified against the current code. Each item cites the file/line where the issue lives.

## Wire format

- [ ] **Length fields use signed `Short` and can overflow** — `TaoAccessibility.kt:496-509` encodes label / valueString / customActions / testTag lengths via `buf.putShort(lb.size.toShort())`. UTF-8 payloads beyond 32 767 bytes (long `EditableText`, paragraph-style `StateDescription`) overflow into negative shorts and the Rust decoder (`a11y_linux.rs:172`, `read_u16`) reads a garbage length. Either clamp at encode time or widen to `u32`.

- [ ] **`F_HIDDEN` is dead** — declared in `TaoAccessibility.kt:121`, consumed in `a11y_linux.rs:452`, but never written by `TaoSemanticsObserver`. Currently harmless because the observer skips invisible nodes outright (`isInvisibleToA11y`, `TaoSemanticsObserver.kt:96`), but it is wire-format debt.

## Performance / gating

- [ ] **Full re-serialisation on every change** — `TaoSemanticsObserver.syncIfDirty()` (l. 76) rebuilds the whole `ArrayList<TaoA11yNode>` from scratch on each `onSemanticsChange` / `onLayoutChange`. No diff, no debounce. AccessKit accepts partial `TreeUpdate`s; we always send everything.

- [ ] **Linux gating bypassed** — `a11y_linux.rs:946-953` returns `JNI_TRUE` unconditionally from `nativeA11yIsActive`. The skip path in `TaoAccessibility.kt:326-328` (`!active && !needsResync`) therefore never fires on Linux. JVM-side cost (BFS + encode + JNI copy) is paid on every recomposition even when no AT is connected.

- [ ] **`maybeForceResync` is dead but still called** — `TaoAccessibility.kt:342-345` is empty (comment: "No longer needed"). Still invoked from `TaoSemanticsObserver.syncIfDirty()` l. 74. Drop the call site.

## Threading / safety

- [ ] **Race window in `nativeA11yApplySnapshot`** — `a11y_linux.rs:901-934` takes `WINDOWS.lock()` immutably, drops it, then re-acquires mutably for `update_if_active`. A concurrent `Detach` between the two locks removes the entry and the snapshot is dropped silently (`return JNI_FALSE`). Refactor to a single lock, or move to `Mutex<HashMap<i64, Mutex<WindowEntry>>>`.

## Semantics coverage

- [ ] **Toggleable without explicit `Role` falls into `Group`** — `TaoSemanticsObserver.kt:284-325` only promotes to `Checkbox`/`Switch`/`RadioButton` when `composeRole` is set. Plain `Modifier.toggleable { … }` (no `role =` argument) carries `ToggleableState` but no `Role`, so it lands in the final `else -> TaoA11yRole.Group`. The `F_CHECKED` flag is set (l. 335-339) but the role doesn't expose `STATE_CHECKABLE` (AT-SPI) / `Toggle` pattern (UIA). Promote to a checkable role whenever `ToggleableState` is present.

- [ ] **`SemanticsProperties.Error` not mapped** — no occurrence in the observer. No `STATE_INVALID_ENTRY` (AT-SPI) or `IsRequiredForForm` / aria-invalid equivalent (UIA) for invalid form fields. Blocking for serious form work.

- [ ] **No `Text` interface for word/character navigation** — `a11y_linux.rs:355-369` only sets `TextSelection` on `TextInput` / `MultilineTextInput` / `PasswordInput`. No `character_lengths`, no `character_positions`, no text runs. Orca/VoiceOver can move the caret but cannot navigate by word/character in non-editable `Text` composables — the label is read as a single unit.

- [ ] **IME composing range not exposed** — `a11y.m:850-853` stubs the marked-text protocol (returns `NSNotFound`). IME input itself works (`TaoComposeSceneHost.kt:790`) but the composing range is never reported to NSAccessibility, and there is no AT-SPI preedit equivalent on Linux. Screen readers cannot announce in-progress composition.

## Naming / hygiene

- [ ] **`linuxXid` is misnamed** — `TaoAccessibility.kt:226-233` documents the field as the X11 Window XID, but the assignment in `attach()` (l. 280-291) makes it the opaque Tao window handle on the EGL+Wayland path. Rename to `nativeViewHandle` (or similar) to stop leaking the historical X11 assumption.

- [ ] **`nsView` naming leaks across platforms** — every JNI export uses `nsView` even on Windows (HWND) and Linux (Tao handle). Cosmetic; mostly noted here as a documentation hazard for new contributors.
