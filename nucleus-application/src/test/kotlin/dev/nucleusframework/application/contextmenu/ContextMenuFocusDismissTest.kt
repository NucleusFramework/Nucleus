package dev.nucleusframework.application.contextmenu

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextMenuFocusDismissTest {
    private fun losses(vararg focused: Boolean): Int =
        runBlocking { flowOf(*focused.toTypedArray()).windowFocusLosses().toList().size }

    @Test
    fun `losing focus while open dismisses once`() {
        assertEquals(1, losses(true, false))
    }

    @Test
    fun `staying focused never dismisses`() {
        assertEquals(0, losses(true, true, true))
    }

    @Test
    fun `a backend that has not reported focus yet does not dismiss`() {
        assertEquals(0, losses(false, false, true))
    }

    @Test
    fun `each focus loss after a regain dismisses again`() {
        assertEquals(2, losses(true, false, true, false))
    }
}
