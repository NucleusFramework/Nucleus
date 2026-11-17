@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.nucleusframework.tray

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import dev.nucleusframework.tray.utils.MenuContentHash
import dev.nucleusframework.tray.utils.debugln
import dev.nucleusframework.tray.utils.errorln
import dev.nucleusframework.tray.utils.extractToTempIfDifferent
import dev.nucleusframework.tray.utils.isMenuBarInDarkMode
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.Platform.Linux
import dev.nucleusframework.core.runtime.Platform.MacOS
import dev.nucleusframework.core.runtime.Platform.Unknown
import dev.nucleusframework.core.runtime.Platform.Windows
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.internal.LowPriorityInOverloadResolution

/**
 * Low-level tray engine behind the `Tray` composables and `TrayApp`.
 *
 * Every change goes through one conflated queue drained by a single worker, so changes reach the
 * backend in order and a burst collapses to its newest state. [dispose] is final: once it
 * returns, no queued or in-flight change can recreate the icon.
 */
internal class NativeTray {
    private val trayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val os = Platform.Current

    // A counter, not identityHashCode: two live trays sharing a hash would share one native icon.
    private val instanceId: String = "tray-" + nextInstance.incrementAndGet()

    private val lock = Any()
    private var initialized = false // guarded by lock
    private var disposed = false // guarded by lock

    private sealed interface Change {
        val tooltip: String
        val primaryAction: (() -> Unit)?
        val menuContent: (TrayMenuBuilder.() -> Unit)?
        val onMenuOpened: (() -> Unit)?
    }

    private class PathChange(
        val iconPath: String,
        val windowsIconPath: String,
        override val tooltip: String,
        override val primaryAction: (() -> Unit)?,
        override val menuContent: (TrayMenuBuilder.() -> Unit)?,
        override val onMenuOpened: (() -> Unit)?,
    ) : Change

    private class ComposableChange(
        val iconContent: @Composable () -> Unit,
        val iconRenderProperties: IconRenderProperties,
        override val tooltip: String,
        override val primaryAction: (() -> Unit)?,
        override val menuContent: (TrayMenuBuilder.() -> Unit)?,
        val maxAttempts: Int,
        val backoffMs: Long,
        val lightIconContent: (@Composable () -> Unit)?,
        val darkIconContent: (@Composable () -> Unit)?,
        override val onMenuOpened: (() -> Unit)?,
    ) : Change

    // Conflated: a newer change replaces a queued one, so a stale render or menu can never land
    // after a fresher one (issue #432).
    private val changes = Channel<Change>(Channel.CONFLATED)

    init {
        trayScope.launch {
            for (change in changes) apply(change)
        }
    }

    // Exposes the unique instance key so TrayApp can compute per-instance positions.
    fun instanceKey(): String = instanceId

    fun update(
        iconPath: String,
        windowsIconPath: String = iconPath,
        tooltip: String,
        primaryAction: (() -> Unit)?,
        menuContent: (TrayMenuBuilder.() -> Unit)?,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        changes.trySend(PathChange(iconPath, windowsIconPath, tooltip, primaryAction, menuContent, onMenuOpened))
    }

