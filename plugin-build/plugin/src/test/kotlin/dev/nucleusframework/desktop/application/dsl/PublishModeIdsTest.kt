package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishModeIdsTest {
    @Test
    fun `publish modes expose electron builder ids`() {
        assertEquals("never", PublishMode.Never.id)
        assertEquals("onTag", PublishMode.Auto.id)
        assertEquals("always", PublishMode.Always.id)
    }
}
