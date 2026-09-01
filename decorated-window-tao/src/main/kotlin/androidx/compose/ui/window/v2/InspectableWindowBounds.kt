@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.ui.window.v2

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition

/**
 * Tao-safe [WindowBoundsProvider] that stores size and position as named
 * fields.
 *
 * Compose's `WindowBoundsProvider(sizeProvider, positionProvider)` factory
 * captures those providers in a hidden lambda. Evaluating that lambda needs
 * either an AWT `WindowGeometryProviderScope` (XAWT deadlock on the Tao
 * thread) or reflection (breaks GraalVM native-image). Use this factory
 * instead when targeting Tao.
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
