# Tao Vendoring Plan

## Context

The `decorated-window-tao` module accumulates external workarounds (Kotlin, C, ObjC, wrapper Rust) to compensate for limitations and bugs in `tao = "0.35"`. An audit identified ~17 hacks across Linux / Windows / macOS, ~12 of which can be solved cleanly by patching tao directly.

The project already uses this pattern for `accesskit_atspi_common` and `accesskit_unix` via `[patch.crates-io]`. We extend it to tao.

## Goals

1. Vendor `tao 0.35` under `src/main/native/vendor/tao/`.
2. Track every modification as a numbered `.patch` file under `src/main/native/vendor/tao-patches/` for traceability and reproducibility across version bumps.
3. Drive each phase by **a single patch**, paired with the **removal of the corresponding external hack** and **E2E validation on the affected platform(s)** before moving to the next phase.

## Target layout

```
decorated-window-tao/src/main/native/
├── Cargo.toml                          # add: tao = { path = "vendor/tao" }
├── vendor/
│   ├── accesskit_atspi_common/         # already present
│   ├── accesskit_unix/                 # already present
│   ├── tao/                            # NEW — copy of tao 0.35.0
│   └── tao-patches/                    # NEW — numbered .patch files
│       └── README.md
```

## Working method (per phase)

Every phase below is **atomic**:

1. Apply one patch to `vendor/tao/`.
2. Regenerate the matching `vendor/tao-patches/000X-*.patch` file.
3. Delete the external workaround that the patch obsoletes.
4. Build and run the affected sample (`sample-tao` / `sample-cmp`).
5. Run the **E2E checklist** for that phase.
6. Run `./gradlew preMerge` on the affected OS.
7. Commit (`feat(tao-patch): <title>`) — one commit per phase.
8. Only then move to the next phase.

If the E2E test fails: revert the phase commit, reopen the patch, do **not** stack on top of a broken state.

---

## Phase 0 — Vendor tao without patches

**Scope:** infrastructure only, behaviour unchanged.

**Steps:**

1. Copy `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tao-0.35.0/` into `src/main/native/vendor/tao/`.
2. Add to `src/main/native/Cargo.toml`:
   ```toml
   [patch.crates-io]
   tao = { path = "vendor/tao" }
   accesskit_atspi_common = { path = "vendor/accesskit_atspi_common" }
   accesskit_unix = { path = "vendor/accesskit_unix" }
   ```
3. Create `vendor/tao-patches/README.md` (pinned upstream version, bump procedure, patch index).

**E2E gate (all platforms):**

- [ ] `cargo build --release` succeeds, native libs are byte-comparable to the pre-vendoring build (modulo timestamps).
- [ ] `./gradlew :sample-tao:run` opens, renders Compose, closes cleanly on Linux **and** Windows **and** macOS.
- [ ] `./gradlew preMerge` is green on all three OSes.

**Commit:** `chore(decorated-window-tao): vendor tao 0.35.0 (no patches yet)`

---

## Phase 1 — Linux: widen resize corner / edge zones

**Why:** tao hardcodes `border = scale_factor * 5` for both edges and corners (`platform_impl/linux/event_loop.rs:503/532/564` + `window.rs::hit_test`). Corners are a 5×5 logical-px square — practically impossible to grab.

**Patch (`0001-linux-resize-zones.patch`):**

- `src/window.rs::hit_test` — add a `corner_size` parameter distinct from `border_x/y`. A point inside `corner_size` from any corner returns the diagonal direction; otherwise fall back to the edge band.
- `src/platform_impl/linux/event_loop.rs` — replace the three `let border = scale * 5;` with `let edge = scale * 8; let corner = scale * 16;` and pass both to `hit_test`.
- Bonus (also covers tao FIXME line 548): call `set_cursor` immediately before `begin_resize_drag` so the cursor stays correct during the drag.

**External code to remove:** none (the hack was never added — this was the trigger of the audit).

**E2E gate (Linux X11 + Wayland):**

- [x] Drag each corner of the window: cursor turns into the diagonal resize cursor in a clearly larger area (~16 px), drag works smoothly.
- [x] Drag each edge: cursor turns into the perpendicular resize cursor; drag works.
- [x] Click 1 px inside the window from a corner: no resize triggered, click is dispatched to Compose.
- [x] Window drag (titlebar grab) still works.
- [x] Maximize / restore / fullscreen transitions unaffected.

---

## Phase 2 — Linux: preserve cursor on motion

