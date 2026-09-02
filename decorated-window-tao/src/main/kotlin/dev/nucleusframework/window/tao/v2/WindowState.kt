@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions")

package dev.nucleusframework.window.tao.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.size
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.channels.Channel

/**
 * Creates a [WindowState] remembered across compositions and saved across
 * configuration changes.
 *
 * ## Migrating from the Compose window API v2
 *
 * This package mirrors `androidx.compose.ui.window.v2` member for member, with
 * one difference: it works on the Tao backend. Change the import and nothing
 * else:
 *
 * ```kotlin
 * // import androidx.compose.ui.window.v2.rememberWindowState
 * import dev.nucleusframework.window.tao.v2.rememberWindowState
 *
 * val state = rememberWindowState(
 *     initialScreenProvider = WindowScreenProvider.Primary,
 *     initialBoundsProvider = WindowBoundsProvider(
 *         sizeProvider = WindowSizeProvider.Fixed(1200.dp, 800.dp),
 *         positionProvider = WindowPositionProvider.CenteredOnScreen,
 *     ),
 * )
 * DecoratedWindow(onCloseRequest = ::exitApplication, state = state) { }
 *
 * state.requestScreen { screens.last() }   // actually moves the window
 * ```
 *
 * ### Why a clone exists
 *
 * Compose's v2 geometry API is hard-wired to AWT: `Screen` wraps a
 * `java.awt.GraphicsDevice` and reads its insets through
 * `Toolkit.getDefaultToolkit()`, and `WindowGeometryProviderScope` takes a
 * `java.awt.Window` that must already be displayable. The Tao backend has
 * neither — it is a native, no-AWT, GraalVM-native-image-first window shell —
 * so every provider that touches the scope is inert there, and `requestScreen`
 * has no screen list to choose from. Reflection is not an option in a
 * native-image-compatible runtime, and faking a `GraphicsDevice` would still
 * boot the AWT toolkit through `Screen.insets`.
 *
 * The clone swaps those two AWT anchors for [dev.nucleusframework.window.tao.TaoMonitors]
 * and [dev.nucleusframework.window.tao.TaoWindow], and keeps every name and
 * signature identical. When Compose decouples its own types from AWT, deleting
 * this package restores the upstream import with no other source change.
 *
 * @param initialScreenProvider Provides the screen the window is first placed on.
 * @param initialPlacement The initial placement of the window.
 * @param initialBoundsProvider Provides the initial bounds of the window.
 * @param initiallyMinimized Whether the window starts minimized.
 */
@Composable
public fun rememberWindowState(
    initialScreenProvider: WindowScreenProvider = WindowScreenProvider.Default,
    initialPlacement: WindowPlacement = WindowPlacement.Floating,
    initialBoundsProvider: WindowBoundsProvider = WindowBoundsProvider.Default,
    initiallyMinimized: Boolean = false,
): WindowState =
    rememberSaveable(saver = WindowState.Saver) {
        WindowState(
            initialScreenProvider = initialScreenProvider,
            initialPlacement = initialPlacement,
            initialBoundsProvider = initialBoundsProvider,
            initiallyMinimized = initiallyMinimized,
        )
    }

/**
 * Creates a [WindowState] remembered across compositions, from a plain position
 * and size.
 *
 * @param initialPosition The initial position; platform default if `null`.
 * @param initialSize The initial size; 800×600 if `null`.
 * @param initiallyMinimized Whether the window starts minimized.
 */
@Composable
public fun rememberWindowStateWithBounds(
    initialPosition: DpOffset? = null,
    initialSize: DpSize? = null,
    initiallyMinimized: Boolean = false,
): WindowState =
    rememberSaveable(saver = WindowState.Saver) {
        WindowStateWithBounds(
            initialPosition = initialPosition,
            initialSize = initialSize,
            initiallyMinimized = initiallyMinimized,
        )
    }

/**
 * Creates a [WindowState] with the given initial values.
 *
 * @param initialScreenProvider Provides the screen the window is first placed on.
 * @param initialPlacement The initial placement of the window.
 * @param initialBoundsProvider Provides the initial bounds of the window.
 * @param initiallyMinimized Whether the window starts minimized.
 */
