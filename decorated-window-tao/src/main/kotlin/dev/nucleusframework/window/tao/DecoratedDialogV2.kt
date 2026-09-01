@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.remember
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
        content = content,
    )
    BindDialogStateV2(state, v1, visible)
    remember(v1, minSize, maxSize) { applyDialogSizeConstraints(v1, minSize, maxSize) }
}

private fun applyDialogSizeConstraints(
    v1: androidx.compose.ui.window.DialogState,
    minSize: DpSize,
    maxSize: DpSize,
) {
    var width = v1.size.width
    var height = v1.size.height
    val min = minSizeOrNull(minSize)
    if (min != null) {
        if (min.width.isSpecified && width.isSpecified && width < min.width) width = min.width
        if (min.height.isSpecified && height.isSpecified && height < min.height) height = min.height
    }
    if (maxSize.width.isSpecified && width.isSpecified && width > maxSize.width) width = maxSize.width
    if (maxSize.height.isSpecified && height.isSpecified && height > maxSize.height) height = maxSize.height
    if (width != v1.size.width || height != v1.size.height) {
        v1.size = DpSize(width, height)
    }
}
