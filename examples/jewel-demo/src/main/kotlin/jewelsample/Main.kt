package jewelsample

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.jewel.JewelDecoratedWindow
import jewelsample.view.TitleBarView
import jewelsample.viewmodel.MainViewModel
import jewelsample.viewmodel.MainViewModel.currentView
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToSvgPainter
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@ExperimentalLayoutApi
fun main() =
    nucleusApplication {
        remember {
            JewelLogger.getInstance("StandaloneSample").info("Starting Jewel Standalone sample")
            true
        }
        val icon = remember { svgResource("icons/jewel-logo.svg") }
        val textStyle = JewelTheme.createDefaultTextStyle()
        val editorStyle = JewelTheme.createEditorTextStyle()

        val systemIsDark = isSystemInDarkMode()
        val isDark = if (MainViewModel.theme == IntUiThemes.System) systemIsDark else MainViewModel.theme.isDark()
        val isTitleBarDark = MainViewModel.theme.isTitleBarDark()

        val darkTheme = JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        val lightTheme = JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)

        val contentTheme = if (isDark) darkTheme else lightTheme
        val titleBarTheme = if (isTitleBarDark) darkTheme else lightTheme

        // The title-bar theme wraps the window: JewelDecoratedWindow reads it at
        // the call site for the native deco + TitleBar styling, and the Tao
        // scene bridge re-exposes it to the content inside the window.
        IntUiTheme(
            theme = titleBarTheme,
            styling = ComponentStyling.default(),
            swingCompatMode = MainViewModel.swingCompat,
        ) {
            JewelDecoratedWindow(
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
            ) {
                // JewelDecoratedWindow already installs the Jewel spellcheck
                // text-context menu; capture it before the content theme below
                // re-provides Jewel's stock one, and restore it inside.
                @Suppress("DEPRECATION")
                val windowTextContextMenu = androidx.compose.foundation.text.LocalTextContextMenu.current
                TitleBarView()
                IntUiTheme(
                    theme = contentTheme,
                    styling = ComponentStyling.default(),
                    swingCompatMode = MainViewModel.swingCompat,
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.text.LocalTextContextMenu provides windowTextContextMenu,
                    ) {
                        ProvideMarkdownStyling { currentView.content() }
                    }
                }
            }
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
