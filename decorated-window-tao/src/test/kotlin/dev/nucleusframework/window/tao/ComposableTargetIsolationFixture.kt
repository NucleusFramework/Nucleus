package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition

/**
 * Compile-time regression fixture for #636 — Tao counterpart of the one in
 * `nucleus-application`.
 *
 * [DecoratedWindow], [DecoratedDialog] and [TaoStandalonePopup] each host their
 * content in a fresh `ComposeScene`, so they are declared
 * `@ComposableOpenTarget(-1)` with a `@UiComposable` content lambda: callable
 * from any applier, always composing UI content. `compileTestKotlin` escalates
 * `COMPOSE_APPLIER_CALL_MISMATCH` to an error (see build.gradle.kts).
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
        TaoStandalonePopup(
            visible = false,
            position = WindowPosition.Absolute(0.dp, 0.dp),
            size = DpSize(1.dp, 1.dp),
        ) { Box(Modifier) }
    }
}
