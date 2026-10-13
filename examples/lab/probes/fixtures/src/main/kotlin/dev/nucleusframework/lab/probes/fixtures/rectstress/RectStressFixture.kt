package dev.nucleusframework.lab.probes.fixtures.rectstress

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.window.TitleBar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Sentinel for the RectManager EDT escape fixed in #551 / PR #554 (tracked upstream:
 * RectManager's `postDelayed` runs on skiko's hard-coded Swing EDT dispatcher), ported from
 * `examples/rect-stress-demo`.
 *
 * It assembles the worst case for the RectManagerEdtGuard (see [StressContent]). The rect
 * callback is the detector: RectManager must only invoke it on the scene thread, and if a
 * delayed dispatch escapes to the EDT the fixture prints the proof and exits with
 * [EXIT_ESCAPED]. On the guarded backend that must never happen; on an unguarded one (upstream
 * Compose 1.12 driven off the EDT) the same shape crashes with
 * `IllegalArgumentException: LayoutNode not found in RectList`.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class RectStressFixture : Fixture {
    override val id = "rect-stress"
    override val title = "RectManager stress (#555)"
    override val description =
        "A window of constantly moving, slow-to-draw, remounting content. Pass: it keeps running " +
            "(the magenta box sliding) and never exits 55; the timed variant ends by itself with exit 0."

    override val variants =
        listOf(
            FixtureVariant("default"),
            FixtureVariant("timed 60 s", jvmArgs = listOf("-D$EXIT_AFTER_PROPERTY=60")),
        )

    override val exitCodes =
        mapOf(
            EXIT_ESCAPED to
                "onLayoutRectChanged fired on the AWT EDT: RectManager's delayed dispatch escaped (#555 reproduced)",
        )

    override fun run(args: Array<String>) {
        val exitAfter = System.getProperty(EXIT_AFTER_PROPERTY)?.toLongOrNull()
        // The Lab holds the single-instance lock when packaged: the child must not hand off to it.
        nucleusApplication(args, enableSingleInstance = false) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "RectManager stress (#555)",
            ) {
                TitleBar { BasicText("RectManager stress (#555)") }
                if (exitAfter != null) {
                    LaunchedEffect(Unit) {
                        delay(exitAfter * 1000)
                        println("rect-stress: $exitAfter s without an EDT escape")
                        exitProcess(0)
                    }
                }
                StressContent()
            }
        }
    }

    companion object {
        const val EXIT_ESCAPED = 55
        private const val EXIT_AFTER_PROPERTY = "lab.rectStress.exitAfterSeconds"
    }
}
