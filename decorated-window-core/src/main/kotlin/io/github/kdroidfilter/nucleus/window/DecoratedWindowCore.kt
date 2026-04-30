package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
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

const val TITLE_BAR_COMPONENT_LAYOUT_ID_PREFIX = "__TITLE_BAR_"

const val TITLE_BAR_LAYOUT_ID = "__TITLE_BAR_CONTENT__"

const val TITLE_BAR_BORDER_LAYOUT_ID = "__TITLE_BAR_BORDER__"

@Stable
interface DecoratedWindowScope : FrameWindowScope {
    override val window: ComposeWindow

    val state: DecoratedWindowState
}

object DecoratedWindowMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(width = constraints.minWidth, height = constraints.minHeight) {}
        }

        val titleBars = measurables.filter { it.layoutId == TITLE_BAR_LAYOUT_ID }
        if (titleBars.size > 1) {
            error("Window just can have only one title bar")
        }
        val titleBar = titleBars.firstOrNull()
        val titleBarBorder = measurables.firstOrNull { it.layoutId == TITLE_BAR_BORDER_LAYOUT_ID }

        val contentConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val titleBarPlaceable = titleBar?.measure(contentConstraints)
        val titleBarHeight = titleBarPlaceable?.height ?: 0

        val titleBarBorderPlaceable = titleBarBorder?.measure(contentConstraints)
        val titleBarBorderHeight = titleBarBorderPlaceable?.height ?: 0

        val measuredPlaceable = mutableListOf<Placeable>()

        for (it in measurables) {
            if (it.layoutId.toString().startsWith(TITLE_BAR_COMPONENT_LAYOUT_ID_PREFIX)) continue
            val offsetConstraints = contentConstraints.offset(vertical = -titleBarHeight - titleBarBorderHeight)
            val placeable = it.measure(offsetConstraints)
            measuredPlaceable += placeable
        }

        return layout(constraints.maxWidth, constraints.maxHeight) {
            titleBarPlaceable?.placeRelative(0, 0)
            titleBarBorderPlaceable?.placeRelative(0, titleBarHeight)

            measuredPlaceable.forEach { it.placeRelative(0, titleBarHeight + titleBarBorderHeight) }
        }
    }
}

@Immutable
@JvmInline
value class DecoratedWindowState(
    val state: ULong,
) {
    val isActive: Boolean
        get() = state and Active != 0UL

    val isFullscreen: Boolean
        get() = state and Fullscreen != 0UL

    val isMinimized: Boolean
        get() = state and Minimize != 0UL

    val isMaximized: Boolean
        get() = state and Maximize != 0UL

    fun copy(
        fullscreen: Boolean = isFullscreen,
        minimized: Boolean = isMinimized,
        maximized: Boolean = isMaximized,
        active: Boolean = isActive,
    ): DecoratedWindowState = of(fullscreen = fullscreen, minimized = minimized, maximized = maximized, active = active)

    override fun toString(): String = "${javaClass.simpleName}(isFullscreen=$isFullscreen, isActive=$isActive)"

    companion object {
        val Active: ULong = 1UL shl 0
        val Fullscreen: ULong = 1UL shl 1
        val Minimize: ULong = 1UL shl 2
        val Maximize: ULong = 1UL shl 3

        fun of(
            fullscreen: Boolean = false,
            minimized: Boolean = false,
            maximized: Boolean = false,
            active: Boolean = true,
        ): DecoratedWindowState =
            DecoratedWindowState(
                (if (fullscreen) Fullscreen else 0UL) or
                    (if (minimized) Minimize else 0UL) or
                    (if (maximized) Maximize else 0UL) or
                    (if (active) Active else 0UL),
            )

        fun of(window: ComposeWindow): DecoratedWindowState =
            of(
                fullscreen = window.placement == WindowPlacement.Fullscreen,
                minimized = window.isMinimized,
                maximized = window.placement == WindowPlacement.Maximized,
                active = window.isActive,
            )
    }
}

@Stable
class TitleBarInfo(
    title: String,
    icon: Painter?,
) {
    var title by mutableStateOf(title)
    var icon by mutableStateOf(icon)
    val clientRegions: MutableMap<String, Rect> = mutableMapOf()
}

val LocalTitleBarInfo: ProvidableCompositionLocal<TitleBarInfo> =
    compositionLocalOf {
        error("LocalTitleBarInfo not provided, TitleBar must be used in DecoratedWindow")
    }

