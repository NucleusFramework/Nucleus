package dev.nucleusframework.internal

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionMetadataComparisonTest {
    @Test
    fun `metadata parts are compared lexicographically`() {
        assertTrue(Version.fromString("1.0.0-rc2") > Version.fromString("1.0.0-rc1"))
    }
}