@Suppress("FunctionNaming")
public fun WindowState(
    initialScreenProvider: WindowScreenProvider = WindowScreenProvider.Default,
    initialPlacement: WindowPlacement = WindowPlacement.Floating,
    initialBoundsProvider: WindowBoundsProvider = WindowBoundsProvider.Default,
    initiallyMinimized: Boolean = false,
): WindowState =
    WindowState.createUninitialized().apply {
        requestScreen(initialScreenProvider)
        requestPlacement(initialPlacement)
        requestBounds(initialBoundsProvider)
        requestMinimized(initiallyMinimized)
    }

/**
 * Creates a [WindowState] with the given initial position and size.
 *
 * @param initialPosition The initial position; platform default if `null`.
 * @param initialSize The initial size; 800×600 if `null`.
 * @param initiallyMinimized Whether the window starts minimized.
 */
@Suppress("FunctionNaming")
public fun WindowStateWithBounds(
    initialPosition: DpOffset? = null,
    initialSize: DpSize? = null,
    initiallyMinimized: Boolean = false,
): WindowState =
    WindowState(
        initialBoundsProvider =
            WindowBoundsProvider(
                sizeProvider = initialSize?.let { WindowSizeProvider.Fixed(it) } ?: WindowSizeProvider.Default,
                positionProvider =
                    initialPosition?.let { WindowPositionProvider.Absolute(it) }
                        ?: WindowPositionProvider.Default,
            ),
        initiallyMinimized = initiallyMinimized,
    )

/**
 * A state object that can be hoisted to control and observe window attributes
 * (screen, size, position, placement).
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowState` — see
 * [rememberWindowState] for what that means and how to migrate.
 *
 * Requests are applied asynchronously by the window that consumes this state;
 * observed values ([bounds], [screenId], [placement], [isMinimized]) only
 * become readable once the window has been shown at least once, which
 * [isInitialized] reports.
 */
