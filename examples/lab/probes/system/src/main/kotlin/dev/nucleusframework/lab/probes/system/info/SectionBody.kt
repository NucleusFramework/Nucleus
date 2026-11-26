package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Tone

/**
 * Draws [content] when the section has data, otherwise says which call came back empty.
 */
@Composable
fun SectionBody(
    availability: Availability,
    content: @Composable () -> Unit,
) {
    when (availability) {
        Availability.Available -> content()
        Availability.Unknown -> EmptyState("Waiting for the first sample.")
        is Availability.Unavailable -> Readout("unavailable because", availability.reason, tone = Tone.Warning)
    }
}
