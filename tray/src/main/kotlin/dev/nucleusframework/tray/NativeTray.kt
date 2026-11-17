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
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.internal.LowPriorityInOverloadResolution

/**
 * Low-level tray engine shared by the `Tray` composables and by `TrayApp` (in the separate
 * `TrayApp` (`dev.nucleusframework.tray.app`)). Public so the tray-app module can drive it directly; most
 * callers should use the `Tray` / `TrayApp` composables instead.
 */
internal class NativeTray {
    private val trayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val os = Platform.Current
    private val instanceId: String = "tray-" + System.identityHashCode(this)
    private var initialized = false

    private data class ComposableUpdate(
        val iconContent: @Composable () -> Unit,
        val iconRenderProperties: IconRenderProperties,
        val tooltip: String,
        val primaryAction: (() -> Unit)?,
        val menuContent: (TrayMenuBuilder.() -> Unit)?,
        val maxAttempts: Int,
        val backoffMs: Long,
        val lightIconContent: (@Composable () -> Unit)?,
        val darkIconContent: (@Composable () -> Unit)?,
        val onMenuOpened: (() -> Unit)?,
    )

    // Conflated so updates are applied one at a time, in submission order, and a
    // newer update replaces any queued-but-not-yet-applied one. This prevents a
    // stale render/menu from landing after a fresher one (issue #432).
    private val composableUpdates = Channel<ComposableUpdate>(Channel.CONFLATED)

    init {
        trayScope.launch {
            for (update in composableUpdates) {
                applyComposableUpdate(update)
            }
        }
    }

    // Expose the unique instance key so UI code (TrayApp) can compute per-instance positions
    fun instanceKey(): String = instanceId

    fun update(
        iconPath: String,
        windowsIconPath: String = iconPath,
        tooltip: String,
        primaryAction: (() -> Unit)?,
        menuContent: (TrayMenuBuilder.() -> Unit)?,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        if (!initialized) {
            initializeTray(iconPath, windowsIconPath, tooltip, primaryAction, menuContent)
            initialized = true
            return
        }

        try {
            when (os) {
                Linux ->
                    LinuxTrayInitializer.update(
                        instanceId,
                        iconPath,
                        tooltip,
                        primaryAction,
                        menuContent,
                        onMenuOpened,
                    )
                Windows ->
                    WindowsTrayInitializer.update(
                        instanceId,
                        windowsIconPath,
                        tooltip,
                        primaryAction,
                        menuContent,
                        onMenuOpened,
                    )
                MacOS ->
                    MacTrayInitializer.update(
                        instanceId,
                        iconPath,
                        tooltip,
                        primaryAction,
                        menuContent,
                        onMenuOpened,
                    )
                Unknown -> errorln { "[NativeTray] Unsupported platform: no system tray backend available" }
                else -> {}
            }
        } catch (th: Throwable) {
            errorln { "[NativeTray] Error updating tray: $th" }
        }
    }

