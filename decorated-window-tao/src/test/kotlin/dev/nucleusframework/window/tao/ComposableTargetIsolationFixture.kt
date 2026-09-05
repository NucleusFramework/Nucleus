package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.window.tao.v2.rememberWindowState

/**
 * Compile-time regression fixture for #636 — Tao counterpart of the one in
 * `nucleus-application`.
 *
 * Every opener below hosts its content in a fresh `ComposeScene`, so each is
 * `@ComposableOpenTarget(-1)` with `@UiComposable` content lambdas — callable
 * from any applier, always composing UI. `compileTestKotlin` escalates
 * `COMPOSE_APPLIER_CALL_MISMATCH` to an error (see build.gradle.kts), so the
 * calls below fail the build if that isolation regresses.
 */
@Composable
@ComposableTarget(applier = "org.example.FakeApplier")
private fun rememberNonUiTargetedState(): Any = remember { Any() }

@Suppress("UnusedPrivateMember")
private fun windowsStayUiRegardlessOfTheScopeApplier() {
    taoApplication {
        // Binds the application scope's applier to a non-UI one.
        rememberNonUiTargetedState()

        DecoratedWindow(onCloseRequest = ::exitApplication) { Box(Modifier) }
        DecoratedDialog(onCloseRequest = ::exitApplication) { Box(Modifier) }
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(),
        ) { Box(Modifier) }
        SatelliteWindow(onCloseRequest = ::exitApplication) { Box(Modifier) }
        TaoStandalonePopup(
            visible = false,
            position = WindowPosition.Absolute(0.dp, 0.dp),
            size = DpSize(1.dp, 1.dp),
        ) { Box(Modifier) }

        // Every composable lambda of an opener, not just `content`: an
        // unannotated one drags the caller's applier back in.
        val satellites = rememberSatelliteWorkspace()
        Satellite(
            workspace = satellites,
            id = "inspector",
            title = "Inspector",
            floatingContentWrapper = { body -> Box(Modifier) { body() } },
            header = { Box(Modifier) },
        ) { Box(Modifier) }

        val tabs = rememberTabWorkspace()
        TabWindows(
            workspace = tabs,
            strip = { Box(Modifier) },
            windowContentWrapper = { body -> Box(Modifier) { body() } },
        )
        Tab(workspace = tabs, id = "first", title = "First") { Box(Modifier) }
    }
}
