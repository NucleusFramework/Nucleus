@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.window.v2.WindowBoundsProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import java.util.logging.Logger
import androidx.compose.ui.window.v2.DialogState as DialogStateV2
import androidx.compose.ui.window.v2.WindowState as WindowStateV2

private val v2Logger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.windowV2")

private val defaultWindowSize = DpSize(800.dp, 600.dp)
private val defaultDialogSize = DpSize(800.dp, 600.dp)
private const val PRIMARY_SCREEN_ID = "primary"

/** Native geometry is only readable once Tao has realized the window. */
private const val OBSERVED_BOUNDS_RETRIES = 20
private const val OBSERVED_BOUNDS_RETRY_MS = 50L
private const val RECT_ARRAY_SIZE = 4

private const val UNRESOLVABLE_PROVIDER_MESSAGE =
    "Ignoring a Compose WindowBoundsProvider that needs AWT window metrics. " +
        "WindowState.requestSize(), requestPosition(), rememberWindowStateWithBounds() and " +
        "capturing WindowBoundsProvider lambdas all route through an AWT-backed " +
        "WindowGeometryProviderScope, which the Tao backend has no window to build. " +
        "Use requestBounds(DpRect), WindowBoundsProvider.Absolute or " +
        "dev.nucleusframework.window.tao.requestInspectableBounds() instead."

internal data class ResolvedV2Bounds(
    val position: WindowPosition,
    val size: DpSize,
)

/**
 * Initial geometry drained out of a not-yet-initialized v2 state.
 *
 * Draining is destructive, so the result is memoized per state object: a window
 * that leaves and re-enters composition before ever becoming visible (or a host
 * that converts the same hoisted state twice) would otherwise see empty request
 * channels and fall back to the platform default instead of the geometry the
 * caller asked for.
 */
private class InitialWindowGeometry(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val bounds: ResolvedV2Bounds,
)

private val initialWindowGeometry: MutableMap<WindowStateV2, InitialWindowGeometry> =
    Collections.synchronizedMap(WeakHashMap())

private val initialDialogGeometry: MutableMap<DialogStateV2, ResolvedV2Bounds> =
    Collections.synchronizedMap(WeakHashMap())

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
    val initial = initialWindowGeometry.getOrPut(state) { drainInitialWindowGeometry(state) }
    return WindowState(
        placement = initial.placement,
        isMinimized = initial.isMinimized,
        position = initial.bounds.position,
        size = initial.bounds.size,
    )
}

