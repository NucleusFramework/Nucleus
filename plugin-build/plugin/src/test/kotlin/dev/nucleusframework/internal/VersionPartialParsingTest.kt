package dev.nucleusframework.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionPartialParsingTest {
    @Test
    fun `missing minor and patch components default to zero`() {
        assertEquals(Version(17, 0, 0, ""), Version.fromString("17"))
        assertEquals(Version(17, 1, 0, ""), Version.fromString("17.1"))
    }
}
