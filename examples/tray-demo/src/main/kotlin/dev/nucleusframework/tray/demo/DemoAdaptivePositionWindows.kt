package dev.nucleusframework.tray.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.tray.app.getTrayPosition
import dev.nucleusframework.tray.app.getTrayWindowPosition
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import org.jetbrains.compose.resources.painterResource

fun main() = nucleusApplication(enableSingleInstance = false) {
    allowNucleusRuntimeLogging = false
    nucleusLoggingLevel = NucleusLoggingLevel.DEBUG
    val logTag = "NativeTray"

    println("$logTag: TrayPosition: ${getTrayPosition()}")

    var isWindowVisible by remember { mutableStateOf(true) }
    var textVisible by remember { mutableStateOf(false) }
    var alwaysShowTray by remember { mutableStateOf(true) }
    var hideOnClose by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    var initialChecked by remember { mutableStateOf(true) }

    // Always create the Tray composable, but make it conditional on visibility
    // This ensures it's recomposed when alwaysShowTray changes
    val showTray = alwaysShowTray || !isWindowVisible

    if (showTray) {
        // Use alwaysShowTray as a key to force recomposition when it changes
        Tray(
           iconContent = {
               Icon(
                   Icons.Default.Notifications,
                   contentDescription = "Application Icon",
                   modifier = Modifier.fillMaxSize(),
                   tint = Color.White,
               )
           },
            primaryAction = {
                isWindowVisible = true
                println("$logTag: On Primary Clicked")
            },
            tooltip = "My Application"
        ) {

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

            // Checkable Items
            CheckableItem(
                label = "Enable notifications",
                checked = notificationsEnabled,
                onCheckedChange = { isChecked ->
                    notificationsEnabled = isChecked
                    println("$logTag: Notifications ${if (isChecked) "enabled" else "disabled"}")
                }
            )
            CheckableItem(
                label = "Initial Checked",
                checked = initialChecked,
                onCheckedChange = { isChecked ->
                    initialChecked = isChecked
                    println("$logTag: Initial Checked ${if (isChecked) "enabled" else "disabled"}")
                }
            )

            Divider()

            Item(label = "About") {
                println("$logTag: Application v1.0 - Developed by Elyahou")
            }

            Divider()

            CheckableItem(
                label = "Always show tray",
                checked = alwaysShowTray,
                onCheckedChange = { isChecked ->
                    alwaysShowTray = isChecked
                    println("$logTag: Always show tray ${if (isChecked) "enabled" else "disabled"}")
                }
            )

            CheckableItem(
                label = "Hide on close",
                checked = hideOnClose,
                onCheckedChange = { isChecked ->
                    hideOnClose = isChecked
                    println("$logTag: Hide on close ${if (isChecked) "enabled" else "disabled"}")
                }
            )

            Divider()


            Item(label = "Exit", isEnabled = true) {
                println("$logTag: Exiting the application")
                dispose()
                exitApplication()
            }

            Item(label = "Version 1.0.0", isEnabled = false)
        }
    }


    val windowWidth = 300
    val windowHeight = 600
    val windowPosition = getTrayWindowPosition(windowWidth, windowHeight)

    NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
        DecoratedWindow(
            onCloseRequest = {
                if (hideOnClose) {
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            },
            state = WindowState(
                width = windowWidth.dp,
                height = windowHeight.dp,
                position = windowPosition
            ),
            title = "Compose Desktop Application with Two Screens",
            visible = isWindowVisible,
            icon = painterResource(Res.drawable.icon) // Optional: Set window icon
        ) {
            TitleBar { Text("Compose Desktop Application with Two Screens") }
            App(textVisible, alwaysShowTray, hideOnClose) { alwaysShow, hideOnCloseState ->
                alwaysShowTray = alwaysShow
                hideOnClose = hideOnCloseState
            }
        }
    }
}
