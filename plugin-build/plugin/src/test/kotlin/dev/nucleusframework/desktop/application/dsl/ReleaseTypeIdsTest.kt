package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTypeIdsTest {
    @Test
    fun `release types expose GitHub release ids`() {
        assertEquals("release", ReleaseType.Release.id)
        assertEquals("draft", ReleaseType.Draft.id)
        assertEquals("prerelease", ReleaseType.Prerelease.id)
    }
}
