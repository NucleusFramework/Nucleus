# tao patches

Local patch series applied on top of vendored tao (see `../tao/`).

## Pinned upstream version

- **tao**: `0.35.0` (crates.io)
- Copied from: `~/.cargo/registry/src/index.crates.io-*/tao-0.35.0/`

## Patch series

Patches are applied in numeric order on the vendored tree at `../tao/`.
Tao 0.35.0 is already vendored; this file is the living list of patches.

| #     | File                              | Phase | Platform | Summary |
| ----- | --------------------------------- | ----- | -------- | ------- |
| 0001  | `0001-linux-resize-zones.patch`           | 1     | Linux    | Widen resize edge band to 8 px and corner zone to 16 px (logical), set the resize cursor before `begin_resize_drag` so it persists during the drag. Adds a `corner` parameter to `crate::window::hit_test`. |
| 0002  | `0002-linux-cursor-preserve-on-motion.patch` | 2 | Linux    | Only override the cursor on edge-zone entry / exit. Outside resize zones the application-level cursor (text I-beam, hand, custom icon) is preserved across motion events. |
| 0003  | `0003-linux-realize-on-build.patch`       | 3     | Linux    | Realise the GtkApplicationWindow at the end of `Window::new` so the underlying GdkWindow (X11 XID / Wayland wl_surface) is valid synchronously when the constructor returns. |
| 0004  | `0004-linux-drain-draw-queue.patch`       | 4     | Linux    | `run_return`: treat pending redraws like pending events (don't park in the blocking `gtk_main_iteration` while `draws` is non-empty) and drain the whole draw channel per cycle instead of one redraw per wakeup. Fixes multi-window frame starvation (each window rendered at ~refresh/N). |
| 0005  | `0005-linux-restore-activation-timestamp.patch` | 5 | Linux | Stamp `Focus` and `Minimized(false)` activations with a real X server timestamp (`gdk_x11_get_server_time`). Mutter's focus-stealing prevention drops `_NET_ACTIVE_WINDOW` requests carrying `GDK_CURRENT_TIME` (0) and keeps a deiconified window Iconic with `_NET_WM_STATE_DEMANDS_ATTENTION`, so restore/focus silently no-op and `EVENT_MINIMIZED(false)` never fires on GNOME X11/XWayland (openbox honors the 0 timestamp, which is why CI never saw it). No-op on Wayland. |
| 0006  | `0006-linux-cursor-ignore-events-region.patch` | 6 | Linux | `CursorIgnoreEvents`: install a genuinely *empty* input region instead of upstream's 1x1 rectangle at the origin (which leaves the top-left pixel clickable), and clear it through the same `GdkWindow` with a NULL region. Upstream cleared it on the `GtkWidget`, which never undid a shape installed on the `GdkWindow`, so click-through could not be switched back off. |
| 0007  | `0007-linux-outer-geometry-placeholder.patch` | 7 | Linux | Stop latching GDK's `(0, 0, 1, 1)` frame-extents placeholder into `outer_position` / `outer_size`. `gdk_window_get_frame_extents` answers with it until the window is mapped and framed, so a `configure-event` that lands in that window pins it until the *next* one — seconds away, or never, on a software-rendered X server under a lightweight WM (the CI Xvfb + openbox leg). Consumers then read a 1x1 window at the screen origin: a torn-off window 1 dp wide, a satellite anchored against a 1px-tall child, a pointer aimed at a negative screen coordinate. The size falls back to the configure event's own (the whole surface, shadow included — what the frame is under CSD, and what `configure_client_size` subtracts the insets from); the position is kept as it was, since every substitute is wrong in a worse way — `event.position()` is frame-relative under a reparenting WM, `root_origin` goes back through `frame_extents`, and `gdk_window_get_origin` names the client rather than the frame. |
| 0008  | `0008-macos-scroll-phase-and-horizontal-sign.patch` | 8 | macOS (+ field on all backends) | `WindowEvent::MouseWheel` gains `scroll_phase: ScrollPhase` — the full AppKit `phase` / `momentumPhase` of a trackpad scroll (`None` for a wheel, and on Windows / Linux), which `TouchPhase` cannot express; Nucleus routes gesture steps to Compose Pan events (#654). Also stops negating `scrollingDeltaX` in `scroll_wheel`: AppKit's sign already matches `MouseScrollDelta`'s documented convention (and winit), and the extra flip reversed horizontal trackpad scrolling once the consumer applied the AWT convention (#652). `PixelDelta` carries AppKit's logical points instead of `x backing scale`: AWT's `preciseWheelRotation` never sees the display scale (#653), and undoing the multiplication downstream with a second scale cache disagreed with the view's for a frame during display hops. |
| 0009  | `0009-macos-cursor-hook.patch` | 9 | macOS | `tao::platform::macos::set_cursor_hook`: with a hook installed, `Cursor::from(CursorIcon)` yields `Cursor::Hooked` and `resetCursorRects` asks the embedder for the `NSCursor` (+0; null = Tao's own table for that icon) instead of Tao's table. Nucleus resolves every icon through `macos/nucleus_tao_cursors.h`, so the cursor rect AppKit re-asserts is the instance `nucleus_tao_set_cursor_icon` just set, and no private `NSCursor` factory is called where an Objective-C exception cannot be caught (`_moveCursor` throws "unrecognized selector" on macOS 27, #746). Also: `load_webkit_cursor` returns `Option` instead of unwrapping the HIServices `cursor.pdf` / `info.plist` lookups (the caller falls back to the arrow), which aborted the JVM inside `resetCursorRects` when the bundle was missing. |
| 0010  | `0010-linux-key-repeat.patch` | 10 | Linux | Report key auto-repeat: upstream always built `KeyEvent` with `repeat: false` (`// todo: implement repeat?`). GDK carries no repeat flag, but a held key arrives as a run of presses and a single release on both X11 (detectable auto-repeat) and Wayland (GDK's repeat timer), so `ImeState` keeps the set of physically held keycodes (before the input method and the embed check, cleared on focus-out) and a press of a held key is a repeat. Unit-tested in `ime.rs`. |
| 0011  | `0011-linux-pointer-source-device-id.patch` | 11 | Linux | Tell tablet tools from the mouse (#827): `CursorMoved` and `MouseInput` carry a `DeviceId` derived from the GDK event's *source* device — `GDK_SOURCE_PEN` and `GDK_SOURCE_ERASER` get ids of their own, everything else keeps the shared dummy id — and `platform::unix::DeviceIdExtUnix::pointer_source()` reads it back as a `PointerSource`. GDK delivers a tablet (Wayland `zwp_tablet_v2`, X11 XI2) as ordinary motion / button events, so without it Nucleus reported every pen as `PointerType.Mouse`. |
| 0012  | `0012-macos-pointer-source-device-id.patch` | 12 | macOS | Tell tablet tools from the mouse (#827), the macOS half of 0011: `DeviceId` carries a tool, `CursorMoved` and `MouseInput` report the pen (or its eraser end) for mouse events whose `subtype` is `TabletPoint`, and `platform::macos::DeviceIdExtMacOS::pointer_source()` reads it back as a `PointerSource`. Which end is in use is only named by the proximity event before the stroke, which AppKit hands to no particular view, so `TaoApp.sendEvent:` records it. Events that carry no subtype (enter, exit, wheel) keep the tool of the last mouse event. |
| 0013  | `0013-windows-pen-device-id.patch` | 13 | Windows | Tell a pen from a finger (#827), the Windows half of 0011/0012: WM_POINTER reports a pen as a `WindowEvent::Touch` like a finger, so its `DeviceId` carries the tool — the pen tip, or its eraser end while `POINTER_PEN_INFO.penFlags` has `PEN_FLAG_INVERTED` / `PEN_FLAG_ERASER` — read back with `platform::windows::DeviceIdExtWindows::pointer_source()`. Also reports `WM_POINTERLEAVE` for pens (only) as a `Cancelled` touch, so a hovering pen leaving the window or the digitizer's range can exit; a hover itself already arrived as a `Moved` touch with no contact. |
| 0014  | `0014-windows-flash-is-not-deactivation.patch` | 14 | Windows | Ignore a `WM_NCACTIVATE(FALSE)` on the window that is still `GetActiveWindow()`: it is `FlashWindow` blinking the caption, which Windows does to a modal dialog when a click hits the owner it disabled (#832). Upstream treated every blink as a focus loss / gain, so the dialog's focus toggled for as long as it flashed. A real deactivation still arrives through `WM_KILLFOCUS`. |
| 0015  | `0015-linux-unmapped-geometry-cache.patch` | 15 | Linux | Record the geometry of a window that is not mapped yet as it is requested: `set_inner_size` / `set_outer_position` write the inner and outer size / position caches when the GtkWindow is unmapped. Those caches are only fed by `configure-event`, which GTK never sends before the map, so a window sized and placed while hidden (wrap-content, #532 / #546) read its creation values — the outer size not even a size (upstream seeds it from `root_origin`) — and was centred against a 0 px tall frame. The frame is unknown before the map, so the outer size is the inner one until the first configure replaces both. |
| 0016  | `0016-windows-end-session-hooks.patch` | 16 | Windows | Hand logoff / restart / shutdown and Restart Manager closes to the embedder (#751): `platform::windows::set_end_session_hooks` installs the hooks every tao window calls on `WM_QUERYENDSESSION` (`(hwnd, lParam, nested) -> may end`) and `WM_ENDSESSION` (`(hwnd, ending, nested)`), app windows as well as the thread target — one app window answering TRUE on its own was enough for the Restart Manager to start ending the process. `hwnd` is always the thread target (it carries the shutdown block reason), `nested` is `should_buffer()`. With the hooks installed, the thread target's `WM_ENDSESSION` no longer runs `loop_destroyed()` (it froze the loop of an app that keeps running after it). Upstream answered the query with `DefWindowProc` (TRUE), so no window could veto. |

## Bump procedure (e.g. 0.35 → 0.36)

```bash
# 1. Pull the new upstream version into the vendor dir
rm -rf vendor/tao
cp -r ~/.cargo/registry/src/index.crates.io-*/tao-0.36.0 vendor/tao

# 2. Re-apply patches in order
cd vendor/tao
for p in ../tao-patches/*.patch; do
  git apply --3way "$p" || echo "CONFLICT: $p — resolve, then regenerate"
done

# 3. For each conflict: resolve by hand, then regenerate the patch
git diff > ../tao-patches/000X-foo.patch

# 4. Rebuild the native lib and run Tao scene + headful tests on the affected OS.
```

## Regenerating a single patch

The vendored tree is not a git repo. To regenerate `000X-foo.patch` after
editing files under `vendor/tao/`, diff against the pristine upstream:

```bash
PRISTINE=~/.cargo/registry/src/index.crates.io-*/tao-0.35.0
diff -ruN "$PRISTINE" vendor/tao \
  --exclude=Cargo.lock --exclude=target \
  > vendor/tao-patches/000X-foo.patch
```
