package dev.nucleusframework.core.runtime

import dev.nucleusframework.core.runtime.tools.AppIdProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdProviderTest {
    @Test
    fun `appId is a sanitized non-blank identifier`() {
        val id = AppIdProvider.appId()
        assertTrue(id.isNotBlank())
        assertTrue(id.length <= 128)
        assertTrue(id.matches(Regex("[A-Za-z0-9._-]+")))
        assertEquals(id, AppIdProvider.appId())
    }
}