**Why:** tao's motion handler resets the cursor to `default` on every `motion-notify-event` to drive its edge detection, overwriting custom cursors set by Compose (e.g. text I-beam).

**Patch (`0002-linux-cursor-preserve-on-motion.patch`):**

- `src/platform_impl/linux/event_loop.rs` motion handler: only call `window.set_cursor(...)` when the pointer is actually inside an edge zone. Otherwise leave the cursor untouched so the user-set cursor persists.

**External code to remove:**

- `src/main/native/src/platform/linux/cursor.rs::LAST_CURSOR_BY_HANDLE` storage and the post-event `XIDefineCursor` re-applier.

**E2E gate (Linux):**

- [x] Hover a Compose `BasicTextField`: cursor turns into I-beam and stays I-beam while moving inside the field.
- [x] Hover a clickable button with `pointerHoverIcon(PointerIcon.Hand)`: cursor stays as Hand.
- [x] Move pointer to a window edge: cursor switches to resize cursor, then back to the Compose cursor when leaving the edge zone.
- [ ] Multi-pointer (XInput2) still works on a touch-enabled device.

---

## Phase 3 — Linux: realize GtkWindow on build

**Why:** GTK realizes widgets lazily; tao's `Window::new` returns before the underlying `GdkWindow` exists, so `nativeLinuxHandles` would return 0 if called too early.

**Patch (`0003-linux-realize-on-build.patch`):**

- `src/platform_impl/linux/window.rs::Window::new` — call `gtk_window.realize()` before returning. Documents that the X11 XID / Wayland `wl_surface` are guaranteed valid post-build.

**External code to remove:**

- `src/main/native/src/event_loop.rs:138-143` — the manual `window.gtk_window().realize()` call wrapped in `#[cfg(target_os = "linux")]`.

**E2E gate (Linux):**

- [x] First-frame paint via EGL succeeds (no `EGL_BAD_NATIVE_WINDOW`).
- [x] `nativeLinuxHandles` returns a non-zero `nativeWindow` synchronously in the `WINDOW_READY` callback.
- [x] No flicker / black frame on window open.

---

## Phase 4 — Windows: clamp on `set_min_inner_size`

**Why:** tao stores the constraint but Windows only enforces it via `WM_GETMINMAXINFO` during user-driven resizes. Programmatic `set_inner_size` calls smaller than the minimum bypass it silently.

**Patch (`0004-windows-clamp-min-size.patch`):**

- `src/platform_impl/windows/window.rs::set_min_inner_size` — after storing the constraint, if the current inner size is smaller, immediately resize to the constraint.

**External code to remove:**

- `src/main/native/src/event_loop.rs:250-259` — the manual clamp loop.

**E2E gate (Windows):**

- [ ] Setting `windowState.size` smaller than the configured `minSize` clamps to `minSize`.
- [ ] Drag-resize cannot make the window smaller than `minSize`.
- [ ] Maximize / restore preserves the clamp.

---

## Phase 5 — macOS: IME `firstRectForCharacterRange:`

**Why:** tao returns a 0×0 rect, breaking AppKit's press-and-hold accent picker and emoji popover positioning.

**Patch (`0005-macos-ime-rect.patch`):**

- `src/platform_impl/macos/view.rs` — add a public `set_ime_rect(NSRect)` setter on the view, return it from `firstRectForCharacterRange:` if non-zero, fallback to current behaviour otherwise.
- Expose via `WindowExtMacOS::set_ime_rect`.

**External code to remove:**

- The ObjC swizzle of `firstRectForCharacterRange:` in `nucleus_tao_*.m`.
- Keep the `nativeSetImeRect` JNI entry point but route it to the new tao API.

**E2E gate (macOS):**

- [ ] Long-press `e` in a Compose `BasicTextField`: accent picker appears anchored under the caret (not at 0,0).
- [ ] `Cmd+Ctrl+Space` emoji picker appears under the caret.
- [ ] CJK input (Pinyin / Kotoeri) candidate window positions correctly.

---

## Phase 6 — macOS: `NSView.isFlipped = YES`

**Why:** tao ships its NSView with `isFlipped == NO` (AppKit default), forcing manual Y-flipping in our accessibility code before `convertRect:toView:nil`.

**Patch (`0006-macos-nsview-isFlipped.patch`):**

- `src/platform_impl/macos/view.rs` — override `is_flipped` to return `YES`.
- Audit tao's internal coordinate paths (event delivery, IME) for any code that assumed bottom-left origin and adapt.

**External code to remove:**

