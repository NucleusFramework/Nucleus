package dev.nucleusframework.jeweltabsdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.jewel.rememberJewelTitleBarStyle
import dev.nucleusframework.window.jewel.rememberJewelWindowStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling

/**
 * The Chrome-like tab workspace wearing IntelliJ's tab chrome.
 *
 * Same archetype as `examples/tabs-demo` — files declared once as tabs of one
 * `TabWorkspace`, windows that follow the tabs — with Jewel's `TabStrip` in
 * place of the stock strip. Everything a tab archetype needs from its chrome is
 * a modifier contract, so swapping the whole design system is one composable:
 * see [JewelEditorTabStrip].
 */
fun main() =
    nucleusApplication {
        val demo = remember { DemoState() }
        val dark = isSystemInDarkMode()

        val textStyle = JewelTheme.createDefaultTextStyle()
        val editorTextStyle = JewelTheme.createEditorTextStyle()
        val theme =
            if (dark) {
                JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorTextStyle)
            } else {
                JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorTextStyle)
            }

        // Both themes sit *above* the windows: the workspace opens and closes
        // them, so there is no window call site for `JewelDecoratedWindow` to
        // install the Jewel window and title-bar styles at. Established here,
        // they are bridged into every scene the workspace creates — which is
        // where the strip in each title bar reads its colours from.
        IntUiTheme(theme = theme, styling = ComponentStyling.default()) {
            NucleusDecoratedWindowTheme(
                isDark = dark,
                windowStyle = rememberJewelWindowStyle(),
                titleBarStyle = rememberJewelTitleBarStyle(),
            ) {
                val panel = JewelTheme.globalColors.panelBackground
                TabWindows(
                    workspace = demo.workspace,
                    strip = { JewelEditorTabStrip(onNewTab = demo::open) },
                    // Per-window chrome, since the app opens no window itself.
                    windowWrapper = { content ->
                        WindowBackground(panel)
                        WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
                        Box(Modifier.fillMaxSize().background(panel)) { content() }
                    },
                    onLastWindowClosed = ::exitApplication,
                )

                for (document in demo.documents) {
                    key(document.id) {
                        Tab(demo.workspace, id = document.id, title = document.title) {
                            EditorContent(demo, document)
                        }
                        DropClosedTab(demo, document.id)
                    }
                }
            }
        }
    }

/**
 * Keeps the file list in step with the workspace: closing a tab is a workspace
 * call, and a file still declared once its tab is gone would be registered
 * again and hosted nowhere.
 */
@Composable
private fun DropClosedTab(
    demo: DemoState,
    id: String,
) {
    val closed = demo.workspace.tab(id) == null
    LaunchedEffect(closed) {
        if (closed) demo.forget(id)
    }
}
