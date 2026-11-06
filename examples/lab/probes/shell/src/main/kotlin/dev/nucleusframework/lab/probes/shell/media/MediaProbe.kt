package dev.nucleusframework.lab.probes.shell.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.formatClock
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.media.control.MediaControlEvent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class MediaProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Media controls",
            domain = Domain.Shell,
            summary = "Do the OS media surfaces show what the app plays, and do media keys and their buttons drive it?",
            modules = listOf("media-control"),
            checks =
                listOf(
                    Check(
                        "now-playing",
                        "The OS media widget shows title, artist, album, cover and duration of the current track",
                    ),
                    Check("unicode", "Track 3's accented and Greek characters render intact in the OS widget"),
                    Check("keys", "Play/pause, next and previous media keys are listed below and drive the player"),
                    Check("widget", "The widget's own buttons (and seek bar where offered) work the same way"),
                    Check("position", "The widget's elapsed time follows the player, and a seek there moves it here"),
                    Check("thread", "No command is flagged off the UI thread"),
                    Check(
                        "detach",
                        "After Detach, media keys no longer reach the Lab and the widget clears or goes idle",
                    ),
                ),
            keywords = listOf("MPRIS", "Now Playing", "SMTC", "media keys", "play pause"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<MediaViewModel>()
        val state by vm.state.collectAsState()
        val ready = state.availability.isAvailable && state.attached

        ProbeLayout(
            capabilities = listOf(Capability("Media session", state.availability, detail = state.backend)),
            controls = {
                Actions {
                    if (state.attached) {
                        SecondaryAction("Detach") { vm.onIntent(MediaIntent.Detach) }
                    } else {
                        PrimaryAction(
                            "Attach media session",
                            enabled = state.availability.isAvailable,
                        ) { vm.onIntent(MediaIntent.Attach) }
                    }
                }
                SubHeading("Player (same path as OS commands)")
                Actions {
                    SecondaryAction(
                        "⏮",
                        enabled = ready,
                    ) { vm.onIntent(MediaIntent.Command(MediaControlEvent.Previous)) }
                    PrimaryAction("⏯", enabled = ready) { vm.onIntent(MediaIntent.Command(MediaControlEvent.Toggle)) }
                    SecondaryAction("⏭", enabled = ready) { vm.onIntent(MediaIntent.Command(MediaControlEvent.Next)) }
                    SecondaryAction("⏹", enabled = ready) { vm.onIntent(MediaIntent.Command(MediaControlEvent.Stop)) }
                    SecondaryAction(
                        "+30 s",
                        enabled = ready,
                    ) { vm.onIntent(MediaIntent.Command(MediaControlEvent.SeekBy(30_000))) }
                }
                SliderRow("Volume", state.volume.toFloat(), format = { percent(it.toDouble(), decimals = 0) }) {
                    vm.onIntent(MediaIntent.Command(MediaControlEvent.SetVolume(it.toDouble())))
                }
                SwitchRow("Cover art", state.withCover, enabled = ready) { vm.onIntent(MediaIntent.SetCover(it)) }
            },
            observed = {
                Readout("Session", if (state.attached) "attached" else "detached")
                Readout("Track", "${state.trackIndex + 1}/${Playlist.size} · ${state.track.title}")
                Readout("Artist · album", "${state.track.artist} · ${state.track.album}")
                Readout("Status", state.status.name)
                Readout("Position", "${formatClock(state.positionMs)} / ${formatClock(state.track.durationMs)}")
                Readout("Volume", percent(state.volume, decimals = 0))
                SubHeading("Commands from the OS")
                EventLog(
                    state.commands.map { it.toLogEntry() },
                    empty = "Press a media key or use the OS media widget.",
                )
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.media-control")
    }
}
