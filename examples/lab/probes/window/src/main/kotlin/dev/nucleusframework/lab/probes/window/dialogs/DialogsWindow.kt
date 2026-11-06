package dev.nucleusframework.lab.probes.window.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.HostedDialog
import dev.nucleusframework.application.HostedWindow
import dev.nucleusframework.application.LocalNucleusDialogHost
import dev.nucleusframework.application.LocalNucleusWindowHost
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDecoratedWindow
import dev.nucleusframework.lab.designsystem.LabDialogFrame
import dev.nucleusframework.lab.designsystem.LabPane
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabTitle
import dev.nucleusframework.lab.designsystem.LabWindowFrame
import dev.nucleusframework.lab.designsystem.MaterialSpecimen
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.DecoratedDialogScope
import dev.nucleusframework.window.material.MaterialDecoratedDialog
import dev.nucleusframework.window.material.MaterialDialogTitleBar
import kotlinx.coroutines.delay

/**
 * The owner window. Every secondary surface is composed from inside it, as an app would,
 * so a dialog sees this window as its parent (modal count, centring, focus handoff).
 */
@Composable
internal fun NucleusApplicationScope.DialogsOwnerWindow(
    vm: DialogsViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    LabDecoratedWindow(
        title = "Dialogs lab",
        onCloseRequest = close,
        state = rememberWindowState(size = DpSize(640.dp, 460.dp)),
    ) {
        val owner = nucleusWindow
        LaunchedEffect(owner) { vm.onIntent(DialogsIntent.Attached(owner)) }
        LabWindowFrame("Dialogs lab", "owner window", scrollable = false) {
            Column(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                                vm.onIntent(DialogsIntent.OwnerPressed)
                            }
                        }
                    }
                },
            ) {
                Text("Owner", style = LabTheme.typography.heading)
                Hint("Click anywhere here while a dialog is open: a modal dialog must swallow the press.")
                Readout(
                    "Presses",
                    "${state.ownerPresses} · while modal ${state.ownerPressesWhileModal}",
                    tone = if (state.ownerPressesWhileModal > 0) Tone.Error else Tone.Neutral,
                )
            }
        }

        val labWindowHost = remember { LabWindowHost { vm.onIntent(DialogsIntent.LabHostInvoked) } }
        val labDialogHost = remember { LabDialogHost { vm.onIntent(DialogsIntent.LabHostInvoked) } }
        state.open.forEach { secondary ->
            key(secondary) {
                val dismiss = { vm.onIntent(DialogsIntent.Dismiss(secondary)) }
                when (secondary) {
                    Secondary.DecoratedDialog ->
                        DecoratedDialog(
                            onCloseRequest = dismiss,
                            state = rememberDialogState(size = DpSize(420.dp, 260.dp)),
                            title = secondary.label,
                        ) {
                            LabDialogFrame(secondary.label, scrollable = false) {
                                SecondaryBody(secondary, vm, owner, nucleusWindow, dismiss)
                            }
                        }
                    Secondary.MaterialDialog ->
                        // Material's own dialog is judged on Material's scheme, title bar included.
                        MaterialSpecimen {
                            MaterialDecoratedDialog(
                                onCloseRequest = dismiss,
                                state = rememberDialogState(size = DpSize(420.dp, 260.dp)),
                                title = secondary.label,
                            ) {
                                MaterialDialogFrame(
                                    secondary,
                                ) { SecondaryBody(secondary, vm, owner, nucleusWindow, dismiss) }
                            }
                        }
                    Secondary.HostedDialogDefault, Secondary.HostedDialogLab ->
                        CompositionLocalProvider(
                            LocalNucleusDialogHost provides
                                if (secondary.viaLabHost) labDialogHost else LocalNucleusDialogHost.current,
                        ) {
                            HostedDialog(
                                onCloseRequest = dismiss,
                                state = rememberDialogState(size = DpSize(420.dp, 260.dp)),
                                title = secondary.label,
                            ) {
                                LabDialogFrame(secondary.label, scrollable = false) {
                                    SecondaryBody(secondary, vm, owner, nucleusWindow, dismiss)
                                }
                            }
                        }
                    Secondary.HostedWindowDefault, Secondary.HostedWindowLab ->
                        CompositionLocalProvider(
                            LocalNucleusWindowHost provides
                                if (secondary.viaLabHost) labWindowHost else LocalNucleusWindowHost.current,
                        ) {
                            HostedWindow(
                                onCloseRequest = dismiss,
                                state = rememberWindowState(size = DpSize(480.dp, 300.dp)),
                                title = secondary.label,
                            ) {
                                LabWindowFrame(secondary.label, scrollable = false) {
                                    SecondaryBody(secondary, vm, owner, nucleusWindow, dismiss)
                                }
                            }
                        }
                }
            }
        }
    }
}

/** Material's own dialog keeps Material's title bar: it is the specimen. */
@Composable
private fun DecoratedDialogScope.MaterialDialogFrame(
    secondary: Secondary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MaterialDialogTitleBar { _ -> LabTitle(secondary.label) }
        Divider()
        LabPane(Modifier.weight(1f), scrollable = false, content = content)
    }
}

/** Reads, from inside the surface, which host composed it and where it sits against its owner. */
@Composable
private fun SecondaryBody(
    secondary: Secondary,
    vm: DialogsViewModel,
    owner: NucleusWindow,
    self: NucleusWindow,
    dismiss: () -> Unit,
) {
    val viaLab = LocalViaLabHost.current
    LaunchedEffect(Unit) {
        // Let the native centring (and its macOS re-centre) land first.
        delay(REPORT_DELAY_MS)
        val offset =
            run {
                val o = owner.boundsOnScreen() ?: return@run null
                val s = self.boundsOnScreen() ?: return@run null
                ((s.x + s.width / 2) - (o.x + o.width / 2)).toInt() to
                    ((s.y + s.height / 2) - (o.y + o.height / 2)).toInt()
            }
        vm.onIntent(DialogsIntent.Reported(secondary, SecondaryReport(viaLab, offset)))
    }
    Text(
        if (viaLab) "Composed by the Lab host" else "Composed by the default host",
        style = LabTheme.typography.heading,
    )
    Hint(
        if (secondary.modal) {
            "Modal: the owner should ignore clicks until this closes."
        } else {
            "Not modal: the owner keeps working."
        },
    )
    SecondaryAction("Close", onClick = dismiss)
}

private const val REPORT_DELAY_MS = 500L
