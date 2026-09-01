@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.application.internal.TaoDecoratedDialogAdapter
import androidx.compose.ui.window.v2.DialogState as DialogStateV2

/**
 * Decorated dialog. Mirrors [DecoratedWindow] but for modal / secondary
 * windows: non-resizable by default, no maximize / minimize affordance.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.DecoratedDialog(
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

/**
 * Receiver-less [DecoratedDialog], resolving the application scope from
 * [LocalNucleusApplicationScope]. Parameters behave exactly like the
 * [NucleusApplicationScope] overload. Fails outside a `nucleusApplication { … }`
 * block, where no scope exists.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedDialog(
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
    LocalNucleusApplicationScope.current.DecoratedDialog(
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

/**
 * [DecoratedDialog] overload for Compose Multiplatform 1.12's experimental
 * dialog API v2.
 *
 * [state] has no default so `DecoratedDialog(onCloseRequest) { }` still
 * resolves to the v1 overload.
 *
 * `requestScreen` / `screenId` are not applied on Tao (primary work area
 * only).
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogStateV2,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedDialogAdapter.DialogV2(
                scope = this,
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                minSize = minSize,
                maxSize = maxSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

/**
 * Receiver-less [DecoratedDialog] for Compose window API v2. See the
 * [NucleusApplicationScope] overload.
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogStateV2,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.DecoratedDialog(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}
