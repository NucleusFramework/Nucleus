package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaoApplicationExitTest {
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
