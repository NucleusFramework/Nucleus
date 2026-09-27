package dev.nucleusframework.updater.internal

import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.UpdateFile
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.UpdateSimulation
import dev.nucleusframework.updater.UpdateSimulation.Scenario
import dev.nucleusframework.updater.Version
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Plays an [UpdateSimulation] for [NucleusUpdater][dev.nucleusframework.updater.NucleusUpdater]. */
internal class SimulatedUpdate(
    private val simulation: UpdateSimulation,
    private val currentVersion: String,
) {
    /** The offered version: explicit, else the next minor of the running version. */
    val offeredVersion: String =
        simulation.version ?: Version.fromString(currentVersion).let { "${it.major}.${it.minor + 1}.0" }

    suspend fun check(): UpdateResult {
        delay(simulation.checkDuration)
        return when (simulation.scenario) {
            Scenario.UP_TO_DATE -> UpdateResult.NotAvailable
            Scenario.CHECK_ERROR ->
                UpdateResult.Error(NetworkException("Simulated update check failure (${UpdaterSettings.SIMULATE})"))
            Scenario.UPDATE_AVAILABLE, Scenario.DOWNLOAD_ERROR, Scenario.CHECKSUM_ERROR -> {
                val offered = Version.fromString(offeredVersion)
                UpdateResult.Available(info(), offered.levelFrom(Version.fromString(currentVersion)))
            }
        }
    }

    fun info(): UpdateInfo {
        val file =
            UpdateFile(
                url = "simulated:$ARTIFACT_PREFIX-$offeredVersion",
                sha512 = Base64.getEncoder().encodeToString(ByteArray(SHA512_BYTES)),
                size = simulation.downloadSize,
                fileName = "$ARTIFACT_PREFIX-$offeredVersion$ARTIFACT_EXTENSION",
            )
        return UpdateInfo(
            version = offeredVersion,
            releaseDate = Instant.now().toString(),
            files = listOf(file),
            currentFile = file,
        )
    }

    fun download(info: UpdateInfo): Flow<DownloadProgress> =
        flow {
            val total =
                if (simulation.isDifferential) {
                    (info.currentFile.size * DIFFERENTIAL_FRACTION).toLong().coerceAtLeast(1)
                } else {
                    info.currentFile.size
                }
            val failAt = if (simulation.scenario == Scenario.DOWNLOAD_ERROR) DOWNLOAD_FAILURE_FRACTION else null
            val steps = (simulation.downloadDuration / TICK).toInt().coerceAtLeast(1)
            val tick: Duration = simulation.downloadDuration / steps

            emit(DownloadProgress(0, total, 0.0, isDifferential = simulation.isDifferential))
            for (step in 1..steps) {
                delay(tick)
                val fraction = step.toDouble() / steps
                if (failAt != null && fraction >= failAt) {
                    throw NetworkException("Simulated download failure (${UpdaterSettings.SIMULATE})")
                }
                val downloaded = (total * fraction).toLong()
                if (step < steps) {
                    emit(
                        DownloadProgress(
                            downloaded,
                            total,
                            fraction * PERCENT_MAX,
                            isDifferential = simulation.isDifferential,
                        ),
                    )
                }
            }
            if (simulation.scenario == Scenario.CHECKSUM_ERROR) {
                throw ChecksumException(info.currentFile.sha512, SIMULATED_MISMATCH)
            }
            emit(
                DownloadProgress(
                    bytesDownloaded = total,
                    totalBytes = total,
                    percent = PERCENT_MAX,
                    file = placeholder(info),
                    isDifferential = simulation.isDifferential,
                ),
            )
        }

    /**
     * A file standing for the artifact, so an app that shows or checks the downloaded file finds
     * one. It is not an installer: [NucleusUpdater] never runs it.
     */
    private fun placeholder(info: UpdateInfo): File {
        val dir = Files.createTempDirectory("nucleus-update-simulated-").toFile()
        dir.deleteOnExit()
        return File(dir, info.currentFile.fileName).apply {
            writeText("Simulated Nucleus update to ${info.version}. Not an installer.\n")
            deleteOnExit()
        }
    }

    companion object {
        private const val ARTIFACT_PREFIX = "simulated-update"
        private const val ARTIFACT_EXTENSION = ".bin"
        private const val SHA512_BYTES = 64
        private const val PERCENT_MAX = 100.0
        private const val DIFFERENTIAL_FRACTION = 0.08
        private const val DOWNLOAD_FAILURE_FRACTION = 0.6
        private const val SIMULATED_MISMATCH = "simulated-mismatch"
        private val TICK = 100.milliseconds
    }
}
