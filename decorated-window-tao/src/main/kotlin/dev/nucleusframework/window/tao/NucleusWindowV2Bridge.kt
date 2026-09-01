@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpInsets
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.size
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.window.tao.v2.CombinedBoundsProvider
import dev.nucleusframework.window.tao.v2.DEFAULT_WINDOW_SIZE
import dev.nucleusframework.window.tao.v2.Screen
import dev.nucleusframework.window.tao.v2.WindowBoundsProvider
import dev.nucleusframework.window.tao.v2.WindowGeometryProviderScope
import dev.nucleusframework.window.tao.v2.WindowMetrics
import dev.nucleusframework.window.tao.v2.WindowScreenProvider
import dev.nucleusframework.window.tao.v2.evaluateBounds
import dev.nucleusframework.window.tao.v2.evaluatePosition
import dev.nucleusframework.window.tao.v2.evaluateScreen
import dev.nucleusframework.window.tao.v2.evaluateSize
import dev.nucleusframework.window.tao.v2.screenScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import androidx.compose.ui.window.DialogState as DialogStateV1
import androidx.compose.ui.window.WindowState as WindowStateV1
import dev.nucleusframework.window.tao.v2.DialogState as NucleusDialogState
import dev.nucleusframework.window.tao.v2.WindowState as NucleusWindowState

/**
 * Binds the AWT-free window API v2 clone ([dev.nucleusframework.window.tao.v2])
 * to the v1 [WindowStateV1] the Tao window path consumes.
 *
 * The counterpart of `ComposeWindowV2Bridge` for our own types — and unlike it,
 * nothing is dropped here: every provider is evaluated against a
 * [WindowGeometryProviderScope] built from [TaoMonitors] and the live
 * [TaoWindow], and `requestScreen` really moves the window.
 */
private class InitialGeometry(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val bounds: ResolvedV2Bounds,
    val screenId: String,
)

/**
 * Draining a request channel is destructive, so the initial conversion is
 * memoized per state: a window that leaves and re-enters composition before
 * ever being shown must still land on the geometry it asked for.
 */
private val initialWindowGeometry: MutableMap<NucleusWindowState, InitialGeometry> =
    Collections.synchronizedMap(WeakHashMap())

private val initialDialogGeometry: MutableMap<NucleusDialogState, InitialGeometry> =
    Collections.synchronizedMap(WeakHashMap())

/** Snapshots the pending requests of [state] into a v1 [WindowStateV1]. */
internal fun nucleusWindowStateToV1(state: NucleusWindowState): WindowStateV1 {
    if (state.isInitialized) {
        val bounds = state.bounds
        return WindowStateV1(
            placement = state.placement,
            isMinimized = state.isMinimized,
            position = WindowPosition(bounds.left, bounds.top),
            size = bounds.size,
        )
    }
    val initial = initialWindowGeometry.getOrPut(state) { drainInitialWindowGeometry(state) }
    return WindowStateV1(
        placement = initial.placement,
        isMinimized = initial.isMinimized,
        position = initial.bounds.position,
        size = initial.bounds.size,
    )
}

/** Snapshots the pending requests of [state] into a v1 [DialogStateV1]. */
internal fun nucleusDialogStateToV1(state: NucleusDialogState): DialogStateV1 {
    if (state.isInitialized) {
        val bounds = state.bounds
        return DialogStateV1(
            position = WindowPosition(bounds.left, bounds.top),
            size = bounds.size,
        )
    }
    val initial = initialDialogGeometry.getOrPut(state) { drainInitialDialogGeometry(state) }
    return DialogStateV1(
        position = initial.bounds.position,
        size = initial.bounds.size,
    )
}

private fun drainInitialWindowGeometry(state: NucleusWindowState): InitialGeometry {
    val screen = resolveScreen(drainLast(state.screenRequests), window = null)
    return InitialGeometry(
        placement = state.placementRequests.tryReceive().getOrNull() ?: WindowPlacement.Floating,
        isMinimized = state.minimizedRequests.tryReceive().getOrNull() ?: false,
        bounds = resolveInitialBounds(drainLast(state.boundsRequests), screen),
        screenId = screen.id,
    )
}