    /**
     * New update path: render the composable icon to PNG/ICO with retries and then update/init the tray.
     * If rendering keeps failing, we log and **do not create/update** the tray (never crash the app).
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
        composableUpdates.trySend(
            ComposableUpdate(
                iconContent = iconContent,
                iconRenderProperties = iconRenderProperties,
                tooltip = tooltip,
                primaryAction = primaryAction,
                menuContent = menuContent,
                maxAttempts = maxAttempts,
                backoffMs = backoffMs,
                lightIconContent = lightIconContent,
                darkIconContent = darkIconContent,
                onMenuOpened = onMenuOpened,
            ),
        )
    }

    private suspend fun applyComposableUpdate(update: ComposableUpdate) {
        with(update) {
            val rendered = renderIconsWithRetry(iconContent, iconRenderProperties, maxAttempts, backoffMs)
            if (rendered == null) {
                errorln {
                    "[NativeTray] Icon rendering failed after $maxAttempts attempts. " +
                        "Tray will not be created/updated."
                }
                return
            }

            val (pngIconPath, windowsIconPath) = rendered

            if (!initialized) {
                initializeTray(pngIconPath, windowsIconPath, tooltip, primaryAction, menuContent, onMenuOpened)
                initialized = true
            } else {
                try {
                    when (os) {
                        Linux ->
                            LinuxTrayInitializer.update(
                                instanceId,
                                pngIconPath,
                                tooltip,
                                primaryAction,
                                menuContent,
                                onMenuOpened,
                            )
                        Windows ->
                            WindowsTrayInitializer.update(
                                instanceId,
                                windowsIconPath,
                                tooltip,
                                primaryAction,
                                menuContent,
                                onMenuOpened,
                            )
                        MacOS ->
                            MacTrayInitializer.update(
                                instanceId,
                                pngIconPath,
                                tooltip,
                                primaryAction,
                                menuContent,
                                onMenuOpened,
                            )
                        Unknown ->
                            errorln { "[NativeTray] Unsupported platform: no system tray backend available" }
                        else -> {}
                    }
                } catch (th: Throwable) {
                    errorln { "[NativeTray] Error updating tray after successful render: $th" }
                }
            }

            // On macOS, pre-render light/dark variants for instant appearance switching
            val light = lightIconContent
            val dark = darkIconContent
            if (os == MacOS && light != null && dark != null) {
                try {
                    val lightPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, light)
                    val darkPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, dark)
                    MacTrayInitializer.setAppearanceIcons(instanceId, lightPath, darkPath)
                } catch (th: Throwable) {
                    errorln { "[NativeTray] Failed to render appearance icons: $th" }
                }
            }
        }
    }

    /**
     * Set macOS appearance icons directly from file paths.
     */
    fun setMacOSAppearanceIcons(
        lightPath: String,
        darkPath: String,
    ) {
        if (os == MacOS && initialized) {
            MacTrayInitializer.setAppearanceIcons(instanceId, lightPath, darkPath)
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
                // Render the composable to PNG for general platforms
                val pngIconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)

                // On Windows, also render to ICO; on other OSes reuse PNG path
                val windowsIconPath =
                    if (os == Windows) {
                        ComposableIconUtils.renderComposableToIcoFile(iconRenderProperties, iconContent)
                    } else {
                        pngIconPath
                    }

                debugln {
                    "[NativeTray] Rendered tray icons (attempt ${attempt + 1}/$maxAttempts): " +
                        "PNG=$pngIconPath, WIN=$windowsIconPath"
                }
                return pngIconPath to windowsIconPath
            } catch (e: Throwable) {
                errorln {
                    "[NativeTray] Icon render attempt ${attempt + 1} failed: " +
                        "${e.message ?: e::class.simpleName}"
                }
                attempt++
                if (attempt < maxAttempts) delay(backoffMs)
            }
        }
        return null
    }

    fun dispose() {
        // Stop the update worker and drop any pending update so a stale in-flight
        // render cannot re-create the tray after disposal.
        trayScope.cancel()
        when (os) {
            Linux -> LinuxTrayInitializer.dispose(instanceId)
            Windows -> WindowsTrayInitializer.dispose(instanceId)
            MacOS -> MacTrayInitializer.dispose(instanceId)
            Unknown -> {}
            else -> {}
        }
        initialized = false
    }

    /**
     * Constructor that accepts file paths for icons
     * @deprecated Use the Composable-based update path instead
     */
    @Deprecated(
        message = "Use the constructor with composable icon content instead",
        replaceWith = ReplaceWith("NativeTray(iconContent, tooltip, primaryAction, menuContent)"),
    )
    private fun initializeTray(
        iconPath: String,
        windowsIconPath: String = iconPath,
        tooltip: String = "",
        primaryAction: (() -> Unit)?,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        trayScope.launch {
            var trayInitialized = false
            val os = Platform.Current
            try {
                when (os) {
                    Linux -> {
                        debugln { "[NativeTray] Initializing Linux tray with icon path: $iconPath" }
                        LinuxTrayInitializer.initialize(
                            instanceId,
                            iconPath,
                            tooltip,
                            primaryAction,
                            menuContent,
                            onMenuOpened,
                        )
                        trayInitialized = true
                    }
                    Windows -> {
                        debugln { "[NativeTray] Initializing Windows tray with icon path: $windowsIconPath" }
                        WindowsTrayInitializer.initialize(
                            instanceId,
                            windowsIconPath,
                            tooltip,
                            primaryAction,
                            menuContent,
                            onMenuOpened,
                        )
                        trayInitialized = true
                    }
                    MacOS -> {
                        debugln { "[NativeTray] Initializing macOS tray with icon path: $iconPath" }
                        MacTrayInitializer.initialize(
                            instanceId,
                            iconPath,
                            tooltip,
                            primaryAction,
                            menuContent,
                            onMenuOpened,
                        )
                        trayInitialized = true
                    }
                    else -> {}
                }
            } catch (th: Throwable) {
                errorln { "[NativeTray] Error initializing tray: $th" }
            }

            if (os == Unknown || !trayInitialized) {
                errorln { "[NativeTray] No native tray backend could be initialized on this platform" }
            }
        }
    }

    /**
     * Constructor that accepts a Composable for the icon — kept for backward compatibility.
     * Now delegates to the retry-safe render+init/update path.
     */
    private fun initializeTray(
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties(),
        tooltip: String = "",
        primaryAction: (() -> Unit)?,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        updateComposable(
            iconContent = iconContent,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            primaryAction = primaryAction,
            menuContent = menuContent,
            onMenuOpened = onMenuOpened,
        )
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
