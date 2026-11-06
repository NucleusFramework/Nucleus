package dev.nucleusframework.lab.probes.system.zstd

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.probes.system.ToolReadBack

/** How this Lab was launched, which decides whether the sandbox rewrite should be in play. */
@Immutable
data class RuntimeFacts(
    val executableType: String,
    val sandboxed: Boolean,
    val nativeImage: Boolean,
)

/** The native library entry inside the zstd-kmp JAR, as the loader will read it. */
@Immutable
data class JarResource(
    val path: String,
    val url: String?,
    val sizeBytes: Long?,
    val sha256: String?,
) {
    /** The sandboxed pipeline swaps the real library (~600 KiB) for a tiny marker file. */
    val isMarker: Boolean get() = sizeBytes != null && sizeBytes < MARKER_MAX_BYTES

    companion object {
        const val MARKER_MAX_BYTES = 4_096L
    }
}

/** `nucleus-sandbox-manifest.properties`, if the pipeline shipped one. */
@Immutable
data class SandboxManifest(
    val location: String?,
    val searched: List<String>,
    /** The bundled library the marker's hash maps to; `null` when absent or not listed. */
    val bundledName: String?,
    val bundledPath: String?,
)

/** What changed on disk and in the process when the library loaded. */
@Immutable
data class LoadObservation(
    val newTempFiles: List<String>,
    /** `false` when an earlier call in this JVM already loaded it: no extraction happens twice. */
    val loadedNow: Boolean,
    val millis: Long,
    val thread: String,
)

@Immutable
data class RoundTrip(
    val epochMillis: Long,
    val inputBytes: Int,
    val compressedBytes: Int,
    val millis: Long,
    val identical: Boolean,
    val error: String? = null,
)

@Immutable
data class ZstdState(
    val runtime: RuntimeFacts? = null,
    val resource: JarResource? = null,
    val manifest: SandboxManifest? = null,
    val load: LoadObservation? = null,
    val loadError: String? = null,
    val mapped: ToolReadBack? = null,
    val roundTrips: List<RoundTrip> = emptyList(),
    val running: Boolean = false,
)

sealed interface ZstdIntent {
    data object Inspect : ZstdIntent

    data class RunRoundTrip(
        val sizeKib: Int,
    ) : ZstdIntent

    data object ReadMappings : ZstdIntent
}

sealed interface ZstdEvent {
    data class Inspected(
        val runtime: RuntimeFacts,
        val resource: JarResource,
        val manifest: SandboxManifest,
    ) : ZstdEvent

    data object Started : ZstdEvent

    data class Loaded(
        val observation: LoadObservation,
    ) : ZstdEvent

    data class LoadFailed(
        val message: String,
    ) : ZstdEvent

    data class RoundTripped(
        val result: RoundTrip,
    ) : ZstdEvent

    data class Mapped(
        val readBack: ToolReadBack,
    ) : ZstdEvent
}

/** Where the library was actually loaded from, as far as the probe can tell, and how it knows. */
fun ZstdState.loadedFrom(): String? {
    val mappedPath = mapped?.lines?.firstOrNull()
    return when {
        mappedPath != null -> "$mappedPath  (OS module list)"
        resource?.isMarker == true && manifest?.bundledPath != null ->
            "${manifest.bundledPath}  (marker → manifest, inferred)"
        load?.newTempFiles?.isNotEmpty() == true -> "${load.newTempFiles.first()}  (temp extraction, inferred)"
        else -> null
    }
}

object ZstdReducer : Reducer<ZstdState, ZstdEvent> {
    override fun reduce(
        state: ZstdState,
        event: ZstdEvent,
    ): ZstdState =
        when (event) {
            is ZstdEvent.Inspected ->
                state.copy(
                    runtime = event.runtime,
                    resource = event.resource,
                    manifest = event.manifest,
                )
            ZstdEvent.Started -> state.copy(running = true)
            // The first observation is the one that saw the extraction; later ones only confirm it is loaded.
            is ZstdEvent.Loaded ->
                state.copy(
                    load =
                        if (state.load?.loadedNow ==
                            true
                        ) {
                            state.load
                        } else {
                            event.observation
                        },
                    loadError = null,
                )
            is ZstdEvent.LoadFailed -> state.copy(loadError = event.message, running = false)
            is ZstdEvent.RoundTripped ->
                state.copy(
                    running = false,
                    roundTrips = state.roundTrips.append(event.result),
                )
            is ZstdEvent.Mapped -> state.copy(mapped = event.readBack)
        }
}
