package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBackendTest {
    @Test
    fun `current defaults to Awt until a backend is recorded`() {
        val alreadyManaged = WindowBackend.isNucleusManaged
        if (!alreadyManaged) {
            assertEquals(WindowBackend.Awt, WindowBackend.Current)
            assertFalse(WindowBackend.isNucleusManaged)
        }

        WindowBackend.setActive(WindowBackend.Tao)
        assertEquals(WindowBackend.Tao, WindowBackend.Current)
        assertTrue(WindowBackend.isNucleusManaged)

        WindowBackend.setActive(WindowBackend.Awt)
        assertEquals(WindowBackend.Awt, WindowBackend.Current)
        assertTrue(WindowBackend.isNucleusManaged)
    }
}
