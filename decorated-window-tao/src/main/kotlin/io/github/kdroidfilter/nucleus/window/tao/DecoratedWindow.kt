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
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
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
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
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
    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    window.onWindowReady { w, h ->
        host.attach()
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                // Override the default Skiko `URIManager` (calls
                // `Desktop.browse` → initialises XAWT → deadlocks our GLX
                // loop). See [TaoLinuxUriHandler].
                LocalUriHandler provides TaoLinuxUriHandler,
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
        // Tao on Linux doesn't emit a dedicated "maximized state changed"
        // event; every maximize/restore cycle resizes the window. Re-query
        // is_maximized so the Compose state stays in sync.
        val maxNow = window.isMaximized
        if (stateHolder.value.isMaximized != maxNow) {
            stateHolder.value = stateHolder.value.copy(maximized = maxNow)
        }
        // Reapply the rounded-rect XShape now that we've observed the
        // post-resize maximized flag — `host.onResized` ran ahead of the
        // is_maximized query and may have set the wrong shape on a
        // borderline maximize/restore frame.
        host.applyRoundedShape()
    }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed { host.detach() }
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
    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: androidx.compose.foundation.layout.ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, androidx.compose.foundation.layout.ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    window.onWindowReady { w, h ->
        host.attach()
        host.setContent {
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalRequestedTitleBarHeight provides titleBarHeightState,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    scopeFactory().content()
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
        if (stateHolder.value.isMaximized != maxNow) {
            stateHolder.value = stateHolder.value.copy(maximized = maxNow)
        }
    }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed { host.detach() }
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
