package dev.nucleusframework.tray.demo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar

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

        // Single-instance handling is now managed by nucleusApplication (disabled here)

        // Always create the Tray composable, but make it conditional on visibility
        // This ensures it's recomposed when alwaysShowTray changes
        val showTray = alwaysShowTray || !isWindowVisible

        if (showTray) {
            Tray(
                iconContent = {
                    // Use alwaysShowTray as a key to force recomposition when it changes
                    val alpha = if (alwaysShowTray) 0.5f else 0.5f
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(
                                    RoundedCornerShape(300.dp),
                                ).background(Color.Red.copy(alpha = alpha)),
                    )
                },
                primaryAction = {
                    isWindowVisible = true
                    println("$logTag: On Primary Clicked")
                },
                tooltip = "My Application",
            )
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
                title = "Compose Desktop Application with Two Screens",
                visible = isWindowVisible,
                icon =
                    org.jetbrains.compose.resources
                        .painterResource(Res.drawable.icon),
                // Optional: Set window icon
            ) {
                TitleBar { Text("Compose Desktop Application with Two Screens") }
                App(textVisible, alwaysShowTray, hideOnClose) { alwaysShow, hideOnCloseState ->
                    alwaysShowTray = alwaysShow
                    hideOnClose = hideOnCloseState
                }
            }
        }
    }