private fun drainInitialWindowGeometry(state: WindowStateV2): InitialWindowGeometry {
    drain(ComposeWindowV2Access.screenRequests(state))
    return InitialWindowGeometry(
        placement =
            ComposeWindowV2Access.placementRequests(state).tryReceive().getOrNull()
                ?: ComposeWindowV2Access.placementOrNull(state)
                ?: WindowPlacement.Floating,
        isMinimized =
            ComposeWindowV2Access.minimizedRequests(state).tryReceive().getOrNull()
                ?: ComposeWindowV2Access.minimizedOrNull(state)
                ?: false,
        bounds = resolveWindowBounds(drainBounds(ComposeWindowV2Access.boundsRequests(state))),
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
    val resolved =
        initialDialogGeometry.getOrPut(state) {
            drain(ComposeWindowV2Access.dialogScreenRequests(state))
            resolveDialogBounds(drainBounds(ComposeWindowV2Access.dialogBoundsRequests(state)))
        }
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
    nativeWindow: TaoWindow? = null,
) {
    val latestV2 = v2
    val latestV1 = v1
    val latestNativeWindow by rememberUpdatedState(nativeWindow)
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
                val insets = latestNativeWindow.decorationInsets(latestV1.size)
                // Skip the whole request when the provider can't be evaluated:
                // writing Floating here would drop a maximized/fullscreen window
                // back to its floating state for a request we then ignore.
                val resolved =
                    resolveWindowBoundsOrNull(
                        provider,
                        latestV1.position,
                        latestV1.size.plusInsets(insets),
                    ) ?: continue
                latestV1.placement = WindowPlacement.Floating
                latestV1.size = resolved.size.minusInsets(insets)
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
    LaunchedEffect(v1.size, v1.position, v1.placement, v1.isMinimized, visible, nativeWindow) {
        publishWindowObserved(v2, v1, visible, nativeWindow)
    }
}

@Composable
internal fun BindDialogStateV2(
    v2: DialogStateV2,
    v1: DialogState,
    visible: Boolean,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    nativeWindow: TaoWindow? = null,
) {
    val latestV2 = v2
    val latestV1 = v1
    val latestNativeWindow by rememberUpdatedState(nativeWindow)
    LaunchedEffect(v2, v1, minSize, maxSize) {
        launch {
            for (provider in ComposeWindowV2Access.dialogBoundsRequests(latestV2)) {
                val insets = latestNativeWindow.decorationInsets(latestV1.size)
                val resolved =
                    resolveDialogBoundsOrNull(
                        provider,
                        latestV1.position,
                        latestV1.size.plusInsets(insets),
                    ) ?: continue
                // minSize / maxSize are inner sizes (they drive
                // TaoWindow.setMinimumSize / setMaximumSize), so clamp after
                // converting the requested outer size back to an inner one.
                latestV1.size = clampSize(resolved.size.minusInsets(insets), minSize, maxSize)
                latestV1.position = resolved.position
            }
        }
        launch {
            ComposeWindowV2Access.dialogScreenRequests(latestV2).discardForever()
        }
    }
    LaunchedEffect(v1.size, v1.position, visible, nativeWindow) {
        publishDialogObserved(v2, v1, visible, nativeWindow)
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
 * surface. `maxSize` is v2-only and is dropped on that fallback, and the
 * observed `bounds` are approximate: without the native window there is nothing
 * to measure the decoration insets against. Hosts that can reach the
 * [TaoWindow] should call [BindWindowStateV2] with it instead.
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
 * wrap the v1 surface, with the same approximate `bounds`. `minSize` /
 * `maxSize` are dropped on that path.
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

/**
 * Same as [resolveWindowBoundsOrNull] but falls back to the current (or
 * default) geometry instead of returning `null`. Used on the window-creation
 * path, which has to produce some geometry.
 */
internal fun resolveWindowBounds(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition = WindowPosition.PlatformDefault,
    currentSize: DpSize = defaultWindowSize,
): ResolvedV2Bounds =
    resolveWindowBoundsOrNull(provider, currentPosition, currentSize)
        ?: ResolvedV2Bounds(
            position = currentOrDefault(currentPosition, WindowPosition.PlatformDefault),
            size =
                currentSize.takeIf { it.width.isSpecified && it.height.isSpecified }
                    ?: defaultWindowSize,
        )

/** `null` when [provider] cannot be evaluated without AWT window metrics. */
internal fun resolveWindowBoundsOrNull(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition = WindowPosition.PlatformDefault,
    currentSize: DpSize = defaultWindowSize,
): ResolvedV2Bounds? =
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
    resolveDialogBoundsOrNull(provider, currentPosition, currentSize)
        ?: ResolvedV2Bounds(
            position = currentOrDefault(currentPosition, WindowPosition(Alignment.Center)),
            size =
                currentSize.takeIf { it.width.isSpecified && it.height.isSpecified }
                    ?: defaultDialogSize,
        )

/** `null` when [provider] cannot be evaluated without AWT window metrics. */
internal fun resolveDialogBoundsOrNull(
    provider: WindowBoundsProvider?,
    currentPosition: WindowPosition = WindowPosition(Alignment.Center),
    currentSize: DpSize = defaultDialogSize,
): ResolvedV2Bounds? =
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
): ResolvedV2Bounds? {
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
    val rect = ComposeWindowV2Access.constantBoundsOrNull(provider)
    if (rect == null) {
        // WARNING, not FINE: the request is dropped entirely, and the API that
        // produced it (requestSize / requestPosition) gives no other feedback.
        v2Logger.warning(UNRESOLVABLE_PROVIDER_MESSAGE)
        return null
    }
    return ResolvedV2Bounds(WindowPosition(rect.left, rect.top), wrapUnspecifiedAxes(rect.size))
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

private suspend fun publishWindowObserved(
    v2: WindowStateV2,
    v1: WindowState,
    visible: Boolean,
    nativeWindow: TaoWindow?,
) {
    ComposeWindowV2Access.setPlacement(v2, v1.placement)
    ComposeWindowV2Access.setMinimized(v2, v1.isMinimized)
    val rect = observedRect(v1.position, v1.size, nativeWindow) ?: return
    ComposeWindowV2Access.setBounds(v2, rect)
    if (ComposeWindowV2Access.screenIdOrNull(v2) == null) {
        ComposeWindowV2Access.setScreenId(v2, PRIMARY_SCREEN_ID)
    }
    if (visible) {
        ComposeWindowV2Access.setInitialized(v2, true)
    }
}

private suspend fun publishDialogObserved(
    v2: DialogStateV2,
    v1: DialogState,
    visible: Boolean,
    nativeWindow: TaoWindow?,
) {
    val rect = observedRect(v1.position, v1.size, nativeWindow) ?: return
    ComposeWindowV2Access.setDialogBounds(v2, rect)
    if (ComposeWindowV2Access.dialogScreenIdOrNull(v2) == null) {
        ComposeWindowV2Access.setDialogScreenId(v2, PRIMARY_SCREEN_ID)
    }
    if (visible) {
        ComposeWindowV2Access.setDialogInitialized(v2, true)
    }
}

/**
 * Observed window rectangle, preferring the native geometry.
 *
 * Compose v2 documents `WindowState.bounds` as the whole window, insets
 * included ([androidx.compose.ui.window.v2.WindowMetrics.bounds]), which is
 * exactly [TaoWindow.outerBoundsPx]. The v1 state is *not* a substitute: it
 * pairs the outer position ([TaoWindow.setOuterPosition]) with the inner size
 * ([TaoWindow.setInnerSize]), so publishing it would make `bounds.size` mean
 * one thing before the first native measurement and another after — enough to
 * shrink a window by its decoration insets on every `requestBounds(bounds)`
 * round-trip, or across a `WindowState.Saver` restore.
 */
private suspend fun observedRect(
    position: WindowPosition,
    size: DpSize,
    nativeWindow: TaoWindow?,
): DpRect? {
    if (nativeWindow != null) {
        repeat(OBSERVED_BOUNDS_RETRIES) { attempt ->
            nativeWindow.outerBoundsDpOrNull()?.let { return it }
            if (attempt < OBSERVED_BOUNDS_RETRIES - 1) delay(OBSERVED_BOUNDS_RETRY_MS)
        }
    }
    return approximateOuterRect(position, size)
}

/**
 * Best-effort rectangle for hosts that never expose the native window — a
 * themed [dev.nucleusframework.window.tao.rememberSyncedWindowState] host binds
 * with `nativeWindow = null` — and for a window the platform bridge can't
 * measure yet.
 *
 * An approximation on two counts: the size is the inner one (insets unknown
 * without a window to measure), and a position that hasn't become
 * [WindowPosition.Absolute] yet is reported at the origin. Publishing it anyway
 * is what keeps `WindowState.isInitialized` from staying `false` — and `bounds`
 * / `size` / `position` from throwing — forever on a window manager that emits
 * no initial move event.
 */
private fun approximateOuterRect(
    position: WindowPosition,
    size: DpSize,
): DpRect? {
    if (!size.width.isSpecified || !size.height.isSpecified) return null
    val absolute = position as? WindowPosition.Absolute
    val left = absolute?.x ?: 0.dp
    val top = absolute?.y ?: 0.dp
    return DpRect(
        left = left,
        top = top,
        right = left + size.width,
        bottom = top + size.height,
    )
}

/**
 * Decoration insets (outer minus inner size), or [DpSize.Zero] when they can't
 * be measured — which is also the right answer for the undecorated CSD windows
 * Tao draws by default.
 */
private fun TaoWindow?.decorationInsets(innerSize: DpSize): DpSize {
    val window = this ?: return DpSize.Zero
    if (!innerSize.width.isSpecified || !innerSize.height.isSpecified) return DpSize.Zero
    val outer = window.outerBoundsDpOrNull() ?: return DpSize.Zero
    return DpSize(
        width = (outer.right - outer.left - innerSize.width).coerceAtLeast(0.dp),
        height = (outer.bottom - outer.top - innerSize.height).coerceAtLeast(0.dp),
    )
}

/** Inner size → outer (v2) size. Unspecified axes stay unspecified. */
private fun DpSize.plusInsets(insets: DpSize): DpSize =
    DpSize(
        width = if (width.isSpecified) width + insets.width else width,
        height = if (height.isSpecified) height + insets.height else height,
    )

/** Outer (v2) size → inner size. Unspecified axes stay unspecified. */
private fun DpSize.minusInsets(insets: DpSize): DpSize =
    DpSize(
        width = if (width.isSpecified) (width - insets.width).coerceAtLeast(0.dp) else width,
        height = if (height.isSpecified) (height - insets.height).coerceAtLeast(0.dp) else height,
    )

private fun TaoWindow.outerBoundsDpOrNull(): DpRect? {
    val rect = outerBoundsPx() ?: return null
    if (rect.size != RECT_ARRAY_SIZE) return null
    val scale = scaleFactor.takeIf { it > 0f } ?: 1f
    val left = rect[0] / scale
    val top = rect[1] / scale
    return DpRect(
        left = left.dp,
        top = top.dp,
        right = (left + rect[2] / scale).dp,
        bottom = (top + rect[3] / scale).dp,
    )
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
