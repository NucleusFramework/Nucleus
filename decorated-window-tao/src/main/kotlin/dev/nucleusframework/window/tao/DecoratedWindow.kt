@file:Suppress("MagicNumber")
@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.nucleusframework.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
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
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.GlobalModalDialogCount
import dev.nucleusframework.window.LocalModalDialogCount
import dev.nucleusframework.window.LocalTitleBarInfo
import dev.nucleusframework.window.TitleBarInfo
import dev.nucleusframework.window.internal.isDark
import dev.nucleusframework.window.tao.a11y.TaoSemanticsObserver
import dev.nucleusframework.window.tao.deco.FullscreenOverlayHost
import dev.nucleusframework.window.tao.deco.FullscreenTitleBarHolder
import dev.nucleusframework.window.tao.deco.LocalFullscreenTitleBarHolder
import dev.nucleusframework.window.tao.deco.rememberUndecoratedWindowBorder
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import dev.nucleusframework.window.tao.ffi.toRgbaIcon
import dev.nucleusframework.window.tao.popup.LocalTaoPopupHost
import dev.nucleusframework.window.tao.scene.TaoComposeSceneHost
import dev.nucleusframework.window.tao.scene.TaoComposeSceneHostLinux
import dev.nucleusframework.window.tao.scene.TaoComposeSceneHostWindows
import dev.nucleusframework.window.tao.scene.catchExceptions
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

private val hiddenFromDockLogger: java.util.logging.Logger =
    java.util.logging.Logger
        .getLogger("dev.nucleusframework.window.tao.hiddenFromDock")

/**
 * Holds the ARGB clear color the Skia render loop applies to each frame,
 * pushed in by the themed window (`DecoratedWindowComposable`, from the window
 * background) and by `TitleBar` from the resolved chrome background — the
 * latter composes deeper, so it wins when both are present. Honored by all
 * three Tao hosts (macOS / Windows / Linux) so a Compose region without an
 * explicit background matches the chrome color on every platform — mirroring
 * the AWT backends' `Modifier.background(titleBarBackground)` in
 * `DecoratedWindowBody`. On Linux the host still carves the rounded corners
 * back to transparent after rendering. Defaults to opaque white until the
 * first composition.
 */
internal val LocalWindowClearColorLayers =
    staticCompositionLocalOf<WindowClearColorLayers?> { null }

/** Opaque white — the clear colour before any style or content publishes one. */
private const val DEFAULT_CLEAR_ARGB = 0xFFFFFFFF.toInt()

/**
 * The window's clear colour as two explicit layers with one resolver.
 *
 * The hoisted window style writes the [style layer][setStyle]
 * (`DecoratedWindowComposable`); content writers (`WindowBackground`,
 * `TitleBar`) stack on the [content layer][setContent], which outranks style.
 * Each writer holds a stable key: re-`setContent` moves it to the top
 * (last SideEffect wins while co-composed); [clearContent] removes only that
 * writer so a surviving `WindowBackground` is restored when `TitleBar`
 * leaves composition instead of wiping the slot to null.
 *
 * When [fullyTransparent] is true (#416), fully opaque ARGB values are coerced
 * to alpha-0 so a default white theme or TitleBar chrome colour cannot fill
 * the empty client and hide the desktop. Semi-transparent colours still tint.
 * Compose widgets keep painting their own backgrounds; this only affects the
 * Skia / native clear under unpainted regions.
 *
 * Every write re-resolves into the host state *synchronously, inside the
 * caller's SideEffect* — so the first composition has fully themed the host
 * before the window's first blocking render and `show()`.
 *
 * Runs on the Tao main thread only, so no synchronization is needed.
 */
internal class WindowClearColorLayers(
    private val hostClearColor: androidx.compose.runtime.MutableState<Int>,
    private val fullyTransparent: Boolean = false,
) {
    private var style: Int = if (fullyTransparent) 0 else DEFAULT_CLEAR_ARGB

    /** Insertion-ordered content writers; last entry is the active content. */
    private val contentWriters = LinkedHashMap<Any, Int>()

    val resolved: Int get() = contentWriters.values.lastOrNull() ?: style

    /** The resolved colour as observable snapshot state (the host state itself). */
    val observableResolved: androidx.compose.runtime.State<Int> get() = hostClearColor

    fun setStyle(argb: Int) {
        style = coerce(argb)
        push()
    }

    /**
     * Publishes [argb] as this [key]'s content contribution. Re-entry moves
     * [key] to the top so co-composed writers resolve by SideEffect order.
     */
    fun setContent(
        key: Any,
        argb: Int,
    ) {
        contentWriters.remove(key)
        contentWriters[key] = coerce(argb)
        push()
    }

    /** Drops only [key]'s contribution; other content writers stay. */
    fun clearContent(key: Any) {
        if (contentWriters.remove(key) != null) push()
    }

    private fun coerce(argb: Int): Int = if (fullyTransparent && ((argb ushr 24) and 0xFF) == 0xFF) 0 else argb

    private fun push() {
        hostClearColor.value = resolved
    }
}

