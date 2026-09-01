@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.size
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.v2.ComposeWindowV2Access
import androidx.compose.ui.window.v2.WindowBoundsProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import androidx.compose.ui.window.v2.DialogState as DialogStateV2
import androidx.compose.ui.window.v2.WindowState as WindowStateV2

private val v2Logger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.windowV2")

private val defaultWindowSize = DpSize(800.dp, 600.dp)
private val defaultDialogSize = DpSize(800.dp, 600.dp)

internal data class ResolvedV2Bounds(
    val position: WindowPosition,
    val size: DpSize,
)

/**
 * Drains the v2 [WindowStateV2] request channels into a v1 [WindowState] the
 * existing [DecoratedWindow] plumbing already knows how to apply.
 *
 * Compose 1.12's window API v2 keeps requested geometry on internal channels
 * and observed geometry on `_bounds` / `_placement`. Tao cannot live in
 * `compose-ui`, so a same-package Java accessor reads those internals.
 */
internal fun windowStateV2ToV1(state: WindowStateV2): WindowState {
    if (state.isInitialized) {
        val bounds = state.bounds
        return WindowState(
            placement = state.placement,
            isMinimized = state.isMinimized,
            position = WindowPosition(bounds.left, bounds.top),
            size = bounds.size,
        )
    }
    drain(ComposeWindowV2Access.screenRequests(state))
    val placement =
        ComposeWindowV2Access.placementRequests(state).tryReceive().getOrNull()
            ?: ComposeWindowV2Access.placementOrNull(state)
            ?: WindowPlacement.Floating
    val minimized =
        ComposeWindowV2Access.minimizedRequests(state).tryReceive().getOrNull()
            ?: ComposeWindowV2Access.minimizedOrNull(state)
            ?: false
    val resolved = resolveWindowBounds(drainBounds(ComposeWindowV2Access.boundsRequests(state)))
    return WindowState(
        placement = placement,
        isMinimized = minimized,
        position = resolved.position,
        size = resolved.size,
    )
}

internal fun dialogStateV2ToV1(state: DialogStateV2): DialogState {
    if (state.isInitialized) {
        val bounds = state.bounds
        return DialogState(
            position = WindowPosition(bounds.left, bounds.top),
            size = bounds.size,
        )
    }
    drain(ComposeWindowV2Access.dialogScreenRequests(state))
    val resolved =
        resolveDialogBounds(drainBounds(ComposeWindowV2Access.dialogBoundsRequests(state)))
    return DialogState(
        position = resolved.position,
        size = resolved.size,
    )
}

@Composable
internal fun BindWindowStateV2(
    v2: WindowStateV2,
    v1: WindowState,
    visible: Boolean,
) {
    val latestV2 = v2
    val latestV1 = v1
    LaunchedEffect(v2, v1) {
        launch {
            for (placement in ComposeWindowV2Access.placementRequests(latestV2)) {
                latestV1.placement = placement
            }
        }
        launch {
            for (minimized in ComposeWindowV2Access.minimizedRequests(latestV2)) {
                latestV1.isMinimized = minimized
            }
        }
        launch {
            for (provider in ComposeWindowV2Access.boundsRequests(latestV2)) {
                val resolved = resolveWindowBounds(provider)
                latestV1.placement = WindowPlacement.Floating
                latestV1.size = resolved.size
                latestV1.position = resolved.position
            }
        }
        launch {
            // Multi-monitor placement is AWT GraphicsDevice-based in Compose v2.
            // Tao only exposes the primary work area today — drain the channel
            // so senders do not suspend forever.
            ComposeWindowV2Access.screenRequests(latestV2).discardForever()
        }
    }
    LaunchedEffect(v1.size, v1.position, v1.placement, v1.isMinimized, visible) {
        publishWindowObserved(v2, v1, visible)
    }
}

@Composable
internal fun BindDialogStateV2(
    v2: DialogStateV2,
    v1: DialogState,
    visible: Boolean,
) {
    val latestV2 = v2
    val latestV1 = v1
    LaunchedEffect(v2, v1) {
        launch {
            for (provider in ComposeWindowV2Access.dialogBoundsRequests(latestV2)) {
                val resolved = resolveDialogBounds(provider)
                latestV1.size = resolved.size
                latestV1.position = resolved.position
            }
        }
        launch {
            ComposeWindowV2Access.dialogScreenRequests(latestV2).discardForever()
        }
    }
    LaunchedEffect(v1.size, v1.position, visible) {
        publishDialogObserved(v2, v1, visible)
    }
}

@Composable
internal fun rememberWindowStateV1(state: WindowStateV2): WindowState = remember(state) { windowStateV2ToV1(state) }

@Composable
internal fun rememberDialogStateV1(state: DialogStateV2): DialogState = remember(state) { dialogStateV2ToV1(state) }

internal fun minSizeOrNull(minSize: DpSize): DpSize? =
    if (minSize.width.isSpecified || minSize.height.isSpecified) minSize else null

