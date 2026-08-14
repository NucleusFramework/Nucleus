# Popup Layout Constraints — Refactor Plan

## Context

`TaoPopupSceneLayer` (macOS) and `TaoPopupSceneLayerWindows` initialize their inner `CanvasLayersComposeScene` with `size = host.parentWindowSize` as a "measurement chicken-and-egg defense": if the scene is constructed at `IntSize.Zero`, Compose's `RootMeasurePolicy` collapses the popup content to `0×0`, `boundsInWindow` is never written, and the popup never becomes visible.

That defense works but uses the **owner window's** size as the upper bound for layout. This is wrong in two visible cases:

1. **Small owner, big menu** — a 400×300 floating window opening a `DropdownMenu` with 40 items: Compose measures it constrained to 300 px tall on a 1200 px monitor, forcing internal scroll instead of laying out at full height + clipping/flipping at the screen edge.
2. **Owner near screen edge** — a popup anchored on the right edge of an owner positioned near the right edge of the monitor: Compose has no signal that more space exists on a different monitor reachable by flipping, because the constraint is the owner, not the screen.

The canonical pattern (Chromium views, Flutter Windows) is: **measure popups unconstrained or with the monitor work area as the upper bound, then position+clip against the work area**.

## Goal

Replace `host.parentWindowSize` as the inner scene's initial size with the work area of the monitor that hosts the owner window. Keep the existing chicken-and-egg defense (non-zero, realistic constraints) while removing the artificial owner-size ceiling.

Cross-platform: macOS + Windows. Linux popup layer (`PopupNativeBridge` on GTK) already has its own surface — out of scope for this pass.

## Non-goals

- Outside-click dismissal (already correct via `SetCapture`).
- Position flipping logic — that's `Popup.PositionProvider`'s job in Compose; this plan only fixes the **constraint** fed to layout.
- HiDPI per-monitor handling (already correct: `WM_DPICHANGED` accepted, scene rebuilt on scale change).
- Replacing `ClientToScreen` with `MapWindowPoints` — separate cleanup, not blocking.

## Architecture

### New host capability

Add to both `TaoPopupHost` (macOS) and `TaoPopupHostWindows`:

```kotlin
/**
 * Size of the work area (screen minus dock/taskbar) of the monitor that
 * currently contains the owner window. In physical pixels. Used by
 * popup layers as the upper bound for inner-scene layout — popups can
 * legitimately extend beyond the owner, up to the screen edge.
 *
 * Read at popup construction time; not reactive. Popup layers do not
 * need to track monitor changes mid-lifetime because Compose tears down
 * + rebuilds the popup HWND on every position change anyway.
 */
val workAreaSize: IntSize
```

Default implementation can fall back to `parentWindowSize` if the host doesn't know yet (pre-attach). The popup layer always reads it via the host, never directly.

### macOS implementation

`TaoComposeSceneHost` (and the overlay wrapper in `NativeViewOverlayController`):

```kotlin
override val workAreaSize: IntSize
    get() = NativeMetalBridge.nativeOwnerScreenVisibleFrame(parentNsView)
        ?.toIntSize() ?: parentWindowSize
```

New JNI: `nativeOwnerScreenVisibleFrame(NSView*) -> {width,height}` calling `[[nsView.window screen] visibleFrame].size` scaled by `backingScaleFactor`. `visibleFrame` already excludes the menu bar and dock — exactly what we need.

### Windows implementation

`TaoComposeSceneHostWindows` (and the overlay wrapper in `NativeViewOverlayControllerWindows`):

```kotlin
override val workAreaSize: IntSize
    get() = NativeTaoWindowsDecoBridge.nativeOwnerMonitorWorkArea(parentHwnd)
        ?.toIntSize() ?: parentWindowSize
```

New JNI: `nativeOwnerMonitorWorkArea(HWND) -> {width,height}` calling:

```c
HMONITOR mon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
MONITORINFO mi = { sizeof(mi) };
GetMonitorInfoW(mon, &mi);
// rcWork is screen minus taskbar/appbars, in physical pixels under
// per-monitor DPI v2 (already our awareness context).
return (mi.rcWork.right - mi.rcWork.left, mi.rcWork.bottom - mi.rcWork.top);
```

Synchronous, microsecond-cost, runs on the owning thread — no thread hop needed.

### Popup layer change

Both `TaoPopupSceneLayer` and `TaoPopupSceneLayerWindows`:

```kotlin
// before
private var widthPx: Int = host.parentWindowSize.width.coerceAtLeast(1)
private var heightPx: Int = host.parentWindowSize.height.coerceAtLeast(1)

private val innerScene: ComposeScene =
    CanvasLayersComposeScene(
        size = IntSize(widthPx, heightPx),
        ...
    )

// after
private val initialSceneSize: IntSize = host.workAreaSize.let {
    IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
}

private var widthPx: Int = initialSceneSize.width  // surface size, updated on boundsInWindow
private var heightPx: Int = initialSceneSize.height

private val innerScene: ComposeScene =
    CanvasLayersComposeScene(
        size = initialSceneSize,  // layout constraint, stays at work area
        ...
    )
```

