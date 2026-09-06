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
| 0007  | `0007-macos-scroll-phase-and-horizontal-sign.patch` | 7 | macOS (+ field on all backends) | `WindowEvent::MouseWheel` gains `scroll_phase: ScrollPhase` — the full AppKit `phase` / `momentumPhase` of a trackpad scroll (`None` for a wheel, and on Windows / Linux), which `TouchPhase` cannot express; Nucleus routes gesture steps to Compose Pan events (#654). Also stops negating `scrollingDeltaX` in `scroll_wheel`: AppKit's sign already matches `MouseScrollDelta`'s documented convention (and winit), and the extra flip reversed horizontal trackpad scrolling once the consumer applied the AWT convention (#652). `PixelDelta` carries AppKit's logical points instead of `x backing scale`: AWT's `preciseWheelRotation` never sees the display scale (#653), and undoing the multiplication downstream with a second scale cache disagreed with the view's for a frame during display hops. |

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
