@file:Suppress("MagicNumber")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalModalDialogCount
import dev.nucleusframework.window.LocalTitleBarInfo
import dev.nucleusframework.window.TitleBarInfo
import dev.nucleusframework.window.tao.render.LocalTaoPopupHost
import dev.nucleusframework.window.tao.render.TaoComposeSceneHost
import dev.nucleusframework.window.tao.render.TaoComposeSceneHostLinux
import dev.nucleusframework.window.tao.render.TaoComposeSceneHostWindows
import kotlin.math.roundToInt

/**
 * Holds the title-bar height (in dp / macOS points) currently requested by the
 * `TitleBar` composable. [DecoratedWindow] consumes this once the window has
 * been shown to centre the native traffic-light buttons inside our custom bar.
 */
internal val LocalRequestedTitleBarHeight =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Float>> {
        error("LocalRequestedTitleBarHeight not provided — DecoratedWindow installs it.")
    }

/**
 * Holds the ARGB clear color the Skia render loop applies to each frame,
 * pushed in by the themed window and by `TitleBar` from the resolved chrome
 * background. macOS-only: Linux/Windows hosts ignore it (they have native
 * window chrome with proper backgrounds). Defaults to opaque white via
 * [TaoComposeSceneHost.clearColorArgbState] until the first composition.
 */
internal val LocalRequestedClearColor =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Int>?> { null }

/**
 * Exposes the [TaoWindow] backing the current `DecoratedWindow` to any
 * descendant composable. Mirrors `androidx.compose.ui.window.LocalWindow` from
 * Compose Desktop, but for Tao-owned windows. Returns `null` outside of a
 * `DecoratedWindow` content lambda — call sites should fail loudly or no-op
 * when absent.
 */
val LocalTaoWindow = staticCompositionLocalOf<TaoWindow?> { null }

/**
 * Translucent black scrim painted over the parent window's content while a
 * modal dialog is open (Linux only). Dims the parent and reinforces the
 * dialog's elevation in the absence of a compositor-drawn drop shadow.
 */
private val ModalScrimColor = Color(0x66000000)

/**
 * Tao-backed equivalent of `decorated-window-jni`'s `DecoratedWindow`.
 * Imperative-on-the-outside, Composable-on-the-inside: opens a single Tao
 * window, mounts the user [content] inside its dedicated `ComposeScene`, and
 * returns the [TaoWindow] handle for further imperative control.
 *
 * Parameter set is intentionally a strict superset / matched subset of the
 * AWT-based backends so an app can swap modules with minimal call-site change.
 * `enabled = false` swallows pointer + keyboard events at the host level so
 * the window appears unresponsive (no native disabled-state visual — matches
 * `decorated-window-jni`'s behavior). `focusable = false` calls
 * `tao::Window::set_focusable(false)`, which prevents the window from ever
 * becoming key (useful for HUD/overlay windows).
 */