/**
 * Shared body for DecoratedWindow, used by both JBR and JNI variants.
 * Each variant calls this from within a [Window] composable, passing the appropriate [undecorated] flag.
 */
/**
 * Shared body for DecoratedWindow, used by both JBR and JNI variants.
 *
 * @param bodyBackground Optional override for the body background colour.
 *   Defaults to `null` which preserves the legacy behaviour (the title bar
 *   background colour from [LocalTitleBarStyle] is filled across the whole
 *   window — this notably mitigates the white flash during live resize on
 *   Windows because every AWT child gets its `background` set, neutralising
 *   `WM_ERASEBKGND`). Pass [Color.Transparent] when you need to host a
 *   native heavyweight view underneath the Compose surface (e.g. a WKWebView
 *   sibling on macOS): the body fill is suppressed and the per-component
 *   AWT background propagation is skipped so the rendered surface keeps its
 *   alpha. On Windows undecorated, this trade-off may reintroduce the
 *   resize flash — only opt in when the transparency is actually required.
 */
@Suppress("FunctionNaming", "MagicNumber", "CyclomaticComplexMethod")
@Composable
fun FrameWindowScope.DecoratedWindowBody(
    title: String,
    icon: Painter?,
    undecorated: Boolean,
    onCloseRequest: () -> Unit = {},
    bodyBackground: Color? = null,
    content: @Composable DecoratedWindowScope.() -> Unit,
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
    // On macOS, setting window.background is enough (sets NSWindow.backgroundColor
    // at the compositor level). On Windows, each AWT component has its own HWND
    // with its own WM_ERASEBKGND handler, so we must set the background on every
    // component in the hierarchy to prevent any child from erasing with white.
    // On Windows, Skiko's ContextHandler.draw() clears to Color.WHITE when
    // SkiaLayer.transparency == false (the default). For dark themes we call
    // setTransparency(true) so it clears to TRANSPARENT instead, which renders
    // as opaque black on the DirectX surface (DXGI_ALPHA_MODE_IGNORE) and avoids
    // the white resize flash.
    // Restricted to Windows: on macOS the clear is always transparent (no issue),
    // and on Linux enabling transparency composites with the desktop compositor,
    // which would cause visual artifacts in dark themes.
    val isWindows = remember { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    val titleBarBackground = LocalTitleBarStyle.current.colors.background
    // When [bodyBackground] is supplied (typically Color.Transparent for hosting
    // a native NSView underneath via macWebView etc.), use it for the AWT-side
    // recursive background so the JFrame's painting matches the Compose body.
    val effectiveAwtBackground = bodyBackground ?: titleBarBackground
    val skipAwtBackground = bodyBackground != null && bodyBackground.alpha < 1f
    LaunchedEffect(window, effectiveAwtBackground, skipAwtBackground) {
        val awtColor = java.awt.Color(effectiveAwtBackground.toArgb(), true)
        val isDark =
            titleBarBackground.red * 0.299f +
                titleBarBackground.green * 0.587f +
                titleBarBackground.blue * 0.114f < 0.5f

        fun applyRecursive(c: java.awt.Component) {
            // For decorated frames AWT throws when setting an alpha<255 background,
            // so we skip the assignment entirely when the caller wants transparency
            // and rely on each component's `isOpaque = false` (set by callers like
            // MacWebView) to suppress AWT's background fill.
            if (!skipAwtBackground) {
                c.background = awtColor
            }
            // [Skiko #1141] Remove this once stable Compose uses Skiko with
            //  https://github.com/JetBrains/skiko/pull/1141 —
            //  ContextHandler.draw() always clears to TRANSPARENT now and
            //  SkiaLayer.update() fills with the AWT background color instead.
            // Windows only: set SkiaLayer transparency to match the theme so
            // Skiko clears to TRANSPARENT (opaque black) instead of WHITE.
            // NoSuchMethodException just means this component is not SkiaLayer.
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
        // Apply immediately (LaunchedEffect runs on the EDT in Compose Desktop)
        // and also defer once to catch SkiaLayer if it was not yet added during
        // the first composition frame.
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
                    object : DecoratedWindowScope {
                        override val state: DecoratedWindowState
                            get() = decoratedWindowState

                        override val window: ComposeWindow
                            get() = this@DecoratedWindowBody.window
                    }
                scope.content()
            },
            modifier = Modifier.background(bodyBackground ?: titleBarBackground).then(undecoratedWindowBorder),
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
