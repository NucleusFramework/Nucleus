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
import dev.nucleusframework.application.SatelliteWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.material.MaterialTitleBar

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
 * Satellite window demo.
 *
 * Two document windows share **one** inspector satellite. The inspector is
 * composed at application scope with an explicit `parent`, which is what makes
 * reparenting possible: switching the owner moves the inspector from one
 * document to the other without moving it on screen, and it then follows — and
 * closes with — its new owner.
 *
 * A satellite that only ever belongs to one window is simpler: declare it
 * inside that window's content and it picks the window up as its parent on its
 * own, via `LocalNucleusWindow`.
 */
fun main() =
    nucleusApplication {
        val demo = remember { DemoState() }
        val dark = isSystemInDarkMode()

        DocumentWindow(
            demo = demo,
            documentId = DocumentId.A,
            dark = dark,
            position = WindowPosition.Absolute(DOCUMENT_A_X_DP.dp, DOCUMENT_Y_DP.dp),
            onCloseRequest = ::exitApplication,
        )

        if (demo.showDocumentB) {
            DocumentWindow(
                demo = demo,
                documentId = DocumentId.B,
                dark = dark,
                position = WindowPosition.Absolute(DOCUMENT_B_X_DP.dp, DOCUMENT_Y_DP.dp),
                // Same-frame reparent: if the inspector belongs to this
                // document it steps out of the owner link before the window
                // is destroyed and carries on, in place, owned by Document A.
                onCloseRequest = {
                    demo.showDocumentB = false
                    demo.attachedTo = DocumentId.A
                },
            )
        }

        // Only composed once the owning document has published itself: a
        // satellite without a parent is just a top-level window, which is not
        // what this demo is about.
        val parent = demo.parentWindow
        if (demo.showInspector && parent != null) {
            SatelliteWindow(
                onCloseRequest = { demo.showInspector = false },
                parent = parent,
                state = demo.inspector,
                title = "Inspector",
                hideWhileParentFullscreenOrMaximized = demo.hideWhenParentFills,
            ) {
                DemoTheme(dark) { colors ->
                    WindowScaffold(
                        titleBar = { MaterialTitleBar { Text("Inspector") } },
                    ) { contentPadding ->
                        Surface(Modifier.fillMaxSize(), color = colors.surface) {
                            Box(Modifier.padding(contentPadding)) {
                                InspectorContent(demo)
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun NucleusApplicationScope.DocumentWindow(
    demo: DemoState,
    documentId: DocumentId,
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
        // Hand this window to the application state so the satellite can be
        // parented to it — and drop it again when the window goes away, so a
        // stale handle can never become somebody's parent.
        val window = nucleusWindow
        DisposableEffect(window) {
            demo.publish(documentId, window)
            onDispose { demo.forget(documentId) }
        }

        DemoTheme(dark) { colors ->
            WindowScaffold(
                titleBar = {
                    MaterialTitleBar { Text(documentId.title) }
                },
            ) { contentPadding ->
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    Box(Modifier.padding(contentPadding)) {
                        DocumentContent(demo, documentId)
                    }
                }
            }
        }
    }
}

/**
 * Every Tao window owns its own ComposeScene, so the theme — and the chrome
 * colours that go with it — are established per window rather than once around
 * the application.
 */
@Composable
private fun NucleusDecoratedWindowScope.DemoTheme(
    dark: Boolean,
    content: @Composable NucleusDecoratedWindowScope.(ColorScheme) -> Unit,
) {
    val colors = if (dark) DemoDarkColors else DemoLightColors
    MaterialTheme(colorScheme = colors) {
        WindowBackground(colors.background)
        WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
        content(colors)
    }
}

private const val DOCUMENT_WIDTH_DP = 560
private const val DOCUMENT_HEIGHT_DP = 720
private const val MIN_WIDTH_DP = 420
private const val MIN_HEIGHT_DP = 480
private const val DOCUMENT_A_X_DP = 80
private const val DOCUMENT_B_X_DP = 700
private const val DOCUMENT_Y_DP = 60
