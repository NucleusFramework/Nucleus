package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.media.control.MediaControlEvent
import dev.nucleusframework.nucleus.media.control.MediaControlService
import dev.nucleusframework.nucleus.media.control.MediaMetadata
import dev.nucleusframework.nucleus.media.control.MediaPlaybackState
import dev.nucleusframework.nucleus.media.control.MediaPlaybackStatus

private const val EVENT_LOG_MAX = 20

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun MediaControlScreen() {
    val events = remember { mutableStateListOf<String>() }
    var configured by remember { mutableStateOf(false) }

    // Player state (simulated)
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var volume by remember { mutableStateOf(1.0) }

    // Track metadata fields
    var title by remember { mutableStateOf("Clair de Lune") }
    var artist by remember { mutableStateOf("Claude Debussy") }
    var album by remember { mutableStateOf("Suite bergamasque") }
    var duration by remember { mutableStateOf(300_000L) }

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeRange(EVENT_LOG_MAX, events.size)
    }

    fun syncToService() {
        if (!MediaControlService.isAvailable()) return
        MediaControlService.setMetadata(
            MediaMetadata(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
            ),
        )
        MediaControlService.setPlaybackState(
            MediaPlaybackState(
                status = if (isPlaying) MediaPlaybackStatus.PLAYING else MediaPlaybackStatus.PAUSED,
                positionMs = currentPosition,
            ),
        )
    }

    DisposableEffect(Unit) {
        if (MediaControlService.isAvailable()) {
            MediaControlService.configure()
            MediaControlService.attach { event ->
                log("Event received: ${event::class.simpleName}")
                when (event) {
                    is MediaControlEvent.Play -> {
                        isPlaying = true
                        syncToService()
                    }
                    is MediaControlEvent.Pause -> {
                        isPlaying = false
                        syncToService()
                    }
                    is MediaControlEvent.Toggle -> {
                        isPlaying = !isPlaying
                        syncToService()
                    }
                    is MediaControlEvent.Next -> {
                        log("Next")
                    }
                    is MediaControlEvent.Previous -> {
                        log("Previous")
                    }
                    is MediaControlEvent.Stop -> {
                        isPlaying = false
                        currentPosition = 0
                        syncToService()
                    }
                    is MediaControlEvent.SeekBy -> {
                        currentPosition =
                            (currentPosition + event.offsetMs)
                                .coerceIn(0, duration)
                        syncToService()
                    }
                    is MediaControlEvent.SetPosition -> {
                        currentPosition = event.positionMs
                        syncToService()
                    }
                    is MediaControlEvent.SetVolume -> {
                        volume = event.volume
                    }
                    is MediaControlEvent.OpenUri -> {
                        log("OpenUri: ${event.uri}")
                    }
                    is MediaControlEvent.Raise -> {
                        log("Raise")
                    }
                    is MediaControlEvent.Quit -> {
                        log("Quit")
                    }
                }
            }
            configured = true
            syncToService()
        }
        onDispose {
            if (configured) {
                MediaControlService.detach()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val backendLabel =
                when (Platform.Current) {
                    Platform.Linux -> "MPRIS D-Bus"
                    Platform.MacOS -> "Now Playing / Control Center"
                    Platform.Windows -> "System Media Transport Controls"
                    else -> "Unsupported platform"
                }
            Text("Media Control — $backendLabel", style = MaterialTheme.typography.headlineSmall)

            if (!MediaControlService.isAvailable()) {
                val reason =
                    when (Platform.Current) {
                        Platform.Linux ->
                            "Native library not available. Requires a running D-Bus session and an MPRIS-aware desktop environment."
                        Platform.MacOS ->
                            "Native library not available. Requires macOS 10.13.2+."
                        Platform.Windows ->
                            "Native library not available. Requires Windows 10+."
                        else ->
                            "Media controls are only supported on Linux, macOS, and Windows."
                    }
                Text(reason, color = MaterialTheme.colorScheme.error)
                return@Surface
            }

            // ── Now Playing ─────────────────────────────────────────────
            SectionCard("Now Playing") {
                Text(
                    text = if (isPlaying) "Playing" else "Paused",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$title — $artist",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // Position slider
                if (duration > 0) {
                    Text(
                        text = "${currentPosition / 1000}s / ${duration / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { currentPosition = it.toLong() },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Volume
                Text(
                    text = "Volume: ${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = volume.toFloat(),
                    onValueChange = {
                        volume = it.toDouble()
                        MediaControlService.setVolume(volume)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Transport Controls ──────────────────────────────────────
            SectionCard("Transport Controls") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            isPlaying = true
                            syncToService()
                            log("Play")
                        },
                        enabled = !isPlaying,
                    ) { Text("Play") }

                    Button(
                        onClick = {
                            isPlaying = false
                            syncToService()
                            log("Pause")
                        },
                        enabled = isPlaying,
                    ) { Text("Pause") }

                    OutlinedButton(
                        onClick = {
                            isPlaying = !isPlaying
                            syncToService()
                            log("Toggle (${if (isPlaying) "playing" else "paused"})")
                        },
                    ) { Text("Toggle") }

                    OutlinedButton(
                        onClick = {
                            isPlaying = false
                            currentPosition = 0
                            syncToService()
                            log("Stop")
                        },
                    ) { Text("Stop") }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            currentPosition = (currentPosition - 10_000).coerceAtLeast(0)
                            syncToService()
                            log("Seek backward 10s")
                        },
                    ) { Text("-10s") }

                    OutlinedButton(
                        onClick = {
                            currentPosition = (currentPosition + 10_000).coerceAtMost(duration)
                            syncToService()
                            log("Seek forward 10s")
                        },
                    ) { Text("+10s") }

                    OutlinedButton(
                        onClick = {
                            currentPosition = 0
                            syncToService()
                            log("Seek to start")
                        },
                    ) { Text("Restart") }
                }
            }

            // ── Metadata Editor ─────────────────────────────────────────
            SectionCard("Metadata Editor") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Duration (ms):", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = duration.toFloat(),
                        onValueChange = { duration = it.toLong() },
                        valueRange = 10_000f..600_000f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${duration / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        syncToService()
                        log("Metadata updated: $title — $artist")
                    },
                ) { Text("Apply Metadata") }
            }

            // ── Event Log ───────────────────────────────────────────────
            SectionCard("Event Log") {
                val emptyHint =
                    when (Platform.Current) {
                        Platform.Linux ->
                            "No events yet. Control this player from a media center (e.g., GNOME Settings, KDE Plasma)."
                        Platform.MacOS ->
                            "No events yet. Control this player from Control Center or the Now Playing menu bar widget."
                        else -> "No events yet."
                    }
                if (events.isEmpty()) {
                    Text(emptyHint, style = MaterialTheme.typography.bodySmall)
                } else {
                    events.forEachIndexed { index, event ->
                        Text(
                            event,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (index < events.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }

            // ── Hint ─────────────────────────────────────────────────────
            val hint =
                when (Platform.Current) {
                    Platform.Linux ->
                        "Tip: Use `playerctl metadata` in a terminal, or control from your desktop environment's media widget."
                    Platform.MacOS ->
                        "Tip: Open Control Center (menu-bar icon) — this app appears as the Now Playing source. " +
                            "Volume, OpenUri, Raise, Quit and relative Seek are Linux-only on macOS."
                    else -> ""
                }
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
