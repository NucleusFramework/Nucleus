package dev.nucleusframework.lab.probes.input.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.lab.designsystem.LabWindowFrame
import dev.nucleusframework.lab.designsystem.TargetArea

/**
 * A second window created with `enabled` / `focusable` off, the tao-demo test: a disabled
 * window must swallow pointer and keys, a non-focusable one must never become key yet still
 * take clicks. `LabSessionWindow` cannot set `enabled` nor observe keys, so the window is
 * built here, in the Lab's frame.
 */
@Composable
internal fun ChildWindow(
    config: ChildConfig,
    onInput: (String) -> Unit,
    onClose: () -> Unit,
) {
    DecoratedWindow(
        onCloseRequest = onClose,
        state = rememberWindowState(size = DpSize(480.dp, 260.dp)),
        title = "Child (enabled=${config.enabled}, focusable=${config.focusable})",
        enabled = config.enabled,
        focusable = config.focusable,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) onInput("key ${event.key}")
            false
        },
    ) {
        LabWindowFrame(
            title = "Child window",
            subtitle = "enabled=${config.enabled} · focusable=${config.focusable}",
            scrollable = false,
        ) {
            InputTarget(config, onInput)
        }
    }
}

@Composable
private fun ColumnScope.InputTarget(
    config: ChildConfig,
    onInput: (String) -> Unit,
) {
    TargetArea(
        Modifier
            .weight(1f)
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            onInput("press at ${event.changes.first().position}")
                        }
                    }
                }
            },
        label =
            buildString {
                append("Click here, then type.")
                if (!config.enabled) append("\nDisabled: nothing may reach the Lab.")
                if (!config.focusable) append("\nNot focusable: clicks yes, keys and activation no.")
            },
    )
}