private fun drainInitialDialogGeometry(state: NucleusDialogState): InitialGeometry {
    val screen = resolveScreen(drainLast(state.screenRequests), window = null)
    return InitialGeometry(
        placement = WindowPlacement.Floating,
        isMinimized = false,
        bounds = resolveInitialBounds(drainLast(state.boundsRequests), screen),
        screenId = screen.id,
    )
}

/**
 * Applies [v2]'s requests to [v1] and publishes the observed geometry back.
 *
 * [nativeWindow] is `null` until the window is realized (and always `null` for
 * hosts that never expose it); every provider is still evaluable then, against
 * the monitor geometry alone.
 */
@Composable
internal fun BindNucleusWindowState(
    v2: NucleusWindowState,
    v1: WindowStateV1,
    visible: Boolean,
    nativeWindow: TaoWindow? = null,
) {
    val latestV2 = v2
    val latestV1 = v1
    val latestNativeWindow by rememberUpdatedState(nativeWindow)
    LaunchedEffect(v2, v1) {
        launch {
            for (placement in latestV2.placementRequests) {
                latestV1.placement = placement
            }
        }
        launch {
            for (minimized in latestV2.minimizedRequests) {
                latestV1.isMinimized = minimized
            }
        }
        launch {
            for (provider in latestV2.boundsRequests) {
                val resolved = resolveBounds(provider, latestV1, latestNativeWindow)
                latestV1.placement = WindowPlacement.Floating
                latestV1.size = resolved.size
                latestV1.position = resolved.position
            }
        }
        launch {
            for (provider in latestV2.screenRequests) {
                val window = latestNativeWindow
                val target = resolveScreen(provider, window)
                latestV1.position = positionOnScreen(target, latestV1, window)
                latestV2.screenIdOrNull = target.id
            }
        }
    }
    val geometrySignal = rememberNativeGeometrySignal(nativeWindow)
    LaunchedEffect(v1.size, v1.position, v1.placement, v1.isMinimized, visible, nativeWindow) {
        latestV2.placementOrNull = v1.placement
        latestV2.minimizedOrNull = v1.isMinimized

        suspend fun publish() =
            publishObserved(
                window = nativeWindow,
                position = v1.position,
                size = v1.size,
                setBounds = { latestV2.boundsOrNull = it },
                setScreenId = { latestV2.screenIdOrNull = it },
                markInitialized = { if (visible) latestV2.isInitialized = true },
            )
        publish()
        for (event in geometrySignal) {
            publish()
        }
    }
}

/** [BindNucleusWindowState] for a dialog state. */
@Composable
internal fun BindNucleusDialogState(
    v2: NucleusDialogState,
    v1: DialogStateV1,
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
            for (provider in latestV2.boundsRequests) {
                val resolved = resolveDialogBounds(provider, latestV1, latestNativeWindow)
                // minSize / maxSize are inner sizes (they drive
                // TaoWindow.setMinimumSize / setMaximumSize), so clamp the inner
                // size the outer request converted to.
                latestV1.size = clampSize(resolved.size, minSize, maxSize)
                latestV1.position = resolved.position
            }
        }
        launch {
            for (provider in latestV2.screenRequests) {
                val window = latestNativeWindow
                val target = resolveScreen(provider, window)
                latestV1.position = positionOnScreenDp(target, latestV1.position, latestV1.size, window)
                latestV2.screenIdOrNull = target.id
            }
        }
    }
    val geometrySignal = rememberNativeGeometrySignal(nativeWindow)
    LaunchedEffect(v1.size, v1.position, visible, nativeWindow) {
        suspend fun publish() =
            publishObserved(
                window = nativeWindow,
                position = v1.position,
                size = v1.size,
                setBounds = { latestV2.boundsOrNull = it },
                setScreenId = { latestV2.screenIdOrNull = it },
                markInitialized = { if (visible) latestV2.isInitialized = true },
            )
        publish()
        for (event in geometrySignal) {
            publish()
        }
    }
}

