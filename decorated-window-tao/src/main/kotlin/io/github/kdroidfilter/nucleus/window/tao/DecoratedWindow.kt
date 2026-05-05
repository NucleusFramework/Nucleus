@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.window.DecoratedWindowState

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.LocalTitleBarInfo
import io.github.kdroidfilter.nucleus.window.TitleBarInfo
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHost
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHostLinux
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHostWindows

/**
 * Holds the title-bar height (in dp / macOS points) currently requested by the
 * `TitleBar` composable. [DecoratedWindow] consumes this once the window has
 * been shown to centre the native traffic-light buttons inside our custom bar.
 */
internal val LocalRequestedTitleBarHeight = staticCompositionLocalOf<androidx.compose.runtime.MutableState<Float>> {
    error("LocalRequestedTitleBarHeight not provided — DecoratedWindow installs it.")
}

/**
 * Exposes the [TaoWindow] backing the current `DecoratedWindow` to any
 * descendant composable. Mirrors `androidx.compose.ui.window.LocalWindow` from
 * Compose Desktop, but for Tao-owned windows. Returns `null` outside of a
 * `DecoratedWindow` content lambda — call sites should fail loudly or no-op
 * when absent.
 */
val LocalTaoWindow = staticCompositionLocalOf<TaoWindow?> { null }

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
@Suppress("LongParameterList", "FunctionNaming")
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
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val window = taoApplication.openWindow(
        title = title,
        width = width,
        height = height,
        // On macOS we keep native decorations (traffic-light buttons live there).
        // On Windows + Linux we drop them — we draw the close/min/max buttons
        // ourselves via [WindowControlsWindows] / [WindowControlsLinux] inside
        // the user's [TitleBar] composable, mirroring decorated-window-jni.
        decorations = Platform.Current == Platform.MacOS,
        resizable = resizable,
        visible = false, // we show after first paint
    )

    if (Platform.Current == Platform.Windows) {
        return openDecoratedWindowWindows(
            window, title, visible, enabled, focusable, alwaysOnTop,
            icon, minimumSize, onCloseRequest, onPreviewKeyEvent, onKeyEvent, content,
        )
    }

    if (Platform.Current == Platform.Linux) {
        return openDecoratedWindowLinux(
            window, title, visible, enabled, focusable, alwaysOnTop,
            icon, minimumSize, onCloseRequest, onPreviewKeyEvent, onKeyEvent, content,
        )
    }

    val host = TaoComposeSceneHost(window, macOSStyle = macOSStyle)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent

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
    val a11yObserver = TaoSemanticsObserver(
        controller = a11yController,
        densityProvider = { host.density() },
        onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
    )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
    // Single source of truth shared with the host (which feeds it as a top
    // inset to the PlatformContext) and the TitleBar composable (which
    // updates it via SideEffect from its requested height).
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 28f }
    var buttonLayoutApplied = false

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    window.onWindowReady { w, h ->
        host.attach()
        // Add the NSTextView overlay subview that AppKit will use as the
        // firstResponder during Compose TextField focus — required to engage
        // press-and-hold (long-press 'e' → accent picker).
        NativeTaoBridge.nativeAttachTextOverlay(window.handle)
        // Install a11y projection (TaoView swizzles + per-view registry +
        // NSWindow focus forwarder). Must follow attach() so the NSView
        // exists and the window is reachable.
        a11yController.attach()
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
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    scopeFactory().content()
                }
            }
        }
        host.onResized(w, h)
        host.onRedrawRequested()
        if (visible) window.show()
    }
    window.onResized { w, h ->
        host.onResized(w, h)
        if (!buttonLayoutApplied) {
            buttonLayoutApplied = true
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            if (nsView != 0L) {
                NativeMetalBridge.nativeApplyButtonLayout(nsView, titleBarHeightState.value)
            }
        }
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
            stateHolder.value = stateHolder.value.copy(
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
    window.onPointerScroll { dx, dy -> if (enabled) host.onPointerScroll(dx, dy) }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        stateHolder.value = stateHolder.value.copy(active = focused)
        host.onFocusChanged(focused)
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
@Suppress("FunctionNaming", "LongParameterList")
private fun ApplicationScope.openDecoratedWindowLinux(
    window: TaoWindow,
    title: String,
    visible: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostLinux(window)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent

    // ── Linux accessibility (AT-SPI2 via AccessKit) ────────────────────────
    // Same SemanticsObserver pipeline as macOS / Windows. The controller
    // resolves the X11 XID at attach time and pushes the binary snapshot
    // through nucleus_tao's accesskit_unix Adapter, which speaks AT-SPI2
    // over D-Bus. Orca / accerciser see the tree like any other native
    // GTK app — modulo XWayland coordinates handled in `applyA11yBounds`.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver = TaoSemanticsObserver(
        controller = a11yController,
        densityProvider = { host.density() },
        onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
    )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
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
                // Override the default Skiko `URIManager` (calls
                // `Desktop.browse` → initialises XAWT → deadlocks our GLX
                // loop). See [TaoLinuxUriHandler].
                LocalUriHandler provides TaoLinuxUriHandler,
            ) {
                val border = rememberUndecoratedWindowBorder(
                    state = stateHolder.value,
                    linuxDe = linuxDe,
                    gnomeCornerArc = 24f,
                    kdeCornerArc = 10f,
                )
                FullscreenOverlayHost(
                    holder = fullscreenHolder,
                    isFullscreen = stateHolder.value.isFullscreen,
                    modifier = Modifier.fillMaxSize().then(border),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        scopeFactory().content()
                    }
                }
            }
        }
        host.onResized(w, h)
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
        // event; every maximize/restore cycle resizes the window. Re-query
        // is_maximized so the Compose state stays in sync.
        val maxNow = window.isMaximized
        val fsNow = window.isFullscreen
        if (stateHolder.value.isMaximized != maxNow ||
            stateHolder.value.isFullscreen != fsNow
        ) {
            stateHolder.value = stateHolder.value.copy(
                maximized = maxNow,
                fullscreen = fsNow,
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
    window.onPointerScroll { dx, dy -> if (enabled) host.onPointerScroll(dx, dy) }
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
private fun pushA11yBoundsLinux(xid: Long, windowHandle: Long, w: Int, h: Int) {
    if (xid == 0L) return
    NativeTaoBridge.nativeA11ySetRootBounds(
        xid,
        0L, 0L, w.toLong(), h.toLong(),
        0L, 0L, w.toLong(), h.toLong(),
    )
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
@Suppress("FunctionNaming", "LongParameterList")
private fun ApplicationScope.openDecoratedWindowWindows(
    window: TaoWindow,
    title: String,
    visible: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostWindows(window)
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent

    // ── Windows accessibility (UIA) ────────────────────────────────────────
    // Per-window UIA projection driven by the same SemanticsObserver pipeline
    // as macOS. The controller resolves the HWND on attach via
    // `nativeHwndHandle` and pushes the binary snapshot to nucleus_tao_a11y.dll.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver = TaoSemanticsObserver(
        controller = a11yController,
        densityProvider = { host.density() },
        onScheduleSync = { obs -> host.scheduleA11ySync { obs.syncIfDirty() } },
    )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
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
            ) {
                val border = rememberUndecoratedWindowBorder(
                    state = stateHolder.value,
                    linuxDe = LinuxDesktopEnvironment.Unknown,
                    gnomeCornerArc = 24f,
                    kdeCornerArc = 10f,
                )
                FullscreenOverlayHost(
                    holder = fullscreenHolder,
                    isFullscreen = stateHolder.value.isFullscreen,
                    modifier = Modifier.fillMaxSize().then(border),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        scopeFactory().content()
                    }
                }
            }
        }
        host.onResized(w, h)
        host.syncTitleBarHeight()
        host.onRedrawRequested()
        if (visible) window.show()
    }
    window.onResized { w, h ->
        host.onResized(w, h)
        host.syncTitleBarHeight()
        // Tao does not emit a dedicated "maximized state changed" event, but
        // every maximize/restore cycle resizes the window. Re-query is_maximized
        // here to keep the Compose state (used by the maximize button icon
        // swap) in sync.
        val maxNow = window.isMaximized
        val fsNow = window.isFullscreen
        if (stateHolder.value.isMaximized != maxNow ||
            stateHolder.value.isFullscreen != fsNow
        ) {
            stateHolder.value = stateHolder.value.copy(
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
    window.onPointerScroll { dx, dy -> if (enabled) host.onPointerScroll(dx, dy) }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        stateHolder.value = stateHolder.value.copy(active = focused)
        host.onFocusChanged(focused)
    }

    if (alwaysOnTop) window.setAlwaysOnTop(true)
    if (!focusable) window.setFocusable(false)
    minimumSize?.let { window.setMinimumSize(it.width.value.toDouble(), it.height.value.toDouble()) }
    icon?.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }

    return window
}
