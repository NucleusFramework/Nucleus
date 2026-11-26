package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import java.io.File

/** Every volume, with the JDK's own figure for the same mount as a cross-check. */
@Composable
fun DisksSection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Disks, state.sample)) {
        state.sample?.disks.orEmpty().forEach { disk ->
            val used = disk.totalSpace - disk.availableSpace
            SubHeading("${disk.mountPoint}  (${disk.name})")
            Meter(
                label = "${disk.fileSystem} ${disk.kind}",
                fraction = if (disk.totalSpace > 0) used.toFloat() / disk.totalSpace else 0f,
                text = "${formatBytes(used)} / ${formatBytes(disk.totalSpace)}",
            )
            val flags = listOfNotNull("removable".takeIf { disk.isRemovable }, "read-only".takeIf { disk.isReadOnly })
            if (flags.isNotEmpty()) Readout("flags", flags.joinToString())
            // java.io.File asks statvfs / GetDiskFreeSpaceEx directly: both should agree within a few MiB.
            val jdkFree = File(disk.mountPoint).takeIf { it.exists() }?.usableSpace
            if (jdkFree != null) {
                val drift = kotlin.math.abs(jdkFree - disk.availableSpace)
                Readout(
                    "JDK usableSpace",
                    formatBytes(jdkFree) + if (drift > DRIFT_BYTES) "  (differs by ${formatBytes(drift)})" else "",
                    tone = if (drift > DRIFT_BYTES) Tone.Warning else Tone.Muted,
                )
            }
        }
    }
}

private const val DRIFT_BYTES = 256L * 1024 * 1024
