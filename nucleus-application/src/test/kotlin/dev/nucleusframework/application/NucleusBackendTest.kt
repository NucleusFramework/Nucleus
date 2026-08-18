package dev.nucleusframework.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleusBackendTest {
    @Test
    fun `explicit backends are returned as-is`() {
        assertEquals(NucleusBackend.Awt, resolveBackend(NucleusBackend.Awt))
        assertEquals(NucleusBackend.Tao, resolveBackend(NucleusBackend.Tao))
    }

    @Test
    fun `auto prefers Tao when TaoApplication is on the classpath`() {
        val taoPresent = taoBackendOnClasspath()
        val resolved = resolveBackend(NucleusBackend.Auto)
        assertEquals(
            if (taoPresent) NucleusBackend.Tao else NucleusBackend.Awt,
            resolved,
        )
        assertTrue(resolved == NucleusBackend.Tao || resolved == NucleusBackend.Awt)
        assertTrue(resolved != NucleusBackend.Auto)
    }

    @Test
    fun `enum lists every supported selector`() {
        assertEquals(
            setOf(NucleusBackend.Auto, NucleusBackend.Awt, NucleusBackend.Tao),
            NucleusBackend.entries.toSet(),
        )
    }

    private fun taoBackendOnClasspath(): Boolean =
        try {
            Class.forName(
                "dev.nucleusframework.window.tao.TaoApplication",
                false,
                NucleusBackend::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        }
}
