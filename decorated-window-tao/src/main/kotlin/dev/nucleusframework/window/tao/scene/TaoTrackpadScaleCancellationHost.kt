package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.nucleusframework.window.tao.event.CancelledTrackpadScale

/**
 * Compose's cancelPointerInput only cancels pressed contacts. Native scale events carry a
 * hovering Mouse pointer, so consume a cancelled ScaleEnd in Initial instead. Application
 * handlers can then discard their release work using the usual event-consumption contract.
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal fun TaoTrackpadScaleCancellationHost(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.nativeEvent === CancelledTrackpadScale) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
    }
}
