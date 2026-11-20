package dev.nucleusframework.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.menu.ComposableTrayMenuScope
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.RecordingComposableScope
import dev.nucleusframework.tray.menu.internal.asTrayMenuBuilderBlock
import dev.nucleusframework.tray.menu.internal.structuralFingerprint
import dev.nucleusframework.tray.utils.IconRenderProperties
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Records the user's `@Composable` menu lambda into a flat [TrayMenuBuilder] block, stable across
 * recompositions when the recorded structure doesn't change. Returns `null` when [menuContent] is
 * `null`, so callers can pass the result directly into the existing non-composable `Tray(…)`
 * overloads.
 */
@Composable
private fun rememberRecordedMenuContent(
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
): (TrayMenuBuilder.() -> Unit)? {
    if (menuContent == null) return null
    val scope = RecordingComposableScope()
    scope.menuContent()
    val ops = scope.snapshot()
    val fingerprint = ops.structuralFingerprint()
    // Stabilize the replay lambda so the underlying Tray's LaunchedEffect key only changes when
    // the recorded menu structure actually changes; clicks still reach the latest lambdas.
    val latestOps = rememberUpdatedState(ops)
    return remember(fingerprint) { ops.asTrayMenuBuilderBlock { latestOps.value } }
}

/**
 * Composable-DSL variant of [Tray]. The [menuContent] lambda runs inside the composition, so
 * `painterResource(...)`, state reads and other composable calls work directly — no need to
 * hoist a `val icon = painterResource(...)` above `application { … }`.
 */
@Composable
public fun Tray(
    icon: Painter,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        icon = icon,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}

/**
 * Tray icon drawn from an [ImageVector], with a composable menu ([ComposableTrayMenuScope]).
 *
 * @param icon The icon. Without [tint] it follows the menu bar theme (white on dark, black on light).
 * @param tint Fixed icon color, or `null` to follow the menu bar theme.
 * @param iconRenderProperties How the icon is rasterized for the platform.
 *
 * @param tooltip Text shown when hovering the icon.
 * @param primaryAction Runs on a left click; `null` opens the menu instead.
 * @param onMenuOpened Runs just before the menu shows.
 * @param menuContent The menu; `null` for an icon without one.
 */
@Composable
public fun Tray(
    icon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        icon = icon,
        tint = tint,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}

/**
 * Tray icon from a Compose resource, with a composable menu ([ComposableTrayMenuScope]).
 *
 * @param icon The icon resource.
 * @param iconRenderProperties How the icon is rasterized for the platform.
 *
 * @param tooltip Text shown when hovering the icon.
 * @param primaryAction Runs on a left click; `null` opens the menu instead.
 * @param onMenuOpened Runs just before the menu shows.
 * @param menuContent The menu; `null` for an icon without one.
 */
@Composable
public fun Tray(
    icon: DrawableResource,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val painter = painterResource(icon)
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        icon = painter,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}

/**
 * Tray icon rendered from any composable, with a composable menu ([ComposableTrayMenuScope]).
 *
 * @param iconContent Composable drawn into the icon; it re-renders when what it reads changes.
 * @param iconRenderProperties How the icon is rasterized for the platform.
 *
 * @param tooltip Text shown when hovering the icon.
 * @param primaryAction Runs on a left click; `null` opens the menu instead.
 * @param onMenuOpened Runs just before the menu shows.
 * @param menuContent The menu; `null` for an icon without one.
 */
@Composable
public fun Tray(
    iconContent: @Composable () -> Unit,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        iconContent = iconContent,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}

/**
 * Polymorphic helper: [Painter] icon on Windows, [ImageVector] on macOS/Linux, composable menu.
 */
@Composable
public fun Tray(
    windowsIcon: Painter,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        windowsIcon = windowsIcon,
        macLinuxIcon = macLinuxIcon,
        tint = tint,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}

/**
 * Platform-polymorphic tray icon with a composable menu: a resource on Windows, an
 * [ImageVector] on macOS and Linux (where icons are usually monochrome).
 *
 * @param windowsIcon Icon used on Windows.
 * @param macLinuxIcon Icon used on macOS and Linux.
 * @param tint Fixed color of [macLinuxIcon], or `null` to follow the menu bar theme.
 * @param iconRenderProperties How the icon is rasterized for the platform.
 *
 * @param tooltip Text shown when hovering the icon.
 * @param primaryAction Runs on a left click; `null` opens the menu instead.
 * @param onMenuOpened Runs just before the menu shows.
 * @param menuContent The menu; `null` for an icon without one.
 */
@Composable
public fun Tray(
    windowsIcon: DrawableResource,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    primaryAction: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
    menuContent: (@Composable ComposableTrayMenuScope.() -> Unit)?,
) {
    val recorded = rememberRecordedMenuContent(menuContent)
    Tray(
        windowsIcon = windowsIcon,
        macLinuxIcon = macLinuxIcon,
        tint = tint,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        primaryAction = primaryAction,
        onMenuOpened = onMenuOpened,
        menuContent = recorded,
    )
}
