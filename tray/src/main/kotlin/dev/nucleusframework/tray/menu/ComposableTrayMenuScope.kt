package dev.nucleusframework.tray.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.utils.IconRenderProperties
import org.jetbrains.compose.resources.DrawableResource

/**
 * Composable-aware DSL for declaring tray menus.
 *
 * Unlike [TrayMenuBuilder] — whose submenu lambdas are plain Kotlin lambdas executed by the
 * native impls outside of any composition — every method here is `@Composable`. You can call
 * `painterResource(...)`, read state, etc., directly inside menu and submenu bodies without
 * hoisting:
 *
 * ```
 * Tray(icon = Res.drawable.icon, tooltip = "App") {
 *     SubMenu(label = "Advanced", icon = painterResource(Res.drawable.advanced)) {
 *         Item(label = "Reload", icon = painterResource(Res.drawable.reload)) { ... }
 *     }
 * }
 * ```
 *
 * Internally, calls are recorded during composition and replayed into the [TrayMenuBuilder]
 * consumed by the native menu impls. Recomposition triggered by state reads inside the lambda
 * transparently rebuilds the menu.
 */
public interface ComposableTrayMenuScope {
    @Composable
    public fun Item(
        label: String,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    @Composable
    public fun Item(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    @Composable
    public fun Item(
        label: String,
        icon: ImageVector,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    @Composable
    public fun CheckableItem(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    @Composable
    public fun CheckableItem(
        label: String,
        icon: Painter,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    @Composable
    public fun CheckableItem(
        label: String,
        icon: ImageVector,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    @Composable
    public fun SubMenu(
        label: String,
        isEnabled: Boolean = true,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    )

    @Composable
    public fun SubMenu(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    )

    @Composable
    public fun SubMenu(
        label: String,
        icon: ImageVector,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    )

    @Composable
    public fun Item(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    @Composable
    public fun CheckableItem(
        label: String,
        icon: DrawableResource,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    @Composable
    public fun SubMenu(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: @Composable ComposableTrayMenuScope.() -> Unit,
    )

    @Composable
    public fun Divider()

    /**
     * Backward-compatibility shim. Under the composable DSL, the tray lifecycle is owned by the
     * surrounding `DisposableEffect` in [Tray], so this is a no-op. Existing menus that call
     * `dispose()` before `exitApplication()` keep compiling without behavior change.
     */
    public fun dispose() {
        // no-op: lifecycle managed by Tray composable
    }

    @Deprecated(
        message = "Use CheckableItem with separate checked and onCheckedChange parameters",
        replaceWith = ReplaceWith("CheckableItem(label, checked, onToggle, isEnabled)"),
    )
    @Composable
    public fun CheckableItem(
        label: String,
        checked: Boolean = false,
        isEnabled: Boolean = true,
        onToggle: (Boolean) -> Unit,
    ) {
        CheckableItem(label, checked, onToggle, isEnabled, null)
    }
}
