package dev.nucleusframework.lab.probes.fixtures.runner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubSection
import dev.nucleusframework.lab.designsystem.Tone

/** One fixture: what a pass looks like, its variants and flags, Run / Stop. */
@Composable
internal fun FixtureCard(
    fixture: FixtureInfo,
    selected: FixtureVariant,
    running: List<FixtureRun>,
    canRun: Boolean,
    onIntent: (FixturesIntent) -> Unit,
) {
    SubSection(fixture.title) {
        Hint(fixture.description)
        if (fixture.variants.size > 1) {
            ChoiceRow(
                label = "Variant",
                options = fixture.variants,
                selected = selected,
                name = FixtureVariant::name,
                onSelect = { onIntent(FixturesIntent.SelectVariant(fixture.id, it.name)) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap)) {
            Readout("id", fixture.id, tone = Tone.Muted)
            Readout("JVM flags", selected.jvmArgs.joinToString(" ").ifEmpty { "none" })
            if (selected.args.isNotEmpty()) Readout("arguments", selected.args.joinToString(" "))
            fixture.exitCodes.toSortedMap().forEach { (code, meaning) ->
                Readout("exit $code", meaning, tone = Tone.Muted)
            }
        }
        Actions {
            PrimaryAction("Run ${selected.name}", enabled = canRun) { onIntent(FixturesIntent.Run(fixture.id)) }
            running.forEach { run ->
                SecondaryAction("Stop #${run.runId} (${run.variant})") { onIntent(FixturesIntent.Stop(run.runId)) }
            }
        }
    }
}