private fun resolveWindowBounds(provider: WindowBoundsProvider?): ResolvedV2Bounds {
    if (provider == null || provider === WindowBoundsProvider.Default) {
        return ResolvedV2Bounds(WindowPosition.PlatformDefault, defaultWindowSize)
    }
    val rect =
        evaluateBoundsProvider(provider)
            ?: return ResolvedV2Bounds(WindowPosition.PlatformDefault, defaultWindowSize)
    return ResolvedV2Bounds(WindowPosition(rect.left, rect.top), wrapUnspecifiedAxes(rect.size))
}

private fun resolveDialogBounds(provider: WindowBoundsProvider?): ResolvedV2Bounds {
    if (provider == null || provider === WindowBoundsProvider.Default) {
        // Tao dialogs centre on their owner when the v1 position is not Absolute.
        return ResolvedV2Bounds(WindowPosition(Alignment.Center), defaultDialogSize)
    }
    val rect =
        evaluateBoundsProvider(provider)
            ?: return ResolvedV2Bounds(WindowPosition(Alignment.Center), defaultDialogSize)
    return ResolvedV2Bounds(WindowPosition(rect.left, rect.top), wrapUnspecifiedAxes(rect.size))
}

/** Zero axes from a content measure before the scene exists become wrap-content. */
private fun wrapUnspecifiedAxes(size: DpSize): DpSize {
    val width = if (size.width.value <= 0f) Dp.Unspecified else size.width
    val height = if (size.height.value <= 0f) Dp.Unspecified else size.height
    return DpSize(width, height)
}

private fun evaluateBoundsProvider(provider: WindowBoundsProvider): DpRect? {
    val dummy =
        geometryPeerOrNull(
            bounds =
                Rectangle(
                    0,
                    0,
                    defaultWindowSize.width.value.roundToInt(),
                    defaultWindowSize.height.value.roundToInt(),
                ),
            insets = Insets(0, 0, 0, 0),
        ) ?: return null
    return try {
        ComposeWindowV2Access.evaluateBounds(
            provider,
            null,
            dummy,
        ) { _: Constraints -> IntSize.Zero }
    } catch (e: Exception) {
        v2Logger.log(Level.FINE, "Failed to evaluate Compose window v2 bounds provider", e)
        null
    } finally {
        dummy.dispose()
    }
}

private fun geometryPeerOrNull(
    bounds: Rectangle,
    insets: Insets,
): java.awt.Window? =
    try {
        val gc =
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
        ComposeWindowV2Access.createGeometryPeer(gc, bounds, insets)
    } catch (_: Exception) {
        null
    }

private fun publishWindowObserved(
    v2: WindowStateV2,
    v1: WindowState,
    visible: Boolean,
) {
    ComposeWindowV2Access.setPlacement(v2, v1.placement)
    ComposeWindowV2Access.setMinimized(v2, v1.isMinimized)
    val pos = v1.position
    val size = v1.size
    if (pos is WindowPosition.Absolute &&
        size.width.isSpecified &&
        size.height.isSpecified
    ) {
        val rect =
            DpRect(
                left = pos.x,
                top = pos.y,
                right = pos.x + size.width,
                bottom = pos.y + size.height,
            )
        ComposeWindowV2Access.setBounds(v2, rect)
        if (ComposeWindowV2Access.screenIdOrNull(v2) == null) {
            ComposeWindowV2Access.setScreenId(v2, currentScreenId())
        }
        if (visible) {
            ComposeWindowV2Access.setInitialized(v2, true)
        }
    }
}

private fun publishDialogObserved(
    v2: DialogStateV2,
    v1: DialogState,
    visible: Boolean,
) {
    val pos = v1.position
    val size = v1.size
    if (pos is WindowPosition.Absolute &&
        size.width.isSpecified &&
        size.height.isSpecified
    ) {
        val rect =
            DpRect(
                left = pos.x,
                top = pos.y,
                right = pos.x + size.width,
                bottom = pos.y + size.height,
            )
        ComposeWindowV2Access.setDialogBounds(v2, rect)
        if (ComposeWindowV2Access.dialogScreenIdOrNull(v2) == null) {
            ComposeWindowV2Access.setDialogScreenId(v2, currentScreenId())
        }
        if (visible) {
            ComposeWindowV2Access.setDialogInitialized(v2, true)
        }
    }
}

private fun currentScreenId(): String =
    try {
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .iDstring
    } catch (_: Exception) {
        "primary"
    }

private fun drainBounds(channel: Channel<WindowBoundsProvider>): WindowBoundsProvider? {
    var last: WindowBoundsProvider? = null
    while (true) {
        last = channel.tryReceive().getOrNull() ?: return last
    }
}

private fun <T> drain(channel: Channel<T>) {
    while (channel.tryReceive().isSuccess) {
        // Discard. Screen switching is not applied on Tao yet.
    }
}

private suspend fun <T> Channel<T>.discardForever() {
    for (item in this) {
        @Suppress("UNUSED_EXPRESSION")
        item
    }
}
