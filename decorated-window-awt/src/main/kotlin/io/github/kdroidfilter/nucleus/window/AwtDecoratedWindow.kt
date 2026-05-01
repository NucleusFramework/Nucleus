package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.internal.insideBorder
import io.github.kdroidfilter.nucleus.window.styling.LocalDecoratedWindowStyle
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import java.awt.ComponentOrientation
import java.awt.Desktop
import java.awt.Frame
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * AWT/Compose Desktop sub-interface of [DecoratedWindowScope] adding access
 * to the backing [ComposeWindow]. Returned to consumers of the JBR/JNI backends.
 */
@Stable
interface AwtDecoratedWindowScope : DecoratedWindowScope, FrameWindowScope {
    override val window: ComposeWindow
}

/**
 * Builds a [DecoratedWindowState] from a Compose Desktop [ComposeWindow].
 */
fun DecoratedWindowState.Companion.of(window: ComposeWindow): DecoratedWindowState =
    of(
        fullscreen = window.placement == WindowPlacement.Fullscreen,
        minimized = window.isMinimized,
        maximized = window.placement == WindowPlacement.Maximized,
        active = window.isActive,
    )

/**
 * Shared body for DecoratedWindow, used by both JBR and JNI variants.
 * Each variant calls this from within a [Window] composable, passing the appropriate [undecorated] flag.
 */
