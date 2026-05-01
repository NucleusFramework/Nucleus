package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.awt.Window

@Suppress("FunctionNaming")
@Composable
fun AwtDecoratedWindowScope.TitleBarImpl(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    GenericTitleBarImpl(
        state = state,
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
        content = content,
    )
}

// Handles window dragging via Compose pointer events.
// Drag starts only when the press is not consumed by a child composable (e.g. a button),
// so interactive elements in the title bar keep working correctly.
fun Modifier.windowDragHandler(window: Window): Modifier =
    pointerInput(window) {
        val ctx = currentCoroutineContext()
        awaitPointerEventScope {
            var dragging = false
            var startScreenX = 0
            var startScreenY = 0
            var startWindowX = 0
            var startWindowY = 0

            @Suppress("LoopWithTooManyJumpStatements")
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue

                when (event.type) {
                    PointerEventType.Press -> {
                        if (!change.isConsumed) {
                            val loc =
                                java.awt.MouseInfo
                                    .getPointerInfo()
                                    ?.location
                            startScreenX = loc?.x ?: 0
                            startScreenY = loc?.y ?: 0
                            startWindowX = window.x
                            startWindowY = window.y
                            dragging = true
                        }
                    }
                    PointerEventType.Move -> {
                        if (dragging) {
                            val loc =
                                java.awt.MouseInfo
                                    .getPointerInfo()
                                    ?.location ?: continue
                            window.setLocation(
                                startWindowX + (loc.x - startScreenX),
                                startWindowY + (loc.y - startScreenY),
                            )
                        }
                    }
                    PointerEventType.Release -> {
                        dragging = false
                    }
                    else -> Unit
                }
            }
        }
    }
