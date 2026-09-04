package dev.nucleusframework.tabsdemo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.material.rememberMaterialTitleBarStyle
import dev.nucleusframework.window.material.rememberMaterialWindowStyle
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle

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

/**
 * Chrome-like tab workspace demo.
 *
 * Three documents are declared once, at application scope, as tabs of one
 * `TabWorkspace`. `TabWindows` composes the windows: one to start with, one
 * more as soon as a tab is dragged out of a strip, one fewer when the last tab
 * leaves it. Dragging a tab onto another window's strip inserts it where it is
 * dropped; dragging the only tab of a window moves the window and merges it
 * into whatever strip it lands on. The editor state in a tab is
 * `rememberSaveable`, so it comes along.
 */
fun main() =
    nucleusApplication {
        val demo = remember { DemoState() }
        val dark = isSystemInDarkMode()
        val colors = if (dark) DemoDarkColors else DemoLightColors

        // The theme sits *above* the windows, not inside one: the workspace
        // opens and closes them, and the locals established here are bridged
        // into every scene it creates — which is where the tab strip in the
        // title bar reads its colours from.
        DemoTheme(colors) {
            TabWindows(
                workspace = demo.workspace,
                strip = { DemoTabStrip(onNewTab = demo::open) },
                // Per-window chrome goes here, since the app opens no window
                // of its own: the receiver is the window being composed.
                windowWrapper = { content ->
                    WindowBackground(colors.background)
                    WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
                    Surface(Modifier.fillMaxSize(), color = colors.background) { content() }
                },
                onLastWindowClosed = ::exitApplication,
            )

            for (document in demo.documents) {
                key(document.id) {
                    Tab(demo.workspace, id = document.id, title = document.title) {
                        DocumentContent(demo, document)
                    }
                    DropClosedTab(demo, document.id)
                }
            }
        }
    }

/**
 * Keeps the document list in step with the workspace.
 *
 * Closing a tab — the × on the tab, or the last tab of a window that the user
 * closes — is a workspace call, and the workspace does not own the app's list.
 * A document still declared once its tab is gone would be registered again and
 * hosted nowhere, so it is dropped here instead.
 */
@Composable
private fun DropClosedTab(
    demo: DemoState,
    id: String,
) {
    // Non-null on the composition that declared it, so this only fires once
    // the workspace has really let the tab go.
    val closed = demo.workspace.tab(id) == null
    LaunchedEffect(closed) {
        if (closed) demo.forget(id)
    }
}

/**
 * Material colours plus the window-chrome styles derived from them.
 *
 * Every Tao window owns its own ComposeScene, so this would normally be
 * established per window; with tabs the app has no window call site, so it is
 * established once here and bridged into each window the workspace opens.
 */
@Composable
private fun DemoTheme(
    colors: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalTitleBarStyle provides rememberMaterialTitleBarStyle(colors),
            LocalDecoratedWindowStyle provides rememberMaterialWindowStyle(colors),
            content = content,
        )
    }
}