/**
 * Holds whether a native system material is showing through this window (see
 * `Modifier.windowGlassRegion`). Backed by the host's `glassBackgroundState`:
 * while `true`, the render loop clears the Skia surface to transparent so the
 * material inserted below the content is visible wherever Compose paints
 * nothing. macOS only — `null` elsewhere.
 */
internal val LocalRequestedGlassBackground =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/**
 * Holds whether the client area must stay transparent so a DWM system backdrop
 * shows through (see `WindowsBackdrop`). Backed by the Windows host's
 * `transparentBackgroundState`: while `true`, the render loop clears the Skia
 * surface to alpha 0 instead of the window background colour. Windows only —
 * `null` elsewhere.
 */
internal val LocalRequestedTransparentBackground =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/**
 * ARGB the render loop clears to while a backdrop is active — the app's tint
 * layer over the DWM material (see the host's `backdropTintArgbState`).
 * Written by `WindowsBackdrop`. Windows only — `null` elsewhere.
 */
internal val LocalBackdropComposeTint =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Int>?> { null }

/**
 * Appearance forced by [dev.nucleusframework.window.WindowAppearance], for the
 * platforms whose chrome is Compose-drawn (Windows). While `System`, the
 * chrome derives light/dark from the window background's luminance — the same
 * signal the native layer feeds `DWMWA_USE_IMMERSIVE_DARK_MODE`, so the
 * Compose-drawn glyphs and the DWM material can never disagree.
 */
internal val LocalRequestedAppearanceOverride =
    staticCompositionLocalOf<
        androidx.compose.runtime.MutableState<dev.nucleusframework.window.WindowAppearanceMode>?,
    > { null }

/**
 * Exposes the [TaoWindow] backing the current `DecoratedWindow` to any
 * descendant composable. Mirrors `androidx.compose.ui.window.LocalWindow` from
 * Compose Desktop, but for Tao-owned windows. Returns `null` outside of a
 * `DecoratedWindow` content lambda — call sites should fail loudly or no-op
 * when absent.
 */
public val LocalTaoWindow: ProvidableCompositionLocal<TaoWindow?> = staticCompositionLocalOf { null }

/**
 * Translucent black scrim painted over the parent window's content while a
 * modal dialog is open (Linux only). Dims the parent and reinforces the
 * dialog's elevation in the absence of a compositor-drawn drop shadow.
 */
private val ModalScrimColor = Color(0x66000000)