@Suppress("LongParameterList", "FunctionNaming", "CyclomaticComplexMethod", "LongMethod")
internal fun ApplicationScope.openDecoratedWindow(
    onCloseRequest: () -> Unit,
    title: String = "",
    icon: Painter? = null,
    width: Double = 800.0,
    height: Double = 600.0,
    minimumSize: DpSize? = null,
    visible: Boolean = true,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    maximized: Boolean = false,
    isDialog: Boolean = false,
    // Fully borderless window: no native chrome at all — on macOS this drops the
    // traffic-light buttons too. For overlay/ghost windows (drag previews, HUDs).
    undecorated: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    // Parent composition locals to bridge into this window's own ComposeScene
    // (applied above the scene's LocalComposeSceneContext so popups still route
    // into THIS scene). Used by DecoratedDialog so a dialog's content sees the
    // parent window's theme/user locals from the first composition without
    // hijacking popup positioning. See [LocalTaoCompositionLocalContextBridge].
    initialCompositionLocalContext: CompositionLocalContext? = null,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val window =
        taoApplication.openWindow(
            title = title,
            width = width,
            height = height,
            // On macOS we keep native decorations (traffic-light buttons live there).
            // On Windows + Linux we drop them — we draw the close/min/max buttons
            // ourselves via [WindowControlsWindows] / [WindowControlsLinux] inside
            // the user's [TitleBar] composable, mirroring decorated-window-jni.
            // `undecorated` opts out entirely (borderless, no traffic lights).
            decorations = !undecorated && Platform.Current == Platform.MacOS,
            resizable = resizable,
            visible = false, // we show after first paint
            // Pass `maximized` to the builder so Tao sets it BEFORE the window
            // is mapped. Applying it post-creation (via `setMaximized(true)`)
            // races the compositor's first configure on Linux/Wayland and
            // produces a one-frame glitch where the window flashes at its
            // requested logical size before snapping to maximized.
            maximized = maximized,
        )

    if (Platform.Current == Platform.Windows) {
        return openDecoratedWindowWindows(
            window,
            title,
            visible,
            enabled,
            focusable,
            alwaysOnTop,
            maximized,
            isDialog,
            icon,
            minimumSize,
            onCloseRequest,
            onPreviewKeyEvent,
            onKeyEvent,
            initialCompositionLocalContext,
            content,
        )
    }

    if (Platform.Current == Platform.Linux) {
        return openDecoratedWindowLinux(
            window,
            title,
            visible,
            enabled,
            focusable,
            alwaysOnTop,
            maximized,
            isDialog,
            icon,
            minimumSize,
            onCloseRequest,
            onPreviewKeyEvent,
            onKeyEvent,
            initialCompositionLocalContext,
            content,
        )
    }

    val host = TaoComposeSceneHost(window, macOSStyle = macOSStyle)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.setSceneCompositionLocalContext(initialCompositionLocalContext)

    // Trackpad pinch / rotate / smart-magnify, intercepted before AppKit
    // dispatches them down the responder chain (Tao 0.35 doesn't surface
    // these events). Synthesised as two-finger Touch pointers in the host
    // so cross-platform `detectTransformGestures` reacts uniformly.
    window.onTrackpadGesture { kind, phase, x, y, value ->
        if (enabled) host.onTrackpadGesture(kind, phase, x, y, value)
    }

    // ── macOS accessibility ────────────────────────────────────────────────
    // Spin up the per-window NSAccessibility projection. The observer hooks
    // into Compose's SemanticsOwnerListener and pushes a flat snapshot to
    // native on every change; the controller owns the per-window state and
    // routes VoiceOver actions back into Compose semantics actions.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver =
        TaoSemanticsObserver(
            controller = a11yController,
            densityProvider = { host.density() },
            onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    // Single source of truth shared with the host (which feeds it as a top
    // inset to the PlatformContext) and the TitleBar composable (which
    // updates it via SideEffect from its requested height).
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 28f }

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    window.onWindowReady { w, h ->
        host.attach()
        // Install a11y projection (TaoView swizzles + per-view registry +
        // NSWindow focus forwarder). Must follow attach() so the NSView
        // exists and the window is reachable.
        a11yController.attach()
        // Bridge Compose's non-editable selection (SelectionContainer) to native
        // a11y so PopClip can read it (editable selection already flows through
        // semantics). See TaoSelectionAccessibilityObserver.
        host.onTextSelectionForA11y = { text, editable, sourceId ->
            a11yController.setExternalSelection(text, editable, sourceId)
        }
        // Apply `minimumSize` synchronously *now*, while the window is still
        // hidden (visible=false). Tao's own `setMinimumSize` is queued via
        // its UserEvent loop and may not be drained before the LE that
        // computes `WindowPosition.Aligned` fires — and centring against the
        // pre-min-size frame puts the window off-centre once the resize
        // catches up. Routing through the deco bridge bypasses the queue so
        // the next read of `[NSWindow frame]` (done by
        // `applyAlignedPosition`) sees the post-resize size.
        if (minimumSize != null && NativeTaoMacOsDecoBridge.isLoaded) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            if (nsView != 0L) {
                NativeTaoMacOsDecoBridge.nativeApplyContentMinSize(
                    nsView,
                    minimumSize.width.value.toDouble(),
                    minimumSize.height.value.toDouble(),
                )
            }
        }
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalRequestedClearColor provides host.clearColorArgbState,
                LocalTaoPopupHost provides host.popupHost(),
                LocalTaoNativeViewHost provides host.nativeViewHost(),
                LocalTaoCompositionLocalContextBridge provides host::setSceneCompositionLocalContext,
            ) {
                // Re-centre the native AppKit traffic-lights whenever the
                // TitleBar/DialogTitleBar publishes a new measured height. A
                // one-shot in window.onResized used to latch the stale initial
                // height: the regular window's first resize fired after its
                // TitleBar had published 40dp, but a dialog's first resize
                // (driven by the centring + addChildWindow path) raced ahead of
                // DialogTitleBar's publish, latching the 28dp init and leaving
                // the traffic-lights at the wrong inset (margin 14 vs 20).
                // snapshotFlow keeps the read out of the content recomposition.
                LaunchedEffect(Unit) {
                    snapshotFlow { titleBarHeightState.value }.collect { height ->
                        val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
                        if (nsView != 0L && NativeMetalBridge.isLoaded) {
                            NativeMetalBridge.nativeApplyButtonLayout(nsView, height)
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    snapshotFlow { host.clearColorArgbState.value }.collect { argb ->
                        val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
                        if (nsView != 0L && NativeMetalBridge.isLoaded) {
                            NativeMetalBridge.nativeSetWindowBackgroundColor(nsView, argb)
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    scopeFactory().content()
                }
            }
        }
        // EVENT_WINDOW_READY carries the requested logical size (e.g. 800x600),
        // while the Metal host expects physical pixels paired with Density(scale).
        // For maximized windows, use the screen visibleFrame because Tao applies
        // the zoom synchronously during build(); otherwise scale the fallback.
        val (initialW, initialH) = initialMacOsSize(window, w, h, maximized)
        host.onResized(initialW, initialH)
        host.renderFrameBlocking()
        if (visible) window.show()
    }
    window.onResized { w, h ->
        host.onResized(w, h)
        // Traffic-light centring is now driven reactively from the title-bar
        // height (see the snapshotFlow in setContent), so it no longer needs to
        // be kicked off here on first resize.
        // Tao does not emit a dedicated "fullscreen state changed" event, but
        // every native fullscreen / unfullscreen transition resizes the
        // window. Re-query so [DecoratedWindowState.isFullscreen] (read by
        // TitleBar's double-click guard) stays in sync whether the user
        // entered fullscreen via the green traffic-light or by toggling
        // `state.placement` from a custom button.
        val maxNow = window.isMaximized
        val fsNow = window.isFullscreen
        if (stateHolder.value.isMaximized != maxNow ||
            stateHolder.value.isFullscreen != fsNow
        ) {
            stateHolder.value =
                stateHolder.value.copy(
                    maximized = maxNow,
                    fullscreen = fsNow,
                )
        }
    }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed {
        a11yController.dispose()
        host.detach()
    }
    window.onScaleFactorChanged { host.onScaleFactorChanged(it) }
    window.onPointerMoved { x, y -> if (enabled) host.onPointerMove(x, y) }
    window.onPointerExited { if (enabled) host.onPointerExited() }
    window.onPointerButton { b, p -> if (enabled) host.onPointerButton(b, p) }
    window.onPointerScroll { event -> if (enabled) host.onPointerScroll(event) }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
    }
    window.onRedrawRequested { host.requestFrame() }
    window.onFocusChanged { focused ->
        // When focus moves to an embedded child HWND (e.g., WebView2 on
        // Windows), Tao reports the main HWND as unfocused, but for app
        // purposes the window is still in active use — keep the chrome's
        // active visual. Only flip to inactive when focus truly left our
        // window tree (Alt-Tab to another app, etc.). The bridge below
        // is no-op on platforms where its DLL isn't loaded (isLoaded is
        // false on macOS), so this is safe to share across paths.
        val effective =
            focused ||
                (
                    NativeTaoWindowsNativeViewBridge.isLoaded &&
                        NativeTaoWindowsNativeViewBridge.nativeIsFocusInTree(window.nativeHandle)
                )
        stateHolder.value = stateHolder.value.copy(active = effective)
        host.onFocusChanged(focused)
    }
    // OS-driven minimize/restore — mirror into the scope's DecoratedWindowState
    // so `scope.state.isMinimized` (read by app code) reflects it. Wired on all
    // three platforms: macOS (windowDidMiniaturize/Deminiaturize), Windows
    // (WM_SIZE hook), and Linux — X11 via the GTK window-state-event, Wayland
    // via an app-driven synthesis hack (our minimize button / programmatic
    // only; the protocol reports no iconified state, so external minimize from
    // a taskbar isn't observable).
    window.onMinimizedChanged { minimized ->
        if (stateHolder.value.isMinimized != minimized) {
            stateHolder.value = stateHolder.value.copy(minimized = minimized)
        }
    }

    if (alwaysOnTop) window.setAlwaysOnTop(true)
    if (!focusable) window.setFocusable(false)
    minimumSize?.let { window.setMinimumSize(it.width.value.toDouble(), it.height.value.toDouble()) }
    icon?.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }

    return window
}

