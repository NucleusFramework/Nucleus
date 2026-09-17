@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The routing chain that sits in every Tao scene's coroutine context: the
 * per-window handler of #621 in front of the app-fatal path of #622.
 *
 * The fatal step is injected here rather than exercised through
 * `TaoApplication.reportFatal`, which records a process-wide first-report-wins
 * throwable and posts a native loop exit — not something a unit test should
 * leave behind for the tests that run after it.
 */
class TaoSceneExceptionRouterTest {
    @Test
    fun `a failure with no window handler takes the fatal path`() {
        val fatal = mutableListOf<Throwable>()
        val router = router(onFatal = fatal::add)
        val boom = RuntimeException("boom")

        router.handleException(EmptyCoroutineContext, boom)

        assertEquals(1, fatal.size, "no handler means the app-fatal path — dialog and clean exit")
        assertSame(boom, fatal.single())
    }

    @Test
    fun `a handler that rethrows takes the fatal path`() {
        val fatal = mutableListOf<Throwable>()
        val router = router(onFatal = fatal::add)
        router.handler = WindowExceptionHandler { throw it }
        val boom = RuntimeException("boom")

        router.handleException(EmptyCoroutineContext, boom)

        assertEquals(1, fatal.size, "rethrowing is how a handler opts into the fatal path")
        assertSame(boom, fatal.single())
    }

    @Test
    fun `a handler may substitute the throwable it rethrows`() {
        val fatal = mutableListOf<Throwable>()
        val router = router(onFatal = fatal::add)
        val wrapper = IllegalStateException("wrapped")
        router.handler = WindowExceptionHandler { throw wrapper }

        router.handleException(EmptyCoroutineContext, RuntimeException("boom"))

        assertSame(wrapper, fatal.single(), "the fatal path reports what the handler actually threw")
    }

    @Test
    fun `a handler that returns normally swallows the failure`() {
        val fatal = mutableListOf<Throwable>()
        val seen = mutableListOf<Throwable>()
        val router = router(onFatal = fatal::add)
        router.handler = WindowExceptionHandler { seen += it }
        val boom = RuntimeException("boom")

        router.handleException(EmptyCoroutineContext, boom)

        assertSame(boom, seen.single(), "the handler is asked first")
        assertTrue(fatal.isEmpty(), "an app that swallows must not get the fatal dialog")
    }

    @Test
    fun `swallowing a failure the scene cannot survive is logged`() {
        val fatal = mutableListOf<Throwable>()
        val router = router(onFatal = fatal::add)
        router.handler = WindowExceptionHandler { }
        router.sceneIsAlive = { false }
        val log = CapturedLog()

        log.use { router.handleException(EmptyCoroutineContext, RuntimeException("boom")) }

        assertTrue(fatal.isEmpty(), "the app asked to continue; that choice is honoured")
        assertTrue(
            log.records.any { it.level == Level.SEVERE },
            "a window that can never update again must not be left silent",
        )
    }

    @Test
    fun `swallowing a survivable failure is not logged`() {
        val router = router(onFatal = { })
        router.handler = WindowExceptionHandler { }
        router.sceneIsAlive = { true }
        val log = CapturedLog()

        log.use { router.handleException(EmptyCoroutineContext, RuntimeException("boom")) }

        assertNull(
            log.records.firstOrNull { it.level == Level.SEVERE },
            "a recovered scene must not be reported as frozen",
        )
    }

    @Test
    fun `a failure during teardown is logged instead of taking the app down`() {
        val fatal = mutableListOf<Throwable>()
        val closed = AtomicBoolean(true)
        val router = TaoSceneExceptionRouter(closed, onFatal = fatal::add)
        router.handler = WindowExceptionHandler { throw it }
        val log = CapturedLog()

        log.use { router.handleException(EmptyCoroutineContext, RuntimeException("boom")) }

        assertTrue(fatal.isEmpty(), "closing one window must never close the whole app (#622)")
        assertTrue(log.records.any { it.level == Level.SEVERE }, "teardown noise is still logged")
    }

    private fun router(onFatal: (Throwable) -> Unit) = TaoSceneExceptionRouter(AtomicBoolean(false), onFatal)

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
}
