package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationTokenTest {
    @Test
    fun `a token is handed out once, the latest one wins`() {
        ActivationToken.take()
        assertNull(ActivationToken.take())

        ActivationToken.offer("first")
        ActivationToken.offer("second")
        assertEquals("second", ActivationToken.take())
        assertNull(ActivationToken.take())
    }

    @Test
    fun `an empty token is not offered`() {
        ActivationToken.take()
        ActivationToken.offer("")
        assertNull(ActivationToken.take())
    }
}