The existing decoupling between `innerScene.size` (layout constraint, fixed) and `widthPx/heightPx` (drawable surface size, updated per `boundsInWindow`) is preserved. Only the constant changes from owner size to monitor work area.

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Work area smaller than parent window (multi-monitor span) | `MonitorFromWindow(DEFAULTTONEAREST)` always returns a valid monitor; `rcWork` is never empty. Fall back to `parentWindowSize` on null. |
| JNI added to popup construction hot path | One-shot at popup creation; `GetMonitorInfo` is ~1µs. Compose creates popups on user interaction (click, hover) — no concern. |
| macOS `[NSView.window]` nil during very-early-init | Already guarded; `workAreaSize` falls back to `parentWindowSize`. Same guard pattern as existing `parentWindowSize`. |
| Behavior change in existing snapshot/visual tests | Popup contents that previously measured tighter (collapsed to owner height) may now expand. Expected and desired — diff the affected screenshots, accept new baselines. |

## Implementation order

1. ✅ **macOS JNI** — `nativeOwnerWorkAreaSize` in `NativeMetalBridge` + impl in `NucleusTaoMetal.m` (packs `(width<<32)|height`, returns 0 on unresolved screen).
2. ✅ **macOS host** — `workAreaSize` added to `TaoPopupHost` (default = `parentWindowSize` fallback), implemented in `TaoComposeSceneHost.popupHost()`, forwarded in `NativeViewOverlayController.overlayPopupHost`.
3. ✅ **macOS layer** — `TaoPopupSceneLayer` feeds `sceneLayoutSize = host.workAreaSize` to `CanvasLayersComposeScene(size = …)`. `widthPx/heightPx` (CAMetalLayer drawable + NSPanel) kept at `parentWindowSize` to avoid allocating a full-screen Metal drawable up front.
4. ✅ **Windows JNI** — add `nativeOwnerMonitorWorkArea` to `NativeTaoWindowsDecoBridge` + native side in `nucleus_tao_windows_deco.c`.
5. ✅ **Windows host** — same surface change in `TaoPopupHostWindows` + `TaoComposeSceneHostWindows` + `NativeViewOverlayControllerWindows`.
6. ✅ **Windows layer** — swap constant in `TaoPopupSceneLayerWindows`.
7. ✅ **GraalVM metadata** — outbound JNI methods don't need explicit registration (resolved by symbol lookup); revisit if a new callback class is introduced.
8. **Manual verify** on each OS:
   - `nucleus-demo` app: open the title-bar tooltips at various window sizes — they should never be clipped by owner height.
   - Resize main window to 400×300 in the gallery; open the `DropdownMenu` on the gallery's color screen. Content should lay out unconstrained relative to the screen, not the owner.
   - Drag the window to the right edge; open a context menu that would extend past the owner's right edge. Expect normal layout (Compose's PositionProvider flips it, since now it knows the full space).

## Multi-Monitor DPI Synchronization (Windows)

Under Windows Per-Monitor DPI Aware v2 across mixed-DPI displays (e.g. 150% 4K primary + 100% 1080p secondary):
1. **Creation Coordinates**: `TaoPopupSceneLayerWindows.ensurePanel` creates the native panel at the parent window's current coordinates (`initX, initY`) rather than off-screen (`-10000, -10000`). Off-screen coordinates were assigned to the primary display by OS default, causing a synthetic cross-monitor DPI leap when moved to the secondary display.
2. **WM_DPICHANGED Handling**: `popupWndProc` in `nucleus_tao_windows_popup.c` ignores `WM_DPICHANGED` without calling `SetWindowPos`. Because Compose Multiplatform's layout engine already measures and positions the popup directly in physical pixels for the target monitor, letting the Win32 message loop auto-scale the HWND caused double-scaling / physical clipping (e.g. 102px window shrunk to 68px).
3. **Reactive LocalDensity**: `TaoPopupSceneLayerWindows` provides `CompositionLocalProvider(LocalDensity provides densityState.value)` inside `innerScene.setContent`, ensuring font rasterization, `Paragraph` layout, and container measurements stay perfectly in sync with the current monitor's DPI.
4. **Adapter Density Decoupling**: Removed static `sceneDensity` snapshot in `TaoDecoratedWindowAdapter.kt` that previously locked `LocalDensity` to startup display scale.

## Out of scope but worth noting later

- `ClientToScreen` → `MapWindowPoints(HWND_DESKTOP)` for RTL-mirrored owners. Pure cleanup; no current visual bug since Nucleus doesn't expose RTL window mirroring (only RTL **content** layout direction, which doesn't trigger `WS_EX_LAYOUTRTL`).
- Adding `WS_EX_TOPMOST` to popup style flags — orthogonal fix, separate plan.
- Per-monitor work area updates if the owner moves between monitors with different DPIs while a popup is open. Today the popup is torn down + rebuilt by Compose on owner-move anyway, so the next popup picks up the new monitor naturally.