/**
 * Tao-backed equivalent of the legacy AWT backend's `DecoratedWindow`.
 * Imperative-on-the-outside, Composable-on-the-inside: opens a single Tao
 * window, mounts the user [content] inside its dedicated `ComposeScene`, and
 * returns the [TaoWindow] handle for further imperative control.
 *
 * Parameter set is intentionally a strict superset / matched subset of the
 * AWT-based backends so an app can swap modules with minimal call-site change.
 * `enabled = false` swallows pointer + keyboard events at the host level so
 * the window appears unresponsive (no native disabled-state visual — matches
 * the legacy AWT backend's behavior). `focusable = false` calls
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
    // Fully borderless: macOS drops traffic lights; Win/Linux skip the Compose
    // CSD outline. For overlays/ghosts (drag previews, HUDs).
    undecorated: Boolean = false,
    // Full-window per-pixel transparency (#416). Creation-time only — see
    // DecoratedWindow(transparent = …).
    transparent: Boolean = false,
    // Linux only: make this window a popup overlay of [popupFor]
    // (GTK_WINDOW_POPUP transient → wl_subsurface on Wayland, the only
    // client-positionable window kind under xdg-shell). Positions are
    // parent-relative on Wayland. Ignored on other platforms.
    popupFor: TaoWindow? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    // Materialise Compose Popup layers as native transparent windows
    // (NSPanel / WS_POPUP HWND / Tao popup window on Linux) instead of
    // drawing them inline in this window's render target.
    nativePopupLayers: Boolean = false,
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    // Hide this window from the taskbar/Dock (macOS: NSApplication accessory
    // policy, app-wide; Windows: WS_EX_TOOLWINDOW, per-window; Linux: GTK
    // skip-taskbar/skip-pager hints, per-window, X11/XWayland only).
    hiddenFromDock: Boolean = false,
    // Parent composition locals to bridge into this window's own ComposeScene
    // (applied above the scene's LocalComposeSceneContext so popups still route
    // into THIS scene). Used by DecoratedDialog so a dialog's content sees the
    // parent window's theme/user locals from the first composition without
    // hijacking popup positioning. See [LocalTaoCompositionLocalContextBridge].
    initialCompositionLocalContext: CompositionLocalContext? = null,
    // Linux only: give this window an X11 surface even on a native Wayland
    // session — see DecoratedWindow(forceX11 = …).
    forceX11: Boolean = false,
    // Builds the handler that catches exceptions raised by user code inside
    // this window (frames, input, IME, a11y). Read once, in the parent
    // composition, from [LocalWindowExceptionHandlerFactory] — the handler is
    // then held in a plain field on the host, because it must also cover
    // exceptions that break the composition it would otherwise be read from.
    exceptionHandlerFactory: WindowExceptionHandlerFactory = DefaultWindowExceptionHandlerFactory,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    // hiddenFromDock rides on the GTK skip-taskbar/skip-pager hint, which
    // native Wayland does not honour: there is no client-side skip-taskbar
    // protocol on Wayland (xdg-shell, gtk_shell1 and the staging extensions all
    // lack it, and Mutter rejects wlr-layer-shell). It is effective only under
    // X11/XWayland. Warn so the no-op isn't silent — either take an X11
    // surface for this window ([forceX11]) or put the whole app on XWayland
    // with NUCLEUS_TAO_LINUX_RENDERER=x11.
    val willBeX11 =
        forceX11 ||
            System.getenv("GDK_BACKEND").orEmpty().equals("x11", ignoreCase = true) ||
            System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
    if (hiddenFromDock &&
        Platform.Current == Platform.Linux &&
        Platform.isWayland &&
        !willBeX11
    ) {
        hiddenFromDockLogger.warning(
            "hiddenFromDock has no effect on native Wayland: Wayland has no client-side " +
                "skip-taskbar protocol. Pass forceX11 = true for this window, or run with " +
                "NUCLEUS_TAO_LINUX_RENDERER=x11 (XWayland) for the whole app.",
        )
    }
    val window =
        taoApplication.openWindow(
            title = title,
            width = width,
            height = height,
            // On macOS we keep native decorations (traffic-light buttons live there).
            // On Windows + Linux we drop them — we draw the close/min/max buttons
            // ourselves via [WindowControlsWindows] / [WindowControlsLinux] inside
            // the user's [TitleBar] composable, mirroring the legacy AWT backend.
            // `undecorated` opts out entirely (borderless, no traffic lights).
            // Linux still gets the native GTK drop shadow through
            // `undecoratedShadow` below (yaru.dart-style hidden-titlebar CSD).
            decorations = !undecorated && Platform.Current == Platform.MacOS,
            resizable = resizable,
            visible = false, // we show after first paint
            // Pass `maximized` to the builder so Tao sets it BEFORE the window
            // is mapped. Applying it post-creation (via `setMaximized(true)`)
            // races the compositor's first configure on Linux/Wayland and
            // produces a one-frame glitch where the window flashes at its
            // requested logical size before snapping to maximized.
            maximized = maximized,
            popupOf = popupFor,
            // Windows + Linux: taskbar/Alt+Tab exclusion is a creation-time
            // tao attribute (on Windows a post-creation style change is
            // clobbered by tao's own style rewrites). macOS handles
            // hiddenFromDock via the activation policy in
            // TaoComposeSceneHost.attach() instead.
            skipTaskbar = hiddenFromDock,
            transparent = transparent,
            forceX11 = forceX11,
            // Tao defaults borderless windows to a drop shadow (DWM on
            // Windows, NSWindow.hasShadow on macOS). Overlays must opt out
            // or the ghost still shows a soft contour. Fully transparent
            // windows drop it on Windows too: the style-level shadow traces
            // the rectangular HWND, not the content-defined shape (#416) —
            // macOS keeps it (AppKit shapes the shadow to the drawn content)
            // and Linux keeps its CSD hidden-titlebar path.
            undecoratedShadow =
                !undecorated && !(transparent && Platform.Current == Platform.Windows),
        )

    // Compose Hot Reload: the agent only auto-wraps AWT `ComposeWindow`/
    // `ComposeDialog` `setContent` — Tao owns its own windows, so we wrap the
    // scene content in `DevelopmentEntryPoint` ourselves, and publish this
    // window's geometry into the orchestration `WindowsState` so the dev-tools
    // sidecar can follow it. Both no-op when not running under the hot-reload
    // agent. See [TaoHotReloadIntegration].
    val hotReloadContent: @Composable TaoDecoratedWindowScope.() -> Unit = {
        TaoHotReloadIntegration.wrapContent { content() }
    }
    // Popups (popupFor != null) are transient overlays; the sidecar tracks
    // only real windows/dialogs, matching Compose Hot Reload's AWT tracker.
    if (popupFor == null) {
        TaoHotReloadIntegration.trackWindow(window, title, alwaysOnTop)
    }

    val exceptionHandler = exceptionHandlerFactory.exceptionHandler(window)

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
            undecorated,
            icon,
            minimumSize,
            onCloseRequest,
            onPreviewKeyEvent,
            onKeyEvent,
            initialCompositionLocalContext,
            nativePopupLayers,
            transparent,
            exceptionHandler,
            hotReloadContent,
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
            undecorated,
            icon,
            minimumSize,
            onCloseRequest,
            onPreviewKeyEvent,
            onKeyEvent,
            initialCompositionLocalContext,
            nativePopupLayers,
            transparent,
            exceptionHandler,
            hotReloadContent,
        )
    }

    val host =
        TaoComposeSceneHost(
            window,
            macOSStyle = macOSStyle,
            hiddenFromDock = hiddenFromDock,
            fullyTransparent = transparent,
        )
    host.nativePopupLayers = nativePopupLayers
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.exceptionHandler = exceptionHandler
    host.setSceneCompositionLocalContext(initialCompositionLocalContext)

    // Trackpad pinch / rotate / smart-magnify, intercepted before AppKit
    // dispatches them down the responder chain (Tao 0.35 doesn't surface
    // these events). Synthesised as two-finger Touch pointers in the host
    // so cross-platform `detectTransformGestures` reacts uniformly.
    window.onTrackpadGesture { kind, phase, x, y, value ->
        exceptionHandler.catchExceptions {
            if (enabled) host.onTrackpadGesture(kind, phase, x, y, value)
        }
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
            onScheduleSync = { obs ->
                host.scheduleA11ySync(gate = a11yController::shouldRunSync) { obs.syncIfDirty() }
            },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    // Single source of truth shared with the host (which feeds it as a top
    // inset to the PlatformContext) and the TitleBar composable (which
    // updates it via SideEffect from its requested height).
    // Borderless overlays have no TitleBar chrome — keep the caption zone at 0
    // (parity with the Windows path).
    val titleBarHeightState =
        host.titleBarHeightDpState.also { it.value = if (undecorated) 0f else 28f }

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState
                get() = stateHolder.value.copy(resizable = window.isResizable)
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
            val clearColorLayers =
                remember {
                    WindowClearColorLayers(
                        host.clearColorArgbState,
                        fullyTransparent = transparent,
                    )
                }
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalWindowClearColorLayers provides clearColorLayers,
                LocalRequestedGlassBackground provides host.glassBackgroundState,
                LocalTaoPopupHost provides host.popupHost(),
                dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
                    provides host.metalTextureHost(),
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
                    // Always publish the themed color: the native side stores
                    // it and applies it according to the window's transparency
                    // mode (kept clear under a full glass backdrop, painted on
                    // the still-opaque window when glass regions are active).
                    snapshotFlow { host.clearColorArgbState.value }.collect { argb ->
                        val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
                        if (nsView != 0L && NativeMetalBridge.isLoaded) {
                            NativeMetalBridge.nativeSetWindowBackgroundColor(nsView, argb)
                        }
                    }
                }
                WindowSceneColumn {
                    scopeFactory().hotReloadContent()
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
    // Input dispatch runs app code (gesture callbacks, key handlers), so every
    // entry is guarded — the Tao counterpart of AWT's `catchExceptions`-wrapped
    // `onMouseEvent` / `onKeyEvent`. Frames are guarded one level down, inside
    // `TaoSceneBundle.render`.
    window.onPointerMoved { x, y -> exceptionHandler.catchExceptions { if (enabled) host.onPointerMove(x, y) } }
    window.onPointerExited { exceptionHandler.catchExceptions { if (enabled) host.onPointerExited() } }
    window.onPointerButton { b, p -> exceptionHandler.catchExceptions { if (enabled) host.onPointerButton(b, p) } }
    window.onPointerScroll { event -> exceptionHandler.catchExceptions { if (enabled) host.onPointerScroll(event) } }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        exceptionHandler.catchExceptions(fallback = false) {
            if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
        }
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
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun ApplicationScope.openDecoratedWindowLinux(
    window: TaoWindow,
    title: String,
    visible: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    maximized: Boolean,
    isDialog: Boolean,
    undecorated: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    initialCompositionLocalContext: CompositionLocalContext?,
    nativePopupLayers: Boolean,
    transparent: Boolean,
    exceptionHandler: WindowExceptionHandler,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostLinux(window, fullyTransparent = transparent)
    host.nativePopupLayers = nativePopupLayers
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.exceptionHandler = exceptionHandler
    // Yaru-style hidden-titlebar CSD (native GTK shadow ring): created via
    // `undecoratedShadow = !undecorated` at openWindow time; the host aligns
    // the frame radius and extends the resize band over the ring. Only
    // effective on Wayland non-popup windows.
    host.nativeCsdDecorations = !undecorated
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
            onScheduleSync = { obs ->
                host.scheduleA11ySync(gate = a11yController::shouldRunSync) { obs.syncIfDirty() }
            },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState
                get() = stateHolder.value.copy(resizable = window.isResizable)
        }
    }

    val fullscreenHolder = FullscreenTitleBarHolder()

    val linuxDe = LinuxDesktopEnvironment.Current
    // Wayland: GTK destroys the window's `wl_surface` on hide and creates a
    // fresh one on show. Suspend the EGL attachment before the hide (a swap
    // racing the surface destruction is a fatal Wayland protocol error) and
    // rebuild it once the new surface exists. No-ops on X11.
    window.onWillHide { host.suspendGpu() }
    window.onShown { host.resumeGpu() }
    window.onWindowReady { w, h ->
        host.attach()
        // Bring the AccessKit adapter up before we hand the SemanticsOwnerListener
        // its first push — same ordering as the macOS path. attach() resolves
        // the X11 XID via NativeTaoBridge.nativeLinuxHandles().
        a11yController.attach()
        host.setContent {
            val clearColorLayers =
                remember {
                    WindowClearColorLayers(
                        host.clearColorArgbState,
                        fullyTransparent = transparent,
                    )
                }
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalWindowClearColorLayers provides clearColorLayers,
                LocalFullscreenTitleBarHolder provides fullscreenHolder,
                LocalTaoNativeViewHost provides host.nativeViewHost(),
                LocalTaoCompositionLocalContextBridge provides host::setSceneCompositionLocalContext,
                // Read as state: a Wayland hide/show rebuilds the EGL + Skia
                // context pair, and TextureView imports must follow it.
                dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
                    provides host.glTextureHostState.value,
                dev.nucleusframework.window.tao.deco.LocalTaoLinuxOverlayController
                    provides host.overlayController(),
                // Override the default Skiko `URIManager` (calls
                // `Desktop.browse` → initialises XAWT → deadlocks our GLX
                // loop). See [TaoLinuxUriHandler].
                LocalUriHandler provides TaoLinuxUriHandler,
            ) {
                // Default: CSD outline (vanilla-style frame for custom chrome).
                // `undecorated` = fully borderless overlay — no Compose stroke.
                // `transparent` skips it too: the stroke traces the rectangular
                // window bounds, not the content-defined shape (#416).
                val border =
                    if (undecorated || transparent) {
                        Modifier
                    } else {
                        rememberUndecoratedWindowBorder(
                            state = stateHolder.value,
                            linuxDe = linuxDe,
                            gnomeCornerArc = 24f,
                            kdeCornerArc = 10f,
                            isDialog = isDialog,
                        )
                    }
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
                            WindowSceneColumn {
                                scopeFactory().content()
                            }
                        }
                        if (modalCount.value > 0 || (!isDialog && GlobalModalDialogCount.value > 0)) {
                            // Dim the whole parent window while a dialog is open.
                            // Linux dialogs are undecorated (no compositor
                            // shadow), so the scrim is what visually pushes the
                            // parent back and lifts the dialog forward — on top
                            // of swallowing pointer events. GlobalModalDialogCount
                            // covers dialogs composed at application scope, which
                            // are application-modal: they block every window
                            // except dialog windows themselves (the app-modal
                            // dialog must stay interactive).
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
    // An app-initiated interactive move hands the pointer to the compositor,
    // which reports the window as unfocused for the duration of the grab —
    // that would flip the chrome to its inactive look mid-drag. Mask it while
    // the move runs.
    //
    // The mask is dropped when real pointer input resumes, NOT on focus-in:
    // GNOME toggles keyboard focus *during* a move grab, and clearing on that
    // mid-grab focus-in would unmask the focus-out that follows, leaving the
    // chrome inactive for the rest of the drag (X11 does not toggle, which is
    // why it only shows on Wayland). The compositor withholds pointer events
    // for the whole grab, so their return is the reliable grab-ended signal.
    var lastFocused = true

    // The host owns the grab state for BOTH interactive moves and resizes (it
    // arms `compositorDragActive` from `dragWindow()` and from its own
    // resize-edge hit test), so read it instead of tracking a second flag —
    // a move-only flag left resize grabs unmasked.
    fun chromeActive(focused: Boolean) = focused || host.isCompositorGrabActive

    // Settle the chrome once real pointer input resumes: that is the host's own
    // grab-ended signal, and it is the only moment we get, since no focus event
    // necessarily follows the grab. Runs AFTER the host has processed the event
    // so its flag is already cleared.
    fun settleAfterGrab() {
        val settled = chromeActive(lastFocused)
        if (stateHolder.value.isActive != settled) {
            stateHolder.value = stateHolder.value.copy(active = settled)
        }
    }
    // Input dispatch runs app code (gesture callbacks, key handlers), so every
    // entry is guarded — the Tao counterpart of AWT's `catchExceptions`-wrapped
    // `onMouseEvent` / `onKeyEvent`. Frames are guarded one level down, inside
    // `TaoSceneBundle.render`.
    window.onPointerMoved { x, y ->
        exceptionHandler.catchExceptions {
            if (enabled) host.onPointerMove(x, y)
            settleAfterGrab()
        }
    }
    window.onPointerExited { exceptionHandler.catchExceptions { if (enabled) host.onPointerExited() } }
    window.onPointerButton { b, p ->
        exceptionHandler.catchExceptions {
            if (enabled) host.onPointerButton(b, p)
            settleAfterGrab()
        }
    }
    window.onPointerScroll { event -> exceptionHandler.catchExceptions { if (enabled) host.onPointerScroll(event) } }
    window.onDragWindow { host.onNativeWindowDragStarted() }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        exceptionHandler.catchExceptions(fallback = false) {
            if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
        }
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        lastFocused = focused
        stateHolder.value = stateHolder.value.copy(active = chromeActive(focused))
        // The host and a11y get the raw truth; only the chrome's visual
        // active state is held through the grab.
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

/**
 * Scale factor the Compose scene's density is seeded with in
 * [dev.nucleusframework.window.tao.scene.TaoComposeSceneHost.attach].
 *
 * A scale factor is a property of the display a window is on, so the window's
 * own reading is the only valid answer. This used to take the *max* with the
 * primary monitor's scale, as a guard against a not-yet-ready reading — but no
 * other display's scale is ever a substitute: with a Retina Main display and
 * the window on a 1x screen, the max laid the whole window out at 2x (#506).
 *
 * The guard was unnecessary anyway. `nativeScaleFactor` reports 1000 both for
 * a genuine 1x window and for a handle the loop does not know, so a stale
 * reading is not detectable here to begin with — and by `attach()` the window
 * is registered (EVENT_WINDOW_READY precedes it) and carries its display's
 * real scale.
 */
