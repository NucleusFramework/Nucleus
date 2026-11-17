package dev.nucleusframework.tray.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.internal.macos.MacOSMenuBarThemeDetector
import java.util.function.Consumer

/**
 * Whether the bar hosting tray icons is dark, so a monochrome icon can pick a contrasting color.
 * macOS observes the menu bar appearance, Windows and KDE follow the system theme, and the other
 * Linux desktops are treated as dark (their panels almost always are).
 */
@Composable
public fun isMenuBarInDarkMode(): Boolean =
    when (Platform.Current) {
        Platform.MacOS -> isMacOsMenuBarInDarkMode()
        Platform.Windows -> isSystemInDarkMode()
        Platform.Linux ->
            when (LinuxDesktopEnvironment.Current) {
                LinuxDesktopEnvironment.Gnome -> true
                LinuxDesktopEnvironment.KDE -> isSystemInDarkMode()
                LinuxDesktopEnvironment.XFCE -> true
                LinuxDesktopEnvironment.Cinnamon -> true
                LinuxDesktopEnvironment.Mate -> true
                LinuxDesktopEnvironment.Unknown -> true
            }
        else -> true
    }

@Composable
internal fun isMacOsMenuBarInDarkMode(): Boolean {
    val darkModeState = remember { mutableStateOf(MacOSMenuBarThemeDetector.isDark()) }
    DisposableEffect(Unit) {
        val listener =
            Consumer<Boolean> { newValue ->
                darkModeState.value = newValue
            }
        MacOSMenuBarThemeDetector.registerListener(listener)
        onDispose {
            MacOSMenuBarThemeDetector.removeListener(listener)
        }
    }
    return darkModeState.value
}
