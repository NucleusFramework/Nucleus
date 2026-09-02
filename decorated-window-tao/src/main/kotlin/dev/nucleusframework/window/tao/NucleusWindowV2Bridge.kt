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
import dev.nucleusframework.window.tao.v2.WindowPositionProvider
import dev.nucleusframework.window.tao.v2.WindowScreenProvider
import dev.nucleusframework.window.tao.v2.evaluateBounds
import dev.nucleusframework.window.tao.v2.evaluatePosition
import dev.nucleusframework.window.tao.v2.evaluateScreen
import dev.nucleusframework.window.tao.v2.evaluateSize
import dev.nucleusframework.window.tao.v2.screenScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * Nothing is dropped here: every provider is evaluated against a
 * [WindowGeometryProviderScope] built from [TaoMonitors] and the live
 * [TaoWindow], and `requestScreen` really moves the window.
 */
private val v2Logger: java.util.logging.Logger =
    java.util.logging.Logger
        .getLogger("dev.nucleusframework.window.tao.windowV2")

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
                // The native flags decide, not the v1 bookkeeping alone: a burst
                // of placement toggles can leave AppKit still zoomed while v1
                // already says Floating (each `zoom:` is a toggle, and the ones
                // issued mid-animation may not land in order).
                val window = latestNativeWindow
                val leftPlacement =
                    latestV1.placement != WindowPlacement.Floating ||
                        (window != null && (window.isMaximized || window.isFullscreen))
                if (leftPlacement) {
                    // Bounds on a non-floating window make it floating (the v2
                    // contract) — but the restore is asynchronous, and on macOS
                    // an animated un-zoom whose final frame lands *after* our
                    // size would put the pre-zoom frame back over it. Let the
                    // window actually leave the placement first, then resolve
                    // against the restored geometry.
                    latestV1.placement = WindowPlacement.Floating
                    window?.let { restoreAndAwaitFloating(it) }
                }
                val resolved = resolveBounds(provider, latestV1, latestNativeWindow)
                latestV1.size = resolved.size
                latestV1.position = resolved.position
                if (leftPlacement) latestNativeWindow?.let { confirmBounds(it, latestV1, resolved) }
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
    LaunchedEffect(nativeWindow) {
        val window = nativeWindow ?: return@LaunchedEffect
        correctInitialOuterSize(
            window = window,
            initialOuterSize = initialWindowGeometry[latestV2]?.bounds?.size,
            currentSize = { latestV1.size },
            applySize = { latestV1.size = it },
        )
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
    /** The window the dialog was opened from, for `AlignedToParentWindow`. */
    parentWindow: TaoWindow? = null,
) {
    val latestV2 = v2
    val latestV1 = v1
    val latestNativeWindow by rememberUpdatedState(nativeWindow)
    val latestParentWindow by rememberUpdatedState(parentWindow)
    LaunchedEffect(v2, v1, minSize, maxSize) {
        launch {
            for (provider in latestV2.boundsRequests) {
                val resolved = resolveDialogBounds(provider, latestV1, latestNativeWindow, latestParentWindow)
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
    LaunchedEffect(nativeWindow) {
        val window = nativeWindow ?: return@LaunchedEffect
        correctInitialOuterSize(
            window = window,
            initialOuterSize = initialDialogGeometry[latestV2]?.bounds?.size,
            currentSize = { latestV1.size },
            applySize = { latestV1.size = clampSize(it, minSize, maxSize) },
        )
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
    parentWindow: TaoWindow?,
): ResolvedV2Bounds {
    val total = window.decorationInsets(v1.size)
    val scope = geometryScope(window, v1.position, v1.size, total, parentWindow)
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
    // Before the window exists, "the current position" is the one the window
    // manager has not chosen yet. `requestSize` / `WindowBoundsProvider(size)`
    // pair their size with `WindowPositionProvider.Current`, and resolving that
    // against the placeholder rectangle above would pin the window to an
    // absolute point — the v1 `size =` idiom this replaces leaves placement to
    // the platform, so keep that here. (`WindowSizeProvider.Current` reads the
    // placeholder's default size, which is already the v1 default.)
    if (provider is CombinedBoundsProvider && provider.positionProvider === WindowPositionProvider.Current) {
        val size = sanitizeSize(scope.evaluateSize(provider.sizeProvider))
        return ResolvedV2Bounds(WindowPosition.PlatformDefault, size)
    }
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
    /** The owner a dialog was opened from; popups resolve theirs natively. */
    parentWindow: TaoWindow? = null,
): WindowGeometryProviderScope {
    val scale = TaoMonitors.referenceScale(window)
    val screen = Screen(TaoMonitors.forWindow(window), scale)
    // `Current` must read the geometry already *requested*, not the native
    // rectangle: applies are asynchronous, so two back-to-back requests —
    // `requestSize` then `requestPosition`, whose implicit size is Current —
    // would otherwise have the second one read the not-yet-resized window and
    // revert the first. The v1 state is that pending truth wherever it has one
    // (an Absolute position, a specified size); the native window fills the
    // axes it does not, and everything before the window exists.
    val native = window?.outerBoundsDpOrNull()
    val pending = approximateOuterRect(currentPosition, currentInnerSize.plusInsets(decorationSize))
    val bounds =
        when {
            native == null -> pending
            pending == null -> native
            else -> {
                val left = if (currentPosition is WindowPosition.Absolute) pending.left else native.left
                val top = if (currentPosition is WindowPosition.Absolute) pending.top else native.top
                val width = if (currentInnerSize.width.isSpecified) pending.size.width else native.size.width
                val height = if (currentInnerSize.height.isSpecified) pending.size.height else native.size.height
                DpRect(left = left, top = top, right = left + width, bottom = top + height)
            }
        }
            ?: DpRect(
                left = screen.availableBounds.left,
                top = screen.availableBounds.top,
                right = screen.availableBounds.left + DEFAULT_WINDOW_SIZE.width,
                bottom = screen.availableBounds.top + DEFAULT_WINDOW_SIZE.height,
            )
    // A dialog's owner is wired at the platform level, so the overload that
    // opens it hands the owner over; popup overlays know theirs natively.
    val parent = parentWindow ?: window?.popupParent
    return WindowGeometryProviderScope(
        windowMetrics = WindowMetrics(screen = screen, bounds = bounds, insets = splitInsets(decorationSize)),
        parentWindowMetrics = parent?.let { parentMetrics(it, scale) },
        scale = scale,
        measureContent = window?.contentMeasurerOrNull(),
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

/**
 * Corrects the one geometry the creation path cannot get right on its own.
 *
 * A v2 bounds provider returns the *outer* rectangle, but before the window
 * exists its decoration insets are unknown, so the initial outer size had to be
 * applied as the inner size — a natively decorated frame then comes out larger
 * by its chrome. Once the window is mapped the insets are measurable: if the
 * inner size is still the initial request, shrink it by them so the outer
 * rectangle matches what was asked. A window the user has already resized
 * (v1 size no longer the initial one) is left alone.
 */
private suspend fun correctInitialOuterSize(
    window: TaoWindow,
    initialOuterSize: DpSize?,
    currentSize: () -> DpSize,
    applySize: (DpSize) -> Unit,
) {
    val requested = initialOuterSize ?: return
    if (!requested.width.isSpecified || !requested.height.isSpecified) return
    repeat(OBSERVED_BOUNDS_RETRIES) { attempt ->
        val outer = window.outerBoundsDpOrNull()
        if (outer != null && outer.size.width.value > 1f && outer.size.height.value > 1f) {
            if (currentSize() != requested) return
            val insets = window.decorationInsets(requested)
            if (insets.width.value > 0f || insets.height.value > 0f) {
                applySize(requested.minusInsets(insets))
            }
            return
        }
        if (attempt < OBSERVED_BOUNDS_RETRIES - 1) delay(OBSERVED_BOUNDS_RETRY_MS)
    }
}

/**
 * Suspends until [window] has actually left its maximized / fullscreen
 * placement: the flag is down *and* the outer rectangle has stopped moving for
 * [PLACEMENT_SETTLED_POLLS] consecutive polls. The flag alone is not enough —
 * macOS clears `isZoomed` at the start of the un-zoom animation, whose final
 * frame would still land on top of anything applied meanwhile. Bounded by
 * [PLACEMENT_RESTORE_RETRIES] polls; gives up silently, and the geometry is
 * then applied as before.
 */
private suspend fun awaitFloating(window: TaoWindow) {
    var previous: List<Long>? = null
    var stable = 0
    repeat(PLACEMENT_RESTORE_RETRIES) {
        if (!window.isMaximized && !window.isFullscreen) {
            val current = window.outerBoundsPx()?.toList()
            stable = if (current != null && current == previous) stable + 1 else 0
            previous = current
            if (stable >= PLACEMENT_SETTLED_POLLS) return
        } else {
            stable = 0
            previous = null
        }
        delay(PLACEMENT_RESTORE_RETRY_MS)
    }
}

/**
 * Apply-and-confirm for geometry applied right after leaving a placement.
 *
 * The flag-and-stillness wait above cannot see an un-zoom animation that has
 * not started yet: AppKit can pause between clearing `isZoomed` and animating,
 * and its final frame then lands on top of whatever was applied meanwhile. So
 * after applying, watch the window settle and compare it with the target; if
 * the animation put the old frame back, the v1 state now carries that observed
 * size, and re-assigning the target re-runs the apply. Bounded attempts; a
 * window manager that refuses the size wins.
 */
private suspend fun confirmBounds(
    window: TaoWindow,
    v1: WindowStateV1,
    target: ResolvedV2Bounds,
) {
    repeat(CONFIRM_ATTEMPTS) {
        awaitSettled(window)
        val outer = window.outerBoundsDpOrNull() ?: return
        val insets = window.decorationInsets(v1.size)
        val sizeOk =
            !target.size.width.isSpecified ||
                !target.size.height.isSpecified ||
                (
                    kotlin.math.abs(
                        (outer.size.width - insets.width - target.size.width).value,
                    ) <= CONFIRM_TOLERANCE_DP &&
                        kotlin.math.abs((outer.size.height - insets.height - target.size.height).value) <=
                        CONFIRM_TOLERANCE_DP
                )
        val position = target.position
        val positionOk =
            position !is WindowPosition.Absolute ||
                (
                    kotlin.math.abs((outer.left - position.x).value) <= CONFIRM_TOLERANCE_DP &&
                        kotlin.math.abs((outer.top - position.y).value) <= CONFIRM_TOLERANCE_DP
                )
        if (sizeOk && positionOk) return
        // A frame that went back to the zoomed size means the native placement
        // reasserted itself; clear it before re-applying.
        if (window.isMaximized || window.isFullscreen) restoreAndAwaitFloating(window)
        v1.size = target.size
        v1.position = target.position
    }
}

/**
 * Clears a native maximized / fullscreen state the v1 bookkeeping does not
 * know about (its `applied.placement` already reads Floating, so its own
 * effect will not act), then waits for the window to leave it.
 */
private suspend fun restoreAndAwaitFloating(window: TaoWindow) {
    if (window.isFullscreen) window.setFullscreen(false)
    if (window.isMaximized) window.setMaximized(false)
    awaitFloating(window)
}

/** Waits until the outer rectangle holds still for [PLACEMENT_SETTLED_POLLS] polls. */
private suspend fun awaitSettled(window: TaoWindow) {
    var previous: List<Long>? = null
    var stable = 0
    repeat(PLACEMENT_RESTORE_RETRIES) {
        val current = window.outerBoundsPx()?.toList()
        stable = if (current != null && current == previous) stable + 1 else 0
        previous = current
        if (stable >= PLACEMENT_SETTLED_POLLS) return
        delay(PLACEMENT_RESTORE_RETRY_MS)
    }
}

private const val PLACEMENT_RESTORE_RETRIES = 60
private const val PLACEMENT_RESTORE_RETRY_MS = 50L
private const val PLACEMENT_SETTLED_POLLS = 3
private const val CONFIRM_ATTEMPTS = 3
private const val CONFIRM_TOLERANCE_DP = 2f

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

// ── Shared geometry helpers ─────────────────────────────────────────────────

/** Native geometry is only readable once Tao has realized the window. */
private const val OBSERVED_BOUNDS_RETRIES = 20
private const val OBSERVED_BOUNDS_RETRY_MS = 50L
private const val RECT_ARRAY_SIZE = 4

internal data class ResolvedV2Bounds(
    val position: WindowPosition,
    val size: DpSize,
)

/**
 * Signals every native move / resize of [window].
 *
 * Keying the observed-geometry effect on the v1 state alone is not enough: the
 * window manager moves and resizes a window without the v1 state changing —
 * the initial geometry apply itself lands *after* that effect has run — which
 * would leave `bounds` reporting a stale rectangle for the rest of the window's
 * life.
 *
 * A conflated channel rather than snapshot state: the callbacks fire on the
 * event-loop thread from inside the platform's resize handling, which can be
 * *within* a Compose measure/layout pass. Writing snapshot state there
 * re-enters layout through the recomposition it schedules
 * ("performMeasureAndLayout called during measure layout"); a channel send
 * carries no such obligation, and the receiving coroutine resumes on the
 * dispatcher once the native frame has unwound.
 *
 * One registration per window instance ([LaunchedEffect] keyed on the window),
 * matching the listeners' append-only contract.
 */
@Composable
internal fun rememberNativeGeometrySignal(window: TaoWindow?): Channel<Unit> {
    val signal = remember(window) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(window) {
        val target = window ?: return@LaunchedEffect
        target.onMoved { _, _ -> signal.trySend(Unit) }
        target.onResized { _, _ -> signal.trySend(Unit) }
    }
    return signal
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
internal suspend fun observedRect(
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
internal fun approximateOuterRect(
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
internal fun TaoWindow?.decorationInsets(innerSize: DpSize): DpSize {
    val window = this ?: return DpSize.Zero
    if (!innerSize.width.isSpecified || !innerSize.height.isSpecified) return DpSize.Zero
    val outer = window.outerBoundsDpOrNull() ?: return DpSize.Zero
    return DpSize(
        width = (outer.right - outer.left - innerSize.width).coerceAtLeast(0.dp),
        height = (outer.bottom - outer.top - innerSize.height).coerceAtLeast(0.dp),
    )
}

/** Inner size → outer (v2) size. Unspecified axes stay unspecified. */
internal fun DpSize.plusInsets(insets: DpSize): DpSize =
    DpSize(
        width = if (width.isSpecified) width + insets.width else width,
        height = if (height.isSpecified) height + insets.height else height,
    )

/** Outer (v2) size → inner size. Unspecified axes stay unspecified. */
internal fun DpSize.minusInsets(insets: DpSize): DpSize =
    DpSize(
        width = if (width.isSpecified) (width - insets.width).coerceAtLeast(0.dp) else width,
        height = if (height.isSpecified) (height - insets.height).coerceAtLeast(0.dp) else height,
    )

internal fun TaoWindow.outerBoundsDpOrNull(): DpRect? {
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
