package dev.nucleusframework.tray.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.utils.IconRenderProperties
import org.jetbrains.compose.resources.DrawableResource

/**
 * Interface for building tray menus in a platform-independent manner.
 * Implementations of this interface allow the creation of tray menus
 * with items, checkable items, submenus, and dividers, and provide a
 * mechanism for disposing resources when the menu is no longer needed.
 */
public interface TrayMenuBuilder {
    /**
     * Adds an item to the tray menu.
     *
     * @param label The text label for the menu item.
     * @param isEnabled Indicates whether the menu item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     * @param onClick Lambda function to be invoked when the menu item is clicked. Defaults to an empty lambda.
     */
    public fun Item(
        label: String,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    /**
     * Adds an item to the tray menu with a Composable icon.
     *
     * @param label The text label for the menu item.
     * @param iconContent A Composable function that defines the icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the menu item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     * @param onClick Lambda function to be invoked when the menu item is clicked. Defaults to an empty lambda.
     */
    public fun Item(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    /**
     * Adds an item to the tray menu with an ImageVector icon.
     *
     * @param label The text label for the menu item.
     * @param icon The ImageVector to display as icon.
     * @param iconTint Optional tint color for the icon. If null, adapts to menu theme.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the menu item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     * @param onClick Lambda function to be invoked when the menu item is clicked. Defaults to an empty lambda.
     */
    public fun Item(
        label: String,
        icon: ImageVector,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    /**
     * Adds an item to the tray menu with a Painter icon.
     *
     * @param label The text label for the menu item.
     * @param icon The Painter to display as icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the menu item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     * @param onClick Lambda function to be invoked when the menu item is clicked. Defaults to an empty lambda.
     */
    public fun Item(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    /**
     * Adds an item to the tray menu with a DrawableResource icon.
     * This allows calling code like: Item(label, icon = Res.drawable.icon, ...)
     */
    public fun Item(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit = {},
    )

    /**
     * Adds a checkable item to the tray menu.
     * This follows Compose's idiomatic pattern for stateful components.
     *
     * @param label The text label for the checkable menu item.
     * @param checked The current checked state of the item.
     * @param onCheckedChange A lambda function called when the user toggles the item. The new checked state is passed as a parameter.
     * @param isEnabled Determines if the checkable item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     */
    public fun CheckableItem(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    /**
     * Adds a checkable item to the tray menu with a Composable icon.
     *
     * @param label The text label for the checkable menu item.
     * @param iconContent A Composable function that defines the icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param checked The current checked state of the item.
     * @param onCheckedChange A lambda function called when the user toggles the item.
     * @param isEnabled Determines if the checkable item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     */
    public fun CheckableItem(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    /**
     * Adds a checkable item to the tray menu with an ImageVector icon.
     *
     * @param label The text label for the checkable menu item.
     * @param icon The ImageVector to display as icon.
     * @param iconTint Optional tint color for the icon. If null, adapts to menu theme.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param checked The current checked state of the item.
     * @param onCheckedChange A lambda function called when the user toggles the item.
     * @param isEnabled Determines if the checkable item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     */
    public fun CheckableItem(
        label: String,
        icon: ImageVector,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    /**
     * Adds a checkable item to the tray menu with a Painter icon.
     *
     * @param label The text label for the checkable menu item.
     * @param icon The Painter to display as icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param checked The current checked state of the item.
     * @param onCheckedChange A lambda function called when the user toggles the item.
     * @param isEnabled Determines if the checkable item is enabled. Defaults to true.
     * @param shortcut Optional keyboard shortcut hint displayed next to the item. Display-only, does not register a hotkey.
     */
    public fun CheckableItem(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    /**
     * Adds a checkable item to the tray menu with a DrawableResource icon.
     * This allows calling code like: CheckableItem(label, icon = Res.drawable.icon, checked = ..., ...)
     */
    public fun CheckableItem(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean = true,
        shortcut: KeyShortcut? = null,
    )

    /**
     * Adds a checkable item to the tray menu with the legacy API.
     * @deprecated Use the new API with separate checked and onCheckedChange parameters for better Compose idiomaticity
     */
    @Deprecated(
        message = "Use CheckableItem with separate checked and onCheckedChange parameters",
        replaceWith = ReplaceWith("CheckableItem(label, checked, onCheckedChange, isEnabled)"),
    )
    public fun CheckableItem(
        label: String,
        checked: Boolean = false,
        isEnabled: Boolean = true,
        onToggle: (Boolean) -> Unit,
    ) {
        // Delegate to the new API
        CheckableItem(label, checked, onToggle, isEnabled)
    }

    /**
     * Adds a submenu to the tray menu.
     *
     * @param label The text label for the submenu.
     * @param isEnabled Indicates whether the submenu is enabled. Defaults to true.
     * @param submenuContent A lambda function defining the contents of the submenu. Can be null.
     */
    public fun SubMenu(
        label: String,
        isEnabled: Boolean = true,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    )

    /**
     * Adds a submenu to the tray menu with a Composable icon.
     *
     * @param label The text label for the submenu.
     * @param iconContent A Composable function that defines the icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the submenu is enabled. Defaults to true.
     * @param submenuContent A lambda function defining the contents of the submenu. Can be null.
     */
    public fun SubMenu(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    )

    /**
     * Adds a submenu to the tray menu with an ImageVector icon.
     *
     * @param label The text label for the submenu.
     * @param icon The ImageVector to display as icon.
     * @param iconTint Optional tint color for the icon. If null, adapts to menu theme.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the submenu is enabled. Defaults to true.
     * @param submenuContent A lambda function defining the contents of the submenu. Can be null.
     */
    public fun SubMenu(
        label: String,
        icon: ImageVector,
        iconTint: Color? = null,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    )

    /**
     * Adds a submenu to the tray menu with a Painter icon.
     *
     * @param label The text label for the submenu.
     * @param icon The Painter to display as icon.
     * @param iconRenderProperties Properties for rendering the icon. Defaults to 16x16 for menu items.
     * @param isEnabled Indicates whether the submenu is enabled. Defaults to true.
     * @param submenuContent A lambda function defining the contents of the submenu. Can be null.
     */
    public fun SubMenu(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    )

    /**
     * Adds a submenu to the tray menu with a DrawableResource icon.
     * This allows calling code like: SubMenu(label, icon = Res.drawable.icon) { ... }
     */
    public fun SubMenu(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties = IconRenderProperties.forMenuItem(),
        isEnabled: Boolean = true,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    )

    /**
     * Adds a visual separator (divider) to the tray menu.
     * This method is used to group or separate menu items, providing better
     * organization and clarity within the menu structure.
     */
    public fun Divider()

    /**
     * Disposes of the resources associated with the tray menu.
     * This method should be called when the tray menu is no longer in use
     * to release any system resources held by it.
     */
    public fun dispose()
}
