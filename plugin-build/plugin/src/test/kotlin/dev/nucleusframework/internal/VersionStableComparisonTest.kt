package dev.nucleusframework.internal

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStableComparisonTest {
    @Test
    fun `stable release sorts after prerelease metadata`() {
        assertTrue(Version.fromString("1.0.0") > Version.fromString("1.0.0-rc1"))
    }
}
