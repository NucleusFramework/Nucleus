package dev.nucleusframework.tray.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.menu.Key
import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar

/**
 * Demo application that showcases dynamic icon changes with callbacks.
 * This demo demonstrates how to change icons dynamically based on user interactions.
 */
fun main() =
    nucleusApplication(enableSingleInstance = false) {
        // Enable logging for debugging
        allowNucleusRuntimeLogging = true
        nucleusLoggingLevel = NucleusLoggingLevel.DEBUG

        val logTag = "DynamicIconsDemo"

        // Window visibility state
        var isWindowVisible by remember { mutableStateOf(true) }
        var alwaysShowTray by remember { mutableStateOf(true) }
        var hideOnClose by remember { mutableStateOf(true) }

        // Single-instance handling is now managed by nucleusApplication (disabled here)

        // Basic state for tray icon
        var currentTrayIcon by remember { mutableStateOf(Icons.Default.Notifications) }

        // States for menu item icons
        var weatherIcon by remember { mutableStateOf(Icons.Default.WbSunny) }
        var musicIcon by remember { mutableStateOf(Icons.Default.MusicNote) }
        var settingsIcon by remember { mutableStateOf(Icons.Default.Settings) }

        // States for theme and features
        var isDarkTheme by remember { mutableStateOf(false) }
        var isWeatherEnabled by remember { mutableStateOf(true) }
        var isMusicEnabled by remember { mutableStateOf(true) }

        // Counter to demonstrate onMenuOpened callback
        var menuOpenCount by remember { mutableIntStateOf(0) }

        // Always create the Tray composable, but make it conditional on visibility
        val showTray = alwaysShowTray || !isWindowVisible

        if (showTray) {
            Tray(
                icon = currentTrayIcon,
                tooltip = "Dynamic Icons Demo",
                primaryAction = {
                    isWindowVisible = true
                    println("$logTag: Primary action clicked")
                },
                onMenuOpened = {
                    menuOpenCount++
                    println("$logTag: Menu opened (count: $menuOpenCount)")
                },
            ) {
                // Weather submenu with dynamic icon
                SubMenu(label = "Weather", icon = weatherIcon) {
                    Item(label = "Sunny", icon = Icons.Default.WbSunny, shortcut = KeyShortcut(Key.Num1, meta = true)) {
                        println("$logTag: Weather set to Sunny")
                        weatherIcon = Icons.Default.WbSunny
                    }

                    Item(label = "Cloudy", icon = Icons.Default.Cloud, shortcut = KeyShortcut(Key.Num2, meta = true)) {
                        println("$logTag: Weather set to Cloudy")
                        weatherIcon = Icons.Default.Cloud
                    }

                    Item(label = "Rainy", icon = Icons.Default.Opacity) {
                        println("$logTag: Weather set to Rainy")
                        weatherIcon = Icons.Default.Opacity
                    }

                    Item(label = "Snowy", icon = Icons.Default.AcUnit) {
                        println("$logTag: Weather set to Snowy")
                        weatherIcon = Icons.Default.AcUnit
                    }
                }

                Divider()

                // Music submenu with dynamic icon
                SubMenu(label = "Music", icon = musicIcon) {
                    Item(label = "Play", icon = Icons.Default.PlayArrow, shortcut = KeyShortcut(Key.P, meta = true)) {
                        println("$logTag: Music playing")
                        musicIcon = Icons.Default.PlayArrow
                    }

                    Item(
                        label = "Pause",
                        icon = Icons.Default.Pause,
                        shortcut = KeyShortcut(Key.P, meta = true, shift = true),
                    ) {
                        println("$logTag: Music paused")
                        musicIcon = Icons.Default.Pause
                    }

                    Item(label = "Stop", icon = Icons.Default.Stop) {
                        println("$logTag: Music stopped")
                        musicIcon = Icons.Default.Stop
                    }

                    Divider()

                    Item(
                        label = "Volume Up",
                        icon = Icons.Default.VolumeUp,
                        shortcut = KeyShortcut(Key.UpArrow, meta = true),
                    ) {
                        println("$logTag: Volume increased")
                    }

                    Item(
                        label = "Volume Down",
                        icon = Icons.Default.VolumeDown,
                        shortcut = KeyShortcut(Key.DownArrow, meta = true),
                    ) {
                        println("$logTag: Volume decreased")
                    }

                    Item(
                        label = "Mute",
                        icon = Icons.Default.VolumeMute,
                        shortcut = KeyShortcut(Key.M, meta = true, shift = true),
                    ) {
                        println("$logTag: Volume muted")
                    }
                }

                Divider()

                // Settings with dynamic icon
                SubMenu(label = "Settings", icon = settingsIcon) {
                    CheckableItem(
                        label = "Dark Theme",
                        icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        checked = isDarkTheme,
                        onCheckedChange = { checked ->
                            isDarkTheme = checked
                            // Change multiple icons based on theme
                            if (checked) {
                                settingsIcon = Icons.Default.Nightlight
                                currentTrayIcon = Icons.Default.DarkMode
                            } else {
                                settingsIcon = Icons.Default.Settings
                                currentTrayIcon = Icons.Default.LightMode
                            }
                            println("$logTag: Dark theme ${if (checked) "enabled" else "disabled"}")
                        },
                    )

                    Divider()

                    CheckableItem(
                        label = "Weather Updates",
                        icon = Icons.Default.WbSunny,
                        checked = isWeatherEnabled,
                        onCheckedChange = { checked ->
                            isWeatherEnabled = checked
                            println("$logTag: Weather updates ${if (checked) "enabled" else "disabled"}")
                        },
                    )

                    CheckableItem(
                        label = "Music Controls",
                        icon = Icons.Default.MusicNote,
                        checked = isMusicEnabled,
                        onCheckedChange = { checked ->
                            isMusicEnabled = checked
                            println("$logTag: Music controls ${if (checked) "enabled" else "disabled"}")
                        },
                    )
                }

                Divider()

                // Tray visibility settings
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

                Item(
                    label = "Hide in tray",
                    icon = Icons.Default.VisibilityOff,
                    shortcut = KeyShortcut(Key.H, meta = true),
                ) {
                    isWindowVisible = false
                    println("$logTag: Application hidden in tray")
                }

                Item(label = "Exit", icon = Icons.Default.ExitToApp, shortcut = KeyShortcut(Key.Q, meta = true)) {
                    println("$logTag: Exiting application")
                    dispose()
                    exitApplication()
                }
            }
        }

        NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
            DecoratedWindow(
                onCloseRequest = {
                    if (hideOnClose) {
                        isWindowVisible = false
                    } else {
                        exitApplication()
                    }
                },
                title = "Dynamic Icons Demo",
                visible = isWindowVisible,
            ) {
                TitleBar { Text("Dynamic Icons Demo") }
                nucleusWindow.toFront()

                // Simple UI to demonstrate icon changes
                MaterialTheme(
                    colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme(),
                ) {
                    Surface {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Dynamic Icons Demo",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )

                            Text(
                                "This demo showcases dynamic icon changes in the system tray menu.",
                                modifier = Modifier.padding(bottom = 8.dp),
                            )

                            Text(
                                "Tray menu opened $menuOpenCount times",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 24.dp),
                            )

                            Button(
                                onClick = {
                                    isDarkTheme = !isDarkTheme
                                    if (isDarkTheme) {
                                        settingsIcon = Icons.Default.Nightlight
                                        currentTrayIcon = Icons.Default.DarkMode
                                    } else {
                                        settingsIcon = Icons.Default.Settings
                                        currentTrayIcon = Icons.Default.LightMode
                                    }
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                Text("Toggle Theme")
                            }

                            Button(
                                onClick = {
                                    val icons =
                                        listOf(
                                            Icons.Default.WbSunny,
                                            Icons.Default.Cloud,
                                            Icons.Default.Opacity,
                                            Icons.Default.AcUnit,
                                        )
                                    weatherIcon = icons.random()
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                Text("Change Weather Icon")
                            }

                            Button(
                                onClick = {
                                    val icons =
                                        listOf(
                                            Icons.Default.PlayArrow,
                                            Icons.Default.Pause,
                                            Icons.Default.Stop,
                                        )
                                    musicIcon = icons.random()
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                Text("Change Music Icon")
                            }

                            Button(
                                onClick = {
                                    isWindowVisible = false
                                },
                            ) {
                                Text("Hide to Tray")
                            }
                        }
                    }
                }
            }
        }
    }
