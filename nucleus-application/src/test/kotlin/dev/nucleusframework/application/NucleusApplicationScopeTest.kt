package dev.nucleusframework.application

import androidx.compose.ui.window.ApplicationScope
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.aot.runtime.AotRuntimeMode
import dev.nucleusframework.core.runtime.DeepLinkHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class NucleusApplicationScopeTest {
    @Test
    fun `awt scope reports Awt and delegates exit`() {
        val compose = RecordingApplicationScope()
        val scope = AwtNucleusApplicationScope(compose, arrayOf("--flag"))
        assertEquals(NucleusBackend.Awt, scope.backend)
        assertSame(compose, scope.composeScope)
        assertFalse(compose.exited)
        scope.exitApplication()
        assertTrue(compose.exited)
    }

    @Test
    fun `aot flags follow the nucleus aot mode property`() {
        val scope = AwtNucleusApplicationScope(RecordingApplicationScope(), emptyArray())
        val key = "nucleus.aot.mode"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertEquals(AotRuntimeMode.OFF, scope.aotMode)
            assertFalse(scope.isAotTraining)
            assertFalse(scope.isAotRuntime)

            System.setProperty(key, "training")
            assertEquals(AotRuntimeMode.TRAINING, AotRuntime.mode())
            assertTrue(scope.isAotTraining)
            assertFalse(scope.isAotRuntime)

            System.setProperty(key, "runtime")
            assertEquals(AotRuntimeMode.RUNTIME, scope.aotMode)
            assertFalse(scope.isAotTraining)
            assertTrue(scope.isAotRuntime)
        } finally {
            restoreProperty(key, previous)
        }
    }

    @Test
    fun `onDeepLink registers a handler that receives delivered URIs`() {
        val scope = AwtNucleusApplicationScope(RecordingApplicationScope(), emptyArray())
        val received = mutableListOf<URI>()
        scope.onDeepLink { received.add(it) }
        val uri = URI("nucleus-test://scope/${System.nanoTime()}")
        DeepLinkHandler.deliver(uri)
        assertEquals(listOf(uri), received)
        assertEquals(uri, DeepLinkHandler.uri)
    }

    @Test
    fun `window bounds keep screen coordinates`() {
        val bounds = NucleusWindowBounds(x = 12f, y = 24f, width = 800f, height = 600f)
        assertEquals(12f, bounds.x)
        assertEquals(24f, bounds.y)
        assertEquals(800f, bounds.width)
        assertEquals(600f, bounds.height)
        assertEquals(bounds, NucleusWindowBounds(12f, 24f, 800f, 600f))
    }

    @Test
    fun `aotTraining is a no-op outside training mode`() {
        val key = "nucleus.aot.mode"
        val previous = System.getProperty(key)
        val compose = RecordingApplicationScope()
        val scope = AwtNucleusApplicationScope(compose, emptyArray())
        try {
            System.setProperty(key, "off")
            var timedOut = false
            scope.aotTraining(duration = 1.milliseconds) { timedOut = true }
            Thread.sleep(30)
            assertFalse(timedOut)
            assertFalse(compose.exited)
        } finally {
            restoreProperty(key, previous)
        }
    }

    @Test
    fun `aotTraining arms once and invokes onTimeout in training mode`() {
        val key = "nucleus.aot.mode"
        val previous = System.getProperty(key)
        val scope = AwtNucleusApplicationScope(RecordingApplicationScope(), emptyArray())
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        try {
            System.setProperty(key, "training")
            assertTrue(scope.isAotTraining)
            scope.aotTraining(duration = 20.milliseconds) { first.countDown() }
            scope.aotTraining(duration = 20.milliseconds) { second.countDown() }
            assertTrue("first aotTraining should fire", first.await(2, TimeUnit.SECONDS))
            assertFalse("second aotTraining must not re-arm", second.await(80, TimeUnit.MILLISECONDS))
        } finally {
            restoreProperty(key, previous)
        }
    }

    private fun restoreProperty(
        key: String,
        previous: String?,
    ) {
        if (previous == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, previous)
        }
    }

    private class RecordingApplicationScope : ApplicationScope {
        var exited: Boolean = false

        override fun exitApplication() {
            exited = true
        }
    }
}
