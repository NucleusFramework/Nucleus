package dev.nucleusframework.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppRestarterTest {
    @Test
    fun `applicationExecutablePath resolves to an existing file`() {
        val path = AppRestarter.applicationExecutablePath
        assertTrue(path.isNotBlank())
        assertTrue("executable should exist: $path", File(path).exists())
        assertEqualsSameAbsolutePath(path)
    }

    private fun assertEqualsSameAbsolutePath(path: String) {
        assertTrue(File(path).isAbsolute)
        assertTrue(path == AppRestarter.applicationExecutablePath)
    }
}
