# Research brief — Drag-and-drop support for the Tao backend

## Goal

Determine the best way to add **drag-and-drop** support to a Compose Desktop
backend that does **not** use AWT/Swing. Two distinct flavours are in scope and
the answer should address both:

1. **External OS DnD** — files / text / URLs dragged from the OS shell
   (Finder, Explorer, Nautilus) onto a window, and conversely Compose-initiated
   drags exporting data to the OS.
2. **Compose in-app DnD** — `Modifier.dragAndDropSource` /
   `Modifier.dragAndDropTarget` between widgets in the same window
   (Compose Multiplatform 1.7+ unified DnD API).

The deliverable should explain (a) whether each flavour is achievable on this
backend, (b) what native plumbing is required per OS, and (c) how the events
reach a `ComposeScene` that has no AWT host.

## Context — what this backend looks like

This is a Kotlin/JVM Compose Desktop project (`ComposeDeskKit / Nucleus`). The
relevant module is `decorated-window-tao`. Unlike the standard Compose Desktop
runtime (which sits on top of AWT's `ComposeWindow` / `SkiaLayer`), this
backend uses:

- **[Tao](https://github.com/tauri-apps/tao) 0.35** (Rust, fork of winit) as
  the windowing layer. Compiled as a `cdylib` (`nucleus_tao`) and called from
  Kotlin via JNI.
- A **custom `ComposeScene`** (`CanvasLayersComposeScene` from compose-ui)
  rendered with **Skia/Skiko** directly into:
  - macOS: a `CAMetalLayer` attached to the Tao-owned `NSView`
  - Windows: WGL on the Tao `HWND`
  - Linux: EGL (Wayland) / GLX (X11) on the Tao GTK widget's surface
- **No AWT involvement** in the rendering / input path. AWT classes are only
  used as data shapes (e.g. `java.awt.event.KeyEvent` is fabricated for
  `KEY_TYPED` events to satisfy Compose's `isTypedEvent` gate). There is **no
  `ComposeWindow`, no `SkiaLayer`, no `JFrame`, no `DropTarget`**.

The Rust event loop dispatches `tao::WindowEvent` to Kotlin via a JNI
callback. Today only these are forwarded:

- `Resized`, `Moved`, `ScaleFactorChanged`, `Focused`, `CloseRequested`,
  `Destroyed`
- `CursorMoved`, `CursorLeft`, `MouseInput`, `MouseWheel`
- `KeyboardInput`, `ReceivedImeText`, `ModifiersChanged`
- `RedrawRequested`, `MainEventsCleared`

Tao does emit `WindowEvent::FileDropped { paths }`,
`WindowEvent::HoveredFile { path }`, `WindowEvent::HoveredFileCancelled` —
**none of them are currently consumed**. There is no `with_file_drop_handler`
call on `WindowBuilder` either.

The Kotlin side then reshapes events and feeds them into the scene via
`ComposeScene.sendPointerEvent(...)` / `sendKeyEvent(...)`. Pointer position
is in physical pixels.

## Why Compose's standard DnD doesn't "just work" here

On Compose Desktop with AWT:

- `Modifier.dragAndDropTarget` ultimately registers a `java.awt.dnd.DropTarget`
  on the underlying `ComposeWindow`.
- `Modifier.dragAndDropSource` calls into AWT's `DragSource.startDrag`.
- Mime conversion goes through `java.awt.datatransfer.Transferable`.

Because this backend has no AWT window, that bridge has nothing to register
on. Internal-only DnD (drag inside the scene, no OS data) might still work in
theory because the gesture is detected from pointer events — but the
`PlatformDragAndDropManager` that Compose uses on Desktop (see
`compose-ui/src/desktopMain` in compose-multiplatform-core) is wired against
AWT and is not exposed to a custom `ComposeScene`. We need to verify whether
`CanvasLayersComposeScene` exposes any hook to plug a custom DnD manager,
or whether the manager is hard-wired through `PlatformContext`.

`PlatformContext` extension points we already use here (see
`TaoComposeSceneHost.kt` / `TaoPlatformContext`): `windowInfo`, `windowInsets`,
`setPointerIcon`, `startInputMethod`, `semanticsOwnerListener`. **Is there a
`dragAndDropManager` (or equivalent) hook?**

## Native primitives available per OS

These are the OS-level facilities the backend would need to drive. The
research should confirm each one is reachable from Tao (or via direct
Cocoa/Win32/GTK calls bypassing Tao when needed).

- **macOS** — `NSDraggingDestination` protocol on the content view
  (`draggingEntered:`, `draggingUpdated:`, `prepareForDragOperation:`,
  `performDragOperation:`, `draggingExited:`) and
  `beginDraggingSessionWithItems:event:source:` for outgoing drags. Tao does
  **not** expose this API; the project already drops down to Objective-C for
  similar needs (`objc/window_drag.m` for `performWindowDragWithEvent:`,
  `objc/a11y.m` for accessibility) so adding an `objc/dnd.m` is the precedent.
- **Windows** — `IDropTarget` registered with `RegisterDragDrop` on the HWND,
  and `DoDragDrop` for outgoing. Tao's `with_file_drop_handler` only covers
  the inbound file-path case; it does **not** expose hover position or arbitrary
  formats. The backend already subclasses the Tao HWND's WndProc (see
  `decorated-window-tao/src/main/native/windows/`), so adding `RegisterDragDrop`
  alongside is straightforward. Windows requires `OleInitialize` (STA) on the
  thread that registers the drop target — note that `launcher-windows` removed
  a redundant `CoInitialize` recently to **unblock Tao's STA** (commit
  `3e63d55`), so the thread is already STA.
- **Linux** — XDND protocol on X11 (`XdndAware`, `XdndPosition`, `XdndDrop`,
  …) and `wl_data_device` on Wayland. Tao goes through GTK, so the practical
  path is `gtk_drag_dest_set` / `drag-data-received` signal on the
  `GtkDrawingArea`/`GtkWindow`. The project already pulls `gtk = "0.18"` and
  `gdkx11-sys`, so GTK-level DnD bindings are reachable from the Rust side.

## Compose-side integration questions to resolve

These are the unknowns the research must answer with code references to
`compose-multiplatform-core` (`compose/ui/ui/src/desktopMain` and
`compose/ui/ui/src/skikoMain`):

1. How does `CanvasLayersComposeScene` route DnD events? Is there a
   `PlatformDragAndDropManager` / `PlatformContext.dragAndDropManager` field
   we can override from a custom `PlatformContext` (we already extend
   `PlatformContext.Empty()`), or does the scene synthesize DnD purely from
   pointer events + a "data provider" callback?
2. For **outgoing** drags initiated by `Modifier.dragAndDropSource`, what
   callback does Compose invoke when the user starts a drag? Is it a single
   "begin session with this transferable" entry point, or does Compose drive
   it via pointer-capture + a per-frame "request data" pull?
3. Can the `compose.ui.draganddrop.DragAndDropTransferData` /
   `DragAndDropTransferable` types be constructed without AWT, or do they
   wrap `java.awt.datatransfer.Transferable` on Desktop?
4. For purely **in-scene** drag (source and target both inside the same
   Compose tree, no OS interaction), is the AWT `DropTarget` still needed, or
   does the scene short-circuit?
5. Does Compose 1.7's unified `DragAndDropEvent` carry enough info
   (mime types, position, modifiers) that a Tao→Compose adapter can be a thin
   shim, or does it expect AWT `DataFlavor` / `DropTargetEvent` instances?

A working reference: `compose-jb`'s `DesktopPlatformDragAndDropManager` (or
whatever it's called in current sources) — the path through `SkiaLayer` /
`ComposeWindow` / `DropTarget`. Reading that code is the single most
important step.

## Existing reference implementations in this repo

These show the pattern for adding a new piece of native plumbing on this
backend. The DnD work would mirror their structure.

- **Window drag** (closest analogue):
  - Kotlin: `TaoWindow.dragWindow()` →
    `NativeTaoBridge.nativeDragWindow(handle)`
  - Rust: `Java_..._NativeTaoBridge_nativeDragWindow` in
    `src/main/native/src/lib.rs:1750` — on macOS calls into
    `objc/window_drag.m`, on Win/Linux calls Tao's `Window::drag_window()`.
  - macOS impl: `objc/window_drag.m` — installs a `mouseDown` monitor
    (`NSEvent.addLocalMonitorForEventsMatchingMask:`) to latch the originating
    event, then dispatches `performWindowDragWithEvent:` on the next runloop
    tick.
- **Accessibility** (most complex existing native bridge):
  - Kotlin: `NativeTaoBridge.nativeA11yAttach/Detach/ApplySnapshot`
    (and the dispatch-callback static methods `dispatchA11yAction`,
    `dispatchA11ySetText`, …)
  - macOS: `objc/a11y.m` exposes a `NucleusA11yElement` tree, calls back into
    Kotlin via `NativeTaoBridge.dispatchA11y*` static methods using
    `JNIEnv->CallStaticVoidMethod`.
  - Windows: `windows/nucleus_tao_a11y.c` (UI Automation provider).
- **Title-bar drag from Compose**: `decorated-window-tao/.../TitleBar.kt`
  uses `Modifier.pointerInput { awaitPointerEventScope { … } }` to detect a
  press → tiny-move and then calls `window.dragWindow()` synchronously
  during the press handler. That same gesture-detection pattern is
  available for spotting a "drag start" inside the scene.

## Constraints / non-negotiables

- **JNI only** — no JNA at runtime (project rule).
- Native libs ship inside the JAR under
  `src/main/resources/nucleus/native/{linux,darwin,win32}-{x64,aarch64}/`
  and are loaded via `NativeLibraryLoader`.
- Must work with **GraalVM native-image** — every JNI-touched class needs a
  `reachability-metadata.json` entry (existing one lives at
  `decorated-window-tao/src/main/resources/META-INF/native-image/io.github.kdroidfilter/nucleus.decorated-window-tao/reachability-metadata.json`).
- macOS event-loop callbacks land on the **macOS main thread**; Win/Linux
  on the thread that called `nativeRunBlocking`. Any synchronous calls back
  into Kotlin from a DnD callback must respect that.
- Project rule: "Never redraw window controls with Compose". Doesn't apply to
  DnD per se but signals the team's preference: lean on native facilities
  rather than re-implementing UX in Compose.

## Deliverable

A concise report (markdown) covering:

1. **Feasibility verdict** for each of the two flavours (external OS DnD,
   in-scene Compose DnD), separated per OS.
2. **Compose hook** identification: exact class name and file path (in
   `compose-multiplatform-core`) of the type that must be implemented or
   the `PlatformContext` field that must be overridden — including signature
   excerpts. Indicate whether the relevant API is `internal`, `@InternalComposeUiApi`,
   or stable.
3. **Per-OS native plan**: which Tao events to wire, which OS APIs to call
   directly, where to place the code (analogous to the existing
   `objc/window_drag.m` / `windows/nucleus_tao_a11y.c` precedents).
4. **Threading & lifecycle**: how to register/unregister the drop target
   alongside `attach()` / `detach()` in `TaoComposeSceneHost`.
5. **Known unknowns / risks** — anything that would need a prototype to
   validate (e.g. whether `CanvasLayersComposeScene` exposes any DnD hook at
   all; whether Compose's `DragAndDropTransferData` is constructible without
   AWT on Desktop).

The goal is *not* to write the implementation — it's to produce enough
research that the implementation is then a mechanical translation. Cite line
numbers / file paths from `compose-multiplatform-core` wherever possible.
