package dev.nucleusframework.tray.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.app.getTrayPosition
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import dev.nucleusframework.tray.utils.isMenuBarInDarkMode
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import org.jetbrains.compose.resources.painterResource

fun main() =
    nucleusApplication(enableSingleInstance = false) {
        allowNucleusRuntimeLogging = true
        nucleusLoggingLevel = NucleusLoggingLevel.DEBUG

        val logTag = "NativeTray"

        println("$logTag: TrayPosition: ${getTrayPosition()}")

        var isWindowVisible by remember { mutableStateOf(true) }
        var textVisible by remember { mutableStateOf(false) }
        var alwaysShowTray by remember { mutableStateOf(false) }
        var hideOnClose by remember { mutableStateOf(true) }

        // Dynamic menu state
        var showAdvancedOptions by remember { mutableStateOf(true) }
        var dynamicItemLabel by remember { mutableStateOf("Dynamic Item") }
        var itemCounter by remember { mutableStateOf(0) }

        // New idiomatic state management
        var notificationsEnabled by remember { mutableStateOf(false) }
        var darkModeEnabled by remember { mutableStateOf(false) }
        var autoStartEnabled by remember { mutableStateOf(true) }

        // Always create the Tray composable, but make it conditional on visibility
        val showTray = alwaysShowTray || !isWindowVisible

        val icon = painterResource(Res.drawable.icon)

        if (showTray) {
            Tray(
                iconContent = {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "",
                        tint = if (isMenuBarInDarkMode()) Color.White else Color.Black,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                primaryAction = {
                    isWindowVisible = true
                    println("$logTag: On Primary Clicked")
                },
                tooltip = "My Application",
                // Note: No menuKey needed anymore!
            ) {
                // Dynamic item that changes label
                Item(label = dynamicItemLabel, icon = Icons.Filled.Adb) {
                    itemCounter++
                    dynamicItemLabel = "Clicked $itemCounter times"
                    println("$logTag: Dynamic item clicked: $dynamicItemLabel")
                }

                Divider()

                // Options SubMenu
                SubMenu(label = "Options", icon = Icons.Default.Notifications) {
                    Item(label = "Show Text") {
                        println("$logTag: Show Text selected")
                        textVisible = true
                    }
                    Item(label = "Hide Text") {
                        println("$logTag: Hide Text selected")
                        textVisible = false
                    }

                    // Conditionally show advanced options
                    if (showAdvancedOptions) {
                        SubMenu(label = "Advanced Sub-options") {
                            Item(label = "Advanced Option 1") {
                                println("$logTag: Advanced Option 1 selected")
                            }
                            Item(label = "Advanced Option 2", icon = Icons.Default.ZoomOut) {
                                println("$logTag: Advanced Option 2 selected")
                            }
                        }
                    }
                }

                Divider()

                // Tools SubMenu
                SubMenu(label = "Tools") {
                    Item(label = "Calculator") {
                        println("$logTag: Calculator launched")
                    }
                    Item(label = "Notepad") {
                        println("$logTag: Notepad opened")
                    }
                }

                Divider()

                // New idiomatic CheckableItem usage
                CheckableItem(
                    icon = Icons.Default.Notifications,
                    label = "Enable notifications",
                    checked = notificationsEnabled,
                    onCheckedChange = { checked ->
                        notificationsEnabled = checked
                        println("$logTag: Notifications ${if (checked) "enabled" else "disabled"}")
                    },
                )

                CheckableItem(
                    label = "Dark mode",
                    checked = darkModeEnabled,
                    onCheckedChange = { checked ->
                        darkModeEnabled = checked
                        println("$logTag: Dark mode ${if (checked) "enabled" else "disabled"}")
                    },
                )

                CheckableItem(
                    label = "Auto-start on login",
                    checked = autoStartEnabled,
                    onCheckedChange = { checked ->
                        autoStartEnabled = checked
                        println("$logTag: Auto-start ${if (checked) "enabled" else "disabled"}")
                    },
                )

                Divider()

                // Toggle advanced options visibility
                CheckableItem(
                    label = "Show advanced options",
                    checked = showAdvancedOptions,
                    onCheckedChange = { checked ->
                        showAdvancedOptions = checked
                        println("$logTag: Advanced options ${if (checked) "shown" else "hidden"}")
                    },
                )

                Item(label = "About") {
                    println("$logTag: Application v1.0 - Developed by Elyahou")
                }

                Divider()

                CheckableItem(
                    label = "Always show tray",
                    checked = alwaysShowTray,
                    onCheckedChange = { checked ->
                        alwaysShowTray = checked
                        println("$logTag: Always show tray ${if (checked) "enabled" else "disabled"}")
                    },
                )

                CheckableItem(
                    label = "Hide on close",
                    checked = hideOnClose,
                    onCheckedChange = { checked ->
                        hideOnClose = checked
                        println("$logTag: Hide on close ${if (checked) "enabled" else "disabled"}")
                    },
                )

                Divider()

                Item(label = "Hide in tray") {
                    isWindowVisible = false
                }

                Item(label = "Exit", isEnabled = true) {
                    println("$logTag: Exiting the application")
                    dispose()
                    exitApplication()
                }

                Item(label = "Version 1.0.0", isEnabled = false)
            }
        }

        NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
            // Note: per-pixel transparency is not supported on the Tao backend,
            // so the window is only undecorated here.
            DecoratedWindow(
                undecorated = true,
                onCloseRequest = {
                    if (hideOnClose) {
                        isWindowVisible = false
                    } else {
                        exitApplication()
                    }
                },
                title = "Compose Desktop Application with Dynamic Tray Menu",
                visible = isWindowVisible,
                icon =
                    org.jetbrains.compose.resources
                        .painterResource(Res.drawable.icon),
            ) {
                App(textVisible, alwaysShowTray, hideOnClose) { alwaysShow, hideOnCloseState ->
                    alwaysShowTray = alwaysShow
                    hideOnClose = hideOnCloseState
                }
            }
        }
    }
