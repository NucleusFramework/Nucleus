package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.State
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tao-backed [NucleusWindow]. Reads from the [TaoWindow] handle and the
 * `DecoratedWindowState` snapshot maintained by the Tao decorated-window
 * pipeline. State writes use the imperative TaoWindow setters; the existing
 * `LaunchedEffect` chain in `tao.DecoratedWindow` echoes them back into the
 * Compose `WindowState` so user code observing it stays in sync.
 */
internal class TaoNucleusWindow(
    private val taoWindow: TaoWindow,
    private val decoratedState: State<DecoratedWindowState>,
) : NucleusWindow {
    private val _focus = MutableStateFlow(decoratedState.value.isActive)
    private val _minimized = MutableStateFlow(decoratedState.value.isMinimized)
    private val _maximized = MutableStateFlow(decoratedState.value.isMaximized)
    private val _fullscreen = MutableStateFlow(decoratedState.value.isFullscreen)

    init {
        // TaoWindow's focus + resize listeners are multi-cast; the per-window
        // pipeline installed by `openDecoratedWindow` keeps running alongside
        // these.
        taoWindow.onFocusChanged { _focus.value = it }
        taoWindow.onResized { _, _ ->
            _maximized.value = taoWindow.isMaximized
            _fullscreen.value = taoWindow.isFullscreen
        }
    }

    override val isFocused: Boolean get() = decoratedState.value.isActive
    override val isMinimized: Boolean get() = decoratedState.value.isMinimized
    override val isMaximized: Boolean get() = taoWindow.isMaximized
    override val isFullscreen: Boolean get() = taoWindow.isFullscreen

    override fun show() = taoWindow.show()

    override fun hide() = taoWindow.hide()

    override fun toFront() {
        // Tao exposes no explicit `toFront`; show() raises and focuses the
        // window on every supported platform.
        taoWindow.show()
    }

    override fun requestFocus() {
        taoWindow.show()
    }

    override fun setMinimized(minimized: Boolean) {
        taoWindow.setMinimized(minimized)
        _minimized.value = minimized
    }

    override fun setMaximized(maximized: Boolean) {
        taoWindow.setMaximized(maximized)
        _maximized.value = maximized
    }

    override fun setFullscreen(fullscreen: Boolean) {
        taoWindow.setFullscreen(fullscreen)
        _fullscreen.value = fullscreen
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) = taoWindow.setAlwaysOnTop(alwaysOnTop)

    override fun setMinimumSize(size: DpSize?) {
        taoWindow.setMinimumSize(
            size?.width?.value?.toDouble(),
            size?.height?.value?.toDouble(),
        )
    }

    override fun setIcon(painter: Painter?) {
        // Icon updates flow through the `icon` parameter of DecoratedWindow
        // (reactive `LaunchedEffect`). Imperative setIcon() is a no-op on both
        // backends to keep a single source of truth.
    }

    override fun close() {
        taoWindow.requestUserClose()
    }

    override val focusFlow: StateFlow<Boolean> = _focus.asStateFlow()
    override val minimizedFlow: StateFlow<Boolean> = _minimized.asStateFlow()
    override val maximizedFlow: StateFlow<Boolean> = _maximized.asStateFlow()
    override val fullscreenFlow: StateFlow<Boolean> = _fullscreen.asStateFlow()

    override val unsafe: NucleusWindowUnsafe =
        object : NucleusWindowUnsafe {
            override val awtWindow: ComposeWindow? = null
            override val taoWindow: TaoWindow = this@TaoNucleusWindow.taoWindow
            override val taoHandle: Long = this@TaoNucleusWindow.taoWindow.handle
        }
}
