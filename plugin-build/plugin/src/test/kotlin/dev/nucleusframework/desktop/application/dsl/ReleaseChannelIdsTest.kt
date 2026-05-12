package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseChannelIdsTest {
    @Test
    fun `release channels expose update metadata prefixes`() {
        assertEquals("latest", ReleaseChannel.Latest.id)
        assertEquals("beta", ReleaseChannel.Beta.id)
        assertEquals("alpha", ReleaseChannel.Alpha.id)
    }
}
