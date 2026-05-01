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
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.DialogWindowScope
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.internal.insideBorder
import io.github.kdroidfilter.nucleus.window.styling.LocalDecoratedWindowStyle
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

@Stable
interface DecoratedDialogScope : DialogWindowScope {
    override val window: ComposeDialog

    val state: DecoratedDialogState
}

object DecoratedDialogMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(width = constraints.minWidth, height = constraints.minHeight) {}
        }

        val titleBars = measurables.filter { it.layoutId == TITLE_BAR_LAYOUT_ID }
        if (titleBars.size > 1) {
            error("Dialog can have only one title bar")
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
value class DecoratedDialogState(
    val state: ULong,
) {
    val isActive: Boolean
        get() = state and Active != 0UL

    fun copy(active: Boolean = isActive): DecoratedDialogState = of(active = active)

    fun toDecoratedWindowState(): DecoratedWindowState =
        DecoratedWindowState.of(
            fullscreen = false,
            minimized = false,
            maximized = false,
            active = isActive,
        )

    override fun toString(): String = "${javaClass.simpleName}(isActive=$isActive)"

    companion object {
        val Active: ULong = 1UL shl 0

        fun of(active: Boolean = true): DecoratedDialogState =
            DecoratedDialogState(
                if (active) Active else 0UL,
            )

        fun of(window: ComposeDialog): DecoratedDialogState = of(active = window.isActive)
    }
}

data class DialogTitleBarInfo(
    val title: String,
    val icon: Painter?,
)

val LocalDialogTitleBarInfo: ProvidableCompositionLocal<DialogTitleBarInfo> =
    compositionLocalOf {
        error("LocalDialogTitleBarInfo not provided, DialogTitleBar must be used in DecoratedDialog")
    }

/**
 * Shared body for DecoratedDialog, used by both JBR and JNI variants.
 * Each variant calls this from within a [DialogWindow] composable, passing the appropriate [undecorated] flag.
 */
@Suppress("FunctionNaming", "MagicNumber")
@Composable
fun DialogWindowScope.DecoratedDialogBody(
    title: String,
    icon: Painter?,
    undecorated: Boolean,
    content: @Composable DecoratedDialogScope.() -> Unit,
) {
    var decoratedDialogState by remember { mutableStateOf(DecoratedDialogState.of(window)) }

    val linuxDe = remember { LinuxDesktopEnvironment.Current }
    val gnomeCornerArc = 24f
    val kdeCornerArc = 10f

    DisposableEffect(window) {
        fun updateDialogShape() {
            decoratedDialogState = DecoratedDialogState.of(window)
            when (linuxDe) {
                LinuxDesktopEnvironment.Gnome -> {
                    val w = window.width.toFloat()
                    val h = window.height.toFloat()
                    window.shape = RoundRectangle2D.Float(0f, 0f, w, h, gnomeCornerArc, gnomeCornerArc)
                }
                LinuxDesktopEnvironment.KDE -> {
                    val w = window.width.toFloat()
                    val h = window.height.toFloat()
                    window.shape =
                        Area(RoundRectangle2D.Float(0f, 0f, w, h, kdeCornerArc, kdeCornerArc)).apply {
                            add(Area(Rectangle2D.Float(0f, h - kdeCornerArc, w, kdeCornerArc)))
                        }
                }
                else -> {}
            }
        }

        updateDialogShape()

        val adapter =
            object : WindowAdapter(), ComponentListener {
                override fun windowActivated(e: WindowEvent?) {
                    updateDialogShape()
                }

                override fun windowDeactivated(e: WindowEvent?) {
                    updateDialogShape()
                }

                override fun componentResized(e: ComponentEvent?) {
                    updateDialogShape()
                }

                override fun componentMoved(e: ComponentEvent?) {
                    // No-op: dialog position changes don't affect decorated state
                }

                override fun componentShown(e: ComponentEvent?) {
                    // No-op: visibility handled elsewhere
                }

                override fun componentHidden(e: ComponentEvent?) {
                    // No-op: visibility handled elsewhere
                }
            }

        window.addWindowListener(adapter)
        window.addComponentListener(adapter)

        onDispose {
            window.removeWindowListener(adapter)
            window.removeComponentListener(adapter)
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
        if (undecorated) {
            Modifier.insideBorder(
                width = style.metrics.borderWidth,
                color = style.colors.borderFor(decoratedDialogState.toDecoratedWindowState()).value,
                shape = borderShape,
            )
        } else {
            Modifier
        }

    // Sync the AWT window background with the title bar color so that the
    // native window surface matches during resize (avoids white flash).
    // On Windows, Skiko's ContextHandler.draw() clears to Color.WHITE when
    // SkiaLayer.transparency == false (the default). For dark themes we call
    // setTransparency(true) so it clears to TRANSPARENT instead, which renders
    // as opaque black on the DirectX surface (DXGI_ALPHA_MODE_IGNORE).
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
        applyRecursive(window)
        javax.swing.SwingUtilities.invokeLater { applyRecursive(window) }
    }

    CompositionLocalProvider(LocalDialogTitleBarInfo provides DialogTitleBarInfo(title, icon)) {
        Layout(
            content = {
                val scope =
                    object : DecoratedDialogScope {
                        override val state: DecoratedDialogState
                            get() = decoratedDialogState

                        override val window: ComposeDialog
                            get() = this@DecoratedDialogBody.window
                    }
                scope.content()
            },
            modifier = Modifier.background(titleBarBackground).then(undecoratedWindowBorder),
            measurePolicy = DecoratedDialogMeasurePolicy,
        )
    }
}
