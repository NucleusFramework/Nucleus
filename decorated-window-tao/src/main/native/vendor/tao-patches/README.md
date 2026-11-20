# tao patches

Nucleus carries its own changes on top of the vendored tao in `../tao/`. This
directory records what they are, why each exists, and which of them upstream
tao should take.

## Pinned upstream version

- **tao**: `0.37.3` (crates.io), taken by the bumps from 0.35.0 to 0.37.2 and
  then to 0.37.3.
- Pristine source: the `.crate` from crates.io, checked against the checksum in
  the crates.io index. Cargo never downloads it here, since `[patch.crates-io]`
  points tao at `../tao/`:

```bash
# fetch_tao <version> <dir>: unpacks pristine tao into <dir>/tao-<version>
fetch_tao() {
  mkdir -p "$2" && curl -sSfL "https://static.crates.io/crates/tao/tao-$1.crate" -o "$2/tao-$1.crate" &&
  want=$(curl -sSf https://index.crates.io/3/t/tao | grep "\"vers\":\"$1\"" | sed 's/.*"cksum":"\([0-9a-f]*\)".*/\1/') &&
  echo "$want  $2/tao-$1.crate" | sha256sum -c --quiet && tar -xzf "$2/tao-$1.crate" -C "$2" &&
  rm -f "$2/tao-$1/.cargo_vcs_info.json"  # packaging metadata, not vendored
}
```

## Where the changes live

Nucleus's changes are made directly in `../tao/`, marked `PATCH(nucleus)` or
`Nucleus patch` in the code. No patch file is kept: the changes are whatever
differs between `../tao/` and pristine tao at the pinned version. To see them,
from `decorated-window-tao/src/main/native`:

```bash
W=$(mktemp -d) && fetch_tao 0.37.3 "$W/crates"
diff -ruN --exclude=target "$W/crates/tao-0.37.3" vendor/tao
```

The 0.35.0 tree kept a numbered patch series (`0001`–`0019`). It covered
about a fifth of the Nucleus commits that touched tao, two of its patches
described code `08bc6b80a` had already removed, and its patches overlapped, so
it could not drive a bump. The catalog below takes over its job of explaining
each change; the old descriptions are in git history
(`git log -- decorated-window-tao/src/main/native/vendor/tao-patches`).

## Catalog

