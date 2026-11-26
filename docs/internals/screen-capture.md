# screen-capture internals

`screen-capture` - AWT-free screen capture in physical pixels: `ScreenCapture.displays()` / `captureDisplay(display, region, includeCursor)` / `captureWindow(id)` / `windows()` (capturable top-level windows, front to back) / permission. Window titles cross JNI as UTF-8 `byte[]`, since `NewStringUTF` rejects emoji.

## Windows

- Displays: GDI `BitBlt` in a per-monitor-v2 *thread* DPI context.
- Windows: `PrintWindow(PW_RENDERFULLCONTENT)` on a **worker thread with a 2 s timeout**. It sends `WM_PRINT` and waits forever on a thread that does not pump — including an app's own UI thread blocked on the capturing thread, and even when `IsHungAppWindow` is already TRUE. On timeout the capture falls back to the visible part of the window read from the screen; the abandoned worker frees itself when the window finally answers. The window's own thread prints inline.

## macOS

- ScreenCaptureKit (14+), else `CGDisplayCreateImageForRect` / `CGWindowListCreateImage` through `dlsym` (obsoleted in the 15 SDK). Never pumps the main run loop.
- A region is captured from a **32 pt grid-aligned** rect and cropped: an odd-pixel origin is resampled, and dithered content follows a tile anchored at the requested rect — unaligned it comes back ±2 levels off the full capture.
- Window ids are `CGWindowID`s, found through `windows()` since Tao does not expose them.

## Linux

- X11: `XGetImage`, RandR monitors, XFixes cursor, XComposite for covered windows. Everything is `dlopen`ed and X errors are trapped per call.
- Wayland: `org.freedesktop.portal.Screenshot` (whole desktop, cropped; the portal file is deleted).

## E2E

- `scripts/screen-capture-windows-e2e.ps1`: the `examples/screen-capture-demo` self-test (pixel-exact pattern, occluded / minimized / blocked-UI / hung-foreign windows, torture + GDI/handle leak sampling).
- `scripts/screen-capture-macos-e2e.sh`: same self-test; hung window, cursor parking and fd/thread/Mach port/RSS sampling done by a Swift helper. Run under `caffeinate` on an untouched Mac — display sleep, mouse moves, Mission Control or a playing video all fail it.
- `scripts/screen-capture-linux-e2e.sh`: Xvfb depths 24/16/8 vs `xwd`, fake portal.
