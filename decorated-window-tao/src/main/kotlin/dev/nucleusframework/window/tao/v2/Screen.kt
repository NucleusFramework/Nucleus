@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.v2

import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpInsets
import androidx.compose.ui.unit.DpRect
import dev.nucleusframework.window.tao.TaoMonitor
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow

/**
 * Represents a screen (a graphical device on which windows can be rendered).
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.Screen`: same members,
 * backed by [TaoMonitor] instead of `java.awt.GraphicsDevice`. Migrating is one
 * import — see the package overview on [WindowState].
 *
 * Unlike the Compose original, a [Screen] holds no native handle, so keeping a
 * reference is harmless. It is still a *snapshot*: a screen that has been
 * unplugged keeps reporting its last known geometry, and [id] no longer
 * resolves through [TaoMonitors.byId].
 */
@Immutable
public class Screen internal constructor(
    internal val monitor: TaoMonitor,
    /**
     * Scale factor the [DpRect] members are expressed in. Every rectangle the
     * window API produces has to share one scale, so this is the scale of the
     * window being positioned rather than the monitor's own — they differ on a
     * mixed-DPI setup. See [TaoMonitor.boundsDp].
     */
    internal val referenceScale: Float,
) {
    /** The identifier of the screen. See [TaoMonitor.id] for its per-platform shape. */
    public val id: String get() = monitor.id

    /**
     * Human-readable display name, for a screen picker UI.
     *
     * Not part of the Compose API — `Screen.id` is the only identity there, and
     * on Windows it is a device path (`\\.\DISPLAY1`) nobody wants to read.
     */
    public val name: String get() = monitor.name

    /**
     * The bounds of the screen in the coordinate system of all screens.
     *
     * Coordinates may be negative: a screen can sit to the left of or above the
     * primary one.
     */
    public val bounds: DpRect get() = monitor.boundsDp(referenceScale)

    /** The insets of the screen — taskbar, menu bar, dock, panels. */
    public val insets: DpInsets
        get() {
            val full = bounds
            val available = availableBounds
            return DpInsets(
                top = available.top - full.top,
                left = available.left - full.left,
                bottom = full.bottom - available.bottom,
                right = full.right - available.right,
            )
        }

    /** The bounds of the screen excluding the insets. */
    public val availableBounds: DpRect get() = monitor.workAreaDp(referenceScale)

    /** Whether this is the primary screen. */
    public val isPrimary: Boolean get() = monitor.isPrimary

    override fun equals(other: Any?): Boolean = this === other || (other is Screen && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Screen $id"
}

/**
 * The scope in which a [WindowScreenProvider] is evaluated.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowScreenProviderScope`.
 */
public class WindowScreenProviderScope internal constructor(
    /** The list of screens on which the window can be placed. Never empty. */
    public val screens: List<Screen>,
    /** The default screen, on which the window should typically be placed. */
    public val defaultScreen: Screen,
) {
    /** The primary screen, or [defaultScreen] when no screen claims the flag. */
    public val primaryScreen: Screen
        get() = screens.firstOrNull { it.isPrimary } ?: defaultScreen
}

/**
 * Provides the screen on which the window will be placed.
 *
 * AWT-free drop-in for `androidx.compose.ui.window.v2.WindowScreenProvider` —
 * and, unlike it, actually applied by the Tao backend.
 */
public fun interface WindowScreenProvider {
    /**
     * Returns the screen on which the window will be placed.
     *
     * Use the [WindowScreenProviderScope] receiver to examine the available
     * screens and pick the appropriate one.
     */
    public fun WindowScreenProviderScope.getScreen(): Screen

    /** Built-in providers. */
    public companion object {
        /** Keeps the window on the screen it would land on by default. */
        public val Default: WindowScreenProvider = WindowScreenProvider { defaultScreen }

        /** Places the window on the primary screen. */
        public val Primary: WindowScreenProvider = WindowScreenProvider { primaryScreen }

        /**
         * Places the window on the screen with the given [id], falling back to
         * [Default] while that screen is not attached.
         *
         * Pairs with the [WindowState.screenId] a previous session persisted.
         */
        public fun ById(id: String): WindowScreenProvider =
            WindowScreenProvider {
                screens.firstOrNull { it.id == id } ?: defaultScreen
            }
    }
}

/**
 * Evaluates [provider] in this scope.
 *
 * The scoped `getScreen` is an internal member extension — mirroring Compose,
 * where the same member keeps provider evaluation out of the public API — so
 * this is how the window bridge reaches it.
 */
internal fun WindowScreenProviderScope.evaluateScreen(provider: WindowScreenProvider): Screen =
    with(provider) { getScreen() }

/**
 * Screen scope for the given window: every attached monitor, with [window]'s
 * own monitor as the default. A `null` window (the window does not exist yet)
 * defaults to the primary monitor.
 */
internal fun screenScope(window: TaoWindow?): WindowScreenProviderScope {
    val scale = TaoMonitors.referenceScale(window)
    val monitors = TaoMonitors.all(window)
    val screens = monitors.map { Screen(it, scale) }
    val defaultMonitor = TaoMonitors.forWindow(window)
    val default = screens.firstOrNull { it.id == defaultMonitor.id } ?: Screen(defaultMonitor, scale)
    return WindowScreenProviderScope(screens = screens, defaultScreen = default)
}