**Outcome on 0.37.2 and 0.37.3**: *kept* (re-applied unchanged in meaning), *ported*
(re-expressed on upstream's new structure), *dropped* (upstream now does it, or
it no longer applies). Where 0.37.3 changed a row's outcome, the row says so.
Everything else in 0.37.3 was taken as is: on Linux, `set_theme(None)` follows
the portal (#1378) and `ThemeChanged` goes to each window's own id (#1413); on
macOS, main-thread window methods (#1420), redraws queued while the handler
runs (#1386) and the maximizable fix (#1411); on Windows, logical positions
matched to the closest monitor (#1397), `drag_window` from other threads
(#1400) and size constraints for undecorated windows with shadows (#1436).

**Upstream**: *send* (a bug in stock tao that a small, self-contained PR would
fix), *maybe* (useful, but needs new public API or a design discussion first),
*no* (exists for Nucleus's embedding model, not a tao bug). "Upstream status"
is what was found in tao's issues, PRs and `dev` branch on 2026-11-17.

### Linux

| Change | Commits | Outcome | Upstream | Upstream status / notes |
|---|---|---|---|---|
| Realize the GtkWindow in `Window::new`, so the XID / `wl_surface` is valid on return | 84655453a | kept | maybe | Nothing upstream. Changes when GTK maps, so it needs care. |
| Resize interception moved to the embedder: tao's hit test, resize cursor, `begin_resize_drag` and touch-resize handler removed. Supersedes the old `0001`/`0002` (resize zones, cursor outside zones) | 08bc6b80a (replaces ed2493b16, 6b20825f4) | kept | no | Upstream's touch-resize fix #1380 has nothing to apply to. |
| Synchronous maximize before the first map | 41fec0ce6 | kept, over upstream's configure-event maximize (#1218) | no | Upstream fixed Wayland error 71 (#977) its own way. |
| Modifier-only keys (Alt, Ctrl, Shift, Super) also emit `KeyboardInput` | c1a12217f | kept | send | Nothing upstream; stock tao drops these key events. |
| Minimize/restore event on X11, through a hook | 576bd599e | kept | no | A `Minimized` event was declined upstream (#892, tauri#15458). |
| Wayland minimize synthesis | 672367bc1 | kept | no | |
| Tiled (Aero Snap) state | 484a20c7a | kept | maybe | New API. |
| Wayland overlay popups (`GTK_WINDOW_POPUP` subsurfaces), hide crash, drag tracking outside the window | 393ef7c18 | kept | split | Drag tracking with event coordinates: send (`CursorMoved` stops at the window edge during a drag). Popups: no. |
| Full modifier state in `ModifiersChanged` | 64072f520 | kept | send | Related: #470. |
| Modifier state derived from every key event, so a lost release heals | 241ff1767 | kept | send | |
| Drain the draw queue; never park with redraws pending (old `0004`) | 66b34a26a | kept | send | Open upstream work: #1335 / #1336. Comment there instead of a duplicate PR. |
| Real X server timestamp for focus and restore (old `0005`) | 1b582e9e9 | kept | send | Related: tauri#6310, tauri#5974. Mutter ignores the requests without it. |
| Native GTK CSD shadow on undecorated Wayland windows (hidden title bar) | de5e20ff9 | kept | maybe | Tauri users ask for a native shadow; needs API design. |
| Discrete wheel scroll gives a unit delta | f81fca49e | kept | send | The bug is in stock 0.37.3 (no `SMOOTH_SCROLL_MASK` handling). |
| Empty, reversible click-through input region (old `0006`) | d1aa1d3a9 | kept | send | Open upstream work: #1409, #1204, #1425. Offer the empty-region part. |
| Force one window onto X11 in a Wayland session | 446c3237b | kept | no | |
| Input method: `IMMulticontext`, preedit/commit events, filter-first key path, press/release pairing, `set_ime_position` | 3d58c5279, 7bc9ea602, 105b6c9e3 | kept | split | The `IMMulticontext` switch and `set_ime_position`: send (CJK input does not work in stock tao). Preedit/commit events: maybe, needs a cross-platform `Ime` event. WebKit runs its own input context, so offer it opt-in. |
| GDK's placeholder frame extents never published (old `0007`) | 20d2c5fb4, 17ac39df8 | kept | send | Nothing upstream. |
| Context menu as an `xdg_popup`, and its placement | 817f5e8ea | kept | no | |
| Embedded native view keyboard and focus ownership | 342b080d4 | kept | no | |
| Touchpad `Pan` events (needs the `ScrollPhase` field, see shared) | fe7b213f9 | kept | maybe | |
| Key auto-repeat flag (old `0010`) | f582745db | kept | send | Stock tao always reports `repeat: false`. |
| Drain pending GTK events between loop steps (#814) | 794feaa98, 43936bdac | kept | maybe | Performance; the batch limit is a heuristic. |
| Touchscreen crossing events kept out of mouse input | 81ad361ef | kept | maybe | |
| Input context focused only while a text field owns input (#842) | 57044dfec | kept | send, with the input method work | Without it the on-screen keyboard opens on every activation. |
| Tablet pen device ids (old `0011`) | 04c0262fb | kept | maybe | New API on all three platforms. |
| Size echo for a window sized while hidden (old `0015`) | 608347413 | kept | send | |
| System decorations: GTK's own title bar moves, maximizes and resizes the window (old `0018`) | 6606212b7, 37b9812df, dd7982fd9 | ported | no | Upstream #1218 deleted `WlHeader`, so the half that skipped it is gone; the marker and event pass-through remain. |
| Non-resizable window sized through its content (old `0017`) | 0fea4589c | kept | send | Would fix upstream #561; see also #929, #1370. |
| Popup sized before map (old `0019`) | 70ed890b8 | kept | no | |

Upstream Linux changes **not** taken:

- #1218's timestamp de-duplication of motion and button events, and its switch
  from `Propagation::Stop` to `Proceed`. Nucleus keeps `Stop`, except over GTK's
  own decorations, which is its narrower fix for the same problem.
- #1218's maximize in the first `configure-event`. Nucleus keeps its
  synchronous maximize.
- #1218's empty `EventBox` title bar on undecorated Wayland windows (KWin
  drawing a frame around them, GTK #5479). Nucleus negotiates
  `org_kde_kwin_server_decoration` itself (`src/platform/linux/server_decoration.rs`,
  #816), and the title bar would give borderless windows the `csd` style class
  that negotiation keys on.
- #1380 (touch resize starts on `TouchBegin`): tao's touch-resize handler no
  longer exists here.

Taken: #1173 (`RwLock` fields), #1351 (null display checks), #1228 (JIS keys),
#1391 (scancodes), #1218's removal of `WlHeader`.

### macOS

| Change | Commits | Outcome | Upstream | Upstream status / notes |
|---|---|---|---|---|
| `setFrameTopLeftPoint` / `setContentSize` applied synchronously on the main thread | 6d7f082f6, 558c8162f | dropped on 0.37.3 | — | Upstream #1420 (closes #1406) runs every window method on the main thread, synchronously. |
| `PanicInfo` Weak gone after the loop exits: return instead of panicking in `wakeup` / `cleared` | dc02669df, 93fad99f2 | kept | send | Two-line fix; still `.expect` on `dev`. Closest report: winit#2282. |
| Force-set the cursor when it changes inside the current cursor rect | f213b9533 | ported | maybe | |
| No hidden `NSTextView` first responder (no forced I-beam) | ff3871d76 | kept | no | |
| Maximize at creation without the zoom animation; `is_zoomed` against `visibleFrame` | feb07a2f4, a3a799d33 | ported; on 0.37.3 re-ported onto `WindowDelegate`'s ivars (#1420 removed `UnownedWindow` and `SharedState`) | maybe | |
| Resize steps delivered during AppKit's zoom animation, one present per resize, and AppKit's own frame animations stepped (#576) | 3642e16b5, b92531fc7, 9c8fff627 | ported (`setFrame:display:animate:` is now a `define_class!` method); on 0.37.3 the zoom state moved to `WindowDelegate`'s ivars, and the animation holds the delegate weakly | maybe | Open upstream PR #1207 takes a different route (`windowShouldZoom:toFrame:`). Comment there. |
| Input method (#595): preedit and commit events, `markedRange` length, `selectedRange`, `attributedSubstringForProposedRange`, `characterIndexForPoint`, input-method-consumed key events | 95e3b4bbb, 3b9fdcbc3 | ported to the `define_class!` view | split | `markedRange` and the stub answers: send (winit already returns `{0, len}`). Events and consumed keys: maybe, needs an `Ime` event. |
| Press-and-hold accent picker: repeats go to `interpretKeyEvents`, commits through `replacementRange` (#611, #612) | 94b50c656, e37a77777, cf83ec2e0 | ported | send | winit has no repeat gate. Needs a test that repeats don't type twice. |
| Overlay-safe detach; `set_focusable` retain leak (#494) | 5c63ce0f2 | detach kept, leak fix dropped | no | Upstream's `AtomicBool` ivar no longer leaks. |
| Cancelable quit: `terminate:` override, Electron's semantics | ff38b93b1, a4801d8e3 | ported; on 0.37.3 a `define_class!` method of `TaoApp`, as upstream's `sendEvent:` became | maybe | Upstream's `ExitRequested` work (#1003) is open and conflicting. |
| Trackpad scrolling as AWT does it: `ScrollPhase`, horizontal sign not negated, `PixelDelta` in logical points (#652–#656) | fe7b213f9 | ported | split | Sign: send, following winit #2105, but it changes behavior for existing users. Phase: maybe, model it on winit's `ScrollSource`. Logical points: no. |
| Hover kept through AppKit's phantom `mouseExited`; position on `mouseEntered` | 2c7ea4165 | ported | maybe | |
| Back and forward mouse buttons from `buttonNumber` | c784a8863 | dropped on 0.37.3 | — | Upstream #1434, the same mapping. |
| Cursor hook for the embedder (#746) | 24e00fd3b | ported to `Retained<NSCursor>` | no | |
| WebKit cursor loading that can fail (#746) | 2029e9c82 | dropped | no | Unreachable with the hook: Nucleus sets only icons its own table resolves (`Move` falls back to the open hand there), so tao never loads `move` or `cell` from HIServices. The bundle is present on macOS 27, and every private `NSCursor` selector tao calls works there. |
| Tablet pen device ids (old `0012`) | f1a4f58eb | ported | maybe | |
| Minimize/deminimize hook in the window delegate | (with the above) | ported (0.37.3: on `WindowDelegate` itself) | no | |

### Windows

| Change | Commits | Outcome | Upstream | Upstream status / notes |
|---|---|---|---|---|
| Nucleus's IMM32 state machine: preedit and commit events | 327ce6a6f, 096172a23, b15a1e0a6 | kept; replaces upstream's `ime.rs`, and upstream's `WM_IME_ENDCOMPOSITION` text event is dropped (it typed every commit twice) | maybe | `dev` has `// TODO: Catch up with winit on the IME events`. Needs an `Ime` event. |
| `VK_PROCESSKEY` keys not delivered (#558) | 327ce6a6f | kept on upstream's keyboard refactor (#1238) | send | |
| `set_ime_position` places the candidate window too | 327ce6a6f | dropped on 0.37.3 | — | Upstream #1437, the same code. |
| Key-event locks not held across `PeekMessageW` (#640) | 5ccfec799 | dropped | — | Upstream #1215 / #1238. |
| Input locks released before pumping calls (#644) | 34847dde2 | kept, except the `TaskbarCreated` part | — | `TaskbarCreated` is upstream #1264. |
| No `RegisterTouchWindow` (caption drags by touch) | 507b904c9 | kept (the call moved to `InitData::create_window`) | send | Open upstream issue #525; add the root cause there. |
| `SIZE_MINIMIZED` not reported as `Resized(0, 0)`; minimized flag from `WM_SIZE` | 367eebee7 | kept | no | winit behaves like stock tao; #1419 (open) syncs the flag but keeps the event. |
| Minimized hook | ff979efba | kept | no | |
| Pointer activation and focus for overlay and popup windows | a7bb17c10 | kept | no | |
| Initial size of a maximized window; trust `IsZoomed` over the `WM_SIZE` wparam | f644aff5f | kept | send | Related, different: #1306, #1352 (in 0.37.2), #1426. |
| Show a window that opens maximized only after maximizing it (no flash) | 6999484e0 | ported: upstream's creation order changed, so the show was moved after `set_maximized` in `init` | maybe | |
| Un-minimizing a maximized window keeps it maximized | ea40ff867 | kept | send | Older #622 was a different path; see #1318 / #1419. |
| `set_min_inner_size` does not un-maximize | f644aff5f | kept | send | Keep the size-bounds re-check if sent: Nucleus drops it, so a too-small window is corrected only on the next resize. |
| `SKIP_TASKBAR` as a tool window, kept across `apply_diff` | c5604ca14 | kept; upstream's conditional `set_skip_taskbar` at creation taken | no | A maintainer declined `WS_EX_TOOLWINDOW` (tauri#10422). At most an opt-in. |
| Wheel notches unscaled (Chromium-style scroll) | 7c6974e65 | kept | no | |
| Trackpad pinch through a magnify hook | 55837dcd8 | kept | no | |
| Smooth resize: synchronous repaint during a size move | 905a66f0b | kept | maybe | |
| `FlashWindow`'s `WM_NCACTIVATE(FALSE)` is not a focus loss; modal dialogs (#832, old `0014`) | fc0148bad | kept | send (the guard) | |
| Session end through cancelable close requests (#751, old `0016`) | 8f086e49b, 87cff7274 | kept; with the hooks installed, the thread target returns before upstream's `process::exit` (#1157) | maybe | Open upstream issue #1115, blocked on `ExitRequested`. Comment there with the design. |
| A mouse drag ends at the OS release point (#837) | ffbb628db, b69e1307c | kept | split | `WM_LBUTTONUP` posted with lparam 0 on `WM_EXITSIZEMOVE`: send (`GetCursorPos`). The release slot: no. |
| Pen device ids and `WM_POINTERLEAVE` (old `0013`) | 8a41e4cf3 | kept | maybe | |

Upstream Windows changes not taken: #1157's `process::exit` on
`WM_ENDSESSION` when Nucleus's session-end hooks are installed. Taken: the
keyboard refactor (#1238), no window subclassing (#1231), lock reentrancy
(#1215), delayed maximize while hidden (#1306, #1352), `windows` 0.62.

### Shared

| Change | Commits | Outcome | Upstream |
|---|---|---|---|
| `WindowEvent::MouseWheel` gains `scroll_phase: ScrollPhase` (old `0008`) | fe7b213f9 | kept | maybe |
| `ImePreedit` / `ImeCommit` events | the input method commits above | kept | maybe |
| `PointerSource` device-id extensions on each platform | 04c0262fb, f1a4f58eb, 8a41e4cf3 | kept | maybe |
| `WindowBuilderExtUnix::with_system_decorations`, `with_csd_hidden_titlebar`, popup builders | 6606212b7, de5e20ff9, 393ef7c18 | kept | no |

## Sending upstream

Upstream (tauri-apps/tao) wants a conventional-commit title, signed commits, a
`.changes/*.md` covector changefile, `cargo fmt`, MSRV 1.85 and gtk-rs 0.18.
In rough order of value for effort:

1. Small Linux fixes, one PR each: the key repeat flag, modifier-only key
   events and full modifier state, the discrete wheel delta, placeholder frame
   extents, the size echo for hidden windows, the non-resizable content size
   (#561).
2. Small macOS fixes: the `PanicInfo` return, `markedRange` and the
   `NSTextInputClient` stubs.
3. Small Windows fixes: the
   `VK_PROCESSKEY` drop, `GetCursorPos` for the synthetic `WM_LBUTTONUP`, the
   `FlashWindow` guard, un-minimize to maximized, `set_min_inner_size`.
4. Comments on existing threads instead of new PRs: #1335/#1336 (draw queue),
   #1409 (empty input region), #1207 (zoom animation), #525
   (`RegisterTouchWindow`), #1115 (session end).
5. Proposals that need API design first: a cross-platform `Ime` event
   (preedit and commit; Linux `IMMulticontext` as an opt-in), a scroll phase
   modelled on winit's `ScrollSource`, pointer source device ids.

## Bump procedure

Diff the vendored tree against pristine tao at the pinned version, then
re-apply that diff on the new version. Below, the diff is the commit `nucleus`
(pristine `OLD` → `../tao/`), and `git cherry-pick` re-applies it on pristine
`NEW` three-way: a hunk whose context upstream changed is merged rather than
rejected, what cannot be merged is left as conflict markers, and a file that
upstream deleted or added is a conflict too. (`git apply --3way` on a patch file
does the same for edits, but gives up on the whole diff when a file it touches
no longer exists; on the 0.37.2 bump that was `windows/minimal_ime.rs`.) From
`decorated-window-tao/src/main/native`:

```bash
OLD=0.37.3 NEW=0.38.0   # the pinned version, and the one to move to
NATIVE=$PWD W=$(mktemp -d)
fetch_tao $OLD "$W/crates" && fetch_tao $NEW "$W/crates"
cd "$W" && git init -q -b base tree && cd tree
rsync -a "$W/crates/tao-$OLD/" . && git add -A && git commit -qm "tao $OLD"
git checkout -qb nucleus && rsync -a --delete --exclude=.git --exclude=target "$NATIVE/vendor/tao/" . \
  && git add -A && git commit -qm nucleus        # the Nucleus diff: git show nucleus
git checkout -qb new base && rsync -a --delete --exclude=.git "$W/crates/tao-$NEW/" . \
  && git add -A && git commit -qm "tao $NEW"
git -c merge.conflictStyle=zdiff3 cherry-pick --no-commit nucleus   # re-apply it on NEW
git diff --name-only --diff-filter=U              # files left with conflicts
```

Resolve each conflict. Then **check every file the diff touches both ways**,
conflicted or not: `git diff nucleus -- <file>` must show only upstream's
changes, and `git diff new -- <file>` only Nucleus's. A hunk can apply cleanly
and still mean something else on the new code, a hunk upstream already made
can be merged away silently, and a `zdiff3` conflict block can leave Nucleus
lines out. On the 0.37.2 bump this check caught a
`Propagation::Stop` flipped back to `Proceed`, a removed `is_wayland` that
Nucleus still uses, a re-added `RegisterTouchWindow`, and upstream's IME text
event typing every commit twice.

A patch release can restructure as much as a minor one: 0.37.3 (#1420) moved
the macOS window methods and their state from `UnownedWindow` and `SharedState`
into `WindowDelegate` and its ivars, and turned `TaoApp` into a `define_class!`.
Where upstream rewrote a file like that, it is simpler to start from upstream's
version (`git show new:<file> > <file>`) and re-express each hunk of
`git diff base nucleus -- <file>` on it than to resolve the conflict blocks.

Then copy the result into the vendor directory:

```bash
rsync -a --delete --exclude=.git --exclude=target ./ "$NATIVE/vendor/tao/"
```

Set `tao = "<NEW>"` in `../../Cargo.toml`, run `cargo update -p tao`,
`cargo check` for each target (`x86_64-unknown-linux-gnu`,
`aarch64-apple-darwin`, `x86_64-pc-windows-msvc`),
`:decorated-window-tao:check`, and the headful suite on each OS. Update the
pinned version and the catalog in this file.