    /**
     * Renders the composable icon (with retries) and then creates or updates the tray. If
     * rendering keeps failing the change is dropped and logged; the app never crashes.
     *
     * @param lightIconContent Optional composable for the light-appearance icon (macOS only).
     * @param darkIconContent Optional composable for the dark-appearance icon (macOS only).
     */
    fun updateComposable(
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties(),
        tooltip: String,
        primaryAction: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        maxAttempts: Int = 3,
        backoffMs: Long = 200,
        lightIconContent: (@Composable () -> Unit)? = null,
        darkIconContent: (@Composable () -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        changes.trySend(
            ComposableChange(
                iconContent,
                iconRenderProperties,
                tooltip,
                primaryAction,
                menuContent,
                maxAttempts,
                backoffMs,
                lightIconContent,
                darkIconContent,
                onMenuOpened,
            ),
        )
    }

    private suspend fun apply(change: Change) {
        val icons =
            when (change) {
                is PathChange -> change.iconPath to change.windowsIconPath
                is ComposableChange ->
                    renderIconsWithRetry(change.iconContent, change.iconRenderProperties, change.maxAttempts, change.backoffMs)
            }
        if (icons == null) {
            errorln { "[NativeTray] Icon rendering kept failing; change dropped." }
            return
        }

        synchronized(lock) {
            if (disposed) return
            try {
                applyToBackend(icons.first, icons.second, change)
                initialized = true
            } catch (t: Throwable) {
                errorln { "[NativeTray] Error applying tray change: $t" }
            }
        }

        // On macOS, pre-render light/dark variants for instant appearance switching.
        if (change is ComposableChange && os == MacOS) {
            val light = change.lightIconContent ?: return
            val dark = change.darkIconContent ?: return
            try {
                val lightPath = ComposableIconUtils.renderComposableToPngFile(change.iconRenderProperties, light)
                val darkPath = ComposableIconUtils.renderComposableToPngFile(change.iconRenderProperties, dark)
                synchronized(lock) { if (!disposed) MacTrayInitializer.setAppearanceIcons(instanceId, lightPath, darkPath) }
            } catch (t: Throwable) {
                errorln { "[NativeTray] Failed to render appearance icons: $t" }
            }
        }
    }

    // Caller holds [lock].
    private fun applyToBackend(
        pngIconPath: String,
        windowsIconPath: String,
        change: Change,
    ) {
        val first = !initialized
        with(change) {
            when (os) {
                Linux ->
                    if (first) {
                        LinuxTrayInitializer.initialize(instanceId, pngIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                    } else {
                        LinuxTrayInitializer.update(instanceId, pngIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                    }
                Windows ->
                    WindowsTrayInitializer.update(instanceId, windowsIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                MacOS ->
                    if (first) {
                        MacTrayInitializer.initialize(instanceId, pngIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                    } else {
                        MacTrayInitializer.update(instanceId, pngIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                    }
                Unknown -> errorln { "[NativeTray] Unsupported platform: no system tray backend available" }
                else -> {}
            }
        }
    }

    /** Sets macOS appearance icons directly from file paths. */
    fun setMacOSAppearanceIcons(
        lightPath: String,
        darkPath: String,
    ) {
        synchronized(lock) {
            if (os == MacOS && initialized && !disposed) MacTrayInitializer.setAppearanceIcons(instanceId, lightPath, darkPath)
        }
    }

    private suspend fun renderIconsWithRetry(
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties,
        maxAttempts: Int,
        backoffMs: Long,
    ): Pair<String, String>? {
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                val pngIconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)
                val windowsIconPath =
                    if (os == Windows) {
                        ComposableIconUtils.renderComposableToIcoFile(iconRenderProperties, iconContent)
                    } else {
                        pngIconPath
                    }
                debugln { "[NativeTray] Rendered tray icons (attempt ${attempt + 1}/$maxAttempts): PNG=$pngIconPath, WIN=$windowsIconPath" }
                return pngIconPath to windowsIconPath
            } catch (e: Throwable) {
                errorln { "[NativeTray] Icon render attempt ${attempt + 1} failed: ${e.message ?: e::class.simpleName}" }
                attempt++
                if (attempt < maxAttempts) delay(backoffMs)
            }
        }
        return null
    }

    /** Removes the icon. Final: later [update] / [updateComposable] calls are ignored. */
    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            changes.close()
            trayScope.cancel()
            when (os) {
                Linux -> LinuxTrayInitializer.dispose(instanceId)
                Windows -> WindowsTrayInitializer.dispose(instanceId)
                MacOS -> MacTrayInitializer.dispose(instanceId)
                else -> {}
            }
            initialized = false
        }
    }

    private companion object {
        val nextInstance = AtomicLong()
    }
}

/**
 * Composable helpers
 */
@Deprecated(
    message = "Use the version with composable icon content instead",
    replaceWith = ReplaceWith("Tray(iconContent, tooltip, primaryAction, menuContent)"),
)
@Composable
public fun Tray(
    iconPath: String,
    windowsIconPath: String = iconPath,
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val absoluteIconPath = remember(iconPath) { extractToTempIfDifferent(iconPath)?.absolutePath.orEmpty() }
    val absoluteWindowsIconPath =
        remember(iconPath, windowsIconPath) {
            if (windowsIconPath == iconPath) {
                absoluteIconPath
            } else {
                extractToTempIfDifferent(windowsIconPath)?.absolutePath.orEmpty()
            }
        }

    val tray = remember { NativeTray() }

    // Calculate menu hash to detect changes
    val menuHash = MenuContentHash.calculateMenuHash(menuContent)

    // Update when params change, including menuHash
    LaunchedEffect(absoluteIconPath, absoluteWindowsIconPath, tooltip, primaryAction, menuContent, menuHash) {
        tray.update(absoluteIconPath, absoluteWindowsIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
    }

    // Dispose only when Tray is removed from composition
    DisposableEffect(Unit) {
        onDispose {
            debugln { "[NativeTray] onDispose" }
            tray.dispose()
        }
    }
}

@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    iconContent: @Composable () -> Unit,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val isDark = isMenuBarInDarkMode() // Observe menu bar theme to trigger recomposition on changes

    // Calculate a hash of the rendered composable content to detect changes, including theme state
    val contentHash = ComposableIconUtils.calculateContentHash(iconRenderProperties, iconContent) + isDark.hashCode()

    // Calculate a hash of the menu content to detect changes
    val menuHash = MenuContentHash.calculateMenuHash(menuContent)

    val tray = remember { NativeTray() }

    // On any content/menu change, delegate to retry-safe path
    LaunchedEffect(contentHash, tooltip, primaryAction, menuContent, menuHash) {
        tray.updateComposable(
            iconContent = iconContent,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            menuContent = menuContent,
            maxAttempts = 3,
            backoffMs = 200,
            onMenuOpened = onMenuOpened,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            debugln { "[NativeTray] onDispose" }
            tray.dispose()
        }
    }
}

@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    icon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val isDark = isMenuBarInDarkMode()
    val isSystemInDarkTheme = isSystemInDarkMode()
    val isMacOS = Platform.Current == MacOS

