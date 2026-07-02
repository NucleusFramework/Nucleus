package dev.nucleusframework.application

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * AWT-backed [NucleusWindow]. Wraps a [ComposeWindow] together with the
 * [WindowState] driven by the user — state writes go through [state] so they
 * compose correctly with the existing `DecoratedWindow` reactivity, while
 * imperative reads come straight off the AWT window.
 */
internal class AwtNucleusWindow(
    private val composeWindow: ComposeWindow,
    private val state: WindowState,
    private val onCloseRequest: () -> Unit,
) : NucleusWindow {
    private val _focus = MutableStateFlow(composeWindow.isFocused)
    private val _minimized = MutableStateFlow(state.isMinimized)
    private val _maximized = MutableStateFlow(state.placement == WindowPlacement.Maximized)
    private val _fullscreen = MutableStateFlow(state.placement == WindowPlacement.Fullscreen)

    init {
        composeWindow.addWindowFocusListener(
            object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    _focus.value = true
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    _focus.value = false
                }
            },
        )
        composeWindow.addWindowStateListener(
            object : WindowAdapter() {
                override fun windowStateChanged(e: WindowEvent) {
                    _minimized.value = (e.newState and Frame.ICONIFIED) != 0
                    _maximized.value = (e.newState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
                }
            },
        )
        composeWindow.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    _fullscreen.value = state.placement == WindowPlacement.Fullscreen
                }
            },
        )
    }

    override val isFocused: Boolean get() = composeWindow.isFocused
    override val isMinimized: Boolean get() = state.isMinimized
    override val isMaximized: Boolean get() = state.placement == WindowPlacement.Maximized
    override val isFullscreen: Boolean get() = state.placement == WindowPlacement.Fullscreen

    override fun boundsOnScreen(): NucleusWindowBounds? =
        runCatching {
            if (!composeWindow.isShowing) return null
            val location = composeWindow.locationOnScreen
            NucleusWindowBounds(
                x = location.x.toFloat(),
                y = location.y.toFloat(),
                width = composeWindow.width.toFloat(),
                height = composeWindow.height.toFloat(),
            )
        }.getOrNull()

    override fun show() = onEdt { composeWindow.isVisible = true }

    override fun hide() = onEdt { composeWindow.isVisible = false }

    override fun toFront() = onEdt { composeWindow.toFront() }

    override fun requestFocus() = onEdt { composeWindow.requestFocus() }

    override fun setMinimized(minimized: Boolean) {
        state.isMinimized = minimized
        _minimized.value = minimized
    }

    override fun setMaximized(maximized: Boolean) {
        state.placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating
        _maximized.value = maximized
    }

    override fun setFullscreen(fullscreen: Boolean) {
        state.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        _fullscreen.value = fullscreen
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) =
        onEdt {
            composeWindow.isAlwaysOnTop = alwaysOnTop
        }

    override fun setMinimumSize(size: DpSize?) =
        onEdt {
            if (size == null) {
                composeWindow.minimumSize = null
            } else {
                val scale =
                    composeWindow.graphicsConfiguration
                        ?.defaultTransform
                        ?.scaleX
                        ?.toFloat() ?: 1f
                composeWindow.minimumSize =
                    java.awt.Dimension(
                        (size.width.value * scale).toInt(),
                        (size.height.value * scale).toInt(),
                    )
            }
        }

    override fun setIcon(painter: Painter?) {
        // AWT icon is set via the `icon` parameter of Compose's Window. Live
        // updates of the icon belong to the @Composable layer; this method is
        // a no-op to avoid fighting the parameter-driven path.
    }

    override fun close() = onEdt { onCloseRequest() }

    override val focusFlow: StateFlow<Boolean> = _focus.asStateFlow()
    override val minimizedFlow: StateFlow<Boolean> = _minimized.asStateFlow()
    override val maximizedFlow: StateFlow<Boolean> = _maximized.asStateFlow()
    override val fullscreenFlow: StateFlow<Boolean> = _fullscreen.asStateFlow()

    override val unsafe: NucleusWindowUnsafe =
        object : NucleusWindowUnsafe {
            override val awtWindow: ComposeWindow get() = composeWindow
        }

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }
}
