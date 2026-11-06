package dev.nucleusframework.lab.probes.rendering.conformance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.MaterialSpecimen
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Section
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ConformanceProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Compose conformance",
            domain = Domain.Rendering,
            summary =
                "Does Compose render on Tao exactly as it should — text, layout, " +
                    "drawing, layers, lists, motion, Material 3?",
            modules = listOf("decorated-window-tao", "decorated-window-material3"),
            checks =
                listOf(
                    Check("expectations", "Every sample in every category matches its \"correct looks like\" note"),
                    Check(
                        "rtl",
                        "With RTL on, layouts, pager, tabs and text mirror; the absolute offset alone does not",
                    ),
                    Check(
                        "font-scale",
                        "At font scale 2.0 nothing overlaps or clips; fields and chips grow with their text",
                    ),
                    Check(
                        "density",
                        "At density 1.25 / 1.5 hairlines stay solid and the 8 dp grid lines up with the samples",
                    ),
                    Check(
                        "popups",
                        "Menus, tooltips, dialogs and the date picker open at the right place " +
                            "and close on Esc / outside click",
                    ),
                    Check(
                        "input",
                        "Hover, ripple, focus rings (Tab), text selection and scrolling respond without lag",
                    ),
                ),
            keywords =
                listOf(
                    "compose",
                    "material3",
                    "expressive",
                    "text",
                    "layout",
                    "canvas",
                    "lazy",
                    "animation",
                    "rtl",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ConformanceViewModel>()
        val state by vm.state.collectAsState()
        val visible = state.visibleSamples(ConformanceCatalog.samples)
        val levers = state.levers

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                ChoiceRow("Category", SampleCategory.entries, state.category, name = { it.title }) {
                    vm.onIntent(ConformanceIntent.SelectCategory(it))
                }
                TextFieldRow("Search every sample", state.query) { vm.onIntent(ConformanceIntent.Search(it)) }
                SwitchRow("Right-to-left", levers.rtl) { vm.onIntent(ConformanceIntent.ToggleRtl) }
                ChoiceRow("Font scale", ConformanceReducer.FontScales, levers.fontScale, name = { "$it×" }) {
                    vm.onIntent(ConformanceIntent.SetFontScale(it))
                }
                ChoiceRow("Extra density", ConformanceReducer.DensityScales, levers.densityScale, name = { "$it×" }) {
                    vm.onIntent(ConformanceIntent.SetDensityScale(it))
                }
                SwitchRow("Outline + 8 dp grid", levers.outlines) { vm.onIntent(ConformanceIntent.ToggleOutlines) }
                Actions { SecondaryAction("Reset levers") { vm.onIntent(ConformanceIntent.ResetLevers) } }
            },
            observed = {
                val density = LocalDensity.current
                Readout("window density", "${density.density.fmt()} (font scale ${density.fontScale.fmt()})")
                Readout(
                    "effective",
                    "${(density.density * levers.densityScale).fmt()} · " +
                        "font ${(density.fontScale * levers.fontScale).fmt()} · " +
                        if (levers.rtl) "RTL" else "LTR",
                )
                Readout("samples", "${visible.size} shown · ${ConformanceCatalog.samples.size} total")
            },
            wide = {
                if (visible.isEmpty()) EmptyState("No sample matches \"${state.query}\".")
                visible.forEach { sample ->
                    key(sample.id) {
                        Section(sample.title) {
                            Hint(sample.expect)
                            ConformanceCatalog.entry(sample)?.let { entry ->
                                SpecimenFrame {
                                    Levered(levers) {
                                        // Only Material samples are judged on Material's scheme; the rest render in the Lab's.
                                        if (sample.category in
                                            MaterialCategories
                                        ) {
                                            MaterialSpecimen(entry.content)
                                        } else {
                                            entry.content()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("rendering.conformance")
    }
}

private val MaterialCategories = setOf(SampleCategory.Material, SampleCategory.Expressive)