@Suppress("FunctionNaming", "MagicNumber", "CyclomaticComplexMethod")
@Composable
fun FrameWindowScope.DecoratedWindowBody(
    title: String,
    icon: Painter?,
    undecorated: Boolean,
    onCloseRequest: () -> Unit = {},
    content: @Composable AwtDecoratedWindowScope.() -> Unit,
) {
    var decoratedWindowState by remember { mutableStateOf(DecoratedWindowState.of(window)) }
    var isMaximizedInAnyDirection by remember { mutableStateOf(false) }

    val linuxDe = remember { LinuxDesktopEnvironment.Current }
    val gnomeCornerArc = 24f
    val kdeCornerArc = 10f

    DisposableEffect(window) {
        var trackedExtendedState = window.extendedState

        fun updateWindowShape() {
            decoratedWindowState = DecoratedWindowState.of(window)
            val ws = decoratedWindowState
            val hasAnyMaxBit =
                (trackedExtendedState and (Frame.MAXIMIZED_VERT or Frame.MAXIMIZED_HORIZ)) != 0
            val gc = window.graphicsConfiguration
            val fillsScreen =
                gc != null &&
                    (
                        window.height >= gc.bounds.height * 0.9 ||
                            window.width >= gc.bounds.width * 0.9
                    )
            isMaximizedInAnyDirection = ws.isMaximized || hasAnyMaxBit || fillsScreen
            val isMaxOrFull = ws.isFullscreen || isMaximizedInAnyDirection
            when (linuxDe) {
                LinuxDesktopEnvironment.Gnome -> {
                    window.shape =
                        if (isMaxOrFull) {
                            null
                        } else {
                            val w = window.width.toFloat()
                            val h = window.height.toFloat()
                            RoundRectangle2D.Float(0f, 0f, w, h, gnomeCornerArc, gnomeCornerArc)
                        }
                }
                LinuxDesktopEnvironment.KDE -> {
                    window.shape =
                        if (isMaxOrFull) {
                            null
                        } else {
                            val w = window.width.toFloat()
                            val h = window.height.toFloat()
                            Area(RoundRectangle2D.Float(0f, 0f, w, h, kdeCornerArc, kdeCornerArc)).apply {
                                add(Area(Rectangle2D.Float(0f, h - kdeCornerArc, w, kdeCornerArc)))
                            }
                        }
                }
                else -> {}
            }
        }

        updateWindowShape()

        val adapter =
            object : WindowAdapter(), ComponentListener {
                override fun windowActivated(e: WindowEvent?) {
                    updateWindowShape()
                }

                override fun windowDeactivated(e: WindowEvent?) {
                    updateWindowShape()
                }

                override fun windowIconified(e: WindowEvent?) {
                    updateWindowShape()
                }

                override fun windowDeiconified(e: WindowEvent?) {
                    updateWindowShape()
                }

                override fun windowStateChanged(e: WindowEvent) {
                    trackedExtendedState = e.newState
                    updateWindowShape()
                }

                override fun componentResized(e: ComponentEvent?) {
                    updateWindowShape()
                }

                override fun componentMoved(e: ComponentEvent?) {
                    // No-op: window position changes don't affect decorated state
                }

                override fun componentShown(e: ComponentEvent?) {
                    // No-op: visibility handled elsewhere
                }

                override fun componentHidden(e: ComponentEvent?) {
                    // No-op: visibility handled elsewhere
                }
            }

        window.addWindowListener(adapter)
        window.addWindowStateListener(adapter)
        window.addComponentListener(adapter)

        val quitHandlerInstalled = installSystemQuitHandler(onCloseRequest)

        onDispose {
            window.removeWindowListener(adapter)
            window.removeWindowStateListener(adapter)
            window.removeComponentListener(adapter)
            if (quitHandlerInstalled) {
                Desktop.getDesktop().setQuitHandler(null)
            }
        }
    }

    val style = LocalDecoratedWindowStyle.current
    val borderShape =
        when (linuxDe) {
            LinuxDesktopEnvironment.Gnome ->
                RoundedCornerShape((gnomeCornerArc / 2).dp)
            LinuxDesktopEnvironment.KDE ->
                RoundedCornerShape(
                    topStart = (kdeCornerArc / 2).dp,
                    topEnd = (kdeCornerArc / 2).dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            else -> RoundedCornerShape(0.dp)
        }
    val undecoratedWindowBorder =
        if (undecorated && !decoratedWindowState.isMaximized && !isMaximizedInAnyDirection) {
            Modifier.insideBorder(
                width = style.metrics.borderWidth,
                color = style.colors.borderFor(decoratedWindowState).value,
                shape = borderShape,
            )
        } else {
            Modifier
        }

    // Detect platform layout direction from JVM locale so that RTL locales
    // (Hebrew, Arabic, …) automatically mirror the title bar and content.
    // Compose Desktop does not propagate java.util.Locale into LocalLayoutDirection.
    val platformLayoutDirection =
        remember {
            if (ComponentOrientation.getOrientation(java.util.Locale.getDefault()).isLeftToRight) {
                LayoutDirection.Ltr
            } else {
                LayoutDirection.Rtl
            }
        }

    // Sync the AWT window background with the title bar color so that the
    // native window surface matches during resize (avoids white flash).
    val isWindows = remember { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    val titleBarBackground = LocalTitleBarStyle.current.colors.background
    LaunchedEffect(window, titleBarBackground) {
        val awtColor = java.awt.Color(titleBarBackground.toArgb(), true)
        val isDark =
            titleBarBackground.red * 0.299f +
                titleBarBackground.green * 0.587f +
                titleBarBackground.blue * 0.114f < 0.5f

        fun applyRecursive(c: java.awt.Component) {
            c.background = awtColor
            // [Skiko #1141] Remove this once stable Compose uses Skiko with
            //  https://github.com/JetBrains/skiko/pull/1141 —
            //  ContextHandler.draw() always clears to TRANSPARENT now and
            //  SkiaLayer.update() fills with the AWT background color instead.
            if (isWindows) {
                try {
                    c.javaClass
                        .getMethod("setTransparency", Boolean::class.javaPrimitiveType)
                        .invoke(c, isDark)
                } catch (_: NoSuchMethodException) {
                    // Not SkiaLayer
                } catch (_: Exception) {
                    // Ignore other reflection errors
                }
            }
            if (c is java.awt.Container) {
                c.components.forEach { applyRecursive(it) }
            }
        }
        applyRecursive(window)
        javax.swing.SwingUtilities.invokeLater { applyRecursive(window) }
    }

    val titleBarInfo = remember { TitleBarInfo(title, icon) }
    LaunchedEffect(title) { titleBarInfo.title = title }
    LaunchedEffect(icon) { titleBarInfo.icon = icon }

    CompositionLocalProvider(
        LocalTitleBarInfo provides titleBarInfo,
        LocalLayoutDirection provides platformLayoutDirection,
    ) {
        Layout(
            content = {
                val scope =
                    object : AwtDecoratedWindowScope {
                        override val state: DecoratedWindowState
                            get() = decoratedWindowState

                        override val window: ComposeWindow
                            get() = this@DecoratedWindowBody.window
                    }
                scope.content()
            },
            modifier = Modifier.background(titleBarBackground).then(undecoratedWindowBorder),
            measurePolicy = DecoratedWindowMeasurePolicy,
        )
    }
}

/**
 * Installs a system-level quit handler that delegates to [onCloseRequest].
 * On macOS this intercepts Cmd+Q, Dock → Quit, and App Menu → Quit.
 * The system quit is always cancelled — [onCloseRequest] decides whether
 * to call exitApplication() or show a confirmation dialog.
 *
 * @return true if the handler was installed successfully.
 */
private fun installSystemQuitHandler(onCloseRequest: () -> Unit): Boolean =
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler { _, response ->
                onCloseRequest()
                response.cancelQuit()
            }
            true
        } else {
            false
        }
    } catch (_: UnsupportedOperationException) {
        false
    }
