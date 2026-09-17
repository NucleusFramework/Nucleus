package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.utils.linux.LinuxButtonLayout

@Composable
public fun createLinuxTitleBarStyle(style: TitleBarStyle): TitleBarStyle =
    remember(style) {
        style.copy(
            colors =
                style.colors.copy(
                    iconButtonHoveredBackground = Color.Transparent,
                    iconButtonPressedBackground = Color.Transparent,
                ),
        )
    }

/**
 * Returns KDE edge padding on the side where control buttons are placed.
 * On non-KDE desktops returns zero padding.
 */
public fun kdePaddingForButtonLayout(): PaddingValues {
    if (LinuxDesktopEnvironment.Current != LinuxDesktopEnvironment.KDE) {
        return PaddingValues(0.dp)
    }
    return if (LinuxButtonLayout.readSystem().controlsOnRight) {
        PaddingValues(end = 4.dp)
    } else {
        PaddingValues(start = 4.dp)
    }
}
