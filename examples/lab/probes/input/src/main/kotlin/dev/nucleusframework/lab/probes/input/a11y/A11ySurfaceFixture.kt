package dev.nucleusframework.lab.probes.input.a11y

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceContent
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceTab
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceTabBar
import dev.nucleusframework.lab.probes.input.a11y.surface.logEvent
import dev.nucleusframework.window.TitleBar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/**
 * The accessibility surface alone in its own process and window — what the CI jobs drive
 * (`.github/workflows/pre-merge.yaml`, `scripts/ci/verify-atspi*.py`, `verify-uia*.ps1`,
 * `verify-ax.swift`). No Lab chrome around it, so the goldens' strict-extra check sees only
 * the fixture's own interactive nodes.
 *
 * `./gradlew :examples:lab:app:run -Dlab.fixture=a11y-surface [-Dlab.a11y.tab=Complex]`
 */
@ContributesIntoSet(AppScope::class)
@Inject
class A11ySurfaceFixture : Fixture {
    override val id: String = ID
    override val title: String = "Accessibility surface"
    override val description: String =
        "Opens '$WINDOW_TITLE' with the A11y / Complex / Events / Scroll / Zoom page tabs. " +
            "Inspect it with Accessibility Inspector, Accerciser or Inspect.exe; the process exits when the window closes."

    override val variants: List<FixtureVariant> =
        SurfaceTab.entries.map { FixtureVariant(it.label, jvmArgs = listOf("-D$TAB_PROPERTY=${it.name}")) }

    override fun run(args: Array<String>) {
        val initial = SurfaceTab.parse(System.getProperty(TAB_PROPERTY)) ?: SurfaceTab.A11y
        nucleusApplication(args) {
            val events = remember { mutableStateListOf<String>() }
            var selected by remember { mutableStateOf(initial) }
            // Dark, as the goldens were recorded; the tree (not the colours) is what CI compares.
            LabTheme(isDark = true) {
                DecoratedWindow(
                    onCloseRequest = ::exitApplication,
                    state = rememberWindowState(size = DpSize(1024.dp, 760.dp)),
                    title = WINDOW_TITLE,
                    minimumSize = DpSize(640.dp, 480.dp),
                    onPreviewKeyEvent = { event ->
                        if (event.type == KeyEventType.KeyDown) logEvent(events, "preview ${event.key}")
                        false
                    },
                ) {
                    TitleBar { _ ->
                        BasicText(
                            text = WINDOW_TITLE,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            style =
                                LabTheme.typography.label.copy(color = LabTheme.colors.text),
                        )
                    }
                    Column(Modifier.fillMaxSize().background(LabTheme.colors.background)) {
                        SurfaceTabBar(selected, onSelect = {
                            selected = it
                            logEvent(events, "tab -> ${it.label}")
                        })
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            SurfaceContent(
                                selected,
                                events,
                                Modifier.fillMaxSize(),
                                onA11yEvent = { logEvent(events, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ID = "a11y-surface"

        /** The title the CI probes find the window by (`-Title` / `--window`). */
        const val WINDOW_TITLE = "Nucleus A11y Surface"
        const val TAB_PROPERTY = "lab.a11y.tab"
    }
}
