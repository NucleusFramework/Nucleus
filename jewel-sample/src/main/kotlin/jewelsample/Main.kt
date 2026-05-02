package jewelsample

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.application.DecoratedWindow
import io.github.kdroidfilter.nucleus.application.NucleusBackend
import io.github.kdroidfilter.nucleus.application.nucleusApplication
import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.jewel.rememberJewelTitleBarStyle
import io.github.kdroidfilter.nucleus.window.jewel.rememberJewelWindowStyle
import jewelsample.view.TitleBarView
import jewelsample.viewmodel.MainViewModel
import jewelsample.viewmodel.MainViewModel.currentView
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.util.JewelLogger
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import java.awt.Dimension

@ExperimentalLayoutApi
fun main() {
    GraalVmInitializer.initialize()

    JewelLogger.getInstance("StandaloneSample").info("Starting Jewel Standalone sample")

    val icon = svgResource("icons/jewel-logo.svg")

    nucleusApplication(backend = NucleusBackend.Tao) {
        val textStyle = JewelTheme.createDefaultTextStyle()
        val editorStyle = JewelTheme.createEditorTextStyle()

        val systemIsDark = isSystemInDarkMode()
        val isDark = if (MainViewModel.theme == IntUiThemes.System) systemIsDark else MainViewModel.theme.isDark()
        val isTitleBarDark = MainViewModel.theme.isTitleBarDark()

        val darkTheme = JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        val lightTheme = JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)

        val contentTheme = if (isDark) darkTheme else lightTheme
        val titleBarTheme = if (isTitleBarDark) darkTheme else lightTheme

        DecoratedWindow(
            onCloseRequest = { exitApplication() },
            title = "Jewel standalone sample",
            icon = icon,
            state =
                rememberWindowState(
                    position = WindowPosition.Aligned(Alignment.Center),
                ),
            minimumSize = DpSize(800.dp, 400.dp),
            onKeyEvent = { keyEvent ->
                processKeyShortcuts(keyEvent = keyEvent, onNavigateTo = MainViewModel::onNavigateTo)
            },
            content = {
                IntUiTheme(
                    theme = titleBarTheme,
                    styling = ComponentStyling.default(),
                    swingCompatMode = MainViewModel.swingCompat,
                ) {
                    val jewelTitleBarStyle = rememberJewelTitleBarStyle()
                    val jewelWindowStyle = rememberJewelWindowStyle()
                    val titleBarIsDark = jewelTitleBarStyle.colors.background.luminance() < 0.5f
                    NucleusDecoratedWindowTheme(
                        isDark = titleBarIsDark,
                        windowStyle = jewelWindowStyle,
                        titleBarStyle = jewelTitleBarStyle,
                    ) {
                        TitleBarView()
                    }
                }
                IntUiTheme(
                    theme = contentTheme,
                    styling = ComponentStyling.default(),
                    swingCompatMode = MainViewModel.swingCompat,
                ) {
                    @OptIn(ExperimentalJewelApi::class)
                    ProvideMarkdownStyling { currentView.content() }
                }
            },
        )
    }
}

/*
   Alt + W -> Welcome
   Alt + M -> Markdown
   Alt + C -> Components
*/
private fun processKeyShortcuts(
    keyEvent: KeyEvent,
    onNavigateTo: (String) -> Unit,
): Boolean {
    if (!keyEvent.isAltPressed || keyEvent.type != KeyEventType.KeyDown) return false
    return when (keyEvent.key) {
        Key.W -> {
            onNavigateTo("Welcome")
            true
        }

        Key.M -> {
            onNavigateTo("Markdown")
            true
        }

        Key.C -> {
            onNavigateTo("Components")
            true
        }

        else -> false
    }
}

@Suppress("SameParameterValue")
@OptIn(ExperimentalResourceApi::class)
private fun svgResource(resourcePath: String): Painter =
    checkNotNull(ResourceLoader.javaClass.classLoader.getResourceAsStream(resourcePath)) {
        "Could not load resource $resourcePath: it does not exist or can't be read."
    }.readAllBytes()
        .decodeToSvgPainter(Density(1f))

private object ResourceLoader
