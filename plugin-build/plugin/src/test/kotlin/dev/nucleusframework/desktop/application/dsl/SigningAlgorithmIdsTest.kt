package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class SigningAlgorithmIdsTest {
    @Test
    fun `signing algorithms expose signtool ids`() {
        assertEquals("sha1", SigningAlgorithm.Sha1.id)
        assertEquals("sha256", SigningAlgorithm.Sha256.id)
        assertEquals("sha512", SigningAlgorithm.Sha512.id)
    }
}
