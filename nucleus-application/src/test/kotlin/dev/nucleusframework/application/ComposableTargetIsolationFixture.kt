package dev.nucleusframework.application

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Compile-time regression fixture for #636.
 *
 * Every window / dialog opener is declared `@ComposableOpenTarget(-1)` with a
 * `@UiComposable` content lambda, so the applier a caller happens to be bound
 * to never reaches the window content — and opening a window never binds the
 * caller's applier either. Without that, one non-UI composable called in the
 * `nucleusApplication` scope reclassified the whole scope (and every nested
 * window) to that applier, and each `@UiComposable` call inside it warned —
 * fatal under `-Werror`.
 *
 * `compileTestKotlin` escalates `COMPOSE_APPLIER_CALL_MISMATCH` to an error
 * (see build.gradle.kts), so a regression fails the build here instead of in a
 * consumer's app.
 */
@Composable
@ComposableTarget(applier = "org.example.FakeApplier")
private fun rememberNonUiTargetedState(): Any = remember { Any() }

/**
 * Unmarked wrapper, exactly like an app's own theme composable: the Compose
 * compiler infers `[0[0]]` for it, so it forwards whatever applier the
 * application scope was bound to.
 */
@Composable
private fun InferredWrapper(content: @Composable () -> Unit) {
    content()
}

@Suppress("UnusedPrivateMember")
private fun windowsStayUiRegardlessOfTheScopeApplier() {
    nucleusApplication(enableSingleInstance = false) {
        // Binds the application scope's applier to a non-UI one.
        rememberNonUiTargetedState()

        InferredWrapper {
            DecoratedWindow(onCloseRequest = ::exitApplication) { Box(Modifier) }
            DecoratedDialog(onCloseRequest = ::exitApplication) { Box(Modifier) }
            HostedWindow(onCloseRequest = ::exitApplication) { Box(Modifier) }
            HostedDialog(onCloseRequest = ::exitApplication) { Box(Modifier) }
        }
    }
}
