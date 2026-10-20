package dev.nucleusframework.lab.probes.window.theming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.materialkolor.dynamicColorScheme
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.lab.core.format.argbHex
import dev.nucleusframework.lab.probes.window.theming.gallery.jewel.JewelGallery
import dev.nucleusframework.lab.probes.window.theming.gallery.material2.Material2Gallery
import dev.nucleusframework.lab.probes.window.theming.gallery.material3.Material3Gallery
import dev.nucleusframework.lab.probes.window.theming.gallery.material3.Material3GallerySeed
import dev.nucleusframework.window.LocalIsDarkTheme
import dev.nucleusframework.window.jewel.JewelDecoratedWindow
import dev.nucleusframework.window.jewel.JewelTitleBar
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import androidx.compose.material.MaterialTheme as M2Theme
import androidx.compose.material.Text as M2Text
import androidx.compose.material.darkColors as m2Dark
import androidx.compose.material.lightColors as m2Light
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import dev.nucleusframework.window.material.MaterialDecoratedWindow as M3Window
import dev.nucleusframework.window.material.MaterialTitleBar as M3TitleBar
import dev.nucleusframework.window.material2.MaterialDecoratedWindow as M2Window
import dev.nucleusframework.window.material2.MaterialTitleBar as M2TitleBar
import org.jetbrains.jewel.ui.component.Text as JewelText

/**
 * [system]'s own window and title bar around its component gallery, every page drawn with that
 * design system's components (a specimen: Material and Jewel are used directly here).
 */
@Composable
internal fun NucleusApplicationScope.ThemedWindow(
    vm: ThemingViewModel,
    system: DesignSystem,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val config = state.config(system)
    val dark =
        when (config.theme) {
            ThemeChoice.System -> isSystemInDarkMode()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
    val windowState = rememberWindowState(size = DpSize(1180.dp, 800.dp))
    val title = "${system.label} · ${if (dark) "dark" else "light"}"
    when (system) {
        DesignSystem.Material2 ->
            M2Theme(colors = if (dark) m2Dark() else m2Light()) {
                M2Window(onCloseRequest = close, state = windowState, title = title) {
                    Column(Modifier.fillMaxSize()) {
                        M2TitleBar(
                            gradientStartColor = if (config.gradient) M2Theme.colors.primary else Color.Unspecified,
                        ) { _ -> M2Text(title) }
                        ReportStyles(vm, system)
                        Material2Gallery(Modifier.weight(1f))
                    }
                }
            }
        DesignSystem.Material3 ->
            M3Theme(colorScheme = dynamicColorScheme(seedColor = Material3GallerySeed, isDark = dark)) {
                M3Window(onCloseRequest = close, state = windowState, title = title) {
                    Column(Modifier.fillMaxSize()) {
                        M3TitleBar(
                            gradientStartColor =
                                if (config.gradient) M3Theme.colorScheme.primary else Color.Unspecified,
                        ) { _ -> M3Text(title) }
                        ReportStyles(vm, system)
                        Material3Gallery(seedColor = Material3GallerySeed, modifier = Modifier.weight(1f))
                    }
                }
            }
        DesignSystem.Jewel ->
            IntUiTheme(
                theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
                styling = ComponentStyling.default(),
            ) {
                JewelDecoratedWindow(onCloseRequest = close, state = windowState, title = title) {
                    Column(Modifier.fillMaxSize()) {
                        JewelTitleBar(
                            gradientStartColor =
                                if (config.gradient) JewelTheme.globalColors.outlines.focused else Color.Unspecified,
                        ) { _ -> JewelText(title) }
                        ReportStyles(vm, system)
                        JewelGallery(Modifier.weight(1f))
                    }
                }
            }
    }
}

/** Sends the styles this window resolved back to the probe. */
@Composable
private fun ReportStyles(
    vm: ThemingViewModel,
    system: DesignSystem,
) {
    val titleBar = LocalTitleBarStyle.current.colors
    val window = LocalDecoratedWindowStyle.current.colors
    val report =
        StyleReport(
            isDark = LocalIsDarkTheme.current,
            titleBarBackground = titleBar.background.hex(),
            titleBarInactiveBackground = titleBar.inactiveBackground.hex(),
            titleBarContent = titleBar.content.hex(),
            windowBorder = window.border.hex(),
            windowBackground = window.background.hex(),
        )
    LaunchedEffect(report) { vm.onIntent(ThemingIntent.Reported(system, report)) }
}

private fun Color.hex(): String = if (this == Color.Unspecified) "unspecified" else toArgb().toLong().argbHex()
