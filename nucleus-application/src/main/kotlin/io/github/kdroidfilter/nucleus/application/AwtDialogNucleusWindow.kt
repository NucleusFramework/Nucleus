package io.github.kdroidfilter.nucleus.application

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * AWT [NucleusWindow] wrapping a [ComposeDialog]. Dialogs cannot minimize,
 * maximize, or fullscreen — those setters are no-ops here.
 */
internal class AwtDialogNucleusWindow(
    private val composeDialog: ComposeDialog,
    private val onCloseRequest: () -> Unit,
) : NucleusWindow {
    private val _focus = MutableStateFlow(composeDialog.isFocused)
    private val _minimized = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)
    private val _fullscreen = MutableStateFlow(false)

    init {
        composeDialog.addWindowFocusListener(
            object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    _focus.value = true
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    _focus.value = false
                }
            },
        )
    }

    override val isFocused: Boolean get() = composeDialog.isFocused
    override val isMinimized: Boolean get() = false
    override val isMaximized: Boolean get() = false
    override val isFullscreen: Boolean get() = false

    override fun show() = onEdt { composeDialog.isVisible = true }

    override fun hide() = onEdt { composeDialog.isVisible = false }

    override fun toFront() = onEdt { composeDialog.toFront() }

    override fun requestFocus() = onEdt { composeDialog.requestFocus() }

    override fun setMinimized(minimized: Boolean) = Unit

    override fun setMaximized(maximized: Boolean) = Unit

    override fun setFullscreen(fullscreen: Boolean) = Unit

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) =
        onEdt {
            composeDialog.isAlwaysOnTop = alwaysOnTop
        }

    override fun setMinimumSize(size: DpSize?) =
        onEdt {
            composeDialog.minimumSize =
                size?.let {
                    val scale =
                        composeDialog.graphicsConfiguration
                            ?.defaultTransform
                            ?.scaleX
                            ?.toFloat() ?: 1f
                    java.awt.Dimension(
                        (it.width.value * scale).toInt(),
                        (it.height.value * scale).toInt(),
                    )
                }
        }

    override fun setIcon(painter: Painter?) = Unit

    override fun close() = onEdt { onCloseRequest() }

    override val focusFlow: StateFlow<Boolean> = _focus.asStateFlow()
    override val minimizedFlow: StateFlow<Boolean> = _minimized.asStateFlow()
    override val maximizedFlow: StateFlow<Boolean> = _maximized.asStateFlow()
    override val fullscreenFlow: StateFlow<Boolean> = _fullscreen.asStateFlow()

    override val unsafe: NucleusWindowUnsafe =
        object : NucleusWindowUnsafe {
            override val awtDialog: ComposeDialog get() = composeDialog
        }

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }
}
