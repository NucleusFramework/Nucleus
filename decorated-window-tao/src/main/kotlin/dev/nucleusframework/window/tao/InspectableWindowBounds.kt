@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.v2.WindowBoundsProvider
import androidx.compose.ui.window.v2.WindowGeometryProviderScope
import androidx.compose.ui.window.v2.DialogState as DialogStateV2
import androidx.compose.ui.window.v2.WindowState as WindowStateV2

/**
 * Tao-safe [WindowBoundsProvider] that stores size and position as named
 * fields.
 *
 * Compose's `WindowBoundsProvider(sizeProvider, positionProvider)` factory
 * captures those providers in a hidden lambda that dereferences an AWT-backed
 * `WindowGeometryProviderScope`. Tao has no AWT window to build that scope
 * from, so every provider routed through it — including the ones
 * `WindowState.requestSize`, `WindowState.requestPosition` and
 * `rememberWindowStateWithBounds` create internally — is inert on this
 * backend. Use [requestInspectableBounds], this factory,
 * `WindowBoundsProvider.Absolute` or `WindowState.requestBounds(DpRect)`
 * instead.
 *
 * A null [size] means "keep the current size" (platform default 800×600
 * before the window exists). A null [position] means "keep the current
 * position" ([WindowPosition.PlatformDefault] or dialog-centred before the
 * window exists).
 */
public fun inspectableWindowBounds(
    size: DpSize? = null,
    position: WindowPosition? = null,
): WindowBoundsProvider = InspectableWindowBoundsProvider(size, position)

/**
 * Tao-safe replacement for `WindowState.requestSize` / `requestPosition`.
 *
 * Those two build a `WindowBoundsProvider(sizeProvider, positionProvider)`
 * internally, and that factory is inert on this backend — see
 * [inspectableWindowBounds]. This applies the same request through a provider
 * Tao can evaluate. A `null` argument keeps the current value, so passing only
 * [size] resizes without moving the window and vice versa.
 */
public fun WindowStateV2.requestInspectableBounds(
    size: DpSize? = null,
    position: WindowPosition? = null,
) {
    requestBounds(inspectableWindowBounds(size, position))
}

/** [requestInspectableBounds] for a v2 dialog state. */
public fun DialogStateV2.requestInspectableBounds(
    size: DpSize? = null,
    position: WindowPosition? = null,
) {
    requestBounds(inspectableWindowBounds(size, position))
}

internal class InspectableWindowBoundsProvider(
    val size: DpSize?,
    val position: WindowPosition?,
) : WindowBoundsProvider {
    override fun WindowGeometryProviderScope.getBounds(): DpRect {
        error("Tao evaluates inspectable bounds without a WindowGeometryProviderScope")
    }
}
