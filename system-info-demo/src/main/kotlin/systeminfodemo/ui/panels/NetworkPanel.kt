package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.systeminfo.model.MeteredStatus
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun NetworkPanel(state: SystemInfoState) {
    state.connectivityInfo?.let { conn ->
        SectionCard("Connectivity") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Connected", if (conn.isConnected) "Yes" else "No")
                InfoRow(
                    "Metered",
                    when (conn.meteredStatus) {
                        MeteredStatus.NOT_AVAILABLE -> "N/A"
                        MeteredStatus.UNMETERED -> "No"
                        MeteredStatus.METERED -> "Yes"
                        MeteredStatus.UNKNOWN -> "Unknown"
                    },
                )
            }
        }
    }

    state.networks.forEach { net ->
        SectionCard(net.name) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("MAC Address", net.macAddress)
                InfoRow("MTU", net.mtu.toString())
                InfoRow("Received", "${formatBytes(net.receivedBytes)} (${net.receivedPackets} packets)")
                InfoRow("Transmitted", "${formatBytes(net.transmittedBytes)} (${net.transmittedPackets} packets)")
                InfoRow("RX Errors", net.errorsOnReceived.toString())
                InfoRow("TX Errors", net.errorsOnTransmitted.toString())
            }
        }
    }

    if (state.networks.isEmpty() && state.connectivityInfo == null) {
        org.jetbrains.jewel.ui.component
            .Text("No network interfaces detected")
    }
}
