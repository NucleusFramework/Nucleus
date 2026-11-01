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
import kotlinx.coroutines.channels.Channel

/**
 * Creates a [DialogState] remembered across compositions and saved across
 * configuration changes.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.rememberDialogState` —
 * see [rememberWindowState] for the migration story.
 *
 * @param initialScreenProvider Provides the screen the dialog is first placed on.
 * @param initialBoundsProvider Provides the initial bounds of the dialog.
 */
@Composable
public fun rememberDialogState(
    initialScreenProvider: WindowScreenProvider = WindowScreenProvider.Default,
    initialBoundsProvider: WindowBoundsProvider = WindowBoundsProvider.Default,
): DialogState =
    rememberSaveable(saver = DialogState.Saver) {
        DialogState(
            initialScreenProvider = initialScreenProvider,
            initialBoundsProvider = initialBoundsProvider,
        )
    }

/**
 * Creates a [DialogState] remembered across compositions, from a plain position
 * and size.
 *
 * @param initialPosition The initial position; centred on the screen if `null`.
 * @param initialSize The initial size; 800×600 if `null`.
 */
@Composable
public fun rememberDialogStateWithBounds(
    initialPosition: DpOffset? = null,
    initialSize: DpSize? = null,
): DialogState =
    rememberSaveable(saver = DialogState.Saver) {
        DialogStateWithBounds(initialPosition = initialPosition, initialSize = initialSize)
    }

/**
 * Creates a [DialogState] with the given initial values.
 *
 * @param initialScreenProvider Provides the screen the dialog is first placed on.
 * @param initialBoundsProvider Provides the initial bounds of the dialog.
 */
@Suppress("FunctionNaming")
public fun DialogState(
    initialScreenProvider: WindowScreenProvider = WindowScreenProvider.Default,
    initialBoundsProvider: WindowBoundsProvider = WindowBoundsProvider.Default,
): DialogState =
    DialogState.createUninitialized().apply {
        requestScreen(initialScreenProvider)
        requestBounds(initialBoundsProvider)
    }

/**
 * Creates a [DialogState] with the given initial position and size.
 *
 * @param initialPosition The initial position; centred on the screen if `null`.
 * @param initialSize The initial size; 800×600 if `null`.
 */
@Suppress("FunctionNaming")
public fun DialogStateWithBounds(
    initialPosition: DpOffset? = null,
    initialSize: DpSize? = null,
): DialogState =
    DialogState(
        initialBoundsProvider =
            WindowBoundsProvider(
                sizeProvider = initialSize?.let { WindowSizeProvider.Fixed(it) } ?: WindowSizeProvider.Default,
                positionProvider =
                    initialPosition?.let { WindowPositionProvider.Absolute(it) }
                        ?: WindowPositionProvider.CenteredOnScreen,
            ),
    )

/**
 * A state object that can be hoisted to control and observe dialog attributes
 * (screen, size, position).
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.DialogState`.
 */
@Stable
public class DialogState private constructor(
    isInitialized: Boolean,
    screenId: String?,
    bounds: DpRect?,
) {
    internal constructor(screenId: String, bounds: DpRect) : this(
        isInitialized = true,
        screenId = screenId,
        bounds = bounds,
    )

    init {
        bounds?.requireReal()
    }

    /** Whether the dialog has become visible at least once. */
    public var isInitialized: Boolean by mutableStateOf(isInitialized)
        internal set

    internal var screenIdOrNull: String? by mutableStateOf(screenId)

    /**
     * The id of the screen the dialog is currently on; throws
     * [IllegalStateException] before [isInitialized].
     */
    public val screenId: String
        get() = screenIdOrNull ?: notInitializedDialog("screenId")

    internal val screenRequests = Channel<WindowScreenProvider>(Channel.CONFLATED)

    /** Requests to move the dialog to the screen the provider picks. */
    public fun requestScreen(screenProvider: WindowScreenProvider) {
        screenRequests.trySend(screenProvider)
    }

    internal var boundsOrNull: DpRect? by mutableStateOf(bounds)

    /**
     * The current bounds of the dialog, decorations included; throws
     * [IllegalStateException] before [isInitialized].
     */
    public val bounds: DpRect
        get() = boundsOrNull ?: notInitializedDialog("bounds")

    /** The current position of the dialog; throws before [isInitialized]. */
    public val position: DpOffset
        get() = boundsOrNull?.topLeft ?: notInitializedDialog("position")

    /** The current size of the dialog; throws before [isInitialized]. */
    public val size: DpSize
        get() = boundsOrNull?.size ?: notInitializedDialog("size")

    internal val boundsRequests = Channel<WindowBoundsProvider>(Channel.UNLIMITED)

    /** Requests to set the bounds of the dialog via a [WindowBoundsProvider]. */
    public fun requestBounds(boundsProvider: WindowBoundsProvider) {
        boundsRequests.trySend(boundsProvider)
    }

    /** Requests to set the bounds of the dialog from a scoped function. */
    public fun requestBounds(boundsProvider: WindowGeometryProviderScope.() -> DpRect) {
        boundsRequests.trySend(WindowBoundsProvider(boundsProvider))
    }

    /** Requests to set the bounds of the dialog. Same as [WindowBoundsProvider.Absolute]. */
    public fun requestBounds(bounds: DpRect) {
        boundsRequests.trySend(WindowBoundsProvider.Absolute(bounds))
    }

    /** Requests to set the position of the dialog via a [WindowPositionProvider]. */
    public fun requestPosition(positionProvider: WindowPositionProvider) {
        boundsRequests.trySend(WindowBoundsProvider(positionProvider = positionProvider))
    }

    /** Requests to move the dialog to [position]. */
    public fun requestPosition(position: DpOffset) {
        requestPosition(WindowPositionProvider.Absolute(position))
    }

    /** Requests to move the dialog to ([x], [y]). */
    public fun requestPosition(
        x: Dp,
        y: Dp,
    ) {
        requestPosition(WindowPositionProvider.Absolute(x, y))
    }

    /** Requests to set the size of the dialog via a [WindowSizeProvider]. */
    public fun requestSize(sizeProvider: WindowSizeProvider) {
        boundsRequests.trySend(WindowBoundsProvider(sizeProvider = sizeProvider))
    }

    /** Requests to resize the dialog to [size]. */
    public fun requestSize(size: DpSize) {
        requestSize(WindowSizeProvider.Fixed(size))
    }

    /** Requests to resize the dialog to [width] × [height]. */
    public fun requestSize(
        width: Dp,
        height: Dp,
    ) {
        requestSize(WindowSizeProvider.Fixed(width, height))
    }

    /** Factories and the [Saver]. */
    public companion object {
        internal fun createUninitialized(): DialogState =
            DialogState(isInitialized = false, screenId = null, bounds = null)

        /** A [Saver] implementation for [DialogState]. */
        public val Saver: Saver<DialogState, Any> =
            listSaver(
                save = {
                    if (!it.isInitialized) {
                        emptyList()
                    } else {
                        val bounds = it.bounds
                        listOf(
                            it.screenId,
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
                        DialogState(
                            screenId = state[0] as String,
                            bounds =
                                DpRect(
                                    top = Dp(state[1] as Float),
                                    left = Dp(state[2] as Float),
                                    right = Dp(state[3] as Float),
                                    bottom = Dp(state[4] as Float),
                                ),
                        )
                    }
                },
            )
    }
}

private fun notInitializedDialog(propertyName: String): Nothing =
    throw IllegalStateException(
        "Can't read $propertyName before the dialog has been made visible; use isInitialized to check.",
    )
