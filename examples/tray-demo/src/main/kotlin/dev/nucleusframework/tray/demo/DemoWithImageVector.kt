package dev.nucleusframework.tray.demo

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.demo.svg.AcademicCap
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.tray.app.getTrayPosition
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import org.jetbrains.compose.resources.painterResource

/**
 * Demo application that showcases the use of the ImageVector API for tray icons.
 * This demo uses the AcademicCap vector as the tray icon.
 */
fun main() = nucleusApplication(enableSingleInstance = false) {
    allowNucleusRuntimeLogging = false
    nucleusLoggingLevel = NucleusLoggingLevel.DEBUG

    val logTag = "ImageVectorTrayDemo"
    
    println("$logTag: TrayPosition: ${getTrayPosition()}")

    var isWindowVisible by remember { mutableStateOf(true) }
    var alwaysShowTray by remember { mutableStateOf(true) }
    var hideOnClose by remember { mutableStateOf(true) }
    
    // Tint color state for the icon
    var iconTint by remember { mutableStateOf<Color?>(null) } // null means use default (white/black based on theme)

    // Single-instance handling is now managed by nucleusApplication (disabled here)

    // Always create the Tray composable, but make it conditional on visibility
    val showTray = alwaysShowTray || !isWindowVisible

    if (showTray) {
        // Using the ImageVector API with the AcademicCap vector
        Tray(
            icon = AcademicCap,  // Using the ImageVector directly
            tint = iconTint,     // Using the tint parameter (null means auto-adapt to theme)
            tooltip = "Academic Cap Demo",
            primaryAction = {
                isWindowVisible = true
                println("$logTag: Primary action clicked")
            }
        ) {
            // Menu items to demonstrate changing the tint color
            SubMenu(label = "Icon Color") {
                Item(label = "Default (Auto)") {
                    iconTint = null
                    println("$logTag: Icon color set to default (auto)")
                }
                Item(label = "Red") {
                    iconTint = Color.Red
                    println("$logTag: Icon color set to red")
                }
                Item(label = "Green") {
                    iconTint = Color.Green
                    println("$logTag: Icon color set to green")
                }
                Item(label = "Blue") {
                    iconTint = Color.Blue
                    println("$logTag: Icon color set to blue")
                }
                Item(label = "Yellow") {
                    iconTint = Color.Yellow
                    println("$logTag: Icon color set to yellow")
                }
            }

            Divider()

            // Standard menu items
            Item(label = "About") {
                println("$logTag: ImageVector API Demo - Using AcademicCap vector")
            }

            Divider()

            // Settings for tray visibility
            CheckableItem(
                label = "Always show tray",
                checked = alwaysShowTray,
                onCheckedChange = { checked ->
                    alwaysShowTray = checked
                    println("$logTag: Always show tray ${if (checked) "enabled" else "disabled"}")
                }
            )

            CheckableItem(
                label = "Hide on close",
                checked = hideOnClose,
                onCheckedChange = { checked ->
                    hideOnClose = checked
                    println("$logTag: Hide on close ${if (checked) "enabled" else "disabled"}")
                }
            )

            Divider()

            Item(label = "Hide in tray") {
                isWindowVisible = false
                println("$logTag: Application hidden in tray")
            }

            Item(label = "Exit") {
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
            title = "ImageVector Tray Demo - AcademicCap",
            visible = isWindowVisible,
            icon = painterResource(Res.drawable.icon)
        ) {
            TitleBar { Text("ImageVector Tray Demo - AcademicCap") }
            nucleusWindow.toFront()
            App(
                textVisible = true,
                alwaysShowTray = alwaysShowTray,
                hideOnClose = hideOnClose
            ) { alwaysShow, hideOnCloseState ->
                alwaysShowTray = alwaysShow
                hideOnClose = hideOnCloseState
            }
        }
    }
}