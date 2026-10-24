package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LiveChart
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.systeminfo.model.MeteredStatus

/** Connectivity, total throughput charts, and per-interface rates and counters. */
@Composable
fun NetworkSection(state: SystemInfoState) {
    val connectivity = state.sample?.connectivity
    Readout(
        "connectivityInfo()",
        connectivity?.let { "connected=${it.isConnected} metered=${it.meteredStatus}" } ?: "null (not reported here)",
        tone =
            when {
                connectivity == null -> Tone.Muted
                connectivity.isConnected -> Tone.Ok
                else -> Tone.Warning
            },
    )
    if (connectivity?.meteredStatus == MeteredStatus.NOT_AVAILABLE) {
        Hint("Metered status is not exposed by this OS.")
    }
    SectionBody(sectionAvailability(InfoSection.Network, state.sample)) {
        LiveChart("receive (all)", state.rxHistory, rate(state.rxHistory.lastOrNull()))
        LiveChart("transmit (all)", state.txHistory, rate(state.txHistory.lastOrNull()))
        // Busy interfaces first; idle ones (loopback, tunnels) collapse to a single line.
        val interfaces =
            state.sample
                ?.networks
                .orEmpty()
                .sortedByDescending { it.receivedBytes + it.transmittedBytes }
        interfaces.forEach { nic ->
            val r = state.netRates[nic.name]
            if (nic.receivedBytes + nic.transmittedBytes == 0L) {
                Readout(nic.name, "no traffic since boot", tone = Tone.Muted)
                return@forEach
            }
            SubHeading("${nic.name}  ${nic.macAddress}  mtu ${nic.mtu}")
            Readout("rate ↓ / ↑", "${rate(r?.rxBytesPerSec)} / ${rate(r?.txBytesPerSec)}")
            Readout("total ↓ / ↑", "${formatBytes(nic.receivedBytes)} / ${formatBytes(nic.transmittedBytes)}")
            val errors = nic.errorsOnReceived + nic.errorsOnTransmitted
            if (errors > 0) {
                Readout("errors ↓ / ↑", "${nic.errorsOnReceived} / ${nic.errorsOnTransmitted}", tone = Tone.Warning)
            }
        }
    }
}

private fun rate(bytesPerSec: Float?): String =
    bytesPerSec?.let { "${formatBytes(it.toLong())}/s" } ?: "needs two samples"
