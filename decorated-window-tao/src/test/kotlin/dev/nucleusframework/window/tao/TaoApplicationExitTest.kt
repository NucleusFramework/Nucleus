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