/**
 * Linux path for [DecoratedWindow]: EGL renderer attached to the GTK-owned
 * surface (X11 XID or wl_surface, picked at runtime by [TaoComposeSceneHostLinux]).
 * Native GTK decorations are kept; the user's [TitleBar] composable still
 * works as a sub-bar inside the content area.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
private fun ApplicationScope.openDecoratedWindowLinux(
    window: TaoWindow,
    title: String,
    visible: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    maximized: Boolean,
    isDialog: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    initialCompositionLocalContext: CompositionLocalContext?,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostLinux(window)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.setSceneCompositionLocalContext(initialCompositionLocalContext)

    // ── Linux accessibility (AT-SPI2 via AccessKit) ────────────────────────
    // Same SemanticsObserver pipeline as macOS / Windows. The controller
    // resolves the X11 XID at attach time and pushes the binary snapshot
    // through nucleus_tao's accesskit_unix Adapter, which speaks AT-SPI2
    // over D-Bus. Orca / accerciser see the tree like any other native
    // GTK app — modulo XWayland coordinates handled in `applyA11yBounds`.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver =
        TaoSemanticsObserver(
            controller = a11yController,
            densityProvider = { host.density() },
            onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    val fullscreenHolder = FullscreenTitleBarHolder()

    val linuxDe = LinuxDesktopEnvironment.Current
    window.onWindowReady { w, h ->
        host.attach()
        // Bring the AccessKit adapter up before we hand the SemanticsOwnerListener
        // its first push — same ordering as the macOS path. attach() resolves
        // the X11 XID via NativeTaoBridge.nativeLinuxHandles().
        a11yController.attach()
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalFullscreenTitleBarHolder provides fullscreenHolder,
                LocalTaoNativeViewHost provides host.nativeViewHost(),
                LocalTaoCompositionLocalContextBridge provides host::setSceneCompositionLocalContext,
                dev.nucleusframework.window.tao.render.LocalTaoLinuxOverlayController
                    provides host.overlayController(),
                // Override the default Skiko `URIManager` (calls
                // `Desktop.browse` → initialises XAWT → deadlocks our GLX
                // loop). See [TaoLinuxUriHandler].
                LocalUriHandler provides TaoLinuxUriHandler,
            ) {
                val border =
                    rememberUndecoratedWindowBorder(
                        state = stateHolder.value,
                        linuxDe = linuxDe,
                        gnomeCornerArc = 24f,
                        kdeCornerArc = 10f,
                        isDialog = isDialog,
                    )
                val modalCount =
                    remember {
                        mutableStateOf(0)
                    }
                CompositionLocalProvider(
                    dev.nucleusframework.window.LocalModalDialogCount provides modalCount,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FullscreenOverlayHost(
                            holder = fullscreenHolder,
                            isFullscreen = stateHolder.value.isFullscreen,
                            modifier = Modifier.fillMaxSize().then(border),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                scopeFactory().content()
                            }
                        }
                        if (modalCount.value > 0) {
                            // Dim the whole parent window while a dialog is open.
                            // Linux dialogs are undecorated (no compositor
                            // shadow), so the scrim is what visually pushes the
                            // parent back and lifts the dialog forward — on top
                            // of swallowing pointer events.
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(ModalScrimColor)
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                }
                                            }
                                        },
                            )
                        }
                    }
                }
            }
        }
        val (initialW, initialH) = initialLinuxSize(window, w, h, maximized)
        host.onResized(initialW, initialH)
        // First paint must happen *after* the surface is shown:
        //  - X11: a pre-show synchronous render leaves the GLX backbuffer
        //    invalidated by the subsequent map, so the dialog stayed black
        //    until something forced a repaint anyway.
        //  - Wayland: stricter — `eglSwapBuffers` against a wl_surface that
        //    hasn't received its xdg_toplevel.configure ack via GTK is
        //    silently dropped by the compositor and the window never
        //    appears at all.
        // Show first; the post-show `requestRedraw` schedules a draw on
        // the next event-loop tick once the surface is mapped (and on
        // Wayland, after the configure handshake completes).
        if (visible) {
            window.show()
            window.requestRedraw()
        }
        // Bounds get pushed by `onResized` which fires immediately after
        // `onWindowReady`. We deliberately don't push them here to avoid
        // any chance of interfering with the redraw chain that keeps a
        // freshly-mapped static window from rendering as black.
    }
    window.onResized { w, h ->
        host.onResized(w, h)
        // Tao on Linux doesn't emit a dedicated "maximized state changed"
        // event; every maximize/restore cycle resizes the window. The same is
        // true for compositor tiling (Aero Snap) — a snap is observed only as a
        // resize. Re-query is_maximized / is_tiled so the Compose state stays in
        // sync (a pure snap leaves maximized/fullscreen false, so isTiled is the
        // only signal that flips and must be part of the reactive diff).
        val maxNow = window.isMaximized
        val fsNow = window.isFullscreen
        val tiledNow = window.isTiled
        if (stateHolder.value.isMaximized != maxNow ||
            stateHolder.value.isFullscreen != fsNow ||
            stateHolder.value.isTiled != tiledNow
        ) {
            stateHolder.value =
                stateHolder.value.copy(
                    maximized = maxNow,
                    fullscreen = fsNow,
                    tiled = tiledNow,
                )
        }
        // EGL replaces the XShape rounded-clip with a Skia post-render
        // BlendMode.CLEAR carve in `host.onRedrawRequested` — the next
        // redraw (already requested by tao after the resize) picks up the
        // updated isMaximized / isFullscreen flag and skips the carve when
        // the window goes rectangular.
        // Push window-local bounds (0,0,w,h) on resize.
        pushA11yBoundsLinux(a11yController.nativeViewHandle, window.handle, w, h)
    }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed {
        a11yController.dispose()
        host.detach()
    }
    window.onScaleFactorChanged { host.onScaleFactorChanged(it) }
    window.onPointerMoved { x, y -> if (enabled) host.onPointerMove(x, y) }
    window.onPointerExited { if (enabled) host.onPointerExited() }
    window.onPointerButton { b, p -> if (enabled) host.onPointerButton(b, p) }
    window.onPointerScroll { event -> if (enabled) host.onPointerScroll(event) }
    window.onDragWindow { host.onNativeWindowDragStarted() }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        stateHolder.value = stateHolder.value.copy(active = focused)
        host.onFocusChanged(focused)
        if (a11yController.nativeViewHandle != 0L) {
            // Forward focus state to AccessKit so AT-SPI's STATE_ACTIVE flag
            // on the toplevel matches the actual X focus.
            NativeTaoBridge.nativeA11ySetWindowFocus(a11yController.nativeViewHandle, focused)
        }
    }
    // OS-driven minimize/restore — mirror into the scope's DecoratedWindowState
    // so `scope.state.isMinimized` (read by app code) reflects it. On Linux this
    // flows from the GTK window-state-event ICONIFIED transition on X11; on
    // Wayland it is synthesized from our own minimize action (the protocol
    // reports no iconified state — external minimize isn't observable).
    window.onMinimizedChanged { minimized ->
        if (stateHolder.value.isMinimized != minimized) {
            stateHolder.value = stateHolder.value.copy(minimized = minimized)
        }
    }

    if (alwaysOnTop) window.setAlwaysOnTop(true)
    if (!focusable) window.setFocusable(false)
    minimumSize?.let { window.setMinimumSize(it.width.value.toDouble(), it.height.value.toDouble()) }
    icon?.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }

    return window
}

/**
 * Pushes window-local bounds to AccessKit. The earlier X11-based
 * screen-space resolver (`nativeA11yResolveX11Bounds`) crashed inside
 * libX11's `XDefaultScreen` on some setups — most likely the second
 * libX11 instance racing GDK's main-loop X traffic. Until we have a
 * safe way to read screen coordinates (e.g. piggybacking on Tao's own
 * X connection), fall back to (0,0,w,h): Orca's flat review will treat
 * widgets as window-relative, which is a regression from
 * pixel-perfect highlights but keeps the app running.
 */
