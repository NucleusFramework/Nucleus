package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.systeminfo.model.BatteryState

/** Charge, power source, capacity and health. Unplugging the charger must flip state live. */
@Composable
fun BatterySection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Battery, state.sample)) {
        val battery = state.sample?.battery ?: return@SectionBody
        Meter("stateOfCharge", battery.stateOfCharge, percent(battery.stateOfCharge.toDouble()))
        Readout(
            "state / plugged in",
            "${battery.state} / ${battery.isPluggedIn}",
            tone = consistency(battery.state, battery.isPluggedIn),
        )
        battery.timeToFull?.let { Readout("time to full", "$it min") }
        battery.timeToEmpty?.let { Readout("time to empty", "$it min") }
        SubHeading("Capacity")
        Readout(
            "current / max / design",
            "${battery.currentCapacity} / ${battery.maxCapacity} / ${battery.designCapacity} mAh",
        )
        Meter("health", battery.health, percent(battery.health.toDouble()))
        Readout("cycle count", battery.cycleCount.toString())
        SubHeading("Electrical")
        Readout("voltage / amperage", "${battery.voltage} mV / ${battery.amperage} mA")
        Readout("temperature", celsius(battery.temperature))
        Readout(
            "device",
            listOfNotNull(battery.manufacturer, battery.modelName).joinToString(" ").ifBlank { "not reported" },
        )
    }
}

/** Discharging while plugged in happens (heavy load), charging while unplugged never should. */
private fun consistency(
    state: BatteryState,
    pluggedIn: Boolean,
): Tone =
    when {
        state == BatteryState.Charging && !pluggedIn -> Tone.Error
        state == BatteryState.Unknown -> Tone.Muted
        else -> Tone.Neutral
    }
