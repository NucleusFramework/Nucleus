@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage-1 coverage for the window exception handler (#621), the Tao mirror of
 * Compose Desktop's `LocalWindowExceptionHandlerFactory`.
 *
 * Frames run through the production guard: the harness installs the handler on
 * the [TaoSceneBundle] exactly like every scene host does, so
 * `recordSceneToPicture` → `TaoSceneBundle.render` is the real code under test.
 * Input and IME entries mirror the host's guarded dispatch, the same way the
 * harness already mirrors the host's pointer guards.
 *
 * Two failure classes behave differently, and the tests pin both down:
 *  - layout / draw / input failures leave the scene fully usable — swallowing
 *    them is what #621 asked for;
 *  - a composition failure is intercepted by `Recomposer.processCompositionError`,
 *    which tears the recomposition loop down before we ever see it. Swallowing
 *    that one cannot resurrect the window, so the scene reports itself dead and
 *    the router logs it instead of leaving a silently frozen window.
 *
 * The routing chain in front of the #622 fatal path is covered separately, in
 * [TaoSceneExceptionRouterTest], where the fatal step can be observed without
 * touching `TaoApplication`'s process-wide state.
 */
class TaoSceneExceptionHandlerTest {
    /** Collects what the handler saw, and swallows it so the scene continues. */
    private class Collector : WindowExceptionHandler {
        val seen = mutableListOf<Throwable>()

        override fun onException(throwable: Throwable) {
            seen += throwable
        }
    }

    @Test
    fun `composition failure reaches the handler`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            var failing by mutableStateOf(false)

            setContent {
                if (failing) error("composition boom")
                Box(Modifier.fillMaxSize().background(Color.Green))
            }
            assertTrue(collector.seen.isEmpty(), "a healthy composition must not report anything")

            failing = true
            // A failed recomposition never leaves `render`: Compose rethrows it
            // into the `runCatching` inside `withFrameNanos`, so it arrives as a
            // failure of the recomposition loop's coroutine — hence the
            // [TaoSceneExceptionRouter] — one pump after the frame that ran it.
            frame()
            pumpUntilIdle()

            assertEquals(1, collector.seen.size, "the composition failure must reach the handler")
            assertEquals("composition boom", collector.seen.single().message)
        }

    @Test
    fun `a swallowed composition failure leaves the scene unable to recompose`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            val log = CapturedLog()
            var failing by mutableStateOf(false)
            var color by mutableStateOf(Color.Green)

            setContent {
                if (failing) error("composition boom")
                Box(Modifier.fillMaxSize().background(color))
            }
            assertTrue(isRecomposerAlive, "the scene starts alive")

            failing = true
            frame()
            log.use { pumpUntilIdle() }

            assertEquals(1, collector.seen.size, "the failure still reaches the handler")
            assertFalse(
                isRecomposerAlive,
                "Compose shuts the recomposition loop down on a composition failure; " +
                    "swallowing it cannot bring it back",
            )
            assertTrue(
                log.records.any { it.level == Level.SEVERE },
                "the unrecoverable case must be logged, not silently swallowed — " +
                    "this is the frozen-window report from ZonePane",
            )

            // The regression itself: the app healed its state, and the window
            // still never updates again.
            failing = false
            color = Color.Blue
            repeat(FRAMES_AFTER_FAILURE) {
                frame()
                pumpUntilIdle()
            }
            assertEquals(GREEN, pixelAt(20, 20), "a dead recomposer can no longer paint state changes")
        }

    @Test
    fun `a dead scene does not spin the frame scheduler`() =
        runTaoSceneTest(width = 40, height = 40) {
            exceptionHandler = Collector()
            var failing by mutableStateOf(false)

            setContent {
                if (failing) error("composition boom")
                Box(Modifier.fillMaxSize().background(Color.Green))
            }

            failing = true
            frame()
            pumpUntilIdle()
            assertFalse(isRecomposerAlive, "precondition: the scene is dead")

            repeat(FRAMES_AFTER_FAILURE) {
                frame()
                pumpUntilIdle()
            }
            assertFalse(
                isSceneInvalidated,
                "a scene that can never change again must not keep asking for frames",
            )
        }

    @Test
    fun `layout failure reaches the handler`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            var failing by mutableStateOf(false)

            setContent {
                Box(Modifier.fillMaxSize().background(Color.Green)) {
                    ThrowingLayout(shouldThrow = { failing })
                }
            }
            assertTrue(collector.seen.isEmpty(), "a healthy layout pass must not report anything")

            failing = true
            frame()

            assertEquals(1, collector.seen.size, "the layout failure must reach the handler")
            assertEquals("layout boom", collector.seen.single().message)
        }

    @Test
    fun `draw failure reaches the handler and the scene keeps rendering`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            var failing by mutableStateOf(false)

            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Green)
                        .drawBehind { if (failing) error("draw boom") },
                )
            }
            assertEquals(GREEN, pixelAt(20, 20))

            // The recoverable case #621 is about: a decoder throwing mid-draw
            // (CMP's "A partial image was generated") used to freeze the window.
            failing = true
            frame()
            assertEquals(1, collector.seen.size, "the draw failure must reach the handler")

            // Handler returned normally, so the frame is dropped and a new one
            // requested — the scene must still be alive and paint again.
            failing = false
            frameUntilIdle()
            assertEquals(GREEN, pixelAt(20, 20), "the scene must keep painting after a swallowed failure")
            assertEquals(1, collector.seen.size, "recovery must not report a second failure")
        }

    @Test
    fun `a swallowed draw failure keeps state updates flowing`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            var failing by mutableStateOf(false)
            var color by mutableStateOf(Color.Green)

            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .drawBehind { if (failing) error("draw boom") },
                )
            }

            failing = true
            frame()
            assertEquals(1, collector.seen.size)
            assertTrue(isRecomposerAlive, "a draw failure must not take the recomposer down")

            // Not just "renders again" — recomposition of a state change must
            // still reach the screen, which is what a live recomposer buys.
            failing = false
            color = Color.Blue
            frameUntilIdle()
            assertEquals(BLUE, pixelAt(20, 20), "state changes must still repaint after a swallowed draw failure")
        }

    @Test
    fun `a scene that survived a swallowed failure still accepts new content`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector
            var failing by mutableStateOf(true)

            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Green)
                        .drawBehind { if (failing) error("draw boom") },
                )
            }
            assertTrue(collector.seen.isNotEmpty(), "the first frame already fails")

            failing = false
            setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
            frameUntilIdle()

            assertEquals(BLUE, pixelAt(20, 20), "content swap must work after a swallowed failure")
        }

    @Test
    fun `input dispatch failure reaches the handler`() =
        runTaoSceneTest(width = 40, height = 40) {
            val collector = Collector()
            exceptionHandler = collector

            setContent {
                Box(Modifier.fillMaxSize().background(Color.Green)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable { error("click boom") },
                    )
                }
            }

            click(20f, 20f)

            assertEquals(1, collector.seen.size, "the click handler failure must reach the handler")
            assertEquals("click boom", collector.seen.single().message)
        }

    @Test
    fun `a handler that rethrows propagates the failure`() =
        runTaoSceneTest(width = 40, height = 40) {
            exceptionHandler = WindowExceptionHandler { throw it }
            var failing by mutableStateOf(false)

            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Green)
                        .drawBehind { if (failing) error("draw boom") },
                )
            }

            failing = true
            val failure = assertFailsWith<IllegalStateException> { frame() }
            assertEquals("draw boom", failure.message)
        }

    /**
     * Propagating out of the guard is what feeds #622: in a real window this
     * unwinds into the `guarded { }` Tao event dispatch (or the render loop's
     * fatal coroutine handler), which logs, shows the native dialog and exits.
     * The default factory rethrows for exactly this reason.
     */
    @Test
    fun `without a handler the failure propagates`() =
        runTaoSceneTest(width = 40, height = 40) {
            var failing by mutableStateOf(false)

            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Green)
                        .drawBehind { if (failing) error("draw boom") },
                )
            }

            failing = true
            val failure = assertFailsWith<IllegalStateException> { frame() }
            assertEquals("draw boom", failure.message)
        }

    /** Throws from the measure pass on demand — a layout-phase failure source. */
    @Composable
    private fun ThrowingLayout(shouldThrow: () -> Boolean) {
        Layout(content = {}) { _, constraints ->
            if (shouldThrow()) error("layout boom")
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }

    /** Captures what the scene-exception logger emits while [use] runs. */
    private class CapturedLog {
        val records = mutableListOf<LogRecord>()

        fun use(block: () -> Unit) {
            val logger = Logger.getLogger("dev.nucleusframework.window.tao.exception")
            val handler =
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        records += record
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                }
            logger.addHandler(handler)
            try {
                block()
            } finally {
                logger.removeHandler(handler)
            }
        }
    }

    private companion object {
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()

        /** Enough frames that a live scene would certainly have repainted. */
        const val FRAMES_AFTER_FAILURE = 5
    }
}
