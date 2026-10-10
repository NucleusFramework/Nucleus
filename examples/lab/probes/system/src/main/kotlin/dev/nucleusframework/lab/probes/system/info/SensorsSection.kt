package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Tone

/** Temperature sensors, hottest first, against their critical threshold when one is reported. */
@Composable
fun SensorsSection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Sensors, state.sample)) {
        val components =
            state.sample
                ?.components
                .orEmpty()
                .sortedByDescending { it.temperature ?: Float.MIN_VALUE }
        val silent = components.count { it.temperature == null }
        if (silent > 0) Readout("no reading", "$silent sensor(s) listed without a temperature", tone = Tone.Muted)
        components.filter { it.temperature != null }.forEach { sensor ->
            val temperature = sensor.temperature ?: return@forEach
            val ceiling = sensor.critical ?: sensor.max?.takeIf { it > temperature } ?: DEFAULT_CEILING
            Meter(
                label = sensor.label,
                fraction = temperature / ceiling,
                text = celsius(temperature) + (sensor.critical?.let { " / crit ${it.fmt(0)}" } ?: ""),
            )
        }
    }
}

/** Scale used when a sensor reports no threshold of its own. */
private const val DEFAULT_CEILING = 100f