- `src/main/native/macos/a11y.m:282-286` — the manual Y-flip branch when `view.isFlipped == NO`.

**E2E gate (macOS):**

- [ ] VoiceOver bounding boxes align with on-screen Compose elements (test with VO cursor on buttons, text fields, lists).
- [ ] Mouse click coordinates still hit the right Compose target.
- [ ] IME caret rect still positioned correctly (regression check from Phase 5).

---

## Phase 7 — Linux: cursor during `begin_resize_drag`

**Why:** Already covered as a bonus in Phase 1 patch. If split off:

**Patch (`0007-linux-cursor-during-resize-drag.patch`):**

- `src/platform_impl/linux/event_loop.rs:548` — call `window.set_cursor(direction.to_cursor())` immediately before `begin_resize_drag`.

**E2E gate:** verified as part of Phase 1.

---

## Phase 8 — Windows: opt out of native `IDropTarget`

**Why:** tao unconditionally calls `RegisterDragDrop` on every HWND. Win32 allows only one `IDropTarget` per HWND, so we must `RevokeDragDrop` before registering ours.

**Patch (`0008-windows-opt-out-drag-drop.patch`):**

- Add a builder option `WindowBuilder::with_native_drag_drop(bool)` (default `true` for upstream compat).
- When `false`, skip the `RegisterDragDrop` call and the associated `IDropTarget` impl.

**External code to update:**

- `src/main/native/src/event_loop.rs::CreateWindow` — pass `with_native_drag_drop(false)`.
- Remove the `RevokeDragDrop` call in `windows/nucleus_tao_dnd.c:391-394`.

**E2E gate (Windows):**

- [ ] Drag a file from Explorer onto the window: Compose receives the drop with the correct file paths.
- [ ] Drag a string from another app: Compose receives the text.
- [ ] No native `WindowEvent::FileDropped` fires (we own the drop).

---

## Phase 9 — Linux: opt out of touch dispatch (or attach to toplevel upstream)

**Why:** tao 0.35's bin_child is a no-window GtkBox; we manually attach touch handlers to the toplevel because tao's own touch wiring doesn't fire there.

**Patch (`0009-linux-touch-toplevel.patch`):**

- `src/platform_impl/linux/event_loop.rs` — attach `connect_event` for touch events to the toplevel `GtkApplicationWindow` instead of (or in addition to) the bin child.

**External code to remove:**

- `src/main/native/src/platform/linux/touch.rs` — the duplicated GTK touch wiring.

**E2E gate (Linux, touch device required):**

- [ ] Single-finger tap dispatches `Press`/`Release` to Compose.
- [ ] Two-finger pinch in a `LazyColumn` scrolls / zooms as expected.
- [ ] `WindowEvent::Touch` arrives with stable finger ids per gesture.

---

## Phase 10 — macOS: window drag uses latched mousedown

**Why:** tao calls `[NSApp currentEvent]` (= `NSLeftMouseDragged` when invoked from a Compose Move handler) and synthesises a fake `LeftMouseDown`, losing pressure / click-count fidelity. Tao's call is also synchronous, blocking modal event tracking.

**Patch (`0010-macos-window-drag-latched.patch`):**

- `src/platform_impl/macos/window.rs::drag_window` — use the latched `NSLeftMouseDown` stored by the view's `mouseDown:` impl, and dispatch via `dispatch_async(dispatch_get_main_queue())`.

**External code to remove:**

- `src/main/native/macos/window_drag.m::nucleus_tao_start_window_drag` and the JNI route that calls it. Restore the direct `tao::Window::drag_window()` call in `window_jni.rs:194-213`.

**E2E gate (macOS):**