@Suppress("UnusedParameter")
private fun pushA11yBoundsLinux(
    xid: Long,
    windowHandle: Long,
    w: Int,
    h: Int,
) {
    if (xid == 0L) return
    NativeTaoBridge.nativeA11ySetRootBounds(
        xid,
        0L,
        0L,
        w.toLong(),
        h.toLong(),
        0L,
        0L,
        w.toLong(),
        h.toLong(),
    )
}

private fun initialMacOsSize(
    window: TaoWindow,
    fallbackW: Int,
    fallbackH: Int,
    maximized: Boolean,
): Pair<Int, Int> {
    fun fallbackPhysicalSize(): Pair<Int, Int> {
        val scale = initialMacOsScaleFactor(window).toDouble()
        return (fallbackW * scale).roundToInt() to (fallbackH * scale).roundToInt()
    }

    if (!maximized || !NativeTaoMacOsDecoBridge.isLoaded) return fallbackPhysicalSize()
    val workArea = NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorWorkArea() ?: return fallbackPhysicalSize()
    if (workArea.size != 4) return fallbackPhysicalSize()
    val width = workArea[2].toInt()
    val height = workArea[3].toInt()
    return if (width > 0 && height > 0) width to height else fallbackPhysicalSize()
}

internal fun initialMacOsScaleFactor(window: TaoWindow): Float {
    val windowScale = NativeTaoBridge.nativeScaleFactor(window.handle).coerceAtLeast(1000) / 1000f
    return maxOf(windowScale, primaryMacOsScaleFactor())
}