internal fun initialMacOsScaleFactor(window: TaoWindow): Float =
    NativeTaoBridge.nativeScaleFactor(window.handle).coerceAtLeast(1000) / 1000f

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
 * Windows path for [DecoratedWindow]: ANGLE/D3D11 renderer + custom WndProc decoration.
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
    undecorated: Boolean,
    icon: Painter?,
    minimumSize: DpSize?,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    initialCompositionLocalContext: CompositionLocalContext?,
    nativePopupLayers: Boolean,
    transparent: Boolean,
    exceptionHandler: WindowExceptionHandler,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host =
        TaoComposeSceneHostWindows(
            window,
            fullyTransparent = transparent,
            borderlessChrome = undecorated,
        )
    host.nativePopupLayers = nativePopupLayers
    host.previewKeyHandler = onPreviewKeyEvent
    host.keyHandler = onKeyEvent
    host.exceptionHandler = exceptionHandler
    host.setSceneCompositionLocalContext(initialCompositionLocalContext)

    // Trackpad pinch-to-zoom. Windows delivers a precision-touchpad pinch (and
    // a real Ctrl+wheel) as a Ctrl-flagged WM_MOUSEWHEEL; the Tao patch routes
    // those to the magnify hook instead of a scroll, and the host synthesises a
    // two-finger Touch pinch so cross-platform `detectTransformGestures` zooms
    // uniformly — same model as macOS.
    window.onTrackpadGesture { kind, phase, x, y, value ->
        exceptionHandler.catchExceptions {
            if (enabled) host.onTrackpadGesture(kind, phase, x, y, value)
        }
    }

    // ── Windows accessibility (AccessKit → UIA) ────────────────────────────
    // Per-window UIA projection via AccessKit, same wire format / SemanticsObserver
    // pipeline as Linux. The controller resolves the HWND on attach via
    // `nativeHwndHandle` and pushes binary snapshots into nucleus_tao.dll.
    val a11yController = TaoAccessibilityController(window.handle)
    val a11yObserver =
        TaoSemanticsObserver(
            controller = a11yController,
            densityProvider = { host.density() },
            onScheduleSync = { obs ->
                host.scheduleA11ySync(gate = a11yController::shouldRunSync) { obs.syncIfDirty() }
            },
        )
    host.semanticsOwnerListener = a11yObserver

    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true, maximized = maximized))
    // Borderless overlays have no TitleBar chrome — keep the caption zone at 0.
    val titleBarHeightState =
        host.titleBarHeightDpState.also { it.value = if (undecorated) 0f else 32f }
    // Hoisted out of the composition so the initial native theme push below
    // (before the first blocking render and show()) can read it.
    val appearanceOverride = mutableStateOf(dev.nucleusframework.window.WindowAppearanceMode.System)

    fun pushNativeTheme() {
        if (!NativeTaoWindowsDecoBridge.isLoaded) return
        val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        if (hwnd == 0L) return
        val argb = host.clearColorArgbState.value
        NativeTaoWindowsDecoBridge.nativeSetBackgroundColor(
            hwnd,
            argb,
            resolveChromeDark(appearanceOverride.value, argb),
        )
    }

    val scopeFactory: androidx.compose.foundation.layout.ColumnScope.() -> TaoDecoratedWindowScope = {
        object : TaoDecoratedWindowScope, androidx.compose.foundation.layout.ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState
                get() = stateHolder.value.copy(resizable = window.isResizable)
        }
    }

    val fullscreenHolder = FullscreenTitleBarHolder()

    window.onWindowReady { w, h ->
        host.attach()
        a11yController.attach()
        host.setContent {
            val clearColorLayers =
                remember {
                    WindowClearColorLayers(
                        host.clearColorArgbState,
                        fullyTransparent = transparent,
                    )
                }
            CompositionLocalProvider(
                LocalTitleBarInfo provides TitleBarInfo(title, icon),
                LocalTaoWindow provides window,
                LocalRequestedTitleBarHeight provides titleBarHeightState,
                LocalWindowClearColorLayers provides clearColorLayers,
                LocalRequestedTransparentBackground provides host.transparentBackgroundState,
                LocalBackdropComposeTint provides host.backdropTintArgbState,
                LocalFullscreenTitleBarHolder provides fullscreenHolder,
                LocalTaoNativeViewHost provides host.nativeViewHost(),
                LocalTaoCompositionLocalContextBridge provides host::setSceneCompositionLocalContext,
                dev.nucleusframework.window.tao.popup.LocalTaoPopupHostWindows
                    provides host.popupHost(),
                dev.nucleusframework.window.tao.scene.LocalTaoWindowsTextureHost
                    provides host.windowsTextureHostState.value,
            ) {
                // Light/dark for the whole chrome. One source of truth (the
                // clear colour + the WindowAppearance override, both snapshot
                // state), one resolution function, and one native call below:
                // the Compose-drawn glyphs and the DWM material derive from
                // the same snapshot-consistent pair, so a torn theme — glyphs
                // on one theme, material on the other — cannot be expressed.
                // The initial push happens synchronously after setContent
                // (before the first blocking render and show()); this flow
                // handles every later change.
                val chromeIsDark =
                    resolveChromeDark(appearanceOverride.value, host.clearColorArgbState.value)
                LaunchedEffect(Unit) {
                    snapshotFlow {
                        // Both states are read inside one snapshot: the pair
                        // can never mix an old colour with a new override.
                        val argb = host.clearColorArgbState.value
                        argb to resolveChromeDark(appearanceOverride.value, argb)
                    }.collect { (argb, isDark) ->
                        if (NativeTaoWindowsDecoBridge.isLoaded) {
                            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
                            if (hwnd != 0L) {
                                NativeTaoWindowsDecoBridge.nativeSetBackgroundColor(hwnd, argb, isDark)
                            }
                        }
                    }
                }
                // Default: CSD outline for custom chrome windows. `undecorated`
                // means fully borderless (vanilla Compose Desktop semantics for
                // overlays/ghosts) — do not stroke a frame. `transparent`
                // windows skip it too: the DWM 1px frame follows the
                // rectangular HWND, not the content-defined shape (#416).
                val border =
                    if (undecorated || transparent) {
                        Modifier
                    } else {
                        rememberUndecoratedWindowBorder(
                            state = stateHolder.value,
                            linuxDe = LinuxDesktopEnvironment.Unknown,
                            gnomeCornerArc = 24f,
                            kdeCornerArc = 10f,
                            isDialog = isDialog,
                        )
                    }
                val modalCount =
                    remember {
                        mutableStateOf(0)
                    }
                CompositionLocalProvider(
                    dev.nucleusframework.window.LocalModalDialogCount provides modalCount,
                    dev.nucleusframework.window.LocalIsDarkTheme provides chromeIsDark,
                    LocalRequestedAppearanceOverride provides appearanceOverride,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FullscreenOverlayHost(
                            holder = fullscreenHolder,
                            isFullscreen = stateHolder.value.isFullscreen,
                            modifier = Modifier.fillMaxSize().then(border),
                        ) {
                            WindowSceneColumn {
                                scopeFactory().content()
                            }
                        }
                        if (modalCount.value > 0 || (!isDialog && GlobalModalDialogCount.value > 0)) {
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
        host.syncTitleBarHeight()
        // The initial composition's SideEffects have themed the host clear
        // colour by now (layer writes push synchronously); mirror it to the
        // native side before the first paint — the snapshot flow above only
        // starts once the event loop resumes, which is after show().
        pushNativeTheme()
        // Theme push re-runs applyCaptionColors; reassert borderless so a
        // transparent clear (alpha-0 → RGB black) cannot restore a DWM border.
        if (undecorated && NativeTaoWindowsDecoBridge.isLoaded) {
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
            if (hwnd != 0L) {
                NativeTaoWindowsDecoBridge.nativeSetBorderlessChrome(hwnd, true)
            }
        }
        // onResized renders synchronously, so this doubles as the guaranteed
        // first paint before the window is shown (no separate onRedrawRequested).
        host.onResized(initialW, initialH)
        if (visible) window.show()
        // DWM finalises non-client chrome on show — reassert once more.
        if (undecorated && NativeTaoWindowsDecoBridge.isLoaded) {
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
            if (hwnd != 0L) {
                NativeTaoWindowsDecoBridge.nativeSetBorderlessChrome(hwnd, true)
            }
        }
    }

    // Fullscreen toggle, two hooks (issue 413):
    //  1. Pre-layout at the target size before the geometry change — warms
    //     Compose measure/layout without presenting anything, with the
    //     chrome already flipped to the TARGET state.
    //  2. The synchronous prepare, invoked from the deco WndProc INSIDE the
    //     toggle's SetWindowPos (WM_WINDOWPOSCHANGED): renders + presents
    //     the new-size frame before the geometry change returns, so DWM
    //     never composites the new geometry with stale content. The Windows
    //     analog of the macOS windowWillEnter/ExitFullScreen prepare.
    fun syncPlacementFlags() {
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
    // Installed lazily from the prepare (the HWND is not resolvable at
    // window-construction time), synchronously before the toggle's geometry
    // change — so the hook always exists by the time the prepare fires.
    var fsSizeHookInstalled = false

    fun installFullscreenSizeHook() {
        if (fsSizeHookInstalled || !NativeTaoWindowsDecoBridge.isLoaded) return
        val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        if (hwnd == 0L) return
        fsSizeHookInstalled = true
        NativeTaoWindowsDecoBridge.setFullscreenSizeHook(hwnd) { w, h ->
            syncPlacementFlags()
            host.fullscreenTransitionResized(w, h)
            host.syncTitleBarHeight()
        }
        window.onDestroyed {
            NativeTaoWindowsDecoBridge.setFullscreenSizeHook(hwnd, null)
        }
    }
    window.onFullscreenPrepare { w, h, fs ->
        installFullscreenSizeHook()
        // Chrome flags first, from the INTENT (the native flag flips later,
        // inside nativeSetFullscreen): the warmed layout must already show
        // the target chrome (overlay armed on enter, inline bar on exit).
        if (stateHolder.value.isFullscreen != fs) {
            stateHolder.value = stateHolder.value.copy(fullscreen = fs)
        }
        host.fullscreenPreLayout(w, h)
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
    // Close *request* is cancelable ("Save before quit?" → Cancel). Do not
    // tear down a live WindowsBackdrop here — that left the window permanently
    // de-mica'd while the composable was still composed. The opaque last frame
    // is prepared on the confirmed destroy path ([TaoWindow.requestClose] →
    // [onPrepareClose]), while the window and EGL surface are still alive
    // (detach() runs after DestroyWindow, far too late to steer the snapshot).
    window.onPrepareClose { host.prepareClose() }
    window.onCloseRequested { onCloseRequest() }
    window.onDestroyed {
        a11yController.dispose()
        host.detach()
    }
    window.onScaleFactorChanged { host.onScaleFactorChanged(it) }
    // The OS modal move / resize loop (WM_NCLBUTTONDOWN + HTCAPTION, or a
    // resize border) can report the window as unfocused while it runs, which
    // would flip the chrome to its inactive look mid-drag. Mask it for exactly
    // the duration of the loop.
    var inSizeMoveLoop = false
    var lastFocused = true

    // Focus alone does not decide the chrome's look: focus moving to an
    // embedded child HWND (e.g. WebView2) leaves the window in active use, and
    // the size/move loop masks a transient loss. Both `onFocusChanged` and the
    // settle below go through this, so they can never disagree.
    fun chromeActive(focused: Boolean) =
        focused ||
            inSizeMoveLoop ||
            (
                NativeTaoWindowsNativeViewBridge.isLoaded &&
                    NativeTaoWindowsNativeViewBridge.nativeIsFocusInTree(window.nativeHandle)
            )
    // Input dispatch runs app code (gesture callbacks, key handlers), so every
    // entry is guarded — the Tao counterpart of AWT's `catchExceptions`-wrapped
    // `onMouseEvent` / `onKeyEvent`. Frames are guarded one level down, inside
    // `TaoSceneBundle.render`.
    window.onPointerMoved { x, y -> exceptionHandler.catchExceptions { if (enabled) host.onPointerMove(x, y) } }
    window.onPointerExited { exceptionHandler.catchExceptions { if (enabled) host.onPointerExited() } }
    window.onPointerButton { b, p -> exceptionHandler.catchExceptions { if (enabled) host.onPointerButton(b, p) } }
    window.onPointerScroll { event -> exceptionHandler.catchExceptions { if (enabled) host.onPointerScroll(event) } }
    // WM_ENTERSIZEMOVE / WM_EXITSIZEMOVE brackets the modal MOVE and RESIZE
    // loops exactly, so it is the only mask signal needed here — unlike Linux,
    // no pointer-driven fallback is required, and using one would be harmful:
    // the loop can deliver mouse moves, which would drop the mask on the first
    // sample, one frame into the drag.
    window.onSizeMoveChanged { active ->
        inSizeMoveLoop = active
        // No focus event necessarily follows the loop, so settle the chrome on
        // its way out.
        if (!active) {
            val settled = chromeActive(lastFocused)
            if (stateHolder.value.isActive != settled) {
                stateHolder.value = stateHolder.value.copy(active = settled)
            }
        }
        host.onResizeLoopChanged(active)
    }
    window.onKeyEvent { type, vk, loc, mods, cp ->
        exceptionHandler.catchExceptions(fallback = false) {
            if (enabled) host.onKeyEvent(type, vk, loc, mods, cp) else false
        }
    }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        lastFocused = focused
        // When focus moves to an embedded child HWND (e.g., WebView2 on
        // Windows), Tao reports the main HWND as unfocused, but for app
        // purposes the window is still in active use — keep the chrome's
        // active visual. Only flip to inactive when focus truly left our
        // window tree (Alt-Tab to another app, etc.). The bridge below
        // is no-op on platforms where its DLL isn't loaded (isLoaded is
        // false on macOS), so this is safe to share across paths.
        stateHolder.value = stateHolder.value.copy(active = chromeActive(focused))
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
 * THE resolution point for the Windows chrome's light/dark: the value it
 * returns is provided as `LocalIsDarkTheme` (caption glyphs, hover overlays)
 * and pushed as `DWMWA_USE_IMMERSIVE_DARK_MODE` (the backdrop material) by
 * the same snapshot-driven flow — one function, one pair of inputs, so the
 * two sides cannot be resolved differently.
 */
private fun resolveChromeDark(
    override: dev.nucleusframework.window.WindowAppearanceMode,
    backgroundArgb: Int,
): Boolean =
    when (override) {
        dev.nucleusframework.window.WindowAppearanceMode.Dark -> true
        dev.nucleusframework.window.WindowAppearanceMode.Light -> false
        // Same Rec.601 luminance split the other backends use.
        dev.nucleusframework.window.WindowAppearanceMode.System -> Color(backgroundArgb).isDark()
    }
