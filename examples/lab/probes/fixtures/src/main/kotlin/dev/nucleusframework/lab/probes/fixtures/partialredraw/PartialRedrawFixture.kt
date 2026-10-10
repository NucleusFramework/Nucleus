package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.lab.designsystem.LabDecoratedWindow
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabTitle
import dev.nucleusframework.lab.designsystem.LabTitleBar
import dev.nucleusframework.lab.designsystem.LabWindowAppearance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import java.io.File
import kotlin.system.exitProcess

/**
 * Partial redraw demo and end-to-end fixture (#755), ported from `examples/partial-redraw-demo`.
 *
 * `-Dpartial.demo.scene=`:
 *  - `blink` (default) — the issue's measurement: a busy, static window and an 8×8 px box
 *    toggling colour at 25 fps, isolated in its own clipped layer;
 *  - `blink-plain` — the same box without a layer of its own: its change re-records the layer
 *    around it, which is what gets repainted;
 *  - `idle` — the busy window alone, nothing animating;
 *  - `main-tick` — the idle window with a main-thread `delay(40)` loop writing the same value
 *    to a state: no change on screen, so no frame (#754);
 *  - `bare` — a full-window background and a counter with no layer of the app's own;
 *  - `tour` — a scripted sequence of every kind of change the damage tracker must get right,
 *    run under `-Dnucleus.tao.partialRedraw.verify=true` by the end-to-end check;
 *  - `torture` — random window-level chaos over partial-friendly churn, see [Torture].
 *
 * `-Dpartial.demo.exitAfterSeconds=N` closes the app after N seconds;
 * `-Dpartial.demo.maximized=true` opens it maximized (the power measurement);
 * `-Dlab.theme=light|dark` overrides the OS appearance the Lab theme follows.
 *
 * Without the plugin's Compose patch (`nucleusOptimization { partialRedraw = true }` on the
 * Lab app) every frame is repainted in full, whatever the flags say.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class PartialRedrawFixture : Fixture {
    override val id = "partial-redraw"
    override val title = "Partial redraw (#755)"
    override val description =
        "Scenes for the damage tracker, all with -Dnucleus.tao.partialRedraw=true. Pass: tint shows " +
            "only what changes, verify logs no failure, the timed run exits 0. Needs the Lab built " +
            "with nucleusOptimization { partialRedraw = true }."

    override val variants: List<FixtureVariant> =
        buildList {
            SCENES.forEach { add(variant(it, "-D$SCENE=$it")) }
            add(variant("blink + tint", "-D$SCENE=blink", "-D$PARTIAL.tint=true"))
            add(variant("blink-plain + tint", "-D$SCENE=blink-plain", "-D$PARTIAL.tint=true"))
            add(variant("blink + debug", "-D$SCENE=blink", "-D$PARTIAL.debug=true"))
            add(variant("tour + verify", "-D$SCENE=tour", "-D$PARTIAL.verify=true"))
            add(variant("tour + verify + tint", "-D$SCENE=tour", "-D$PARTIAL.verify=true", "-D$PARTIAL.tint=true"))
            add(variant("torture + verify", "-D$SCENE=torture", "-D$PARTIAL.verify=true"))
            add(variant("torture seed 42", "-D$SCENE=torture", "-Dpartial.demo.seed=42"))
            add(
                variant(
                    "torture window only",
                    "-D$SCENE=torture",
                    "-Dpartial.demo.events=animate-size,bounce,maximize,fullscreen,minimize",
                ),
            )
            add(variant("torture settle", "-D$SCENE=torture", "-Dpartial.demo.settleDir=$settleDirDefault"))
            add(
                variant(
                    "blink maximized 60 s",
                    "-D$SCENE=blink",
                    "-Dpartial.demo.maximized=true",
                    "-Dpartial.demo.exitAfterSeconds=60",
                ),
            )
            // The same measurement with the feature off, to compare against.
            add(FixtureVariant("blink, partial redraw off", listOf("-D$PARTIAL=false", "-D$SCENE=blink")))
        }

    override fun run(args: Array<String>) {
        val scene = System.getProperty(SCENE) ?: "blink"
        val exitAfter = System.getProperty("partial.demo.exitAfterSeconds")?.toLongOrNull()
        val maximized = System.getProperty("partial.demo.maximized") == "true"
        // The settle handshake writes its markers there; the directory may not exist yet.
        System.getProperty("partial.demo.settleDir")?.let { File(it).mkdirs() }
        println("partial-redraw: scene=$scene partialRedraw=${System.getProperty(PARTIAL)}")

        nucleusApplication(args, enableSingleInstance = false) {
            val isDark =
                when (System.getProperty("lab.theme")?.lowercase()) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkMode()
                }
            LabTheme(isDark = isDark) {
                val windowState =
                    rememberWindowState(
                        placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                        size = DpSize(1100.dp, 760.dp),
                    )
                LabDecoratedWindow(
                    title = "Partial redraw ($scene)",
                    onCloseRequest = ::exitApplication,
                    state = windowState,
                    // The torture resizes the window freely; nothing may clamp it.
                    minimumSize = null,
                ) {
                    // The torture owns the clear colour (its `clear-color` and `settle` events).
                    if (scene != "torture") LabWindowAppearance()
                    LabTitleBar { _ -> LabTitle("Partial redraw", scene) }
                    if (exitAfter != null) {
                        LaunchedEffect(Unit) {
                            delay(exitAfter * 1000)
                            exitProcess(0)
                        }
                    }
                    if (scene == "bare") {
                        // No surface, no clip, no layer of the app's own: the root's layer is all there is.
                        BareToggle()
                        return@LabDecoratedWindow
                    }
                    // A clipped layer around the scene, as the Material Surface it replaces had.
                    Box(Modifier.fillMaxSize().background(LabTheme.colors.panel).clip(RectangleShape)) {
                        when (scene) {
                            "tour" -> Tour()
                            "torture" -> Torture(windowState)
                            "main-tick" -> MainTick()
                            else -> BusyWindow(blink = scene != "idle", isolated = scene != "blink-plain")
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val SCENE = "partial.demo.scene"
        const val PARTIAL = "nucleus.tao.partialRedraw"
        val SCENES = listOf("blink", "blink-plain", "idle", "main-tick", "bare", "tour", "torture")

        val settleDirDefault: String =
            File(System.getProperty("java.io.tmpdir"), "nucleus-lab-partial-settle").path

        /** Every variant runs with partial redraw on; later flags win over inherited ones. */
        fun variant(
            name: String,
            vararg flags: String,
        ) = FixtureVariant(name, listOf("-D$PARTIAL=true", *flags))
    }
}
