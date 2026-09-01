@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.v2.WindowBoundsProvider
import androidx.compose.ui.window.v2.WindowGeometryProviderScope

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
 * backend. Use this factory, `WindowBoundsProvider.Absolute` or
 * `WindowState.requestBounds(DpRect)` instead.
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

internal class InspectableWindowBoundsProvider(
    val size: DpSize?,
    val position: WindowPosition?,
) : WindowBoundsProvider {
    override fun WindowGeometryProviderScope.getBounds(): DpRect {
        error("Tao evaluates inspectable bounds without a WindowGeometryProviderScope")
    }
}