@Stable
public class WindowState private constructor(
    isInitialized: Boolean,
    screenId: String?,
    placement: WindowPlacement?,
    isMinimized: Boolean?,
    bounds: DpRect?,
) {
    internal constructor(
        screenId: String,
        placement: WindowPlacement,
        isMinimized: Boolean,
        bounds: DpRect,
    ) : this(
        isInitialized = true,
        screenId = screenId,
        placement = placement,
        isMinimized = isMinimized,
        bounds = bounds,
    )

    init {
        bounds?.requireReal()
    }

    /** Whether the window has become visible at least once. */
    public var isInitialized: Boolean by mutableStateOf(isInitialized)
        internal set

    internal var screenIdOrNull: String? by mutableStateOf(screenId)

    /**
     * The id of the screen the window is currently on; throws
     * [IllegalStateException] before [isInitialized].
     */
    public val screenId: String
        get() = screenIdOrNull ?: notInitialized("screenId")

    internal val screenRequests = Channel<WindowScreenProvider>(Channel.CONFLATED)

    /** Requests to move the window to the screen the provider picks. */
    public fun requestScreen(screenProvider: WindowScreenProvider) {
        screenRequests.trySend(screenProvider)
    }

    internal var placementOrNull: WindowPlacement? by mutableStateOf(placement)

    /**
     * The placement of the window; throws [IllegalStateException] before
     * [isInitialized].
     */
    public val placement: WindowPlacement
        get() = placementOrNull ?: notInitialized("placement")

    internal val placementRequests = Channel<WindowPlacement>(Channel.CONFLATED)

    /** Requests to set the placement of the window. */
    public fun requestPlacement(placement: WindowPlacement) {
        placementRequests.trySend(placement)
    }

    internal var minimizedOrNull: Boolean? by mutableStateOf(isMinimized)

    /**
     * Whether the window is minimized; throws [IllegalStateException] before
     * [isInitialized].
     */
    public val isMinimized: Boolean
        get() = minimizedOrNull ?: notInitialized("isMinimized")

    internal val minimizedRequests = Channel<Boolean>(Channel.CONFLATED)

    /** Requests to minimize or restore the window. */
    public fun requestMinimized(value: Boolean) {
        minimizedRequests.trySend(value)
    }

    internal var boundsOrNull: DpRect? by mutableStateOf(bounds)

    /**
     * The current bounds of the window, decorations included; throws
     * [IllegalStateException] before [isInitialized].
     */
    public val bounds: DpRect
        get() = boundsOrNull ?: notInitialized("bounds")

    /** The current position of the window; throws before [isInitialized]. */
    public val position: DpOffset
        get() = boundsOrNull?.topLeft ?: notInitialized("position")

    /** The current size of the window; throws before [isInitialized]. */
    public val size: DpSize
        get() = boundsOrNull?.size ?: notInitialized("size")

    internal val boundsRequests = Channel<WindowBoundsProvider>(Channel.UNLIMITED)

    /**
     * Requests to set the bounds of the window via a [WindowBoundsProvider].
     *
     * Applying bounds to a window that is not [WindowPlacement.Floating] also
     * makes it floating.
     */
    public fun requestBounds(boundsProvider: WindowBoundsProvider) {
        boundsRequests.trySend(boundsProvider)
    }

    /** Requests to set the bounds of the window from a scoped function. */
    public fun requestBounds(boundsProvider: WindowGeometryProviderScope.() -> DpRect) {
        boundsRequests.trySend(WindowBoundsProvider(boundsProvider))
    }

    /** Requests to set the bounds of the window. Same as [WindowBoundsProvider.Absolute]. */
    public fun requestBounds(bounds: DpRect) {
        boundsRequests.trySend(WindowBoundsProvider.Absolute(bounds))
    }

    /** Requests to set the position of the window via a [WindowPositionProvider]. */
    public fun requestPosition(positionProvider: WindowPositionProvider) {
        boundsRequests.trySend(WindowBoundsProvider(positionProvider = positionProvider))
    }

    /** Requests to move the window to [position]. */
    public fun requestPosition(position: DpOffset) {
        requestPosition(WindowPositionProvider.Absolute(position))
    }

    /** Requests to move the window to ([x], [y]). */
    public fun requestPosition(
        x: Dp,
        y: Dp,
    ) {
        requestPosition(WindowPositionProvider.Absolute(x, y))
    }

    /** Requests to set the size of the window via a [WindowSizeProvider]. */
    public fun requestSize(sizeProvider: WindowSizeProvider) {
        boundsRequests.trySend(WindowBoundsProvider(sizeProvider = sizeProvider))
    }

    /** Requests to resize the window to [size]. */
    public fun requestSize(size: DpSize) {
        requestSize(WindowSizeProvider.Fixed(size))
    }

    /** Requests to resize the window to [width] × [height]. */
    public fun requestSize(
        width: Dp,
        height: Dp,
    ) {
        requestSize(WindowSizeProvider.Fixed(width, height))
    }

    /** Factories and the [Saver]. */
    public companion object {
        internal fun createUninitialized(): WindowState =
            WindowState(
                isInitialized = false,
                screenId = null,
                placement = null,
                isMinimized = null,
                bounds = null,
            )

        /** A [Saver] implementation for [WindowState]. */
        public val Saver: Saver<WindowState, Any> =
            listSaver(
                save = {
                    if (!it.isInitialized) {
                        emptyList()
                    } else {
                        val bounds = it.bounds
                        listOf(
                            it.screenId,
                            it.placement.ordinal,
                            it.isMinimized,
                            bounds.top.value,
                            bounds.left.value,
                            bounds.right.value,
                            bounds.bottom.value,
                        )
                    }
                },
                restore = { state ->
                    if (state.isEmpty()) {
                        null
                    } else {
                        WindowState(
                            screenId = state[0] as String,
                            placement = WindowPlacement.entries[state[1] as Int],
                            isMinimized = state[2] as Boolean,
                            bounds =
                                DpRect(
                                    top = Dp(state[3] as Float),
                                    left = Dp(state[4] as Float),
                                    right = Dp(state[5] as Float),
                                    bottom = Dp(state[6] as Float),
                                ),
                        )
                    }
                },
            )
    }
}

internal fun notInitialized(propertyName: String): Nothing =
    throw IllegalStateException(
        "Can't read $propertyName before the window has been made visible; use isInitialized to check.",
    )