    // Define the icon content lambda
    val iconContent: @Composable () -> Unit = {
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter =
                tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) }
                    ?: if (isDark) {
                        androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                    } else {
                        androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                    },
        )
    }

    // On macOS with auto-tint, pre-render both light and dark variants for instant switching
    val lightIconContent: (@Composable () -> Unit)? =
        if (tint == null && isMacOS) {
            {
                Image(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black),
                )
            }
        } else {
            null
        }

    val darkIconContent: (@Composable () -> Unit)? =
        if (tint == null && isMacOS) {
            {
                Image(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                )
            }
        } else {
            null
        }

    // Calculate menu hash to detect changes
    val menuHash = MenuContentHash.calculateMenuHash(menuContent)

    // Updated contentHash to include icon and tint for proper recomposition on changes
    val contentHash =
        ComposableIconUtils.calculateContentHash(iconRenderProperties, iconContent) +
            isDark.hashCode() +
            isSystemInDarkTheme.hashCode() +
            icon.hashCode() +
            (tint?.hashCode() ?: 0)

    val tray = remember { NativeTray() }

    LaunchedEffect(contentHash, tooltip, primaryAction, menuContent, menuHash) {
        tray.updateComposable(
            iconContent = iconContent,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            menuContent = menuContent,
            maxAttempts = 3,
            backoffMs = 200,
            lightIconContent = lightIconContent,
            darkIconContent = darkIconContent,
            onMenuOpened = onMenuOpened,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            debugln { "[NativeTray] onDispose" }
            tray.dispose()
        }
    }
}

@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    icon: Painter,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val isDark = isMenuBarInDarkMode() // Included for consistency, even if not used in rendering

    // Define the icon content lambda
    val iconContent: @Composable () -> Unit = {
        Image(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Calculate menu hash to detect changes
    val menuHash = MenuContentHash.calculateMenuHash(menuContent)

    // Updated contentHash to include icon for proper recomposition on changes
    val contentHash =
        ComposableIconUtils.calculateContentHash(iconRenderProperties, iconContent) +
            isDark.hashCode() +
            icon.hashCode()

    val tray = remember { NativeTray() }

    LaunchedEffect(contentHash, tooltip, primaryAction, menuContent, menuHash) {
        tray.updateComposable(
            iconContent = iconContent,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            menuContent = menuContent,
            maxAttempts = 3,
            backoffMs = 200,
            onMenuOpened = onMenuOpened,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            debugln { "[NativeTray] onDispose" }
            tray.dispose()
        }
    }
}

/**
 * Platform-polymorphic helper
 */
@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    windowsIcon: Painter,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val os = Platform.Current

    if (os == Windows) {
        // Use Painter for Windows
        Tray(
            icon = windowsIcon,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            onMenuOpened = onMenuOpened,
            menuContent = menuContent,
        )
    } else {
        // Use ImageVector for macOS and Linux
        Tray(
            icon = macLinuxIcon,
            tint = tint,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            onMenuOpened = onMenuOpened,
            menuContent = menuContent,
        )
    }
}

/**
 * DrawableResource helpers
 */
@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    icon: DrawableResource,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    // Convert DrawableResource to Painter and delegate to the Painter overload
    val painter = painterResource(icon)
    Tray(
        icon = painter,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = menuContent,
    )
}

@Composable
@LowPriorityInOverloadResolution
public fun Tray(
    windowsIcon: DrawableResource,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (TrayMenuBuilder.() -> Unit)? = null,
) {
    val os = Platform.Current

    if (os == Windows) {
        // Convert DrawableResource to Painter for Windows and delegate
        val painter = painterResource(windowsIcon)
        Tray(
            icon = painter,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            onMenuOpened = onMenuOpened,
            menuContent = menuContent,
        )
    } else {
        // Use ImageVector for macOS and Linux
        Tray(
            icon = macLinuxIcon,
            tint = tint,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            onMenuOpened = onMenuOpened,
            menuContent = menuContent,
        )
    }
}
