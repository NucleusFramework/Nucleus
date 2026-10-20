package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.common.rememberRecompositionCounter
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory
import kotlinx.coroutines.delay

@Composable
private fun CountedLabel(
    name: String,
    value: Int,
) {
    val recompositions = rememberRecompositionCounter()
    Text("$name = $value · recomposed ${recompositions[0]}×", style = LabTheme.typography.body)
}

val StateSamples: List<SampleEntry> =
    samples(SampleCategory.State) {
        sample(
            "skipping",
            "Recomposition scopes",
            "Clicking A recomposes A's label only: B's count stays put, and the reverse. Neither moves while idle.",
        ) {
            var a by remember { mutableIntStateOf(0) }
            var b by remember { mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction("A +1") { a++ }
                    SecondaryAction("B +1") { b++ }
                }
                CountedLabel("A", a)
                CountedLabel("B", b)
            }
        }
        sample(
            "saveable",
            "remember vs rememberSaveable",
            "Bump both, open another probe, come back: the saveable count survived, the remembered one is back to 0. Reset clears both.",
        ) {
            var remembered by remember { mutableIntStateOf(0) }
            var saved by rememberSaveable { mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction("Bump both") {
                        remembered++
                        saved++
                    }
                }
                Text("remember: $remembered")
                Text("rememberSaveable: $saved")
            }
        }
        sample(
            "derived",
            "derivedStateOf",
            "The counter ticks ten times a second; the parity label recomposes only when crossing a multiple of 10, not on every tick.",
        ) {
            var ticks by remember { mutableIntStateOf(0) }
            var running by remember { mutableStateOf(true) }
            LaunchedEffect(running) {
                while (running) {
                    delay(100)
                    ticks++
                }
            }
            val decade by remember { derivedStateOf { ticks / 10 } }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryAction(if (running) "Pause" else "Resume") { running = !running }
                Text("ticks = $ticks")
                CountedLabel("decade", decade)
            }
        }
        sample(
            "effects",
            "Keyed effects",
            "Changing the key restarts the effect (restarts climbs by one, elapsed resets); unrelated clicks don't restart it.",
        ) {
            var key by remember { mutableIntStateOf(0) }
            var unrelated by remember { mutableIntStateOf(0) }
            var restarts by remember { mutableIntStateOf(0) }
            var elapsed by remember { mutableIntStateOf(0) }
            LaunchedEffect(key) {
                restarts++
                elapsed = 0
                while (true) {
                    delay(1_000)
                    elapsed++
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction("Change key") { key++ }
                    SecondaryAction("Unrelated ($unrelated)") { unrelated++ }
                }
                Text("key = $key · restarts = $restarts · elapsed = $elapsed s")
            }
        }
    }
