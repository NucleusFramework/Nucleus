package dev.nucleusframework.nucleus.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.nucleus.application.internal.TaoDecoratedDialogAdapter
import dev.nucleusframework.nucleus.window.AwtDecoratedDialogScope
import dev.nucleusframework.nucleus.window.DecoratedDialogState
import dev.nucleusframework.nucleus.window.DecoratedDialog as AwtDecoratedDialog

/**
 * Backend-agnostic decorated dialog. Mirrors [DecoratedWindow] but for modal /
 * secondary windows: non-resizable by default, no maximize / minimize affordance.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun NucleusApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
) {
    when (this) {
        is AwtNucleusApplicationScope ->
            AwtDecoratedDialog(
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
                val awtScope: AwtDecoratedDialogScope = this
                val nucleusWindow =
                    remember(window) {
                        AwtDialogNucleusWindow(window, onCloseRequest)
                    }
                val scope =
                    remember(awtScope, nucleusWindow) {
                        AwtNucleusDecoratedDialogScope(awtScope, nucleusWindow)
                    }
                CompositionLocalProvider(
                    LocalNucleusBackend provides NucleusBackend.Awt,
                    LocalNucleusWindow provides nucleusWindow,
                ) {
                    scope.content()
                }
            }

        is TaoNucleusApplicationScope ->
            TaoDecoratedDialogAdapter.Dialog(
                scope = this,
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
                content = content,
            )
    }
}

internal class AwtNucleusDecoratedDialogScope(
    private val delegate: AwtDecoratedDialogScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedDialogScope,
    AwtDecoratedDialogScope by delegate {
    override val state: DecoratedDialogState get() = delegate.state
}
