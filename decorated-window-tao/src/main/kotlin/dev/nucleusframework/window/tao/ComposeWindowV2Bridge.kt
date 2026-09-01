@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.size
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.v2.ComposeWindowV2Access
import androidx.compose.ui.window.v2.InspectableWindowBoundsProvider
import androidx.compose.ui.window.v2.WindowBoundsProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import androidx.compose.ui.window.v2.DialogState as DialogStateV2
import androidx.compose.ui.window.v2.WindowState as WindowStateV2

private val v2Logger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.windowV2")

private val defaultWindowSize = DpSize(800.dp, 600.dp)
private val defaultDialogSize = DpSize(800.dp, 600.dp)
private const val PRIMARY_SCREEN_ID = "primary"

internal data class ResolvedV2Bounds(
    val position: WindowPosition,
    val size: DpSize,
)

/**
 * Snapshots pending v2 requests into the v1 [WindowState] the existing window
 * path consumes.
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
                val resolved =
                    resolveWindowBounds(provider, latestV1.position, latestV1.size)
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
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
) {
    val latestV2 = v2
    val latestV1 = v1
    LaunchedEffect(v2, v1, minSize, maxSize) {
        launch {
            for (provider in ComposeWindowV2Access.dialogBoundsRequests(latestV2)) {
                val resolved =
                    resolveDialogBounds(provider, latestV1.position, latestV1.size)
                val clamped = clampSize(resolved.size, minSize, maxSize)
                latestV1.size = clamped
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

/**
 * v1 [WindowState] kept in sync with v2 [state].
 *
 * Used so a v2 `HostedWindow` still reaches hosts that only wrap the v1
 * surface. `maxSize` is v2-only and is dropped on that fallback.
 */
@Composable
public fun rememberSyncedWindowState(
    state: WindowStateV2,
    visible: Boolean,
): WindowState {
    val v1 = rememberWindowStateV1(state)
    BindWindowStateV2(state, v1, visible)
    return v1
}

/**
 * v1 [DialogState] kept in sync with v2 [state].
 *
 * Same fallback as [rememberSyncedWindowState] for dialog hosts that only
 * wrap the v1 surface. `minSize` / `maxSize` are dropped on that path.
 */
@Composable
public fun rememberSyncedDialogState(
    state: DialogStateV2,
    visible: Boolean,
): DialogState {
    val v1 = rememberDialogStateV1(state)
    BindDialogStateV2(state, v1, visible)
    return v1
}

internal fun minSizeOrNull(minSize: DpSize): DpSize? =
    if (minSize.width.isSpecified && minSize.height.isSpecified) minSize else null

internal fun clampSize(
    size: DpSize,
    minSize: DpSize,
    maxSize: DpSize,
): DpSize {
    var width = size.width
    var height = size.height
    val min = minSizeOrNull(minSize)
    if (min != null) {
        if (width.isSpecified && width < min.width) width = min.width
        if (height.isSpecified && height < min.height) height = min.height
    }
    if (maxSize.width.isSpecified && width.isSpecified && width > maxSize.width) width = maxSize.width
    if (maxSize.height.isSpecified && height.isSpecified && height > maxSize.height) height = maxSize.height
    return DpSize(width, height)
}

internal fun resolveWindowBounds(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition = WindowPosition.PlatformDefault,
    currentSize: DpSize = defaultWindowSize,
): ResolvedV2Bounds =
    resolveBounds(
        provider = provider,
        currentPosition = currentPosition,
        currentSize = currentSize,
        defaultPosition = WindowPosition.PlatformDefault,
        defaultSize = defaultWindowSize,
    )

internal fun resolveDialogBounds(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition = WindowPosition(Alignment.Center),
    currentSize: DpSize = defaultDialogSize,
): ResolvedV2Bounds =
    resolveBounds(
        provider = provider,
        currentPosition = currentPosition,
        currentSize = currentSize,
        defaultPosition = WindowPosition(Alignment.Center),
        defaultSize = defaultDialogSize,
    )

private fun resolveBounds(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition,
    currentSize: DpSize,
    defaultPosition: WindowPosition,
    defaultSize: DpSize,
): ResolvedV2Bounds {
    if (provider == null || provider === WindowBoundsProvider.Default) {
        return ResolvedV2Bounds(defaultPosition, defaultSize)
    }
    if (provider is InspectableWindowBoundsProvider) {
        val size =
            provider.size
                ?: currentSize.takeIf { it.width.isSpecified && it.height.isSpecified }
                ?: defaultSize
        val position =
            provider.position ?: currentOrDefault(currentPosition, defaultPosition)
        return ResolvedV2Bounds(position, wrapUnspecifiedAxes(size))
    }
    ComposeWindowV2Access.constantBoundsOrNull(provider)?.let { rect ->
        return ResolvedV2Bounds(WindowPosition(rect.left, rect.top), wrapUnspecifiedAxes(rect.size))
    }
    v2Logger.log(
        Level.FINE,
        "Compose capturing WindowBoundsProvider cannot be read without AWT; using current geometry",
    )
    return ResolvedV2Bounds(
        position = currentOrDefault(currentPosition, defaultPosition),
        size = currentSize.takeIf { it.width.isSpecified && it.height.isSpecified } ?: defaultSize,
    )
}

private fun currentOrDefault(
    current: WindowPosition,
    default: WindowPosition,
): WindowPosition = if (current is WindowPosition.Absolute) current else default

/** Zero axes from a content measure before the scene exists become wrap-content. */
private fun wrapUnspecifiedAxes(size: DpSize): DpSize {
    val width = if (size.width.value <= 0f) Dp.Unspecified else size.width
    val height = if (size.height.value <= 0f) Dp.Unspecified else size.height
    return DpSize(width, height)
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
            ComposeWindowV2Access.setScreenId(v2, PRIMARY_SCREEN_ID)
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
            ComposeWindowV2Access.setDialogScreenId(v2, PRIMARY_SCREEN_ID)
        }
        if (visible) {
            ComposeWindowV2Access.setDialogInitialized(v2, true)
        }
    }
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
