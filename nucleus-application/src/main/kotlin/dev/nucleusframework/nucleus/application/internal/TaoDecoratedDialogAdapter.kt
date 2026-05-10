package dev.nucleusframework.nucleus.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import dev.nucleusframework.nucleus.application.LocalNucleusBackend
import dev.nucleusframework.nucleus.application.LocalNucleusWindow
import dev.nucleusframework.nucleus.application.NucleusBackend
import dev.nucleusframework.nucleus.application.NucleusDecoratedDialogScope
import dev.nucleusframework.nucleus.application.NucleusWindow
import dev.nucleusframework.nucleus.application.TaoNucleusApplicationScope
import dev.nucleusframework.nucleus.application.TaoNucleusWindow
import dev.nucleusframework.nucleus.window.DecoratedDialogState
import dev.nucleusframework.nucleus.window.DecoratedWindowState
import dev.nucleusframework.nucleus.window.LocalIsDarkTheme
import dev.nucleusframework.nucleus.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.nucleus.window.styling.LocalTitleBarStyle
import dev.nucleusframework.nucleus.window.tao.TaoDecoratedDialogScope
import dev.nucleusframework.nucleus.window.tao.DecoratedDialog as TaoDecoratedDialog

internal object TaoDecoratedDialogAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Dialog(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        val outerIsDark = LocalIsDarkTheme.current
        val outerWindowStyle = LocalDecoratedWindowStyle.current
        val outerTitleBarStyle = LocalTitleBarStyle.current

        with(scope.taoScope) {
            TaoDecoratedDialog(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            ) {
                val taoScope: TaoDecoratedDialogScope = this
                // Tao dialogs share TaoWindow with regular windows; rebuild the
                // active-state mirror as a single-bit DecoratedWindowState so
                // [TaoNucleusWindow] can read uniform flow values.
                val windowStateMirror =
                    remember(taoScope) {
                        derivedStateOf {
                            DecoratedWindowState.of(active = taoScope.state.isActive)
                        }
                    }
                val nucleusWindow: NucleusWindow =
                    remember(taoScope.window) {
                        TaoNucleusWindow(taoScope.window, windowStateMirror)
                    }
                val nucleusScope =
                    remember(taoScope, nucleusWindow) {
                        TaoNucleusDecoratedDialogScope(taoScope, nucleusWindow)
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

private class TaoNucleusDecoratedDialogScope(
    private val taoScope: TaoDecoratedDialogScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedDialogScope,
    TaoDecoratedDialogScope by taoScope {
    override val state: DecoratedDialogState get() = taoScope.state
}
