package com.example.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.example.demo.gallery.GalleryScreen
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.BasicTitleBar
import io.github.kdroidfilter.nucleus.window.TitleBarLayoutPolicy
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.newFullscreenControls

@Composable
fun FillCenterDemoWindow(
    visible: Boolean,
    onCloseRequest: () -> Unit,
    seedColor: Color,
) {
    if (!visible) return

    val fillCenterWindowState =
        rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            placement = WindowPlacement.Floating,
            size = DpSize(1340.dp, 480.dp),
        )

    MaterialDecoratedWindow(
        state = fillCenterWindowState,
        onCloseRequest = onCloseRequest,
        title = "Fill Title",
    ) {
        val demoTabs = remember { buildDemoWindowTabs() }
        var demoSelectedTab by remember { mutableStateOf("Nucleus") }

        BasicTitleBar(
            modifier = Modifier.newFullscreenControls().macOSLargeCornerRadius(),
            layoutPolicy = TitleBarLayoutPolicy.FillCenter,
        ) { _ ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Start)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "Start",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "End",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                FillTitleTabs(
                    tabs = demoTabs,
                    selectedTab = demoSelectedTab,
                    onSelect = { demoSelectedTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        DemoWindowTabContent(
            selectedTab = demoSelectedTab,
            seedColor = seedColor,
            window = window,
        )
    }
}

private fun buildDemoWindowTabs(): List<String> =
    buildList {
        addAll(listOf("Nucleus", "Gallery", "Taskbar"))
        add("Notifications (Common)")
        if (Platform.Current == Platform.MacOS ||
            Platform.Current == Platform.Linux ||
            Platform.Current == Platform.Windows
        ) {
            add("Notifications")
        }
        if (Platform.Current == Platform.Windows ||
            Platform.Current == Platform.Linux ||
            Platform.Current == Platform.MacOS
        ) {
            add("Launcher")
        }
        add("Media Control")
        add("Auto-Launch")
        add("Hotkeys")
        if (Platform.Current == Platform.MacOS) {
            add("Menu")
        }
    }

@Composable
private fun io.github.kdroidfilter.nucleus.window.TitleBarScope.FillTitleTabs(
    tabs: List<String>,
    selectedTab: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tabTitle ->
            val isSelected = tabTitle == selectedTab
            val hoverInteraction = remember { MutableInteractionSource() }
            val isHovered by hoverInteraction.collectIsHoveredAsState()

            val backgroundColor =
                when {
                    isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
                    isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }

            val textColor =
                if (isSelected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(backgroundColor)
                        .hoverable(hoverInteraction)
                        .titleBarClickable { onSelect(tabTitle) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tabTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DemoWindowTabContent(
    selectedTab: String,
    seedColor: Color,
    window: java.awt.Window,
) {
    when (selectedTab) {
        "Nucleus" -> NucleusContent()
        "Notifications (Common)" -> CommonNotificationsScreen()
        "Gallery" -> {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides
                    Density(
                        density = currentDensity.density * 0.75f,
                        fontScale = currentDensity.fontScale,
                    ),
            ) {
                GalleryScreen(seedColor = seedColor)
            }
        }
        "Taskbar" -> TaskbarProgressScreen(window)
        "Notifications" -> {
            when (Platform.Current) {
                Platform.MacOS -> NotificationsScreen()
                Platform.Linux -> LinuxNotificationsScreen()
                Platform.Windows -> WindowsNotificationsScreen()
                else -> {}
            }
        }
        "Launcher" -> {
            when (Platform.Current) {
                Platform.Windows -> WindowsLauncherScreen(window)
                Platform.MacOS -> MacOsLauncherScreen()
                Platform.Linux -> LauncherScreen()
                else -> {}
            }
        }
        "Media Control" -> MediaControlScreen()
        "Auto-Launch" -> AutoLaunchScreen()
        "Hotkeys" -> GlobalHotKeyScreen()
        "Menu" -> MacOsMenuScreen()
    }
}
