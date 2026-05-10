package dev.nucleusframework.nucleus.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.nucleus.application.LocalNucleusBackend
import dev.nucleusframework.nucleus.application.LocalNucleusWindow
import dev.nucleusframework.nucleus.application.NucleusBackend
import dev.nucleusframework.nucleus.application.NucleusDecoratedWindowScope
import dev.nucleusframework.nucleus.application.NucleusWindow
import dev.nucleusframework.nucleus.application.TaoNucleusApplicationScope
import dev.nucleusframework.nucleus.application.TaoNucleusWindow
import dev.nucleusframework.nucleus.window.DecoratedWindowState
import dev.nucleusframework.nucleus.window.LocalIsDarkTheme
import dev.nucleusframework.nucleus.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.nucleus.window.styling.LocalTitleBarStyle
import dev.nucleusframework.nucleus.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.nucleus.window.tao.DecoratedWindow as TaoDecoratedWindow

/**
 * Isolates references to Tao symbols. Loaded only when the Tao backend is
 * active — keeps the unified DecoratedWindow callable on AWT-only classpaths.
 */
internal object TaoDecoratedWindowAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Window(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: WindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        minimumSize: DpSize?,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        // Tao opens a fresh ComposeScene per window; CompositionLocals from
        // the outer scope don't propagate across scenes. Capture the styling
        // locals here so wrapping `NucleusDecoratedWindowTheme` outside the
        // DecoratedWindow works the same on both backends.
        val outerIsDark = LocalIsDarkTheme.current
        val outerWindowStyle = LocalDecoratedWindowStyle.current
        val outerTitleBarStyle = LocalTitleBarStyle.current

        with(scope.taoScope) {
            TaoDecoratedWindow(
                onCloseRequest = onCloseRequest,
                state = state,
                title = title,
                icon = icon,
                minimumSize = minimumSize,
                visible = visible,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            ) {
                val taoScope: TaoDecoratedWindowScope = this
                val decoratedState =
                    remember(taoScope) {
                        derivedStateOf { taoScope.state }
                    }
                val nucleusWindow: NucleusWindow =
                    remember(taoScope.window) {
                        TaoNucleusWindow(taoScope.window, decoratedState)
                    }
                val nucleusScope =
                    remember(taoScope, nucleusWindow) {
                        TaoNucleusDecoratedWindowScope(taoScope, nucleusWindow)
                    }
                CompositionLocalProvider(
                    LocalNucleusBackend provides NucleusBackend.Tao,
                    LocalNucleusWindow provides nucleusWindow,
                    LocalIsDarkTheme provides outerIsDark,
                    LocalDecoratedWindowStyle provides outerWindowStyle,
                    LocalTitleBarStyle provides outerTitleBarStyle,
                ) {
                    nucleusScope.content()
                }
            }
        }
    }
}

private class TaoNucleusDecoratedWindowScope(
    private val taoScope: TaoDecoratedWindowScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedWindowScope,
    TaoDecoratedWindowScope by taoScope {
    override val state: DecoratedWindowState get() = taoScope.state
}