internal fun primaryMacOsScaleFactor(): Float {
    if (!NativeTaoMacOsDecoBridge.isLoaded) return 1f
    return NativeTaoMacOsDecoBridge
        .nativeGetPrimaryMonitorScaleMilli()
        .coerceAtLeast(1000) / 1000f
}

private fun initialLinuxSize(
    window: TaoWindow,
    fallbackW: Int,
    fallbackH: Int,
    maximized: Boolean,
): Pair<Int, Int> {
    // For a maximized first frame, swap WINDOW_READY's requested size for the
    // primary monitor's work area so Compose lays out at the final size before
    // the compositor's first configure — avoids the one-frame glitch at the
    // requested logical size before snapping to maximized.
    //
    // `host.onResized` stores into `widthPx`/`heightPx` (physical pixels — fed
    // directly to `nativeResize` and used by Compose with `Density(scale)`),
    // and monitor.rs already returns the work area in physical pixels, so we
    // pass the values through unchanged.
    if (!maximized || !NativeTaoBridge.isLoaded) return fallbackW to fallbackH
    val workArea =
        NativeTaoBridge.nativeLinuxPrimaryMonitorWorkArea(window.handle)
            ?: return fallbackW to fallbackH
    if (workArea.size != 4) return fallbackW to fallbackH
    val width = workArea[2].toInt()
    val height = workArea[3].toInt()
    return if (width > 0 && height > 0) width to height else fallbackW to fallbackH
}

