@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
 * `requestScreen` / `screenId` are drained and ignored, and scoped geometry
 * providers cannot be evaluated without an AWT window. The AWT-free clone
 * ([dev.nucleusframework.window.tao.v2.DialogState], one import away) has no
 * such gap — see [dev.nucleusframework.window.tao.v2.rememberDialogState].
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
    val nativeWindow = remember(state) { mutableStateOf<TaoWindow?>(null) }
    // Clamping is a side effect, not composition output: writing v1.size during
    // composition schedules a recomposition on every native resize past maxSize.
    LaunchedEffect(v1, v1.size, minSize, maxSize) {
        val clamped = clampSize(v1.size, minSize, maxSize)
        if (clamped != v1.size) {
            v1.size = clamped
        }
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
            CaptureNativeWindow(nativeWindow)
            content()
        },
    )
    BindDialogStateV2(state, v1, visible, minSize, maxSize, nativeWindow.value)
}

/** See `DecoratedWindowV2`'s counterpart — lets the bridge read real geometry. */
@Composable
private fun TaoDecoratedDialogScope.CaptureNativeWindow(holder: MutableState<TaoWindow?>) {
    val window = this.window
    LaunchedEffect(window) { holder.value = window }
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
