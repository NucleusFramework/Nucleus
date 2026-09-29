package dev.nucleusframework.speech

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechSessionsTest {
    @AfterTest
    fun clear() {
        SpeechSessions.unregisterAll()
    }

    @Test
    fun terminalAndCancelledSessionsDiscardLateNativeResults() {
        val received = mutableListOf<SpeechEvent>()
        assertTrue(SpeechSessions.register(1001) { received += it })
        SpeechSessions.emit(1001, SpeechEvent.Transcript("draft", isFinal = false))
        SpeechSessions.emit(1001, SpeechEvent.Stopped)
        SpeechSessions.emit(1001, SpeechEvent.Transcript("stale", isFinal = true))
        assertEquals(listOf(SpeechEvent.Transcript("draft", isFinal = false), SpeechEvent.Stopped), received)

        assertTrue(SpeechSessions.register(1002) { received += it })
        // A previous session's delayed callback must not reach its successor, even when
        // recognition restarted immediately.
        SpeechSessions.emit(1001, SpeechEvent.Error(SpeechErrorKind.Other, "late failure"))
        SpeechSessions.unregister(1002)
        SpeechSessions.emit(1002, SpeechEvent.Started)
        assertEquals(2, received.size)
        assertFalse(SpeechSessions.isActive(1001))
        assertFalse(SpeechSessions.isActive(1002))
    }

    @Test
    fun onlyOneSessionIsLiveAtATime() {
        assertTrue(SpeechSessions.register(2001) {})
        assertFalse(SpeechSessions.register(2002) {})
        SpeechSessions.emit(2001, SpeechEvent.Error(SpeechErrorKind.Audio, "gone"))
        assertTrue(SpeechSessions.register(2002) {})
    }

    @Test
    fun aThrowingListenerDoesNotEndTheSession() {
        assertTrue(SpeechSessions.register(3001) { throw IllegalStateException("listener bug") })
        SpeechSessions.emit(3001, SpeechEvent.Started)
        assertTrue(SpeechSessions.isActive(3001))
    }
}