/**
 * Windows path for [DecoratedWindow]: WGL renderer + custom WndProc decoration.
 * Boutons min/max/close drawn in Compose by the user content (the [TitleBar]
 * composable lays them out at `Modifier.align(Alignment.End)`).
 *
 * Hit-testing rule (memorised in CLAUDE.md): the WndProc returns HTCLIENT for
 * the entire title bar zone — never HTMINBUTTON/HTMAXBUTTON/HTCLOSE — so DWM
 * doesn't repaint native buttons on top of our Compose UI.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun ApplicationScope.openDecoratedWindowWindows(
    window: TaoWindow,
    title: String,
    visible: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    maximized: Boolean,
    isDialog: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    initialCompositionLocalContext: CompositionLocalContext?,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostWindows(window)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.setSceneCompositionLocalContext(initialCompositionLocalContext)

    // Trackpad pinch-to-zoom. Windows delivers a precision-touchpad pinch (and
    // a real Ctrl+wheel) as a Ctrl-flagged WM_MOUSEWHEEL; the Tao patch routes
    // those to the magnify hook instead of a scroll, and the host synthesises a
    // two-finger Touch pinch so cross-platform `detectTransformGestures` zooms
    // uniformly — same model as macOS.
    window.onTrackpadGesture { kind, phase, x, y, value ->
        if (enabled) host.onTrackpadGesture(kind, phase, x, y, value)
    }

    // ── Windows accessibility (UIA) ────────────────────────────────────────
    // Per-window UIA projection driven by the same SemanticsObserver pipeline
    // as macOS. The controller resolves the HWND on attach via
    // `nativeHwndHandle` and pushes the binary snapshot to nucleus_tao_a11y.dll.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver =
        TaoSemanticsObserver(
            controller = a11yController,
            densityProvider = { host.density() },
            onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: androidx.compose.foundation.layout.ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, androidx.compose.foundation.layout.ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    val fullscreenHolder = FullscreenTitleBarHolder()

    window.onWindowReady { w, h ->
        host.attach()
        a11yController.attach()
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalFullscreenTitleBarHolder provides fullscreenHolder,
                LocalTaoNativeViewHost provides host.nativeViewHost(),
                LocalTaoCompositionLocalContextBridge provides host::setSceneCompositionLocalContext,
                dev.nucleusframework.window.tao.render.LocalTaoPopupHostWindows
                    provides host.popupHost(),
            ) {
                val border =
                    rememberUndecoratedWindowBorder(
                        state = stateHolder.value,
                        linuxDe = LinuxDesktopEnvironment.Unknown,
                        gnomeCornerArc = 24f,
                        kdeCornerArc = 10f,
                        isDialog = isDialog,
                    )
                val modalCount =
                    remember {
                        mutableStateOf(0)
                    }
                CompositionLocalProvider(
                    dev.nucleusframework.window.LocalModalDialogCount provides modalCount,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FullscreenOverlayHost(
                            holder = fullscreenHolder,
                            isFullscreen = stateHolder.value.isFullscreen,
                            modifier = Modifier.fillMaxSize().then(border),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                scopeFactory().content()
                            }
                        }
                        if (modalCount.value > 0) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                }
                                            }
                                        },
                            )
                        }
                    }
                }
            }
        }
        // For an initially-maximized window, EVENT_WINDOW_READY carries the
        // requested *logical* size (e.g. 800x600), not the actual maximized
        // size — the WindowEvent::Resized that would update Compose to the
        // maximized dimensions hasn't been dispatched yet. Without this fix
        // Compose lays out at 800x600 inside a 2560x1040 GL surface and the
        // user sees their content in the top-left corner with white margins.
        // Use the monitor's work area: the client size of a maximized
        // borderless window matches rcWork exactly (Tao's WM_NCCALCSIZE
        // clips the client to rcWork for the maximized borderless case).
        // GetWindowRect would return the *outer* rect which extends ~8px past
        // every edge (Win32 frame quirk), and sizing the GL surface to that
        // would draw Compose content into the off-screen frame area.
        val (initialW, initialH) =
            if (maximized && NativeTaoWindowsDecoBridge.isLoaded) {
                val workArea = NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                if (workArea != null && workArea.size == 4) {
                    workArea[2].toInt() to workArea[3].toInt()
                } else {
                    w to h
                }
            } else {
                w to h
            }
        host.onResized(initialW, initialH)
        host.syncTitleBarHeight()
        host.onRedrawRequested()
        if (visible) window.show()
    }
    window.onResized { w, h ->
        host.onResized(w, h)
        host.syncTitleBarHeight()
        // Tao does not emit a dedicated "fullscreen state changed" event, but
        // every maximize/restore cycle resizes the window. Re-query is_maximized
        // here to keep the Compose state (used by the maximize button icon
        // swap) in sync.
        val maxNow = window.isMaximized
        val fsNow = window.isFullscreen
        if (stateHolder.value.isMaximized != maxNow ||
            stateHolder.value.isFullscreen != fsNow
        ) {
            stateHolder.value =
                stateHolder.value.copy(
                    maximized = maxNow,
                    fullscreen = fsNow,
                )
        }
    }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed {
        a11yController.dispose()
        host.detach()
    }
    window.onScaleFactorChanged { host.onScaleFactorChanged(it) }
    window.onPointerMoved { x, y -> if (enabled) host.onPointerMove(x, y) }
    window.onPointerExited { if (enabled) host.onPointerExited() }
    window.onPointerButton { b, p -> if (enabled) host.onPointerButton(b, p) }
    window.onPointerScroll { event -> if (enabled) host.onPointerScroll(event) }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        // When focus moves to an embedded child HWND (e.g., WebView2 on
        // Windows), Tao reports the main HWND as unfocused, but for app
        // purposes the window is still in active use — keep the chrome's
        // active visual. Only flip to inactive when focus truly left our
        // window tree (Alt-Tab to another app, etc.). The bridge below
        // is no-op on platforms where its DLL isn't loaded (isLoaded is
        // false on macOS), so this is safe to share across paths.
        val effective =
            focused ||
                (
                    NativeTaoWindowsNativeViewBridge.isLoaded &&
                        NativeTaoWindowsNativeViewBridge.nativeIsFocusInTree(window.nativeHandle)
                )
        stateHolder.value = stateHolder.value.copy(active = effective)
        host.onFocusChanged(focused)
    }
    // OS-driven minimize/restore — mirror into the scope's DecoratedWindowState
    // so `scope.state.isMinimized` (read by app code) reflects it. Wired on all
    // three platforms: macOS (windowDidMiniaturize/Deminiaturize), Windows
    // (WM_SIZE hook), and Linux — X11 via the GTK window-state-event, Wayland
    // via an app-driven synthesis hack (our minimize button / programmatic
    // only; the protocol reports no iconified state, so external minimize from
    // a taskbar isn't observable).
    window.onMinimizedChanged { minimized ->
        if (stateHolder.value.isMinimized != minimized) {
            stateHolder.value = stateHolder.value.copy(minimized = minimized)
        }
    }

    if (alwaysOnTop) window.setAlwaysOnTop(true)
    if (!focusable) window.setFocusable(false)
    minimumSize?.let { window.setMinimumSize(it.width.value.toDouble(), it.height.value.toDouble()) }
    icon?.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }

    return window
}
