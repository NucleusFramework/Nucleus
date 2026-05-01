package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHost

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
 * Parameters that have no Tao equivalent yet (e.g. `enabled`, `focusable`,
 * `onPreviewKeyEvent`) are accepted and silently ignored — wiring them is
 * Phase 2b work.
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
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable DecoratedWindowScope.() -> Unit,
): TaoWindow {
    val window = taoApplication.openWindow(
        title = title,
        width = width,
        height = height,
        decorations = true,
        resizable = resizable,
        visible = false, // we show after first paint
    )

    val host = TaoComposeSceneHost(window, macOSStyle = macOSStyle)
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
        // TODO Phase 2b: expose tao::Window::set_always_on_top via JNI.
    }

    return window
}
