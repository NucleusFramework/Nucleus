package dev.nucleusframework.satellitedemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.material.MaterialTitleBar
import dev.nucleusframework.window.material.rememberMaterialTitleBarStyle
import dev.nucleusframework.window.material.rememberMaterialWindowStyle
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.SatelliteScope

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
 * Satellite workspace demo.
 *
 * Two document windows join one `SatelliteWorkspace`; an Inspector and a Tools
 * palette are declared against it, once, at application scope. Floating
 * satellites belong to whichever document was focused last (or the pinned
 * one), follow it, and survive its closing by moving on to the other. Either
 * satellite can be docked into a document's `DockLayout` and lifted off again
 * in place, with its `rememberSaveable` state intact.
 */
fun main() =
    nucleusApplication {
        val demo = remember { DemoState() }
        val dark = isSystemInDarkMode()
        val colors = if (dark) DemoDarkColors else DemoLightColors

        DocumentWindow(
            demo = demo,
            documentId = DocumentId.A,
            colors = colors,
            dark = dark,
            position = WindowPosition.Absolute(DOCUMENT_A_X_DP.dp, DOCUMENT_Y_DP.dp),
            onCloseRequest = ::exitApplication,
        )

        if (demo.showDocumentB) {
            DocumentWindow(
                demo = demo,
                documentId = DocumentId.B,
                colors = colors,
                dark = dark,
                position = WindowPosition.Absolute(DOCUMENT_B_X_DP.dp, DOCUMENT_Y_DP.dp),
                onCloseRequest = { demo.showDocumentB = false },
            )
        }

        // The satellites. Declared here, next to the windows, not inside one:
        // the workspace decides which window hosts them. The theme wrapped
        // around them is bridged into the floating windows' own scenes, which
        // is where their chrome comes from; docked, they inherit the host's.
        DemoTheme(colors) {
            Satellite(
                workspace = demo.workspace,
                id = DemoState.INSPECTOR_ID,
                title = "Inspector",
                initialPlacement = DemoState.InspectorPlacement,
                hideWhileOwnerFullscreenOrMaximized = demo.hideWhenParentFills,
            ) {
                SatelliteSurface(colors) { InspectorContent(demo, this) }
            }
            Satellite(
                workspace = demo.workspace,
                id = DemoState.TOOLS_ID,
                title = "Tools",
                initialPlacement = DemoState.ToolsPlacement,
                hideWhileOwnerFullscreenOrMaximized = demo.hideWhenParentFills,
            ) {
                SatelliteSurface(colors) { ToolsContent(this) }
            }
        }
    }

@Composable
private fun NucleusApplicationScope.DocumentWindow(
    demo: DemoState,
    documentId: DocumentId,
    colors: ColorScheme,
    dark: Boolean,
    position: WindowPosition,
    onCloseRequest: () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onCloseRequest,
        title = documentId.title,
        state =
            rememberWindowState(
                width = DOCUMENT_WIDTH_DP.dp,
                height = DOCUMENT_HEIGHT_DP.dp,
                position = position,
            ),
        minimumSize = DpSize(MIN_WIDTH_DP.dp, MIN_HEIGHT_DP.dp),
    ) {
        // Member of the workspace for as long as the window lives: a candidate
        // owner for the floating satellites, and a dock host.
        JoinSatelliteWorkspace(demo.workspace)

        // Named so the UI can show and pin the owner; dropped with the window
        // so a stale handle can never be pinned.
        val window = nucleusWindow
        DisposableEffect(window) {
            demo.publish(documentId, window)
            onDispose { demo.forget(documentId) }
        }

        DemoTheme(colors) {
            // Window-level chrome: the native frame follows the theme too.
            WindowBackground(colors.background)
            WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
            WindowScaffold(
                titleBar = { MaterialTitleBar { Text(documentId.title) } },
            ) { contentPadding ->
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    // Docked satellites are laid out around the document.
                    DockLayout(demo.workspace, Modifier.fillMaxSize().padding(contentPadding)) {
                        DocumentContent(demo, documentId)
                    }
                }
            }
        }
    }
}

/**
 * Material colours plus the window-chrome styles derived from them.
 *
 * Every Tao window owns its own ComposeScene, so this is established per
 * window rather than once around the application — and once more around the
 * satellites, whose floating windows get it through the bridged locals.
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

/** Themed body of a satellite, the same whether it floats or is docked. */
@Composable
private fun SatelliteScope.SatelliteSurface(
    colors: ColorScheme,
    content: @Composable SatelliteScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private const val DOCUMENT_WIDTH_DP = 720
private const val DOCUMENT_HEIGHT_DP = 760
private const val MIN_WIDTH_DP = 480
private const val MIN_HEIGHT_DP = 480
private const val DOCUMENT_A_X_DP = 80
private const val DOCUMENT_B_X_DP = 840
private const val DOCUMENT_Y_DP = 60