- [ ] Drag from the custom titlebar: window follows pointer immediately, no click-count / pressure loss.
- [ ] Reorderable list items still receive their drag gesture (no event stolen by AppKit's modal loop).
- [ ] Double-click on the titlebar still triggers zoom (click-count preserved).

---

## Phase 11 — macOS: opt out of native file-drop

**Why:** tao registers `NSPasteboardTypeFileURL` and emits `WindowEvent::FileDropped`. We override the four NSDraggingDestination methods via `class_replaceMethod` to route drops through our Compose payload pipeline.

**Patch (`0011-macos-opt-out-file-drop.patch`):**

- `WindowBuilder::with_native_file_drop(bool)` — default `true`. When `false`, do not register `NSPasteboardTypeFileURL` and do not implement the dragging destination methods.

**External code to remove:**

- `src/main/native/macos/dnd.m:184-187` — the four `class_replaceMethod` calls.

**E2E gate (macOS):**

- [ ] Drop a file from Finder onto the window: Compose receives the drop with the correct paths.
- [ ] Drop text / image: Compose receives the matching transferable.
- [ ] No tao-side `WindowEvent::FileDropped` fires.

---

## Phase 12 — macOS: trackpad gesture `WindowEvent`

**Why:** tao 0.35 only exposes `TouchpadPressure`. Magnification and rotation gestures are intercepted via a custom `NSEvent` global monitor.

**Patch (`0012-macos-trackpad-gesture.patch`):**

- Add `WindowEvent::TrackpadGesture { kind: Magnify | Rotate, phase, value }`.
- `src/platform_impl/macos/view.rs` — implement `magnifyWithEvent:` and `rotateWithEvent:`, dispatch as the new event.

**External code to remove:**

- `src/main/native/macos/touchpad_gestures.m` — the `NSEvent` local monitor and its callback wiring.
- `src/main/native/src/platform/macos/trackpad.rs` — replace with a thin consumer of the new `WindowEvent`.

**E2E gate (macOS, trackpad required):**

- [ ] Pinch on the trackpad over Compose: zoom gesture is received with phase Begin / Update / End.
- [ ] Rotate on the trackpad: rotation gesture received.
- [ ] Smart-zoom (two-finger double-tap) still works.

---

## Out of scope (kept as external workarounds)

- **L3** — GLX `Display*` via `gdk_x11_display_get_xdisplay()`. Architectural: GLX requires the same `Display` connection as the GdkWindow; tao's `XOpenDisplay(NULL)` is fundamentally incompatible. Keep the bypass.
- **L5** — `with_transparent(true)` for ARGB visual. Already a public tao API; not a hack, just configuration.
- **W3** — `RegisterTouchWindow` Windows touch routing. tao's default is intentional and our routing is robust; no upstream change worthwhile.
- **M6** — Custom `NSAppleEventManager` handler for deep-link cold start. tao would need a non-trivial AppleEvents API surface; current C-side hook is cleaner.

---

## Bump procedure (tao 0.35 → 0.36)

```bash
# 1. Pull the new upstream version
cargo update -p tao
cp -r ~/.cargo/registry/src/index.crates.io-*/tao-0.36.0/* \
      decorated-window-tao/src/main/native/vendor/tao/

# 2. Re-apply the patch series in order
cd decorated-window-tao/src/main/native/vendor/tao
for p in ../tao-patches/*.patch; do
  git apply --3way "$p" || echo "CONFLICT: $p"
done

# 3. For each conflict: resolve by hand, then regenerate the patch
git diff > ../tao-patches/000X-foo.patch

# 4. Re-run all E2E gates from Phase 0 through the last applied phase
```

Estimated cost per bump: 30 min – 2 h depending on conflict density.

---

## Success criteria

- Each phase's commit removes its corresponding external hack — no half-finished phases.
- `vendor/tao-patches/README.md` lists every patch with rationale and (when applicable) link to the upstream issue / PR.
- `./gradlew preMerge` is green on all three OSes after each phase.
- `sample-tao` and `sample-cmp` pass manual E2E checklists for the affected feature.
- After Phase 12, the codebase has zero remaining tao-related hacks except the four explicitly out-of-scope ones above.

---

## Estimated effort

| Phase | Effort  | Risk   | Platform |
| ----- | ------- | ------ | -------- |
| 0     | 1 h     | Low    | All      |
| 1     | 2 h     | Low    | Linux    |
| 2     | 1 h     | Low    | Linux    |
| 3     | 30 min  | Low    | Linux    |
| 4     | 1 h     | Low    | Windows  |
| 5     | 2 h     | Low    | macOS    |
| 6     | 3 h     | Medium | macOS    |
| 7     | covered | n/a    | Linux    |
| 8     | 2 h     | Medium | Windows  |
| 9     | 2 h     | Medium | Linux    |
| 10    | 2 h     | Medium | macOS    |
| 11    | 1 h     | Low    | macOS    |
| 12    | 4 h     | Medium | macOS    |

**Total: ~3 days of focused work**, vs continuing to maintain 17 external workarounds.

---

## Optional: upstream contributions

For each patch, consider opening a PR against https://github.com/tauri-apps/tao. While unmerged, the local fork stays. Once merged upstream, drop the corresponding `.patch` file at the next bump.

Priority candidates: 0001 (resize zones), 0004 (Windows clamp), 0006 (NSView isFlipped), 0012 (trackpad gestures).
