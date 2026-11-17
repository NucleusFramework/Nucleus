package dev.nucleusframework.tray.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.tray.internal.macos.MacOSMenuBarThemeDetector
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import java.util.function.Consumer

@Composable
public fun isMenuBarInDarkMode(): Boolean {
    return when (Platform.Current) {
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