// ── Request resolution ──────────────────────────────────────────────────────

private fun resolveScreen(
    provider: WindowScreenProvider?,
    window: TaoWindow?,
): Screen {
    val scope = screenScope(window)
    return provider?.let { scope.evaluateScreen(it) } ?: scope.defaultScreen
}

/**
 * Evaluates [provider] against the live window, converting the outer rectangle
 * it returns into the inner size the v1 state carries.
 */
private fun resolveBounds(
    provider: WindowBoundsProvider,
    v1: WindowStateV1,
    window: TaoWindow?,
): ResolvedV2Bounds {
    val total = window.decorationInsets(v1.size)
    val scope = geometryScope(window, v1.position, v1.size, total)
    val resolved = scope.resolve(provider, v1.position)
    return ResolvedV2Bounds(
        position = resolved.position,
        size = resolved.size.minusInsets(total),
    )
}

private fun resolveDialogBounds(
    provider: WindowBoundsProvider,
    v1: DialogStateV1,
    window: TaoWindow?,
): ResolvedV2Bounds {
    val total = window.decorationInsets(v1.size)
    val scope = geometryScope(window, v1.position, v1.size, total)
    val resolved = scope.resolve(provider, v1.position)
    return ResolvedV2Bounds(
        position = resolved.position,
        size = resolved.size.minusInsets(total),
    )
}

/**
 * Initial bounds, before any native window exists.
 *
 * The scope reports [screen] — the one the initial `WindowScreenProvider`
 * picked, so `CenteredOnScreen` and friends resolve against it — and, as the
 * window's own metrics, a default-sized rectangle centred there. That stands in
 * for a window that does not exist yet: `WindowSizeProvider.Current` reads
 * 800×600 (Compose's own default) and `WindowPositionProvider.Current` reads
 * the centre of the target screen instead of throwing or reporting a corner.
 */
private fun resolveInitialBounds(
    provider: WindowBoundsProvider?,
    screen: Screen,
): ResolvedV2Bounds {
    val fallback = ResolvedV2Bounds(WindowPosition.PlatformDefault, DEFAULT_WINDOW_SIZE)
    if (provider == null) return fallback
    val available = screen.availableBounds
    val left = available.left + ((available.right - available.left - DEFAULT_WINDOW_SIZE.width).value / 2f).dp
    val top = available.top + ((available.bottom - available.top - DEFAULT_WINDOW_SIZE.height).value / 2f).dp
    val scope =
        WindowGeometryProviderScope(
            windowMetrics =
                WindowMetrics(
                    screen = screen,
                    bounds =
                        DpRect(
                            left = left,
                            top = top,
                            right = left + DEFAULT_WINDOW_SIZE.width,
                            bottom = top + DEFAULT_WINDOW_SIZE.height,
                        ),
                    insets = ZERO_INSETS,
                ),
            parentWindowMetrics = null,
        )
    return scope.resolve(provider, WindowPosition.PlatformDefault)
}

/**
 * The window's position after moving it to [target], preserving its offset
 * inside the work area and clamping it so the whole window stays visible.
 */
private fun positionOnScreen(
    target: Screen,
    v1: WindowStateV1,
    window: TaoWindow?,
): WindowPosition = positionOnScreenDp(target, v1.position, v1.size, window)

private fun positionOnScreenDp(
    target: Screen,
    currentPosition: WindowPosition,
    currentSize: DpSize,
    window: TaoWindow?,
): WindowPosition {
    val available = target.availableBounds
    val outer = window?.outerBoundsDpOrNull()
    val size =
        outer?.size?.takeIf { it.width.isSpecified && it.height.isSpecified }
            ?: currentSize.takeIf { it.width.isSpecified && it.height.isSpecified }
            ?: DEFAULT_WINDOW_SIZE
    val source = window?.let { screenScope(it).defaultScreen }
    val fraction = relativePosition(outer, currentPosition, source)
    val maxX = (available.right - available.left - size.width).value.coerceAtLeast(0f)
    val maxY = (available.bottom - available.top - size.height).value.coerceAtLeast(0f)
    return WindowPosition.Absolute(
        x = available.left + (fraction.x.value * maxX).dp,
        y = available.top + (fraction.y.value * maxY).dp,
    )
}

