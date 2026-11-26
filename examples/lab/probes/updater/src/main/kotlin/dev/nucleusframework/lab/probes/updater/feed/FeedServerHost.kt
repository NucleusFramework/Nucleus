package dev.nucleusframework.lab.probes.updater.feed

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.updater.testing.FeedFault
import dev.nucleusframework.updater.testing.FeedRequest
import dev.nucleusframework.updater.testing.UpdateFeedServer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Random

@Immutable
data class PublishedRelease(
    val version: String,
    val artifact: String,
    val sizeBytes: Long,
    val manifest: String,
)

@Immutable
data class ActiveFault(
    val fault: String,
    val path: String,
    val times: Int?,
)

/** One request the server answered, as it logged it. */
@Immutable
data class ServedRequest(
    val line: String,
    val status: Int,
) {
    val failed: Boolean get() = status >= HTTP_ERROR

    private companion object {
        const val HTTP_ERROR = 400
    }
}

@Immutable
data class FeedServerStatus(
    val baseUrl: String? = null,
    val directory: String? = null,
    val published: PublishedRelease? = null,
    val faults: List<ActiveFault> = emptyList(),
    val requests: List<ServedRequest> = emptyList(),
) {
    val running: Boolean get() = baseUrl != null
}

/**
 * The one loopback release host of the Lab, shared by the feed probe (which shapes it) and
 * the updater probe (which reads from it). Lives until stopped or the app exits.
 */
@SingleIn(AppScope::class)
@Inject
class FeedServerHost {
    private var server: UpdateFeedServer? = null
    private val state = MutableStateFlow(FeedServerStatus())
    val status: StateFlow<FeedServerStatus> = state.asStateFlow()

    @Synchronized
    fun start(): String {
        server?.let { return it.baseUrl }
        val started =
            UpdateFeedServer(
                directory =
                    LabPaths.scratch("update-feed").toFile().also {
                        it.deleteRecursively()
                        it.mkdirs()
                    },
            )
        server = started
        state.value = FeedServerStatus(baseUrl = started.baseUrl, directory = started.directory.path)
        return started.baseUrl
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        state.value = FeedServerStatus()
    }

    /** Publishes [version] with a random artifact of [sizeBytes], named the way this OS's client looks for. */
    fun publish(
        version: String,
        sizeBytes: Long,
    ): PublishedRelease {
        val feed = requireServer()
        val artifact = File(LabPaths.scratch("update-artifacts").toFile(), artifactName(version))
        writeRandom(artifact, sizeBytes, seed = 31L * version.hashCode() + sizeBytes)
        val manifest = feed.publish(version, artifact)
        val release = PublishedRelease(version, artifact.name, sizeBytes, manifest.readText())
        state.update { it.copy(published = release) }
        return release
    }

    fun fault(
        fault: FeedFault,
        path: String,
        times: Int?,
    ) {
        requireServer().fault(fault, path, times ?: Int.MAX_VALUE)
        state.update { it.copy(faults = it.faults + ActiveFault(fault.toString(), path, times)) }
    }

    fun clearFaults() {
        requireServer().clearFaults()
        state.update { it.copy(faults = emptyList()) }
    }

    fun clearRequests() {
        requireServer().clearRequests()
        state.update { it.copy(requests = emptyList()) }
    }

    /** Re-reads the request log; returns the requests not seen before. */
    fun pollRequests(): List<FeedRequest> {
        val feed = server ?: return emptyList()
        val all = feed.requests
        val seen = state.value.requests.size
        state.update { status -> status.copy(requests = all.map { ServedRequest(it.toString(), it.status) }) }
        return if (all.size > seen) all.drop(seen) else emptyList()
    }

    private fun requireServer(): UpdateFeedServer = checkNotNull(server) { "The feed server is not running" }

    private fun artifactName(version: String): String {
        val arch =
            if (System.getProperty("os.arch").contains("aarch64") ||
                System.getProperty("os.arch").contains("arm")
            ) {
                "arm64"
            } else {
                "x64"
            }
        return when (Platform.Current) {
            Platform.MacOS -> "NucleusLab-$version-$arch.zip"
            Platform.Windows -> "NucleusLab-Setup-$version-$arch.exe"
            else -> "NucleusLab-$version-$arch.deb"
        }
    }

    /** Random bytes, seeded per version: every release gets its own SHA-512. */
    private fun writeRandom(
        file: File,
        sizeBytes: Long,
        seed: Long,
    ) {
        val random = Random(seed)
        val chunk = ByteArray(CHUNK)
        file.outputStream().buffered().use { out ->
            var left = sizeBytes
            while (left > 0) {
                random.nextBytes(chunk)
                val n = minOf(left, CHUNK.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
