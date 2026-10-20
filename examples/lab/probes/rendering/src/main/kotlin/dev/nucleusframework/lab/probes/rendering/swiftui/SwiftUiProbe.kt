package dev.nucleusframework.lab.probes.rendering.swiftui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.OverlayPill
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SwiftUiProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "SwiftUI view",
            domain = Domain.Rendering,
            summary =
                "Does an AppKit-hosted SwiftUI view embed in the scene, follow Compose " +
                    "state and blend under Compose?",
            modules = listOf("decorated-window-tao"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "embed",
                        "The gradient view with \"Hello from SwiftUI\" fills the rounded frame, clipped to its corners",
                    ),
                    Check(
                        "state",
                        "Counter and hue changes from the Compose controls appear in the SwiftUI view immediately",
                    ),
                    Check(
                        "overlay",
                        "The Compose label on top of the view stays visible and crisp over the SwiftUI content",
                    ),
                    Check(
                        "release",
                        "Removing the view and adding it back leaves live handles at 1 and restores counter and hue",
                    ),
                    Check("resize", "Resizing the window keeps the SwiftUI view inside its frame with no stale edge"),
                ),
            keywords = listOf("ffm", "nshostingview", "nativeview", "appkit"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SwiftUiViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities = listOf(Capability("SwiftUI helper (FFM)", state.availability)),
            controls = {
                Actions {
                    SecondaryAction("−") { vm.onIntent(SwiftUiIntent.Decrement) }
                    PrimaryAction("+") { vm.onIntent(SwiftUiIntent.Increment) }
                    SecondaryAction("Reset") { vm.onIntent(SwiftUiIntent.Reset) }
                }
                SliderRow("Hue", state.hue) { vm.onIntent(SwiftUiIntent.SetHue(it)) }
                SwitchRow("Embedded", state.embedded) { vm.onIntent(SwiftUiIntent.ToggleEmbedded) }
            },
            observed = {
                Readout("counter", state.counter.toString())
                Readout("hue", state.hue.fmt())
                Readout("NSHostingView*", state.viewAddress?.let { "0x${it.toULong().toString(16)}" } ?: "none")
                Readout("handles created / released", "${state.created} / ${state.released}")
                Readout("live handles", state.live.toString(), tone = if (state.live > 1) Tone.Error else Tone.Neutral)
            },
            wide = {
                if (state.availability.isAvailable && state.embedded) {
                    SpecimenFrame(height = 320.dp) {
                        NativeView(
                            factory = {
                                val address = vm.attach()
                                object : NucleusPlatformView.NsView {
                                    override val nsViewHandle: Long = address

                                    override fun dispose() = vm.detach()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 8.dp,
                        ) {
                            Box(
                                Modifier.fillMaxSize().padding(LabDimens.page),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                OverlayPill("Compose, drawn over SwiftUI · counter ${state.counter}")
                            }
                        }
                    }
                } else if (state.availability.isAvailable) {
                    EmptyState("Not embedded: turn Embedded on to add the view back.")
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("rendering.swiftui")
    }
}
