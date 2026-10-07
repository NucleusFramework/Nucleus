package dev.nucleusframework.lab.probes.fixtures.swingtao

import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.window.tao.TaoApplication
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Pure Swing driven by the Tao event loop — no Compose anywhere — ported from
 * `examples/swing-tao-demo`.
 *
 * [TaoApplication.run] seizes the calling (main) thread and pumps the native Tao event loop
 * until [TaoApplication.exit]. The launch callback runs on that thread and hands the UI to a
 * plain Swing frame on the AWT EDT, so both loops run side by side in one process: the Tao
 * loop owns the main thread and its native windows, the EDT owns the frame. The frame's
 * buttons cross from the EDT into the Tao loop, whose commands are thread-safe.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class SwingTaoFixture : Fixture {
    override val id = "swing-tao"
    override val title = "Swing on the Tao loop"
    override val description =
        "A Swing frame on the EDT while the Tao loop owns the main thread. Pass: the EDT tick keeps " +
            "counting, the native Tao window opens and closes from the buttons, and Quit exits 0."

    override val exitCodes = mapOf(EXIT_FATAL to "TaoApplication.run rethrew a fatal dispatch failure (#622)")

    override fun run(args: Array<String>) {
        try {
            TaoApplication.run { app ->
                // Fires once, on the Tao main thread.
                val taoThread = Thread.currentThread().name
                SwingUtilities.invokeLater { SwingTaoFrame(app, taoThread).show() }
            }
        } catch (t: Throwable) {
            // run() rethrows a fatal dispatch failure after logging it and showing the native
            // error dialog. Without this catch the non-daemon EDT would keep the dead process alive.
            t.printStackTrace()
            exitProcess(EXIT_FATAL)
        }
        // run() returned: exit() stopped the Tao loop. AWT's non-daemon EDT would keep the JVM
        // alive past main(), so end the process explicitly.
        exitProcess(0)
    }

    private companion object {
        const val EXIT_FATAL = 1
    }
}
