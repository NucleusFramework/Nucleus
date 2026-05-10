# NativeView Windows — Implementation Plan

## Context

`decorated-window-tao` exposes a `NativeView` composable with platform-native HWND/NSView/GtkWidget embedding plus a Compose overlay slot (e.g. floating navigation pill above an embedded WebView). macOS and Linux are shipped. Windows currently only has the API stub:

```kotlin
// NativeView.kt:75
is NucleusPlatformView.HWnd -> Box(modifier) // Not yet implemented.
```

Goal: full feature parity with macOS — embedded native HWND with rounded corners, GPU-rendered Compose overlay above with per-pixel alpha, click-through outside interactive regions, and **fully working context menus / dropdowns / tooltips** anchored from the overlay.

## Rendering approach: WGL + `DwmEnableBlurBehindWindow`

The existing main Tao scene on Windows uses WGL (`nucleus_tao_gl.c`, links `opengl32.lib`, `DirectContext.makeGL()` in `TaoComposeSceneHostWindows`). The overlay reuses the same stack with two additions:

1. Pixel format selected with `WGL_ALPHA_BITS_ARB = 8` so the back-buffer carries alpha.
2. `DwmEnableBlurBehindWindow` called once after creation with an empty blur region (`CreateRectRgn(0,0,-1,-1)`) — the canonical Win32 trick that makes DWM honor the window's alpha channel without WS_EX_LAYERED. This is the same mechanism JetBrains skiko ships in `enableTransparentWindow` (`skiko/src/awtMain/cpp/windows/window_util.cc`).

This stays consistent with the rest of the module, no new technology stack, no new dependencies.

## Architecture decisions (locked — do not reconsider)

### Topology

- **Embedded native HWND** (the `NucleusPlatformView.HWnd` payload) → `WS_CHILD` of the Tao main HWND, attached via `SetParent`. Sized via `SetWindowPos`. Rounded corners via `SetWindowRgn(CreateRoundRectRgn(...))`.
- **Compose overlay HWND** → top-level **owned** popup: `WS_POPUP` + `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`, owner = Tao main HWND. Position tracked manually via subclass on owner.
- **Each Compose popup** (DropdownMenu, ContextMenu, Tooltip, BasicTextField context menu) → its own top-level owned HWND with the same setup. Mirrors macOS `TaoPopupSceneLayer` (one NSPanel per popup).

### Rendering surface

