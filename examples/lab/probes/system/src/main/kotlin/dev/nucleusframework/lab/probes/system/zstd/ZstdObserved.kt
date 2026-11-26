package dev.nucleusframework.lab.probes.system.zstd

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.system.ToolReadBackView

/** The JAR entry, the sandbox manifest, what loading changed, and the round trips. */
@Composable
fun ZstdObserved(state: ZstdState) {
    SubHeading("Loaded from")
    val loadedFrom = state.loadedFrom()
    if (loadedFrom == null) {
        EmptyState("Not loaded yet: run a round trip.")
    } else {
        Readout("path", loadedFrom, tone = Tone.Ok)
    }

    SubHeading("JAR entry")
    ResourceReadouts(state)

    SubHeading("Sandbox manifest")
    ManifestReadouts(state.manifest)

    SubHeading("Load")
    LoadReadouts(state)
    ToolReadBackView("module list", state.mapped, empty = "No zstd library mapped into this process.")

    SubHeading("Round trips")
    EventLog(state.roundTrips.map { it.toLogEntry() }, empty = "None yet.")
}

@Composable
private fun ResourceReadouts(state: ZstdState) {
    val resource = state.resource
    if (resource == null) {
        EmptyState("Not inspected yet.")
        return
    }
    Readout("resource", resource.path)
    Readout("from", resource.url ?: "missing", tone = if (resource.url == null) Tone.Error else Tone.Neutral)
    Readout(
        "size",
        resource.sizeBytes?.let {
            formatBytes(it) +
                if (resource.isMarker) "  → marker (stripped by the sandboxed pipeline)" else "  → real library"
        },
    )
    Readout("sha-256", resource.sha256)
    if (state.runtime?.sandboxed == true && !resource.isMarker) {
        Readout(
            "pipeline",
            "sandboxed, yet the JAR still holds the real library: the pipeline did not strip it",
            tone = Tone.Error,
        )
    }
}

@Composable
private fun ManifestReadouts(manifest: SandboxManifest?) {
    when {
        manifest == null -> EmptyState("Not inspected yet.")
        manifest.location == null ->
            EmptyState("None (expected outside sandboxed builds); searched ${manifest.searched.size} dir(s).")
        else -> {
            Readout("manifest", manifest.location)
            Readout(
                "marker maps to",
                manifest.bundledName ?: "nothing: this marker's hash is not listed",
                tone = if (manifest.bundledName == null) Tone.Error else Tone.Neutral,
            )
            Readout(
                "bundled copy",
                manifest.bundledPath ?: "not found in any searched dir",
                tone = if (manifest.bundledPath == null) Tone.Error else Tone.Ok,
            )
        }
    }
}

@Composable
private fun LoadReadouts(state: ZstdState) {
    val load = state.load
    state.loadError?.let { Readout("failed", it, tone = Tone.Error) }
    if (load == null && state.loadError == null) EmptyState("Happens on the first round trip.")
    load?.let {
        Readout(
            "first load",
            "${it.millis} ms on ${it.thread}" + if (it.loadedNow) "" else "  (already loaded earlier in this JVM)",
        )
        Readout(
            "new temp files",
            it.newTempFiles.joinToString().ifEmpty { "none (already loaded, or extracted elsewhere)" },
            tone = if (it.newTempFiles.isEmpty()) Tone.Muted else Tone.Neutral,
        )
    }
}

private fun RoundTrip.toLogEntry(): LogEntry {
    val text =
        error ?: run {
            val ratio = if (compressedBytes > 0) inputBytes.toFloat() / compressedBytes else 0f
            "${formatBytes(inputBytes.toLong())} → ${formatBytes(compressedBytes.toLong())} " +
                "(×${ratio.fmt(1)}) in $millis ms, identical=$identical"
        }
    return LogEntry(text, epochMillis, tone = if (identical) Tone.Ok else Tone.Error)
}
