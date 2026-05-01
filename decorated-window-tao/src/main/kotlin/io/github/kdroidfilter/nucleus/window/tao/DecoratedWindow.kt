package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHost
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHostWindows

/**
 * Title of the enclosing [DecoratedWindow]. Read by [TitleBar] to populate
 * [TitleBarScope.title]. Matches `decorated-window-core`'s `LocalTitleBarInfo`.
 */
val LocalDecoratedWindowTitle = staticCompositionLocalOf { "" }

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
 * Parameters that have no Tao equivalent yet (e.g. `enabled`, `focusable`)
 * are accepted and silently ignored — wiring them is Phase 2b work.
 */
@Suppress("LongParameterList", "FunctionNaming")
fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    title: String = "",
    width: Double = 800.0,
    height: Double = 600.0,
    visible: Boolean = true,
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable DecoratedWindowScope.() -> Unit,
): TaoWindow {
    val window = taoApplication.openWindow(
        title = title,
        width = width,
        height = height,
        // On macOS we keep native decorations (traffic-light buttons live there).
        // On Windows we drop them: the WndProc subclass installed by
        // `NativeTaoWindowsDecoBridge` repaints the title bar zone as client area
        // and Compose draws min/max/close itself.
        decorations = Platform.Current != Platform.Windows,
        resizable = resizable,
        visible = false, // we show after first paint
    )

    if (Platform.Current == Platform.Windows) {
        return openDecoratedWindowWindows(window, title, visible, alwaysOnTop, onCloseRequest, onPreviewKeyEvent, content)
    }

    val host = TaoComposeSceneHost(window, macOSStyle = macOSStyle)
    host.previewKeyHandler = onPreviewKeyEvent
    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
    // Single source of truth shared with the host (which feeds it as a top
    // inset to the PlatformContext) and the TitleBar composable (which
    // updates it via SideEffect from its requested height).
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 28f }
    var buttonLayoutApplied = false

    val scopeFactory: ColumnScope.() -> DecoratedWindowScope = {
        object : DecoratedWindowScope, ColumnScope by this {
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
        host.setContent {
            CompositionLocalProvider(
                LocalDecoratedWindowTitle provides title,
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
    window.onDestroyed { host.detach() }
    window.onScaleFactorChanged { host.onScaleFactorChanged(it) }
    window.onPointerMoved { x, y -> host.onPointerMove(x, y) }
    window.onPointerExited { host.onPointerExited() }
    window.onPointerButton { b, p -> host.onPointerButton(b, p) }
    window.onPointerScroll { dx, dy -> host.onPointerScroll(dx, dy) }
    window.onKeyEvent { type, vk, loc, mods, cp -> host.onKeyEvent(type, vk, loc, mods, cp) }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        stateHolder.value = stateHolder.value.copy(active = focused)
        host.onFocusChanged(focused)
    }

    if (alwaysOnTop) {
        window.setAlwaysOnTop(true)
    }

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
    alwaysOnTop: Boolean,
    onCloseRequest: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable DecoratedWindowScope.() -> Unit,
): TaoWindow {
    val host = TaoComposeSceneHostWindows(window)
    host.previewKeyHandler = onPreviewKeyEvent
    val stateHolder = mutableStateOf(DecoratedWindowState.of(active = true))
    val titleBarHeightState = host.titleBarHeightDpState.also { it.value = 32f }

    val scopeFactory: androidx.compose.foundation.layout.ColumnScope.() -> DecoratedWindowScope = {
        object : DecoratedWindowScope, androidx.compose.foundation.layout.ColumnScope by this {
            override val window: TaoWindow = window
            override val state: DecoratedWindowState get() = stateHolder.value
        }
    }

    window.onWindowReady { w, h ->
        host.attach()
        host.setContent {
            CompositionLocalProvider(
                LocalDecoratedWindowTitle provides title,
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
    window.onPointerMoved { x, y -> host.onPointerMove(x, y) }
    window.onPointerExited { host.onPointerExited() }
    window.onPointerButton { b, p -> host.onPointerButton(b, p) }
    window.onPointerScroll { dx, dy -> host.onPointerScroll(dx, dy) }
    window.onKeyEvent { type, vk, loc, mods, cp -> host.onKeyEvent(type, vk, loc, mods, cp) }
    window.onRedrawRequested { host.onRedrawRequested() }
    window.onFocusChanged { focused ->
        stateHolder.value = stateHolder.value.copy(active = focused)
        host.onFocusChanged(focused)
    }

    if (alwaysOnTop) {
        window.setAlwaysOnTop(true)
    }

    return window
}
