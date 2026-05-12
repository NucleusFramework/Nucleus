package dev.nucleusframework.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionParsingTest {
    @Test
    fun `parses complete semantic versions`() {
        assertEquals(Version(2, 3, 4, ""), Version.fromString("2.3.4"))
    }

    @Test
    fun `invalid versions fall back to zero`() {
        assertEquals(Version(0, 0, 0, ""), Version.fromString("not-a-version"))
    }
}