/**
 * Where the window sits inside its current screen's work area, as a `0..1`
 * fraction on each axis. Centres the window when its current position is
 * unknown — a window that never reported a position has nothing to preserve.
 */
private fun relativePosition(
    outer: DpRect?,
    currentPosition: WindowPosition,
    source: Screen?,
): DpOffset {
    val left = outer?.left ?: (currentPosition as? WindowPosition.Absolute)?.x ?: return HALF_OFFSET
    val top = outer?.top ?: (currentPosition as? WindowPosition.Absolute)?.y ?: return HALF_OFFSET
    val available = source?.availableBounds ?: return HALF_OFFSET
    val spanX = (available.right - available.left).value
    val spanY = (available.bottom - available.top).value
    if (spanX <= 0f || spanY <= 0f) return HALF_OFFSET
    return DpOffset(
        x = ((left - available.left).value / spanX).coerceIn(0f, 1f).dp,
        y = ((top - available.top).value / spanY).coerceIn(0f, 1f).dp,
    )
}

private val HALF_OFFSET = DpOffset(0.5f.dp, 0.5f.dp)

private val ZERO_INSETS = DpInsets(top = 0.dp, left = 0.dp, bottom = 0.dp, right = 0.dp)

// ── Scope construction ──────────────────────────────────────────────────────

private fun geometryScope(
    window: TaoWindow?,
    currentPosition: WindowPosition,
    currentInnerSize: DpSize,
    /** Total outer-minus-inner difference, as reported by the platform. */
    decorationSize: DpSize,
): WindowGeometryProviderScope {
    val scale = TaoMonitors.referenceScale(window)
    val screen = Screen(TaoMonitors.forWindow(window), scale)
    val bounds =
        window?.outerBoundsDpOrNull()
            ?: approximateOuterRect(currentPosition, currentInnerSize.plusInsets(decorationSize))
            ?: DpRect(
                left = screen.availableBounds.left,
                top = screen.availableBounds.top,
                right = screen.availableBounds.left + DEFAULT_WINDOW_SIZE.width,
                bottom = screen.availableBounds.top + DEFAULT_WINDOW_SIZE.height,
            )
    // Only popup overlays know their parent natively; a DecoratedDialog's owner
    // is wired at the platform level, so `parentWindowMetrics` stays null there
    // and AlignedToParentWindow reports the missing parent instead of guessing.
    val parent = window?.popupParent
    return WindowGeometryProviderScope(
        windowMetrics = WindowMetrics(screen = screen, bounds = bounds, insets = splitInsets(decorationSize)),
        parentWindowMetrics = parent?.let { parentMetrics(it, scale) },
    )
}

private fun parentMetrics(
    parent: TaoWindow,
    scale: Float,
): WindowMetrics? {
    val bounds = parent.outerBoundsDpOrNull() ?: return null
    return WindowMetrics(
        screen = Screen(TaoMonitors.forWindow(parent), scale),
        bounds = bounds,
        insets = ZERO_INSETS,
    )
}

/**
 * Decoration insets as a per-side [DpInsets], derived from the one thing the
 * platform actually reports: the total outer-minus-inner difference.
 *
 * The split assumes the common frame shape — equal side borders, the remaining
 * vertical difference on top for the title bar. Exact for the undecorated
 * client-side-decorated windows `DecoratedWindow` draws by default (all zero),
 * and off by at most a border width on a natively decorated one.
 */
private fun splitInsets(total: DpSize): DpInsets {
    if (!total.width.isSpecified || !total.height.isSpecified) return ZERO_INSETS
    if (total.width.value <= 0f && total.height.value <= 0f) return ZERO_INSETS
    val side = (total.width.value / 2f).coerceAtLeast(0f)
    val bottom = minOf(side, total.height.value)
    return DpInsets(
        top = (total.height.value - bottom).dp,
        left = side.dp,
        bottom = bottom.dp,
        right = side.dp,
    )
}

