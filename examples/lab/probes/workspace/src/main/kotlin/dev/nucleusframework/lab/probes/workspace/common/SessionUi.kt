package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone

/** Whether the session under test is open; [detail] names what it is built with. */
fun sessionCapability(
    open: Boolean,
    detail: String? = null,
): Capability =
    Capability(
        "Session",
        if (open) Availability.Available else Availability.Unavailable("closed — open it to test"),
        detail = detail,
    )

/** Open / reset and close, plus a warning while declaration options wait for a reset. */
@Composable
fun SessionActions(
    state: SessionState<*, *>,
    onIntent: (SessionIntent<Nothing, Nothing>) -> Unit,
    optionsPending: Boolean = false,
) {
    Actions {
        PrimaryAction(if (state.open) "Reset session" else "Open session") { onIntent(SessionIntent.Open) }
        SecondaryAction("Close session", enabled = state.open) { onIntent(SessionIntent.Close) }
    }
    if (optionsPending) Readout("options", "changed — reset the session to apply them", tone = Tone.Warning)
}

/** The last refusal, shown under the controls that caused it. */
@Composable
fun SessionNotice(state: SessionState<*, *>) {
    state.notice?.let { Readout("last refusal", it, tone = Tone.Warning) }
}

/** A saved layout, written out as the JSON an app would persist. */
@Composable
fun SavedLayout(
    title: String,
    json: String?,
) {
    SubHeading(title + (json?.let { " · ${it.length} chars" } ?: ""))
    CodeBlock(json.orEmpty(), empty = "nothing saved")
}
