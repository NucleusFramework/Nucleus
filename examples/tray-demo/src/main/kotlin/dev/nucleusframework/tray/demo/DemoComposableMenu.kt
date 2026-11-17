package dev.nucleusframework.tray.demo

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import dev.nucleusframework.tray.demo.resources.icon2
import org.jetbrains.compose.resources.painterResource

/**
 * Showcases the @Composable menu DSL. No need to hoist `painterResource(...)` above
 * `nucleusApplication { … }` — every menu/submenu lambda is composable.
 */
fun main() = nucleusApplication(enableSingleInstance = false) {
    var isWindowVisible by remember { mutableStateOf(true) }
    var enableHeavyMode by remember { mutableStateOf(false) }

    Tray(
        icon = painterResource(Res.drawable.icon),
        tooltip = "Composable Menu Demo",
        primaryAction = { isWindowVisible = true },
        menuContent = {
            Item(label = "Open", icon = painterResource(Res.drawable.icon)) {
                isWindowVisible = true
            }

            SubMenu(
                label = "Advanced",
                icon = painterResource(Res.drawable.icon2),
            ) {
                Item(label = "Reload", icon = painterResource(Res.drawable.icon)) {
                    println("Reload")
                }
                Item(label = "Reset", icon = painterResource(Res.drawable.icon2)) {
                    println("Reset")
                }
            }

            Divider()

            CheckableItem(
                label = "Heavy mode",
                icon = painterResource(Res.drawable.icon2),
                checked = enableHeavyMode,
                onCheckedChange = { enableHeavyMode = it },
            )

            Divider()

            Item(label = "Exit", icon = painterResource(Res.drawable.icon2)) {
                dispose()
                exitApplication()
            }
        },
    )

    NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
        DecoratedWindow(
            onCloseRequest = { isWindowVisible = false },
            title = "Composable Menu Demo",
            visible = isWindowVisible,
            icon = painterResource(Res.drawable.icon),
        ) {
            TitleBar { Text("Composable Menu Demo") }
            val window = nucleusWindow
            LaunchedEffect(isWindowVisible) {
                if (isWindowVisible) {
                    window.toFront()
                }
            }
        }
    }
}
