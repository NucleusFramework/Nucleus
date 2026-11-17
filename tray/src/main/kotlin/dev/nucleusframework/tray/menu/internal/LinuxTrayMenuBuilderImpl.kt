package dev.nucleusframework.tray.menu.internal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.tray.internal.linux.LinuxTrayManager
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import dev.nucleusframework.tray.utils.isMenuBarInDarkMode
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class LinuxTrayMenuBuilderImpl(
    private val iconPath: String,
    private val tooltip: String = "",
    private val onLeftClick: (() -> Unit)?,
    private val trayManager: LinuxTrayManager? = null,
) : TrayMenuBuilder {
    private val menuItems = mutableListOf<LinuxTrayManager.MenuItem>()
    private val lock = ReentrantLock()

    // Maintain persistent references to prevent GC
    private val persistentMenuItems = mutableListOf<LinuxTrayManager.MenuItem>()

    override fun Item(
        label: String,
        isEnabled: Boolean,
        shortcut: KeyShortcut?,
        onClick: () -> Unit,
    ) {
        lock.withLock {
            val menuItem =
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    shortcut = shortcut,
                    onClick = onClick,
                )
            menuItems.add(menuItem)
            persistentMenuItems.add(menuItem)
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
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    iconPath = iconPath,
                    shortcut = shortcut,
                    onClick = onClick,
                )
            menuItems.add(menuItem)
            persistentMenuItems.add(menuItem)
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
            val isDark = isMenuBarInDarkMode()

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

            val menuItem =
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    isCheckable = true,
                    isChecked = checked,
                    shortcut = shortcut,
                    onClick = {
                        val newChecked = !liveChecked.get()
                        liveChecked.set(newChecked)
                        onCheckedChange(newChecked)
                        trayManager?.updateMenuItemCheckedState(label, newChecked)
                    },
                )
            menuItems.add(menuItem)
            persistentMenuItems.add(menuItem)
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

            val menuItem =
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    isCheckable = true,
                    isChecked = checked,
                    iconPath = iconPath,
                    shortcut = shortcut,
                    onClick = {
                        val newChecked = !liveChecked.get()
                        liveChecked.set(newChecked)
                        onCheckedChange(newChecked)
                        trayManager?.updateMenuItemCheckedState(label, newChecked)
                    },
                )
            menuItems.add(menuItem)
            persistentMenuItems.add(menuItem)
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
            val isDark = isMenuBarInDarkMode()

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
        val subMenuItems = mutableListOf<LinuxTrayManager.MenuItem>()
        if (submenuContent != null) {
            val subMenuImpl =
                LinuxTrayMenuBuilderImpl(
                    iconPath,
                    tooltip,
                    onLeftClick,
                    trayManager,
                ).apply(submenuContent)
            subMenuItems.addAll(subMenuImpl.menuItems)
        }
        lock.withLock {
            val subMenu =
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    subMenuItems = subMenuItems,
                )
            menuItems.add(subMenu)
            persistentMenuItems.add(subMenu)
        }
    }

    override fun SubMenu(
        label: String,
        iconContent: @Composable () -> Unit,
        iconRenderProperties: IconRenderProperties,
        isEnabled: Boolean,
        submenuContent: (TrayMenuBuilder.() -> Unit)?,
    ) {
        val subMenuItems = mutableListOf<LinuxTrayManager.MenuItem>()
        if (submenuContent != null) {
            val subMenuImpl =
                LinuxTrayMenuBuilderImpl(
                    iconPath,
                    tooltip,
                    onLeftClick,
                    trayManager,
                ).apply(submenuContent)
            subMenuItems.addAll(subMenuImpl.menuItems)
        }

        lock.withLock {
            val iconPath = ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)

            val subMenu =
                LinuxTrayManager.MenuItem(
                    text = label,
                    isEnabled = isEnabled,
                    iconPath = iconPath,
                    subMenuItems = subMenuItems,
                )
            menuItems.add(subMenu)
            persistentMenuItems.add(subMenu)
        }
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
            val isDark = isMenuBarInDarkMode()
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

    override fun Divider() {
        lock.withLock {
            val divider = LinuxTrayManager.MenuItem(text = "-")
            menuItems.add(divider)
            persistentMenuItems.add(divider)
        }
    }

    override fun dispose() {
        lock.withLock {
            persistentMenuItems.clear()
        }
    }

    fun build(): List<LinuxTrayManager.MenuItem> = lock.withLock { menuItems.toList() }
}
