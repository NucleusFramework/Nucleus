// Compose marks ScrollConfig / LocalScrollConfig `internal`, but their JVM
// symbols are public. Suppressing the visibility checks lets us reference and
// override them with direct (non-reflective) bytecode calls — fully GraalVM
// native-image compatible. All access to these internals is isolated to this
// file.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package dev.nucleusframework.window.tao.event

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * Windows wheel scrolling identical to stock Compose Desktop
 * (`DesktopScrollable.desktop.kt` → `WindowsWinUIConfig`):
 *
 *  - each wheel unit scrolls a FRACTION of the viewport (`bounds / 20`),
 *    times the OS lines-per-notch policy (default 3) — a notch moves a
 *    natural amount regardless of list length;
 *  - smooth scrolling stays ON: `MouseWheelScrollingLogic` tweens each
 *    accumulated delta (~100 ms animation), so a fast spin sums pending
 *    notches into one longer, quicker sweep — that tween IS the whole
 *    "inertia" feel;
 *  - the wheel is NEVER treated as precise (Windows animates even
 *    free-spinning wheels and touchpad fractional ticks), so every event
 *    stays on the smooth path;
 *  - no synthetic fling: Compose Desktop's default `FlingBehavior` has
 *    `shouldBeTriggeredByMouseWheel = false`, and nothing is added on top.
 *
 * The only reason Compose's own `LocalScrollConfig` default can't be used
 * directly is the hardcoded [WHEEL_LINES_PER_NOTCH]: the Tao backend emits
 * the raw notch count and reports `scrollAmount = 1` (it deliberately does
 * not apply `SPI_GETWHEELSCROLLLINES`), so the standard three-lines-per-notch
 * policy is applied here.
 */
internal class TaoWindowsScrollConfig : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(
        event: PointerEvent,
        bounds: IntSize,
    ): Offset {
        // A trackpad pan (#706) is already in pixels — stock configs answer
        // the same (CMP-1610), which `transformable`'s Ctrl+pan zoom reads.
        if (event.type == PointerEventType.PanMove) {
            return -event.changes.fold(Offset.Zero) { acc, change -> acc + change.panOffset }
        }
        val totalScrollDelta =
            event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
        // WindowsWinUIConfig formula, negated for its sign convention (it
        // multiplies by `-scrollAmount`; the notch policy is applied here).
        return Offset(
            x = totalScrollDelta.x * (bounds.width / VIEWPORT_FRACTION),
            y = totalScrollDelta.y * (bounds.height / VIEWPORT_FRACTION),
        ) * -WHEEL_LINES_PER_NOTCH
    }

    override fun isPreciseWheelScroll(event: PointerEvent): Boolean = false

    companion object {
        /** WindowsWinUIConfig: one wheel unit scrolls bounds/20. */
        const val VIEWPORT_FRACTION = 20f

        /** OS lines-per-notch default (SPI_GETWHEELSCROLLLINES). */
        const val WHEEL_LINES_PER_NOTCH = 3f
    }
}

/** Installs [TaoWindowsScrollConfig] for [content] via Compose's `LocalScrollConfig`. */
@Composable
internal fun ProvideTaoWindowsScrollConfig(content: @Composable () -> Unit) {
    val config = remember { TaoWindowsScrollConfig() }
    CompositionLocalProvider(LocalScrollConfig provides config, content = content)
}
