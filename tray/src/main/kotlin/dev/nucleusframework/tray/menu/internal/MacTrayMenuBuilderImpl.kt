package dev.nucleusframework.tray.menu.internal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.internal.macos.MacTrayManager
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MacTrayMenuBuilderImpl(
    private val trayManager: MacTrayManager? = null,
) : TrayMenuBuilder {
    private val menuItems = mutableListOf<MacTrayManager.MenuItem>()
    private val lock = ReentrantLock()

    override fun Item(
        label: String,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        lock.withLock {
            val menuItem =
                MacTrayManager.MenuItem(
                    text = label,
                    iconPath = null,
                    isEnabled = isEnabled,
                    shortcut = shortcut,
                    onClick = onClick,
                )
            menuItems.add(menuItem)
        }
    }

    override fun Item(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        lock.withLock {
            val iconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)

            val menuItem =
                MacTrayManager.MenuItem(
                    text = label,
                    iconPath = iconPath,
                    isEnabled = isEnabled,
                    shortcut = shortcut,
                    onClick = onClick,
                )
            menuItems.add(menuItem)
        }
    }

    override fun Item(
        label: String,
        icon: ImageVector,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        val iconContent: @Composable () -> Unit = {
            val isDark = isSystemInDarkMode()
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter =
                    iconTint?.let { ColorFilter.tint(it) }
                        ?: if (isDark) {
                            ColorFilter.tint(Color.White)
                        } else {
                            ColorFilter.tint(Color.Black)
                        },
            )
        }

        Item(label, iconContent, iconRenderProperties, isEnabled, shortcut, onClick)
    }

    override fun Item(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Item(label, iconContent, iconRenderProperties, isEnabled, shortcut, onClick)
    }

    override fun Item(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Item(label, iconContent, iconRenderProperties, isEnabled, shortcut, onClick)
    }

    override fun CheckableItem(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        lock.withLock {
            // Live state: clicking must toggle the current value, not the one captured
            // when the menu was built (issue #432).
            val liveChecked = AtomicBoolean(checked)
            // Identity, not the label: two items may share a label and must toggle independently.
            val token = Any()
            val menuItem =
                MacTrayManager.MenuItem(
                    text = label,
                    iconPath = null,
                    isEnabled = isEnabled,
                    isCheckable = true,
                    isChecked = checked,
                    shortcut = shortcut,
                    checkToken = token,
                    onClick = {
                        val newChecked = !liveChecked.get()
                        liveChecked.set(newChecked)
                        onCheckedChange(newChecked)
                        trayManager?.updateMenuItemCheckedState(token, newChecked)
                    },
                )
            menuItems.add(menuItem)
        }
    }

    override fun CheckableItem(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        lock.withLock {
            val iconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)

            val liveChecked = AtomicBoolean(checked)
            // Identity, not the label: two items may share a label and must toggle independently.
            val token = Any()
            val menuItem =
                MacTrayManager.MenuItem(
                    text = label,
                    iconPath = iconPath,
                    isEnabled = isEnabled,
                    isCheckable = true,
                    isChecked = checked,
                    shortcut = shortcut,
                    checkToken = token,
                    onClick = {
                        val newChecked = !liveChecked.get()
                        liveChecked.set(newChecked)
                        onCheckedChange(newChecked)
                        trayManager?.updateMenuItemCheckedState(token, newChecked)
                    },
                )
            menuItems.add(menuItem)
        }
    }

    override fun CheckableItem(
        label: String,
        icon: ImageVector,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        val iconContent: @Composable () -> Unit = {
            val isDark = isSystemInDarkMode()
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter =
                    iconTint?.let { ColorFilter.tint(it) }
                        ?: if (isDark) {
                            ColorFilter.tint(Color.White)
                        } else {
                            ColorFilter.tint(Color.Black)
                        },
            )
        }

        CheckableItem(label, iconContent, iconRenderProperties, checked, onCheckedChange, isEnabled, shortcut)
    }

    override fun CheckableItem(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        CheckableItem(label, iconContent, iconRenderProperties, checked, onCheckedChange, isEnabled, shortcut)
    }

    override fun CheckableItem(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        CheckableItem(label, iconContent, iconRenderProperties, checked, onCheckedChange, isEnabled, shortcut)
    }

    override fun SubMenu(
        label: String,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        createSubMenu(label, null, isEnabled, submenuContent)
    }

    override fun SubMenu(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val iconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)
        createSubMenu(label, iconPath, isEnabled, submenuContent)
    }

    override fun SubMenu(
        label: String,
        icon: ImageVector,
        iconTint: Color?,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val iconContent: @Composable () -> Unit = {
            val isDark = isSystemInDarkMode()
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter =
                    iconTint?.let { ColorFilter.tint(it) }
                        ?: if (isDark) {
                            ColorFilter.tint(Color.White)
                        } else {
                            ColorFilter.tint(Color.Black)
                        },
            )
        }

        SubMenu(label, iconContent, iconRenderProperties, isEnabled, submenuContent)
    }

    override fun SubMenu(
        label: String,
        icon: Painter,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        SubMenu(label, iconContent, iconRenderProperties, isEnabled, submenuContent)
    }

    override fun SubMenu(
        label: String,
        icon: DrawableResource,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val iconContent: @Composable () -> Unit = {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        SubMenu(label, iconContent, iconRenderProperties, isEnabled, submenuContent)
    }

    private fun createSubMenu(
        label: String,
        iconPath: String?,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val subMenuItems = mutableListOf<MacTrayManager.MenuItem>()
        if (submenuContent != null) {
            val subMenuImpl = MacTrayMenuBuilderImpl(trayManager).apply(submenuContent)
            subMenuItems.addAll(subMenuImpl.menuItems)
        }
        lock.withLock {
            val subMenu =
                MacTrayManager.MenuItem(
                    text = label,
                    iconPath = iconPath,
                    isEnabled = isEnabled,
                    subMenuItems = subMenuItems,
                )
            menuItems.add(subMenu)
        }
    }

    override fun Divider() {
        lock.withLock {
            menuItems.add(MacTrayManager.MenuItem(text = "", isSeparator = true))
        }
    }

    override fun dispose() {
        // Nothing native is held: the menu lives in the tray's own state.
    }

    fun build(): List<MacTrayManager.MenuItem> = lock.withLock { menuItems.toList() }
}
