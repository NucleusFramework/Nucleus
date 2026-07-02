package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.internal.TaoDecoratedWindowAdapter
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.DecoratedWindow as AwtDecoratedWindow

/**
 * Backend-agnostic decorated window. Inside [content], `window` is a
 * [NucleusWindow] usable on any backend; reach for `window.unsafe.*` only when
 * you genuinely need backend-specific behaviour.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun NucleusApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    // Fully borderless window (no macOS traffic lights) — for overlay/ghost windows.
    // Honoured by the Tao backend; the AWT backend currently ignores it.
    undecorated: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is AwtNucleusApplicationScope ->
            AwtDecoratedWindow(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                minimumSize = minimumSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            ) {
                val awtScope: AwtDecoratedWindowScope = this
                val nucleusWindow =
                    remember(window) {
                        AwtNucleusWindow(window, state, onCloseRequest)
                    }
                val scope =
                    remember(awtScope, nucleusWindow) {
                        AwtNucleusDecoratedWindowScope(awtScope, nucleusWindow)
                    }
                ObserveSingleInstanceRestore(nucleusWindow)
                CompositionLocalProvider(
                    LocalNucleusBackend provides NucleusBackend.Awt,
                    LocalNucleusWindow provides nucleusWindow,
                ) {
                    scope.content()
                }
            }

        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.Window(
                scope = this,
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                undecorated = undecorated,
                minimumSize = minimumSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

internal class AwtNucleusDecoratedWindowScope(
    private val delegate: AwtDecoratedWindowScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedWindowScope,
    AwtDecoratedWindowScope by delegate {
    override val state: DecoratedWindowState get() = delegate.state
}
