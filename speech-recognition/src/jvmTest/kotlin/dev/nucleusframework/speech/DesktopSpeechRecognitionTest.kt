package dev.nucleusframework.speech

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSpeechRecognitionTest {
    private val os = System.getProperty("os.name").lowercase()

    @Test
    fun theBridgeMatchesTheHostPlatform() {
        when {
            os.startsWith("windows") -> {
                assertTrue(NativeSpeechBridge.isLoaded)
                assertEquals("windows-sapi", SpeechRecognition.engineName)
            }
            os.startsWith("mac") -> {
                assertTrue(NativeSpeechBridge.isLoaded)
                assertEquals("apple-sfspeech", SpeechRecognition.engineName)
            }
            else -> {
                assertFalse(SpeechRecognition.isSupported)
                assertEquals("none", SpeechRecognition.engineName)
                val failure = assertFailsWith<SpeechException> { SpeechRecognition.start {} }
                assertEquals(SpeechErrorKind.Unavailable, failure.kind)
            }
        }
    }

    /**
     * Opens the real microphone for a few seconds: `-Dnucleus.speech.live=true`. Speak to see
     * transcripts; the session must end with exactly one terminal event either way.
     */
    @Test
    fun aLiveSessionEndsWithOneTerminalEvent() {
        if (System.getProperty("nucleus.speech.live") != "true" || !SpeechRecognition.isSupported) return
        val events = CopyOnWriteArrayList<SpeechEvent>()
        val ended = CountDownLatch(1)
        val session =
            SpeechRecognition.start { event ->
                if (event !is SpeechEvent.AudioLevel) println("speech event: $event")
                events += event
                if (event.isTerminal) ended.countDown()
            }
        val busy = assertFailsWith<SpeechException> { SpeechRecognition.start {} }
        assertEquals(SpeechErrorKind.Busy, busy.kind)
        Thread.sleep(LIVE_RECORDING_MS)
        session.stop()
        assertTrue(ended.await(STOP_TIMEOUT_S, TimeUnit.SECONDS), "no terminal event after stop(): $events")
        assertEquals(1, events.count { it.isTerminal }, "$events")
        assertFalse(session.isActive)
        println("levels: ${events.count { it is SpeechEvent.AudioLevel }}, last: ${events.last()}")
    }

    private companion object {
        const val LIVE_RECORDING_MS = 6_000L
        const val STOP_TIMEOUT_S = 10L
    }
}
