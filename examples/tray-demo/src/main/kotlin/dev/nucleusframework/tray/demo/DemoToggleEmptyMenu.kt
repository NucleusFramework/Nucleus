package dev.nucleusframework.tray.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import dev.nucleusframework.tray.demo.resources.icon2
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import org.jetbrains.compose.resources.painterResource

/**
 * DemoToggleEmptyMenu
 *
 * Minimal sample to test the Linux bug when switching the tray menu
 * from empty to non-empty (and back). Left-click the tray icon to toggle
 * the presence of a single menu item. When the flag is false, the menuContent
 * is intentionally left empty.
 */
fun main() =
    nucleusApplication(enableSingleInstance = false) {
        // Enable logging to help diagnose behavior on Linux
        allowNucleusRuntimeLogging = false
        nucleusLoggingLevel = NucleusLoggingLevel.DEBUG

        var showMenuItem by remember { mutableStateOf(false) }
        val icon = painterResource(Res.drawable.icon)
        val icon2 = painterResource(Res.drawable.icon2)

        Tray(
            iconContent = {
                Image(
                    painter = icon,
                    contentDescription = "ComposeNativeTray Demo",
                    modifier = Modifier.fillMaxSize(),
                )
            },
            primaryAction = {
                // Toggle between empty and non-empty menu
                showMenuItem = !showMenuItem
                println("Toggled showMenuItem to $showMenuItem")
            },
            tooltip = "Toggle Empty Menu",
        ) {
            if (showMenuItem) {
                Item("I'm here when toggled ON") {
                    // Clicking this item turns the menu empty again on next open
                    showMenuItem = false
                    println("Clicked showMenuItem to $showMenuItem")
                }
            }
        }
        NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "Compose Native Tray Demo",
            ) {
                TitleBar { Text("Compose Native Tray Demo") }
                Text("Compose Native Tray Demo")
            }
        }
    }
