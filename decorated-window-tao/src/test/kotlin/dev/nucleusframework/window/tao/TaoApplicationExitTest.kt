package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaoApplicationExitTest {
    /** Runs [block] against a fresh quit state; the deferred settle is run by hand via the returned list. */
    private fun quit(
        vararg open: TaoWindow,
        scope: MutableList<TaoWindow> = open.toMutableList(),
        block: (settle: () -> Unit, exits: () -> Int) -> Unit = { settle, _ -> settle() },
    ): Int {
        var exits = 0
        val pending = mutableListOf<() -> Unit>()
        TaoApplication.resetQuit()
        TaoApplication.quitExit = { exits++ }
        TaoApplication.afterQuitRequests = { pending += it }
        try {
            TaoApplication.requestQuit(scope)
            block({ pending.toList().also { pending.clear() }.forEach { it() } }, { exits })
            return exits
        } finally {
            TaoApplication.resetQuit()
        }
    }

    private fun window(
        handle: Long,
        log: MutableList<Long>,
        accept: Boolean,
    ) = TaoWindow(handle).also { w ->
        w.onCloseRequested {
            log += handle
            if (accept) w.isClosing = true
        }
    }

    @Test
    fun `quit asks every window newest first and exits once all closed`() {
        val asked = mutableListOf<Long>()
        val exits = quit(window(1, asked, true), window(3, asked, true), window(2, asked, true))
        assertEquals(listOf(3L, 2L, 1L), asked)
        assertEquals(1, exits)
    }

    @Test
    fun `a window that stays open cancels the quit`() {
        val asked = mutableListOf<Long>()
        val exits =
            quit(window(1, asked, true), window(2, asked, false)) { settle, _ ->
                assertTrue(TaoApplication.isQuitting)
                settle()
                assertFalse(TaoApplication.isQuitting)
            }
        assertEquals(listOf(2L, 1L), asked)
        assertEquals(0, exits)
    }

    @Test
    fun `a second quit while one is in flight is ignored`() {
        val asked = mutableListOf<Long>()
        val w = window(1, asked, false)
        quit(w) { settle, _ ->
            TaoApplication.requestQuit(listOf(w))
            settle()
        }
        assertEquals(listOf(1L), asked)
    }

    @Test
    fun `exitApplication from a close request consents without overriding another veto`() {
        val asked = mutableListOf<Long>()
        val main =
            TaoWindow(1).also { w ->
                w.onCloseRequested {
                    asked += 1
                    check(TaoApplication.consentToQuit())
                }
            }
        val doc = window(2, asked, false)
        val exits = quit(main, doc)
        assertEquals(listOf(2L, 1L), asked)
        assertEquals(0, exits)
        assertFalse(TaoApplication.consentToQuit(), "consent is only absorbed inside a close request")
    }

    @Test
    fun `exitApplication consent from every window completes the quit`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        assertEquals(1, quit(main))
    }

    @Test
    fun `a window opened during the quit defers the exit until it closes`() {
        val asked = mutableListOf<Long>()
        val scope = mutableListOf<TaoWindow>()
        val ask = TaoWindow(9)
        val doc =
            TaoWindow(1).also { w ->
                w.onCloseRequested {
                    asked += 1
                    w.isClosing = true
                    scope += ask // the "Save?" window it opens on its way out
                }
            }
        scope += doc
        val exits =
            quit(doc, scope = scope) { settle, exits ->
                settle()
                assertEquals(0, exits(), "the new window keeps the app alive")
                assertTrue(TaoApplication.isQuitting)
                ask.isClosing = true
                TaoApplication.remove(ask.handle)
            }
        assertEquals(listOf(1L), asked)
        assertEquals(1, exits)
    }

    @Test
    fun `a new quit while waiting for a window asks it`() {
        val asked = mutableListOf<Long>()
        val scope = mutableListOf<TaoWindow>()
        val ask = window(9, asked, false)
        val doc =
            TaoWindow(1).also { w ->
                w.onCloseRequested {
                    w.isClosing = true
                    scope += ask
                }
            }
        scope += doc
        quit(doc, scope = scope) { settle, _ ->
            settle()
            TaoApplication.requestQuit(scope)
            settle()
            assertFalse(TaoApplication.isQuitting, "the window it asked refused")
        }
        assertEquals(listOf(9L), asked)
    }

    @Test
    fun `quit exits at once when no app window is open`() {
        val asked = mutableListOf<Long>()
        val palette = window(1, asked, false).apply { closesOnQuit = false }
        val closing = window(2, asked, false).apply { isClosing = true }
        val exits = quit(palette, closing) { _, exits -> assertEquals(1, exits()) }
        assertEquals(emptyList(), asked)
        assertEquals(1, exits)
    }

    /** Exits the quit performed during the last [querySession]. */
    private var sessionExits = 0

    /**
     * Runs a Windows session-end query against a fresh quit state, counting
     * released holds. [deferSettle] `false` resolves the quit inside the query,
     * as a bare `TaoApplication` (no Compose loop) does.
     */
    private fun querySession(
        vararg open: TaoWindow,
        deferSettle: Boolean = true,
        block: (answer: Int, settle: () -> Unit, releases: () -> Int) -> Unit,
    ) {
        var releases = 0
        val pending = mutableListOf<() -> Unit>()
        TaoApplication.resetQuit()
        sessionExits = 0
        TaoApplication.quitExit = { sessionExits++ }
        if (deferSettle) TaoApplication.afterQuitRequests = { pending += it }
        TaoApplication.releaseSessionEndHold = { releases++ }
        try {
            val answer = TaoApplication.queryEndSession(open.toList())
            block(answer, { pending.toList().also { pending.clear() }.forEach { it() } }, { releases })
        } finally {
            TaoApplication.resetQuit()
        }
    }

    @Test
    fun `session end may proceed when every window consents synchronously`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        val closing = TaoWindow(2).also { w -> w.onCloseRequested { w.isClosing = true } }
        querySession(main, closing) { answer, settle, releases ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
            assertTrue(TaoApplication.isQuitting)
            settle()
            assertEquals(0, releases())
            assertEquals(0, sessionExits, "the exit waits for WM_ENDSESSION")
        }
    }

    @Test
    fun `a session end cancelled after an agree keeps the app`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        querySession(main) { answer, settle, _ ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
            settle()
            TaoApplication.endSession(ending = false)
            assertFalse(TaoApplication.isQuitting)
            assertEquals(0, sessionExits)
        }
    }

    @Test
    fun `a session end cancelled before the quit settles keeps the app`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        querySession(main) { answer, settle, _ ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
            TaoApplication.endSession(ending = false)
            settle()
            assertFalse(TaoApplication.isQuitting)
            assertEquals(0, sessionExits)
        }
    }

    @Test
    fun `a confirmed session end after an agree tears down`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        querySession(main, deferSettle = false) { answer, _, _ ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
            val calls = mutableListOf<String>()
            TaoApplication.sessionEndTeardown = { calls += "teardown" }
            TaoApplication.sessionEndExit = { calls += "exit" }
            TaoApplication.endSession(ending = true)
            assertEquals(listOf("teardown", "exit"), calls)
            assertEquals(0, sessionExits, "the quit's own exit never ran")
        }
    }

    @Test
    fun `a session end cancelled after a hold lets the quit finish`() {
        // HOLD: a window closes through recomposition — it is gone, so the app exits.
        val doc = TaoWindow(1).also { w -> w.onCloseRequested {} }
        querySession(doc) { answer, settle, _ ->
            assertEquals(TaoApplication.END_SESSION_HOLD, answer)
            TaoApplication.endSession(ending = false)
            doc.isClosing = true
            settle()
            assertEquals(1, sessionExits)
        }
    }

    @Test
    fun `a quit still in flight holds the session and a veto releases the hold`() {
        val asked = mutableListOf<Long>()
        val doc = window(1, asked, false)
        querySession(doc) { answer, settle, releases ->
            assertEquals(TaoApplication.END_SESSION_HOLD, answer)
            assertTrue(TaoApplication.isQuitting)
            // A second query while the quit is in flight asks nobody again.
            assertEquals(TaoApplication.END_SESSION_HOLD, TaoApplication.queryEndSession(listOf(doc)))
            assertEquals(listOf(1L), asked, "one quit, each window asked once")
            settle()
            assertFalse(TaoApplication.isQuitting)
            assertEquals(1, releases())
        }
    }

    @Test
    fun `a synchronous veto refuses without holding`() {
        val asked = mutableListOf<Long>()
        querySession(window(1, asked, false), deferSettle = false) { answer, _, releases ->
            assertEquals(TaoApplication.END_SESSION_REFUSE, answer)
            assertFalse(TaoApplication.isQuitting)
            assertEquals(0, releases(), "nothing was held")
            // The next session end asks again and still refuses — no stale hold.
            val next = TaoApplication.queryEndSession(listOf(window(2, asked, false)))
            assertEquals(TaoApplication.END_SESSION_REFUSE, next)
            assertEquals(listOf(1L, 2L), asked)
        }
    }

    @Test
    fun `a synchronous consent agrees`() {
        val main = TaoWindow(1).also { w -> w.onCloseRequested { TaoApplication.consentToQuit() } }
        querySession(main, deferSettle = false) { answer, _, _ ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
        }
    }

    @Test
    fun `session end may proceed at once when no app window is open`() {
        val palette = TaoWindow(1).apply { closesOnQuit = false }
        querySession(palette) { answer, _, _ ->
            assertEquals(TaoApplication.END_SESSION_AGREE, answer)
            assertEquals(0, sessionExits, "the exit waits for WM_ENDSESSION")
            TaoApplication.endSession(ending = false)
            assertFalse(TaoApplication.isQuitting)
        }
    }

    @Test
    fun `an ending session tears down then exits, a cancelled one does neither`() {
        val calls = mutableListOf<String>()
        TaoApplication.resetQuit()
        TaoApplication.sessionEndTeardown = { calls += "teardown" }
        TaoApplication.sessionEndExit = { calls += "exit" }
        try {
            TaoApplication.endSession(ending = false)
            assertEquals(emptyList(), calls)
            assertFalse(TaoApplication.isQuitting)
            TaoApplication.endSession(ending = true)
            assertEquals(listOf("teardown", "exit"), calls)
            assertTrue(TaoApplication.isQuitting)
            // Every window — and AWT's, forwarded — gets a WM_ENDSESSION: one teardown.
            TaoApplication.endSession(ending = true)
            assertEquals(listOf("teardown", "exit"), calls)
        } finally {
            TaoApplication.resetQuit()
        }
    }

    @Test
    fun `default finish exits 0 after a normal quit`() {
        val exits = mutableListOf<Int>()
        finishTaoApplication(exitProcessOnExit = true, failure = null, exit = { exits += it })
        assertEquals(listOf(0), exits)
    }

    @Test
    fun `default finish exits 1 after a failure`() {
        val exits = mutableListOf<Int>()
        finishTaoApplication(
            exitProcessOnExit = true,
            failure = IllegalStateException("boom"),
            exit = { exits += it },
        )
        assertEquals(listOf(1), exits)
    }

    @Test
    fun `exitProcessOnExit false returns after a normal quit`() {
        val exits = mutableListOf<Int>()
        finishTaoApplication(exitProcessOnExit = false, failure = null, exit = { exits += it })
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `exitProcessOnExit false rethrows after a failure`() {
        val exits = mutableListOf<Int>()
        val failure = IllegalStateException("boom")
        val thrown =
            assertFailsWith<IllegalStateException> {
                finishTaoApplication(
                    exitProcessOnExit = false,
                    failure = failure,
                    exit = { exits += it },
                )
            }
        assertSame(failure, thrown)
        assertTrue(exits.isEmpty())
    }
}
