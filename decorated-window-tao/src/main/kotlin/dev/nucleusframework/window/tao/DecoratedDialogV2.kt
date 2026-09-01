@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.v2.DialogState
import dev.nucleusframework.window.tao.DecoratedDialog as DecoratedDialogV1

/**
 * [DecoratedDialog] overload that accepts Compose Multiplatform 1.12's
 * experimental dialog API v2 ([androidx.compose.ui.window.v2.DialogState]).
 *
 * [state] has no default so `DecoratedDialog(onCloseRequest) { }` still
 * resolves to the v1 overload.
 *
 * `requestScreen` / `screenId` are drained and ignored: Tao only exposes the
 * primary work area.
 *
 * @param minSize Minimum inner size. [DpSize.Unspecified] means no minimum.
 * @param maxSize Maximum inner size. [DpSize.Unspecified] means no maximum.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState,
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
    compositionLocalContext: CompositionLocalContext? = null,
    content: @Composable TaoDecoratedDialogScope.() -> Unit,
) {
    val v1 = rememberDialogStateV1(state)
    val clamped = clampSize(v1.size, minSize, maxSize)
    if (clamped != v1.size) {
        v1.size = clamped
    }
    DecoratedDialogV1(
        onCloseRequest = onCloseRequest,
        state = v1,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        compositionLocalContext = compositionLocalContext,
        content = {
            ApplySizeConstraints(minSize, maxSize)
            content()
        },
    )
    BindDialogStateV2(state, v1, visible, minSize, maxSize)
}

@Composable
private fun TaoDecoratedDialogScope.ApplySizeConstraints(
    minSize: DpSize,
    maxSize: DpSize,
) {
    val window = this.window
    LaunchedEffect(window, minSize, maxSize) {
        val min = minSizeOrNull(minSize)
        if (min != null) {
            window.setMinimumSize(min.width.value.toDouble(), min.height.value.toDouble())
        } else {
            window.setMinimumSize(null, null)
        }
        if (maxSize.width.isSpecified && maxSize.height.isSpecified) {
            window.setMaximumSize(
                maxSize.width.value.toDouble(),
                maxSize.height.value.toDouble(),
            )
        } else {
            window.setMaximumSize(null, null)
        }
    }
}
