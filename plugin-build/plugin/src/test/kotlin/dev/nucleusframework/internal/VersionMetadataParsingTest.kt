package dev.nucleusframework.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionMetadataParsingTest {
    @Test
    fun `metadata suffix is preserved`() {
        assertEquals(Version(1, 2, 3, "rc-01"), Version.fromString("1.2.3-rc-01"))
    }
}
