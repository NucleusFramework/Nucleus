package dev.nucleusframework.internal

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNumericComparisonTest {
    @Test
    fun `major minor and patch are compared before metadata`() {
        assertTrue(Version.fromString("2.0.0") > Version.fromString("1.99.99"))
        assertTrue(Version.fromString("1.3.0") > Version.fromString("1.2.99"))
        assertTrue(Version.fromString("1.2.4") > Version.fromString("1.2.3"))
    }
}