- WGL context per overlay/popup HWND, set up by cloning `nucleus_tao_gl.c`'s pixel-format + context creation logic with one delta: `WGL_ALPHA_BITS_ARB = 8` added to the attribute list. **Do not** use `WGL_TRANSPARENT_ARB` (reserved/legacy in Khronos spec).
- **Pixel format**: the popup MUST use the same pixel format as the host (MSDN `wglShareLists` requirement: "All rendering contexts of a shared display list must use an identical pixel format"). Cache the host's `PIXELFORMATDESCRIPTOR` + format index at host init; reuse for every popup via `SetPixelFormat(popupDC, hostFormatIndex, &cachedPfd)`.
- **Share group**: create the popup HGLRC via `wglCreateContextAttribsARB(popupDC, hostHGLRC, attribs)` — the `hShareContext` parameter atomically joins the popup to the host's share group at creation time. **Do not** use `wglShareLists` (its "no pre-existing objects in second context" restriction would force creation-order acrobatics; the ARB path bypasses it cleanly).
- **Window class**: register popup/overlay classes with `CS_OWNDC` so `GetDC` returns a stable HDC and we avoid repeated `GetDC`/`ReleaseDC` cycles.
- After window creation: `DwmEnableBlurBehindWindow(hwnd, { dwFlags = DWM_BB_ENABLE | DWM_BB_BLURREGION; hRgnBlur = CreateRectRgn(0,0,-1,-1); fEnable = TRUE; })`. Call once at creation, re-call on `WM_DWMCOMPOSITIONCHANGED`.
- Skia binds via `DirectContext.makeGL()` — **one `GrDirectContext` per HWND**, not shared. Skia's own guidance (skia.org `skcanvas_creation`, Brian Salomon on skia-discuss): GL backend's GrContexts are 1:1 with GL contexts. The share group amortises shaders/programs/textures (server-side GL objects) across popups, while each `GrDirectContext` keeps its own FBO/VAO state — exactly what `wglShareLists` semantics permit.
- Render premultiplied RGBA, `glClearColor(0,0,0,0)`, blend `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`. Present via `SwapBuffers` followed by `DwmFlush()` to sync to the DWM compositor (GLFW pattern, mitigates SDL #5797 windowed-mode stutter). Set `wglSwapIntervalEXT(0)` on each popup context — `DwmFlush` provides the vsync.

### Cross-context synchronization

When swapping the current HGLRC between host and popup (or between two popups):

```
hostDirectContext.flushAndSubmit()
glFlush()                             // GPU sees host commands before share-group consumer
wglMakeCurrent(popupDC, popupHGLRC)
popupDirectContext.resetContext()     // Skia: GL state cache no longer reflects truth
// ... draw popup frame ...
popupDirectContext.flushAndSubmit()
SwapBuffers(popupDC); DwmFlush()
wglMakeCurrent(hostDC, hostHGLRC)
hostDirectContext.resetContext()
```

`resetContext()` is cheap (state-cache invalidation only) and prevents the "Skia thinks GL state is X but it's Y" class of bug.

### Input / focus

- Overlay/popup WndProc returns `MA_NOACTIVATE` from `WM_MOUSEACTIVATE`, never steals focus from owner / WebView2.
- Overlay `WM_NCHITTEST` returns `HTCLIENT` if cursor inside a registered interactive region, `HTTRANSPARENT` otherwise. Click-through routes to whichever HWND is beneath at screen coordinates via `WindowFromPoint` semantics — for in-process WebView2 child this delivers the click directly to it, no manual forwarding required.
- Popup outside-click dismissal: **`SetCapture(hwndPopup)` immediately after `ShowWindow(SW_SHOWNOACTIVATE)`**. In `WM_LBUTTONDOWN/RBUTTONDOWN/MBUTTONDOWN`: `ClientToScreen` + `GetWindowRect` + `PtInRect` — if outside, fire the JNI outside-listener and dismiss; if inside, dispatch to Compose. Always handle `WM_CAPTURECHANGED` to detect capture loss (Alt-Tab, system modal, nested popup taking capture) and clean up. **For nested menus**, the inner popup calls `SetCapture(hwndInner)` which transfers capture from the outer — the outer must NOT treat capture loss caused by a known sibling popup as outside-click; track the active-popup chain to disambiguate. Pattern reference: Old New Thing on `SetCapture`, codeproject.com/Tips/127813. (Replaces the heavier `WH_MOUSE_LL` global hook.)
- Owner of nested popups = **root host HWND**, not the parent menu. Owner chain is single-level only; using parent-menu as owner causes z-order glitches when the parent dismisses.
- Host loses foreground (`WM_ACTIVATEAPP wParam=FALSE` on the Tao main HWND): dismiss all open popups. Mirrors macOS `NSApp` deactivation.
- Keyboard: overlay/popup never takes focus. Key events arriving on the Tao main HWND are routed to the topmost overlay/popup's `ComposeScene` when it holds a focusable Compose node (e.g. `BasicTextField` inside NavPill, arrow-keys in DropdownMenu). Pattern mirrors macOS `popupKeyHandlers` chain.
- `ShowWindow(SW_SHOWNOACTIVATE)`. Every `SetWindowPos` carries `SWP_NOACTIVATE`.

### DPI

- `SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)` before each `CreateWindowEx`, restored after.
- Each owned HWND receives its own `WM_DPICHANGED` (top-level windows get it independently).
- WndProc honors `lParam` suggested rectangle.
- Avoid caching DPI per-overlay: read `GetDpiForWindow(hwnd)` on demand to survive owner-drag-across-monitor edge cases.

### Z-order tracking

- Owned windows are guaranteed above their owner by Win32 (no `HWND_TOPMOST` needed; `HWND_TOPMOST` would wrongly hide the owner when another non-topmost app gets focus).
- On owner `WM_WINDOWPOSCHANGED` (intercepted via the existing subclass in `nucleus_tao_windows_deco.c`): batch-reposition overlay + active popups via `BeginDeferWindowPos / DeferWindowPos / EndDeferWindowPos` for atomicity. Flags: `SWP_NOACTIVATE | SWP_NOZORDER | SWP_NOREDRAW | SWP_DEFERERASE`.
- On owner `WM_ACTIVATE` / `WM_NCACTIVATE`: re-issue `SetWindowPos(overlay, owner, ...)` defensively (Chromium pattern).

### Forbidden combinations (research-confirmed)

- ❌ `WS_EX_LAYERED` (CPU-bound, breaks GPU compositing)
- ❌ `UpdateLayeredWindow` (CPU readback)
- ❌ `HWND_TOPMOST` (wrong z-order semantics for in-app popups)
- ❌ `WGL_TRANSPARENT_ARB` (reserved/legacy in Khronos spec)
- ❌ `WS_EX_TRANSPARENT` for click-through (a layered-window concept; we are not layered — use `HTTRANSPARENT` from `WM_NCHITTEST`)

## Public Kotlin API

The user-facing API does not change. The existing `NucleusPlatformView.HWnd { val hwndHandle: Long }` (in `NativeView.kt`) is the contract; the implementation simply replaces the `Box(modifier) // Not yet implemented` branch with the real composable.

## Module additions

### Native sources (`src/main/native/windows/`)

| File | Purpose |
|---|---|
| `nucleus_tao_windows_native_view.c` | Subview path (`SetParent`, `SetWindowPos`, `SetWindowRgn`) |
| `nucleus_tao_windows_overlay.c` | Overlay HWND lifecycle + WndProc (`WM_NCHITTEST`, `WM_MOUSEACTIVATE`, `WM_DPICHANGED`, pointer dispatch) |
| `nucleus_tao_windows_overlay_gl.c` | WGL bridge for transparent overlay HWNDs (clone of `nucleus_tao_gl.c` with `cAlphaBits=8` + `DwmEnableBlurBehindWindow`) |
| `nucleus_tao_windows_popup.c` | Popup HWND lifecycle (mirrors macOS `popup_panel.m`); reuses `overlay_gl.c` for rendering |

### Kotlin bridges

| File | Purpose |
|---|---|
| `NativeTaoWindowsNativeViewBridge.kt` | Subview JNI bridge (signatures aligned with macOS `NativeTaoMacOsNativeViewBridge`) |
| `NativeTaoWindowsOverlayBridge.kt` | Overlay JNI bridge: createOverlay, setFrame, setRegions, setEventCallback, setKeyCallback, releaseOverlay |
| `PopupNativeBridge.windows.kt` | Popup JNI bridge (mirrors macOS `PopupNativeBridge`) |

### Kotlin overlay/popup controllers

| File | Purpose |
|---|---|
| `NativeViewOverlayController.windows.kt` | Port of macOS `NativeViewOverlayController`. Same `ComposeScene` lifecycle, same `PlatformLayersComposeScene` + `TaoComposeSceneContextWindows`. Replaces `NativeMetalBridge` calls with `NativeTaoWindowsOverlayBridge`. |
| `TaoPopupSceneLayerWindows.kt` | Port of macOS `TaoPopupSceneLayer`. Each instance creates a popup HWND via `PopupNativeBridge.nativeCreatePanel`. |
| `TaoComposeSceneContextWindows.kt` | Plug into `PlatformLayersComposeScene` to instantiate `TaoPopupSceneLayerWindows` for every popup mounted from the main scene OR the overlay scene. |
| `TaoPopupHostWindows.kt` | Implements the `TaoPopupHost` interface for Windows (parent HWND access, scale, redraw scheduling, key handler chain). |

### Modifications to existing files

| File | Change |
|---|---|
| `NativeView.kt:75` | Replace `is NucleusPlatformView.HWnd -> Box(modifier)` stub with real `HwndEmbedding(...)` composable invoking the new bridges. |
| `decorated-window-tao/src/main/native/windows/nucleus_tao_windows_deco.c` | In the existing main-HWND subclass proc: hook `WM_WINDOWPOSCHANGED` to batch-reposition active overlay + popups via a registered C callback list. |
| `TaoComposeSceneHostWindows.kt:120-130` | Switch `CanvasLayersComposeScene(...)` → `PlatformLayersComposeScene(... composeSceneContext = TaoComposeSceneContextWindows(...))`. Cross-cutting benefit: unlocks context menus / dropdowns / tooltips for the **main** Compose scene on Windows, not just the overlay. |

### Cleanup

Stale uncommitted artifacts to remove before starting:
```
decorated-window-tao/src/main/native/windows/nucleus_tao_windows_native_view.obj
decorated-window-tao/src/main/native/windows/nucleus_tao_windows_overlay.obj
```
Add a `.gitignore` rule: `src/main/native/windows/*.obj`.

### CI

Per `CLAUDE.md` "Adding a Native JNI Module" checklist:

1. `build.bat` updated to compile the new C sources into the existing native library output (or a sibling DLL — pick whatever keeps JNI loader hops minimal).
2. Output paths: `src/main/resources/nucleus/native/win32-x64/` and `win32-aarch64/`.
3. `reachability-metadata.json` updated with new JNI-accessible classes/methods (`NativeTaoWindowsOverlayBridge.OverlayEventCallback`, etc.).
4. `build-natives.yaml`: extend Windows build steps for x64 + aarch64 to cover the new C files.
5. `pre-merge.yaml`, `publish-maven.yaml`, `publish-plugin.yaml`, `test-packaging.yaml`, `test-graalvm.yaml`, `release-graalvm.yaml`: keep download + EXPECTED-verify entries consistent (all 6 workflows — common pitfall per `CLAUDE.md`).
6. Build script clears `~/.cache/nucleus/native/<arch>/` after compilation so `NativeLibraryLoader` doesn't serve stale copies.

## JNI surface

### Subview (`NativeTaoWindowsNativeViewBridge`)

```
external fun nativeAttach(parentHwnd: Long, childHwnd: Long)
external fun nativeDetach(childHwnd: Long)
external fun nativeSetFrame(parentHwnd: Long, childHwnd: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
external fun nativeSetCornerRadius(parentHwnd: Long, childHwnd: Long, radiusPx: Float)
```

### Overlay (`NativeTaoWindowsOverlayBridge`)

```
external fun nativeCreateOverlay(ownerHwnd: Long): Long              // returns overlayHandle (composite of HWND + GL ctx)
external fun nativeSetOverlayFrame(overlay: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
external fun nativeSetOverlayRegions(overlay: Long, rectsXYWHPx: FloatArray, count: Int)
external fun nativeSetOverlayCallback(overlay: Long, callback: OverlayEventCallback?)
external fun nativeSetOverlayKeyCallback(overlay: Long, callback: OverlayKeyCallback?)
external fun nativeMakeCurrent(overlay: Long): Boolean                // wglMakeCurrent before frame
external fun nativeSwapBuffers(overlay: Long)                         // SwapBuffers after frame
external fun nativeReleaseOverlay(overlay: Long)

interface OverlayEventCallback {
    fun onPointerEvent(type: Int, xPx: Float, yPx: Float, button: Int, modifiers: Int)
    fun onScroll(xPx: Float, yPx: Float, dxPx: Float, dyPx: Float)
}

interface OverlayKeyCallback {
    fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int): Boolean
}
```

### Popup (`PopupNativeBridge` — Windows actual)

Mirrors `decorated-window-tao/src/main/native/macos/popup_panel.m` 1:1:

```
external fun nativeCreatePanel(parentHwnd: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int): Long
external fun nativeSetFrameInWindow(panel: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
external fun nativeSetFocusable(panel: Long, focusable: Boolean)
external fun nativeContentHwnd(panel: Long): Long
external fun nativeMakeCurrent(panel: Long): Boolean
external fun nativeSwapBuffers(panel: Long)
external fun nativeSetEventCallback(panel: Long, callback: EventCallback?)
external fun nativeInstallOutsideClickMonitor(panel: Long, listener: OutsideClickListener)
external fun nativeUninstallOutsideClickMonitor(panel: Long)
external fun nativeRelease(panel: Long)
```

`nativeInstallOutsideClickMonitor` on Windows is implemented via `SetWindowsHookEx(WH_MOUSE_LL)` filtered to clicks outside the panel rect (analog to NSEvent local monitor on macOS).

## Implementation phases

Each phase is **atomic**: write code, build, smoke-test on a Windows machine, commit. Do not stack work on a broken phase.

### Phase 0 — Cleanup (0.25 d)
- Delete stale `*.obj` files in `src/main/native/windows/`.
- Add `.gitignore` rule.
- Confirm baseline `./gradlew :sample-tao:run` still works on Windows (no overlay yet).

**Commit:** `chore(decorated-window-tao): clean stale native obj artifacts`

### Phase 1 — JNI skeleton + bridge stubs (0.5 d)
- Create `NativeTaoWindowsNativeViewBridge.kt`, `NativeTaoWindowsOverlayBridge.kt`, `PopupNativeBridge.windows.kt` with all `external` declarations and `NativeLibraryLoader.load` calls.
- Create `nucleus_tao_windows_native_view.c`, `nucleus_tao_windows_overlay.c`, `nucleus_tao_windows_popup.c`, `nucleus_tao_windows_overlay_gl.c` with empty no-op JNI exports.
- Update `build.bat`. Wire CI download + verify entries in all 6 workflows.
- The `NativeView.kt` `is HWnd` branch still returns `Box(modifier)`.

**Validates:** native build pipeline + library loading + GraalVM reachability.

**Commit:** `feat(decorated-window-tao): scaffold Windows native-view + overlay JNI bridges`

### Phase 2 — Subview path (1 d)
- Implement `nativeAttach` (`SetParent` + flip `WS_CHILD`, strip `WS_POPUP|WS_CAPTION|WS_THICKFRAME`).
- Implement `nativeSetFrame` (`SetWindowPos` with `SWP_NOZORDER|SWP_NOACTIVATE|SWP_DEFERERASE`).
- Implement `nativeSetCornerRadius` (`SetWindowRgn(CreateRoundRectRgn)`, recompute on every frame change).
- Implement `nativeDetach` (`SetParent(child, NULL)`).
- Wire `is HWnd ->` branch in `NativeView.kt` to a new `HwndEmbedding(...)` composable mirroring macOS `NsViewEmbedding`.

**Smoke test:** sample creates a child `EDIT` HWND via `CreateWindowEx`, embeds it, drags the Tao window, resizes — child follows, corners clipped.

**Commit:** `feat(decorated-window-tao): implement Windows NativeView subview path`

### Phase 3 — Overlay HWND lifecycle + WndProc (1 d)
- Implement `nativeCreateOverlay`:
  - `SetThreadDpiAwarenessContext(PMv2)`
  - Register window class `NucleusTaoOverlayCls` with custom WndProc
  - `CreateWindowEx(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, ..., WS_POPUP, ..., owner=tao_main, ...)`
  - `ShowWindow(SW_SHOWNOACTIVATE)`
- WndProc handles:
  - `WM_MOUSEACTIVATE` → `MA_NOACTIVATE`
  - `WM_NCHITTEST` → consult region table, return `HTCLIENT`/`HTTRANSPARENT`
  - `WM_LBUTTONDOWN/UP/MOUSEMOVE/MOUSEWHEEL/RBUTTONDOWN/UP/MBUTTONDOWN/UP` → forward to JNI callback
  - `WM_DPICHANGED` → honor `lParam` suggested rect
  - `WM_DESTROY` → cleanup
- Implement `nativeSetOverlayFrame` (`SetWindowPos` with `SWP_NOACTIVATE`).
- Implement `nativeSetOverlayRegions` (atomic update of region table behind mutex; consulted from `WM_NCHITTEST`).
- Hook `WM_WINDOWPOSCHANGED` in the existing `nucleus_tao_windows_deco.c` subclass to call back into a registered list of "owner-position-listeners" (overlay + active popups).
- Render: solid red `FillRect(GetDC(hwnd), &rc, redBrush)` for now to validate Z-order above the embedded child HWND.

**Smoke test:** overlay appears as red rectangle covering the Tao client area, on top of an embedded child HWND. Mouse outside red regions click-throughs to child; mouse inside fires JNI callback. Drag the Tao window — overlay follows atomically, no lag visible.

**Commit:** `feat(decorated-window-tao): implement Windows overlay HWND lifecycle and WndProc`

### Phase 4 — WGL transparent rendering bridge (0.75 d)
- Clone `nucleus_tao_gl.c` → `nucleus_tao_windows_overlay_gl.c`.
- Reuse the host's pixel format (cached at host init) — `SetPixelFormat(popupDC, hostFormatIndex, &cachedPfd)`. The host's pixel format must already include `WGL_ALPHA_BITS_ARB = 8` (add to the attribute list in `nucleus_tao_gl.c` if not already present — required for transparency anyway, harmless when not used).
- Create the popup HGLRC via `wglCreateContextAttribsARB(popupDC, hostHGLRC, attribs)` — the `hShareContext = hostHGLRC` joins the popup to the host's share group atomically (no `wglShareLists` needed; bypasses its "no pre-existing objects" restriction).
- After window creation: call `DwmEnableBlurBehindWindow(hwnd, { DWM_BB_ENABLE | DWM_BB_BLURREGION, TRUE, CreateRectRgn(0,0,-1,-1), FALSE })`. Re-call on `WM_DWMCOMPOSITIONCHANGED`.
- Set `wglSwapIntervalEXT(0)` per popup context — `DwmFlush()` provides the vsync.
- DWM polish on creation:
  - `DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &DWMWCP_ROUNDSMALL)` (Win11 22000+, silently no-ops on Win10)
  - `DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &dark)` (mirror host setting)
  - `DwmExtendFrameIntoClientArea(hwnd, &MARGINS{1,1,1,1})` for four-sided shadow
- Expose JNI: `nativeMakeCurrent`, `nativeSwapBuffers` (calls `SwapBuffers` then `DwmFlush()`).
- Smoke from C: `glClearColor(0.5f, 0.0f, 0.0f, 0.5f); glClear(GL_COLOR_BUFFER_BIT); SwapBuffers(hdc); DwmFlush();` — overlay shows as semi-transparent red over the child HWND, with rounded corners + shadow on Win11.

**Commit:** `feat(decorated-window-tao): implement transparent WGL overlay rendering`

### Phase 5 — `NativeViewOverlayController.windows.kt` (1 d)
Port `NativeViewOverlayController.kt` (macOS) line-by-line:
- Replace `NativeMetalBridge` calls with `NativeTaoWindowsOverlayBridge`
- `DirectContext.makeMetal` → `DirectContext.makeGL` (after `nativeMakeCurrent`) — **one `GrDirectContext` per overlay HWND**, not shared with the host's
- Render loop: `host.flushAndSubmit() + glFlush()` → `nativeMakeCurrent(overlay)` → `overlayDirectContext.resetContext()` → `glClear(0,0,0,0)` → `scene.render(canvas, time)` → `overlayDirectContext.flushAndSubmit()` → `nativeSwapBuffers` (which does `SwapBuffers + DwmFlush`) → `nativeMakeCurrent(host)` → `hostDirectContext.resetContext()`
- Use `PlatformLayersComposeScene` + `TaoComposeSceneContextWindows` (forward-reference Phase 7)
- Keep `registerRegion` / `flushRegions` exactly as-is — semantically identical

**Smoke test:** sample `WebViewTab` ported with a single Compose `Box` (red, 200×60) as overlay content over a child `EDIT` HWND. Box renders crisply with anti-aliased corners. Click on box fires Compose handlers; click outside passes to `EDIT`.

**Commit:** `feat(decorated-window-tao): port NativeViewOverlayController to Windows`

### Phase 6 — Popup HWND + `TaoPopupSceneLayerWindows` (1.5 d)
- Implement `nucleus_tao_windows_popup.c`:
  - WndProc skeleton: `WM_MOUSEACTIVATE → MA_NOACTIVATE`; `WM_LBUTTONDOWN/RBUTTONDOWN/MBUTTONDOWN` → `ClientToScreen + GetWindowRect + PtInRect`, outside → fire JNI outside-callback, inside → forward to Compose; `WM_CAPTURECHANGED` → if capture transferred to a non-tracked HWND, fire JNI capture-lost; `WM_DPICHANGED` → honor `lParam` rect + notify density change.
  - On `nativeShow`: `ShowWindow(SW_SHOWNOACTIVATE)` then `SetCapture(hwndPopup)`. On `nativeHide`: `ReleaseCapture()` if owned + `ShowWindow(SW_HIDE)`.
  - Owner of every popup = **root host HWND** (passed in by `TaoPopupHostWindows`), not the parent menu.
  - Track active popup chain process-wide so nested `SetCapture` transfers are not mistaken for outside-click dismissals.
- Hook `WM_ACTIVATEAPP(wParam=FALSE)` in `nucleus_tao_windows_deco.c` subclass → fire callback that dismisses all open popups.
- Reuse `nucleus_tao_windows_overlay_gl.c` for rendering (same WGL setup, **separate `DirectContext` per popup HWND**, all share-grouped via `wglCreateContextAttribsARB`).
- Port `TaoPopupSceneLayer.kt` (macOS) → `TaoPopupSceneLayerWindows.kt` line-by-line, swap NSPanel/Metal calls for HWND/GL. Apply DWM polish: `DWMWCP_ROUNDSMALL`, `DWMWA_USE_IMMERSIVE_DARK_MODE`, `DwmExtendFrameIntoClientArea({1,1,1,1})`.
- Implement `TaoComposeSceneContextWindows.kt` → `createLayer` returns `TaoPopupSceneLayerWindows`.

**Smoke test:** Compose `DropdownMenu` mounted from inside the overlay's NavPill opens as a separate top-level HWND, can extend beyond the Tao window's bounds, dismisses on outside-click, animates correctly.

**Commit:** `feat(decorated-window-tao): implement Windows popup HWND and TaoPopupSceneLayer`

### Phase 7 — Switch main scene to `PlatformLayersComposeScene` (0.5 d)
- In `TaoComposeSceneHostWindows.kt`, change `CanvasLayersComposeScene(...)` to `PlatformLayersComposeScene(... composeSceneContext = TaoComposeSceneContextWindows(...))`.
- Wire `TaoPopupHostWindows` (provides parent HWND, scale, redraw scheduling).

**Cross-cutting benefit:** context menus / `DropdownMenu` / `Tooltip` / `BasicTextField` context menus in the **main** Compose scene now also render as proper top-level HWNDs, can extend beyond the window, dismiss on outside-click. Today they're confined to the canvas because Windows uses `CanvasLayersComposeScene`.

**Smoke test:** in `sample-tao`, right-click a `BasicTextField` in the main scene (not the overlay) — context menu opens, can extend below the window edge.

**Commit:** `feat(decorated-window-tao): use PlatformLayersComposeScene on Windows`

### Phase 8 — Keyboard forwarding to overlay (0.5 d)
Pattern mirrors macOS `popupKeyHandlers`:
- `TaoComposeSceneHostWindows` exposes a `popupKeyHandlers: MutableList<(KeyEvent) -> Boolean>`.
- `NativeViewOverlayController.windows.kt` registers itself when its scene has a focused focusable node (Compose `LocalFocusManager` reports focus state).
- In the main host's key dispatch, before sending to the main scene, walk `popupKeyHandlers` — if any returns true, consume.

**Smoke test:** focus a `BasicTextField` inside the overlay's NavPill, type — characters appear in the field, not in the WebView2 below.

**Commit:** `feat(decorated-window-tao): route key events to overlay scene when focused`

### Phase 9 — Sample (0.5–1 d)
- `SampleWebViewWindowsBridge.kt` + native code wrapping WebView2 (or, for v1, a stub `CreateWindowEx("EDIT", ...)` so the sample builds without WebView2 Runtime dependency).
- Extend `WebViewTab.kt` to call `createSampleWebViewPlatformView()` returning `NucleusPlatformView.HWnd` on Windows.

**Smoke test:** `./gradlew :sample-tao:run` on Windows shows the WebView/EDIT child with the floating NavPill overlay above; clicking buttons and typing the URL field works; right-click on the field opens a context menu that can extend outside the window.

**Commit:** `feat(sample-tao): wire NativeView WebView demo on Windows`

### Phase 10 — GPU matrix validation (1 d)
Test on:
- Win10 22H2 + NVIDIA RTX
- Win11 23H2 + Intel Iris Xe
- Win11 24H2 + AMD Radeon
- Snapdragon X (ARM64) if accessible, else skip

Validate per device:
- Overlay creates without errors (`DirectContext.makeGL()` returns non-null on the transparent context)
- Frame time < 16 ms when nothing is drawn on a 1920×1080 transparent overlay
- No flicker / black frame during owner resize
- DPI changes (drag across 100%/175% monitors) do not corrupt the overlay
- Snap Layouts (Win11) do not stall the overlay

If any device fails, open a dedicated ticket with reproduction details. Do not block the PR on theoretical hardware we can't access.

**Commit:** `chore(decorated-window-tao): validate Windows overlay across GPU matrix`

## Total effort

| Phase | Effort |
|---|---|
| 0 — Cleanup | 0.25 d |
| 1 — JNI skeleton | 0.5 d |
| 2 — Subview path | 1 d |
| 3 — Overlay HWND + WndProc | 1 d |
| 4 — WGL transparent rendering + share group + DWM polish | 0.75 d |
| 5 — `NativeViewOverlayController.windows` | 1 d |
| 6 — Popup HWND + `TaoPopupSceneLayerWindows` | 1.5 d |
| 7 — Main scene → `PlatformLayersComposeScene` | 0.5 d |
| 8 — Keyboard forwarding | 0.5 d |
| 9 — Sample | 0.5–1 d |
| 10 — GPU matrix validation | 1 d |
| **Total** | **~8–8.5 d** |

## Risks (informational)

- **WGL transparent contexts (`cAlphaBits=8` + `DwmEnableBlurBehindWindow`) have documented driver fragility** on some AMD and older Intel GPUs (skiko issue JBR-Skiko #270, Compose Multiplatform #3171). Skiko ships this exact path in production, so the fragility surface is bounded and well-known. Mitigation = Phase 10 validation; if a real user reports a GPU-specific failure later, address it then with concrete data.
- `MA_NOACTIVATE` may feel wrong on some popups (context menu vs autocomplete). `nativeSetFocusable(panel, true/false)` flips between `MA_ACTIVATE` and `MA_NOACTIVATE` per-popup. macOS already has this distinction.
- Owner drag at high refresh rate may show < 1 frame lag. Acceptable v1; this is inherent to the WGL+`SetWindowPos` path.
- The `DwmEnableBlurBehindWindow` per-pixel-alpha behavior is undocumented as a transparency mechanism (Microsoft docs only document the alpha-honoring side effect, not as a transparency contract). Treat as a stable trick (used by skiko / Electron / historic Chromium for ~15 years), not a permanent contract. Re-call after `WM_DWMCOMPOSITIONCHANGED` to recover from compositor restarts.

## Out of scope (v1)

- IME composition window positioning over overlay text fields (matches macOS Phase 5 of `VENDORING_PLAN.md` — ship after that lands)
- Drag-and-drop into / out of the overlay (separate concern, hooks `RegisterDragDrop` on the overlay HWND independently — easy follow-up)
- Trackpad gesture forwarding to the overlay scene (matches macOS `VENDORING_PLAN.md` Phase 12)
- Acrylic / Mica system effects on the overlay HWND (would require `WS_EX_LAYERED` or `SystemBackdrop` API — incompatible with current design)

## References

- Microsoft Learn — `DwmEnableBlurBehindWindow`, `WM_WINDOWPOSCHANGED`, `WM_MOUSEACTIVATE`, `SetCapture`, `WM_CAPTURECHANGED`, extended window styles
- Microsoft Learn — *"Apply rounded corners in desktop apps"* (`DWMWA_WINDOW_CORNER_PREFERENCE`, `DWMWCP_ROUNDSMALL` for menus)
- Microsoft Learn — `wglShareLists` (identical-pixel-format requirement, "no pre-existing objects" restriction)
- Khronos OpenGL Registry — `WGL_ARB_create_context` (`hShareContext` parameter for atomic share-group join)
- Khronos Wiki — *"OpenGL Context"* (shareable vs container objects)
- Raymond Chen, *"WindowFromPoint, ChildWindowFromPoint, RealChildWindowFromPoint, when will it all end?"* (2010) — `HTTRANSPARENT` semantics
- Raymond Chen, *"How can I have a window that rejects activation but still receives pointer input?"* (2016) — `WS_EX_NOACTIVATE` + `MA_NOACTIVATE` recipe
- skia.org docs `skcanvas_creation` + Brian Salomon (skia-discuss, June 2023) — N:N pattern for `GrDirectContext` across share-grouped GL contexts
- skiko `skiko/src/awtMain/cpp/windows/window_util.cc::enableTransparentWindow` — production reference for the empty-blur-region trick
- GLFW `src/wgl_context.c` — reference WGL context creation; `DwmFlush` after `SwapBuffers` pattern (also SDL #5797)
- `github.com/rossy/borderless-window` — canonical `DwmExtendFrameIntoClientArea({1,1,1,1})` borderless+shadow pattern
- Chromium `ui/views/widget/desktop_aura/desktop_window_tree_host_win.cc` — production reference for owner subclass + overlay reposition
- macOS counterparts in this module: `NativeViewOverlayController.kt`, `TaoPopupSceneLayer.kt`, `popup_panel.m`, `native_view.m`