// ── Observed geometry ───────────────────────────────────────────────────────

private suspend fun publishObserved(
    window: TaoWindow?,
    position: WindowPosition,
    size: DpSize,
    setBounds: (DpRect) -> Unit,
    setScreenId: (String) -> Unit,
    markInitialized: () -> Unit,
) {
    val rect = observedRect(position, size, window) ?: return
    setBounds(rect)
    setScreenId(TaoMonitors.forWindow(window).id)
    markInitialized()
}

/**
 * Evaluates [provider] into a v1 position + **outer** size.
 *
 * [CombinedBoundsProvider] is unfolded instead of going through `getBounds`:
 * only the split form can express "let the window manager position it"
 * (unspecified position) or "size to content" (unspecified axis) without the
 * `NaN` a [DpRect] would turn either sentinel into.
 */
private fun WindowGeometryProviderScope.resolve(
    provider: WindowBoundsProvider,
    currentPosition: WindowPosition,
): ResolvedV2Bounds {
    if (provider is CombinedBoundsProvider) {
        val size = evaluateSize(provider.sizeProvider)
        val position = evaluatePosition(provider.positionProvider, size)
        return ResolvedV2Bounds(
            position = positionOfOffset(position, currentPosition),
            size = sanitizeSize(size),
        )
    }
    val rect = evaluateBounds(provider)
    return ResolvedV2Bounds(
        position = positionOf(rect, currentPosition),
        size = sanitizeSize(rect.size),
    )
}

private fun positionOfOffset(
    offset: DpOffset,
    current: WindowPosition,
): WindowPosition =
    when {
        offset.isSpecified -> WindowPosition.Absolute(offset.x, offset.y)
        current is WindowPosition.Absolute -> current
        else -> WindowPosition.PlatformDefault
    }

private fun positionOf(
    rect: DpRect,
    current: WindowPosition,
): WindowPosition =
    when {
        rect.left.isSpecified && rect.top.isSpecified -> WindowPosition.Absolute(rect.left, rect.top)
        current is WindowPosition.Absolute -> current
        else -> WindowPosition.PlatformDefault
    }

/** Zero or negative axes (an unmeasured content pass) become wrap-content. */
private fun sanitizeSize(size: DpSize): DpSize =
    DpSize(
        width = if (size.width.isSpecified && size.width.value > 0f) size.width else Dp.Unspecified,
        height = if (size.height.isSpecified && size.height.value > 0f) size.height else Dp.Unspecified,
    )

private fun <T> drainLast(channel: Channel<T>): T? {
    var last: T? = null
    while (true) {
        last = channel.tryReceive().getOrNull() ?: return last
    }
}

// ── Fallback for hosts that only wrap the v1 surface ────────────────────────

/**
 * v1 [WindowStateV1] kept in sync with the AWT-free v2 [state].
 *
 * For hosts (themed `NucleusWindowHost` implementations) that only wrap the v1
 * window surface. The native window is unavailable on that path, so geometry
 * providers resolve against monitor data alone and the published `bounds` is
 * the inner size rather than the outer one.
 */
@Composable
public fun rememberSyncedNucleusWindowState(
    state: NucleusWindowState,
    visible: Boolean,
): WindowStateV1 {
    val v1 = remember(state) { nucleusWindowStateToV1(state) }
    BindNucleusWindowState(state, v1, visible)
    return v1
}

/**
 * v1 [DialogStateV1] kept in sync with the AWT-free v2 [state]. Same fallback
 * contract as [rememberSyncedNucleusWindowState].
 */
@Composable
public fun rememberSyncedNucleusDialogState(
    state: NucleusDialogState,
    visible: Boolean,
): DialogStateV1 {
    val v1 = remember(state) { nucleusDialogStateToV1(state) }
    BindNucleusDialogState(state, v1, visible)
    return v1
}
