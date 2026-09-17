package dev.nucleusframework.scaffolddemo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.WindowsBackdrop
import dev.nucleusframework.window.macOSLargeCornerRadius

private val DemoDarkColors =
    darkColorScheme(
        primary = Color(0xFF8AA4FF),
        surface = Color(0xFF15171C),
        surfaceContainer = Color(0xFF1C1F26),
        surfaceContainerHigh = Color(0xFF232730),
        background = Color(0xFF101216),
    )

private val DemoLightColors =
    lightColorScheme(
        primary = Color(0xFF3F5DDB),
        surface = Color(0xFFF7F8FB),
        surfaceContainer = Color(0xFFEDEFF5),
        surfaceContainerHigh = Color(0xFFE4E7EF),
        background = Color(0xFFFBFCFE),
    )

fun main() =
    nucleusApplication {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "Nucleus Window Lab",
            state = rememberWindowState(width = 1080.dp, height = 720.dp),
            minimumSize = DpSize(880.dp, 560.dp),
        ) {
            val windowScope = this
            // ThemeMode.System (the default) follows the OS live; Light/Dark
            // override until the title-bar button cycles back to System.
            val systemDark = isSystemInDarkMode()
            val demo = remember { DemoState(initialSystemInDark = systemDark) }
            LaunchedEffect(systemDark) { demo.systemInDark = systemDark }
            val colors = if (demo.darkTheme) DemoDarkColors else DemoLightColors

            // The Tao backend gives every window its own ComposeScene, so the
            // theme lives inside the window — and the two lines below are all
            // it takes to hand it to the chrome: the colour behind everything
            // Compose does not paint, and the appearance of the native
            // surfaces (traffic lights, system materials).
            MaterialTheme(colorScheme = colors) {
                WindowBackground(colors.background)
                WindowAppearance(
                    if (demo.darkTheme) WindowAppearanceMode.Dark else WindowAppearanceMode.Light,
                )
                // Windows 11 only, and deliberately named so. DWM composites
                // the material behind the window; everything this app leaves
                // unpainted shows it.
                WindowsBackdrop(demo.backdrop, demo.backdropTint.color, demo.backdropTier)
                WindowScaffold(
                    modifier = Modifier.macOSLargeCornerRadius(),
                    titleBar = { windowScope.DemoToolbar(demo) },
                    titleBarPlacement =
                        if (demo.overlay) {
                            TitleBarPlacement.Overlay(autoHideInFullscreen = false)
                        } else {
                            TitleBarPlacement.Docked
                        },
                ) { contentPadding ->
                    Row(Modifier.fillMaxSize()) {
                        DemoSidebar(demo, contentPadding)
                        // Painted by default: while a glass region is active
                        // the Compose surface is cleared to transparent, so any
                        // unpainted area would show the desktop. A Windows
                        // backdrop is the one case where that is the point —
                        // an opaque background would hide the material.
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            color = if (demo.backdropActive) Color.Transparent else colors.background,
                        ) {
                            DemoContent(demo, contentPadding)
                        }
                    }
                }
            }
        }
    }
